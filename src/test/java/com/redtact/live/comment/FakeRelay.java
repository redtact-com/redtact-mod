package com.redtact.live.comment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redtact.live.net.LiveApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * テスト用の中継サーバ (チャレンジ・閲覧者のトークン・コメントの投稿と取得だけ)。protocol.md の形で答える。
 */
final class FakeRelay implements AutoCloseable {
	/** 受けたリクエスト (「メソッド パス Authorization」)。 */
	final List<String> requests = new CopyOnWriteArrayList<>();
	final AtomicInteger tokensIssued = new AtomicInteger();
	/** トークンの入口が無い古い中継サーバ (本文が JSON でない 404)。 */
	volatile boolean oldServer;
	/** これ以前に出したトークンは signin_expired にする。 */
	volatile int rejectTokensUpTo;
	/** 表示名 (X-Live-User) のコメントを受けるか (開発用の設定)。 */
	volatile boolean allowUnverified;
	/** セッションが終わった (404 not_found)。 */
	volatile boolean ended;
	/** 次の POST comments で返すエラー (status と error。null なら受ける)。 */
	volatile String[] nextPostError;
	/** セッションのコメント (古い順。JSON の文字列)。 */
	final List<String> comments = new CopyOnWriteArrayList<>();
	private final AtomicInteger seq = new AtomicInteger();
	private final HttpServer server;

	FakeRelay() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	LiveApi api(String user) {
		return new LiveApi(LiveApi.newHttpClient(), this::url, () -> user);
	}

	/** ほかの人のコメントをサーバに足す (ID は時刻順に並ぶ)。 */
	String addComment(String author, String text, boolean byHost) {
		String id = String.format("c_%012x%010d", 1_000_000L + seq.incrementAndGet(), 0);
		comments.add("{\"id\":\"" + id + "\",\"author\":\"" + author + "\",\"authorVerified\":true,\"authorUuid\":\"u-" + author
				+ "\",\"authorIsHost\":" + byHost + ",\"text\":\"" + text + "\",\"createdAt\":\"2026-10-18T12:00:00.000Z\"}");
		return id;
	}

	private void handle(HttpExchange ex) throws IOException {
		String method = ex.getRequestMethod();
		String path = ex.getRequestURI().getPath();
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		requests.add(method + " " + path + (auth == null ? "" : " " + auth));
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

		if (method.equals("POST") && path.equals("/api/auth/minecraft/challenge")) {
			send(ex, 200, "{\"challengeId\":\"mc_" + seq.incrementAndGet() + "\",\"serverId\":\"" + "ab".repeat(20) + "\",\"expiresAt\":\"x\"}");
			return;
		}

		if (method.equals("POST") && path.equals("/api/auth/minecraft/token")) {
			if (oldServer) {
				sendText(ex, 404, "404 page not found\n");
				return;
			}

			JsonObject in = JsonParser.parseString(body).getAsJsonObject();
			int n = tokensIssued.incrementAndGet();
			send(ex, 200, "{\"token\":\"lv1.t" + n + "\",\"expiresAt\":\"x\",\"viewer\":{\"name\":\"" + in.get("name").getAsString()
					+ "\",\"verified\":true,\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}}");
			return;
		}

		if (path.startsWith("/api/sessions/") && path.endsWith("/comments")) {
			if (oldServer && method.equals("GET")) {
				sendText(ex, 405, "Method Not Allowed\n");
				return;
			}

			if (ended) {
				send(ex, 404, "{\"error\":\"not_found\",\"message\":\"セッションが見つからないか、終了している\"}");
				return;
			}

			if (method.equals("GET")) {
				String query = ex.getRequestURI().getRawQuery();
				String after = query != null && query.startsWith("after=") ? URLDecoder.decode(query.substring(6), StandardCharsets.UTF_8) : null;
				List<String> out = new ArrayList<>();
				boolean found = after == null;

				for (String c : comments) {
					if (found) {
						out.add(c);
					} else if (c.contains("\"id\":\"" + after + "\"")) {
						found = true;
					}
				}

				send(ex, 200, "{\"session\":{\"sessionId\":\"s1\",\"title\":\"回路\",\"host\":\"hana\",\"hostOnline\":true,\"viewerCount\":2,\"comments\":[]},"
						+ "\"comments\":[" + String.join(",", out) + "]}");
				return;
			}

			String[] err = nextPostError;

			if (err != null) {
				nextPostError = null;
				send(ex, Integer.parseInt(err[0]), "{\"error\":\"" + err[1] + "\",\"message\":\"x\"}");
				return;
			}

			if (auth == null) {
				if (!allowUnverified) {
					send(ex, 401, "{\"error\":\"signin_required\",\"message\":\"マイクラアカウントでのサインインが必要\"}");
					return;
				}
			} else {
				int n = Integer.parseInt(auth.substring("Bearer lv1.t".length()));

				if (n <= rejectTokensUpTo) {
					send(ex, 401, "{\"error\":\"signin_expired\",\"message\":\"x\"}");
					return;
				}
			}

			String text = JsonParser.parseString(body).getAsJsonObject().get("text").getAsString();
			String id = addComment(auth == null ? "表示名" : "Steve", text, false);
			send(ex, 201, "{\"comment\":" + comments.getLast() + "}");
			return;
		}

		sendText(ex, 404, "404 page not found\n");
	}

	private static void send(HttpExchange ex, int status, String json) throws IOException {
		ex.getResponseHeaders().set("Content-Type", "application/json");
		write(ex, status, json);
	}

	private static void sendText(HttpExchange ex, int status, String text) throws IOException {
		ex.getResponseHeaders().set("Content-Type", "text/plain");
		write(ex, status, text);
	}

	private static void write(HttpExchange ex, int status, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(status, b.length);

		try (OutputStream out = ex.getResponseBody()) {
			out.write(b);
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
