package com.redtact.live.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParseException;
import com.redtact.live.i18n.Tr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ホストの WebSocket ({@code /ws/host/{id}})。切れたら指数バックオフで張り直す。
 *
 * <p>再接続しないのは次のとき: {@code closed} を受けた・close code 4001 (別の接続に置き換え)・
 * ハンドシェイクが 404 (セッション終了済み) / 401 / 403・{@link #stop()}。
 * ハンドラは {@code callbackExecutor} の上で 1 つずつ呼ぶ。
 */
public final class HostSocket {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	public static final int CLOSE_REPLACED = 4001;
	private static final long MIN_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 60_000;
	/** サーバは 30 秒ごとに ping する。これだけ何も来なければ死んだ接続とみなす。 */
	private static final long SILENCE_LIMIT_MS = 75_000;

	public interface Handler {
		void onHello(Api.Session session, List<Api.Comment> pending, boolean reconnected);

		void onComment(Api.Comment comment);

		void onViewers(int count);

		/** サーバ側でセッションが終わった ({@code closed} の reason、またはハンドシェイク 404 なら "not_found")。 */
		void onEnded(String reason);

		/** トークンが通らない (401/403)。 */
		void onRejected(int status);

		/** 別の接続に置き換えられた (close code 4001)。 */
		void onReplaced();

		/** このセッションから分岐した子の一覧 (全体) が変わった。 */
		default void onBranches(List<Api.Branch> branches) {
		}

		/** 分岐元が終わった (parent.ended が true)。 */
		default void onParent(Api.Parent parent) {
		}

		/** 切れた。retryMillis 後に張り直す。attempt は 0 始まりの連続失敗回数。 */
		void onDisconnected(String why, long retryMillis, int attempt);
	}

	private final HttpClient http;
	private final URI uri;
	private final String hostToken;
	private final String user;
	private final Handler handler;
	private final Executor callbackExecutor;
	private final ScheduledExecutorService scheduler;

	// 以下は this で守る
	private int generation;
	private WebSocket socket;
	private boolean stopped;
	private boolean everConnected;
	private int attempt;
	private long lastActivity;
	private ScheduledFuture<?> watchdog;
	private ScheduledFuture<?> reconnect;
	private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

	public HostSocket(HttpClient http, URI uri, String hostToken, String user, Handler handler, Executor callbackExecutor, ScheduledExecutorService scheduler) {
		this.http = http;
		this.uri = uri;
		this.hostToken = hostToken;
		this.user = user;
		this.handler = handler;
		this.callbackExecutor = callbackExecutor;
		this.scheduler = scheduler;
	}

	public synchronized void start() {
		if (watchdog == null) {
			watchdog = scheduler.scheduleAtFixedRate(this::checkSilence, 15, 15, TimeUnit.SECONDS);
		}

		connect();
	}

	public synchronized boolean isConnected() {
		return socket != null && !stopped;
	}

	/** 張り直しをやめて閉じる。 */
	public synchronized void stop() {
		if (stopped) {
			return;
		}

		stopped = true;
		generation++;

		if (watchdog != null) {
			watchdog.cancel(false);
		}

		if (reconnect != null) {
			reconnect.cancel(false);
		}

		WebSocket ws = socket;
		socket = null;

		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
					.orTimeout(3, TimeUnit.SECONDS)
					.whenComplete((r, e) -> ws.abort());
		}
	}

	/** ローカルに保存できたコメントを知らせる。 */
	public synchronized void sendAck(List<String> commentIds) {
		if (commentIds.isEmpty() || socket == null) {
			return;
		}

		WebSocket ws = socket;
		String text = Json.GSON.toJson(new Api.AckMessage(commentIds));
		sendChain = sendChain
				.handle((r, e) -> null)
				.thenCompose(v -> ws.sendText(text, true))
				.whenComplete((r, e) -> {
					if (e != null) {
						// 届かなければ次の hello.pending でもう一度来るので、そのとき ack し直す
						LOGGER.warn("[redtact-live] ack を送れませんでした: {}", e.toString());
					}
				});
	}

	private synchronized void connect() {
		if (stopped) {
			return;
		}

		int gen = ++generation;
		reconnect = null;
		http.newWebSocketBuilder()
				.header("Authorization", "Bearer " + hostToken)
				.header("X-Live-User", LiveApi.encodeUser(user))
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(uri, new Listener(gen))
				.whenComplete((ws, err) -> onConnectResult(gen, ws, err));
	}

	private synchronized void onConnectResult(int gen, WebSocket ws, Throwable err) {
		if (gen != generation) {
			if (ws != null) {
				ws.abort();
			}

			return;
		}

		if (err == null) {
			socket = ws;
			lastActivity = System.currentTimeMillis();
			return;
		}

		Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;

		if (cause instanceof WebSocketHandshakeException hs) {
			int status = hs.getResponse().statusCode();

			if (status == 404) {
				terminate();
				callbackExecutor.execute(() -> handler.onEnded("not_found"));
				return;
			}

			if (status == 401 || status == 403) {
				terminate();
				callbackExecutor.execute(() -> handler.onRejected(status));
				return;
			}

			scheduleReconnect("HTTP " + status);
			return;
		}

		scheduleReconnect(cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
	}

	/** 再接続しない状態にする (ハンドラは呼ばない)。 */
	private void terminate() {
		stopped = true;
		generation++;
		socket = null;

		if (watchdog != null) {
			watchdog.cancel(false);
		}

		if (reconnect != null) {
			reconnect.cancel(false);
		}
	}

	private void scheduleReconnect(String why) {
		if (stopped) {
			return;
		}

		socket = null;
		generation++;
		long base = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << Math.min(attempt, 16));
		long delay = base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
		int n = attempt++;
		LOGGER.info("[redtact-live] WebSocket が切れました ({})。{} ms 後に再接続 (#{})", why, delay, n + 1);
		callbackExecutor.execute(() -> handler.onDisconnected(why, delay, n));
		reconnect = scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
	}

	private void checkSilence() {
		WebSocket stale = null;

		synchronized (this) {
			if (stopped || socket == null) {
				return;
			}

			if (System.currentTimeMillis() - lastActivity > SILENCE_LIMIT_MS) {
				stale = socket;
				scheduleReconnect(Tr.t("応答なし"));
			}
		}

		if (stale != null) {
			stale.abort();
		}
	}

	private synchronized void opened(int gen, WebSocket ws) {
		if (gen == generation) {
			socket = ws;
			lastActivity = System.currentTimeMillis();
		}
	}

	private synchronized boolean touch(int gen) {
		if (gen != generation) {
			return false;
		}

		lastActivity = System.currentTimeMillis();
		return true;
	}

	private void onMessage(int gen, String text) {
		Api.WsMessage msg;

		try {
			msg = Json.GSON.fromJson(text, Api.WsMessage.class);
		} catch (JsonParseException e) {
			LOGGER.warn("[redtact-live] 読めないメッセージ: {}", text);
			return;
		}

		if (msg == null || msg.type == null) {
			return;
		}

		synchronized (this) {
			if (gen != generation) {
				return;
			}

			switch (msg.type) {
				case "hello" -> {
					boolean reconnected = everConnected;
					everConnected = true;
					attempt = 0;
					Api.Session session = msg.session;
					List<Api.Comment> pending = msg.pending == null ? List.of() : msg.pending;
					callbackExecutor.execute(() -> handler.onHello(session, pending, reconnected));
				}
				case "comment" -> {
					if (msg.comment != null) {
						Api.Comment c = msg.comment;
						callbackExecutor.execute(() -> handler.onComment(c));
					}
				}
				case "viewers" -> {
					int count = msg.count == null ? 0 : msg.count;
					callbackExecutor.execute(() -> handler.onViewers(count));
				}
				case "branches" -> {
					List<Api.Branch> branches = msg.branches == null ? List.of() : List.copyOf(msg.branches);
					callbackExecutor.execute(() -> handler.onBranches(branches));
				}
				case "parent" -> {
					if (msg.parent != null) {
						Api.Parent p = msg.parent;
						callbackExecutor.execute(() -> handler.onParent(p));
					}
				}
				case "closed" -> {
					String reason = msg.reason == null ? "closed" : msg.reason;
					terminate();
					callbackExecutor.execute(() -> handler.onEnded(reason));
				}
				default -> LOGGER.debug("[redtact-live] 未知のメッセージ: {}", msg.type);
			}
		}
	}

	private synchronized void onClosed(int gen, int code, String reason) {
		if (gen != generation) {
			return;
		}

		if (code == CLOSE_REPLACED) {
			terminate();
			callbackExecutor.execute(handler::onReplaced);
			return;
		}

		scheduleReconnect("close " + code + (reason == null || reason.isEmpty() ? "" : " " + reason));
	}

	private synchronized void onFailed(int gen, Throwable error) {
		if (gen != generation) {
			return;
		}

		scheduleReconnect(error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
	}

	private final class Listener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder buffer = new StringBuilder();

		Listener(int gen) {
			this.gen = gen;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			opened(gen, webSocket);
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);

			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);

				if (touch(gen)) {
					onMessage(gen, text);
				}
			}

			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			touch(gen);
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
			// pong は java.net.http が自動で返す
			touch(gen);
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
			touch(gen);
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			onClosed(gen, statusCode, reason);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			onFailed(gen, error);
		}
	}
}
