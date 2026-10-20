package com.redtact.live.login;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外のブラウザでのログインの結果を受け取る、127.0.0.1 だけで待つ小さな受け取り口 (#50)。1 回受け取ったら閉じる。
 *
 * <ul>
 * <li>{@code GET /callback}: redtact の {@code /login} が結果をフラグメントに載せて移してくる
 * ({@code #state=…&result=credential&method=…&id_token=…&raw_nonce=…})。返すページの JS がフラグメントを読み、
 * 履歴から消して {@code POST /complete} に送る (フラグメントはサーバへ届かないため)
 * <li>{@code POST /complete}: 本文はフラグメントのまま (application/x-www-form-urlencoded の形)。state を突き合わせ、
 * 合えば結果を渡して閉じる。合わなければ 400 で待ち続ける
 * </ul>
 * Host が 127.0.0.1:port でない要求は断る (DNS の付け替えでよそのページから届くのを防ぐ)。Mojang の Java には
 * jdk.httpserver が入っていないことがあるので、HTTP は自前で最小限だけ読む。
 */
public final class LoopbackServer implements Closeable {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final int MAX_HEAD = 16 * 1024;
	private static final int MAX_BODY = 64 * 1024;
	private static final int SOCKET_TIMEOUT_MS = 10_000;

	/** 受け取った結果 (フラグメントの値)。 */
	public record Result(Map<String, String> fields) {
		public @Nullable String get(String key) {
			return fields.get(key);
		}
	}

	private final ServerSocket socket;
	private final String expectedState;
	private final Consumer<Result> onResult;
	private final AtomicBoolean done = new AtomicBoolean();
	private final Thread thread;

	/**
	 * @param expectedState 頼んだときの state (これと同じ state の結果だけ受け取る)
	 * @param onResult 受け取ったら 1 回だけ呼ぶ (受け取り口のスレッドから)
	 */
	public LoopbackServer(String expectedState, Consumer<Result> onResult) throws IOException {
		this.expectedState = expectedState;
		this.onResult = onResult;
		this.socket = new ServerSocket();
		this.socket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 8);
		this.thread = Thread.ofPlatform().daemon().name("redtact-live-login").start(this::serve);
	}

	public int port() {
		return socket.getLocalPort();
	}

	/** 受け取り終えたか。 */
	public boolean done() {
		return done.get();
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException ignored) {
			// 閉じるだけ
		}
	}

	/**
	 * 受け口を閉じるまで、1 つずつ接続に答える。1 つの接続の失敗 (相手が途中で切った・遅すぎる・壊れた要求) では止まらない
	 * (#54。前は接続の SocketException を「閉じた」と取り違えて抜け、以後の要求に答えなかった)。
	 */
	private void serve() {
		while (!socket.isClosed()) {
			Socket s;

			try {
				s = socket.accept();
			} catch (IOException e) {
				if (socket.isClosed()) {
					return; // 閉じた
				}

				LOGGER.debug("[redtact-live] ログインの受け取り口で接続を受けられません: {}", e.toString());
				pause();
				continue;
			}

			try (s) {
				s.setSoTimeout(SOCKET_TIMEOUT_MS);
				handle(s);
			} catch (IOException | RuntimeException e) {
				LOGGER.debug("[redtact-live] ログインの受け取り口: {}", e.toString());
			}
		}
	}

	/** 接続を受けられないときに空回りしないよう少し待つ。 */
	private static void pause() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void handle(Socket s) throws IOException {
		InputStream in = s.getInputStream();
		OutputStream out = s.getOutputStream();
		String head = readHead(in);

		if (head == null) {
			respond(out, 400, "text/plain", "bad request");
			return;
		}

		String[] lines = head.split("\r\n");
		String[] start = lines[0].split(" ");

		if (start.length < 2) {
			respond(out, 400, "text/plain", "bad request");
			return;
		}

		Map<String, String> headers = new LinkedHashMap<>();

		for (int i = 1; i < lines.length; i++) {
			int c = lines[i].indexOf(':');

			if (c > 0) {
				headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
			}
		}

		String self = "127.0.0.1:" + port();

		if (!self.equals(headers.get("host"))) {
			respond(out, 403, "text/plain", "forbidden");
			return;
		}

		String method = start[0];
		String path = start[1];
		int q = path.indexOf('?');
		path = q < 0 ? path : path.substring(0, q);

		if (method.equals("GET") && path.equals("/callback")) {
			respond(out, 200, "text/html; charset=utf-8", CallbackPage.html()); // 開くたびにいまの言語で作る (#79)
			return;
		}

		if (method.equals("POST") && path.equals("/complete")) {
			String origin = headers.get("origin");

			if (origin != null && !origin.equals("http://" + self)) {
				respond(out, 403, "text/plain", "forbidden");
				return;
			}

			int length;

			try {
				length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
			} catch (NumberFormatException e) {
				length = -1;
			}

			if (length < 0 || length > MAX_BODY) {
				respond(out, 413, "text/plain", "too large");
				return;
			}

			Result r = new Result(parseForm(new String(in.readNBytes(length), StandardCharsets.UTF_8)));

			if (!sameState(r.get("state"))) {
				respond(out, 400, "application/json", "{\"ok\":false,\"reason\":\"state\"}");
				return;
			}

			if (!done.compareAndSet(false, true)) {
				respond(out, 410, "application/json", "{\"ok\":false,\"reason\":\"used\"}");
				return;
			}

			respond(out, 200, "application/json", "{\"ok\":true,\"result\":\"" + jsonSafe(r.get("result")) + "\"}");
			close();

			try {
				onResult.accept(r);
			} catch (RuntimeException e) {
				LOGGER.warn("[redtact-live] ログインの結果を渡せません", e);
			}

			return;
		}

		respond(out, 404, "text/plain", "not found");
	}

	private boolean sameState(@Nullable String state) {
		return state != null && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), expectedState.getBytes(StandardCharsets.UTF_8));
	}

	/** 空行までを読む (長すぎれば null)。 */
	private static @Nullable String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int state = 0;

		while (buf.size() < MAX_HEAD) {
			int b = in.read();

			if (b < 0) {
				return null;
			}

			buf.write(b);
			state = switch (state) {
				case 0, 2 -> b == '\r' ? state + 1 : 0;
				case 1 -> b == '\n' ? 2 : 0;
				case 3 -> b == '\n' ? 4 : 0;
				default -> 0;
			};

			if (state == 4) {
				String s = buf.toString(StandardCharsets.ISO_8859_1);
				return s.substring(0, s.length() - 4);
			}
		}

		return null;
	}

	/** application/x-www-form-urlencoded (フラグメントの形) を読む。先頭の # は外す。 */
	public static Map<String, String> parseForm(String body) {
		Map<String, String> out = new LinkedHashMap<>();
		String s = body.startsWith("#") ? body.substring(1) : body;

		for (String pair : s.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}

			int eq = pair.indexOf('=');
			String k = eq < 0 ? pair : pair.substring(0, eq);
			String v = eq < 0 ? "" : pair.substring(eq + 1);

			try {
				out.putIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
			} catch (IllegalArgumentException ignored) {
				// 崩れた値は飛ばす
			}
		}

		return out;
	}

	private static String jsonSafe(@Nullable String s) {
		return s == null ? "" : s.replaceAll("[^a-z_]", "");
	}

	private static void respond(OutputStream out, int status, String type, String body) throws IOException {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		String reason = switch (status) {
			case 200 -> "OK";
			case 400 -> "Bad Request";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 410 -> "Gone";
			case 413 -> "Payload Too Large";
			default -> "Error";
		};
		String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
				+ "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.ISO_8859_1));
		out.write(b);
		out.flush();
	}
}
