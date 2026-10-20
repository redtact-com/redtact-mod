package com.redtact.live.net;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.JsonParseException;
import com.redtact.live.i18n.Tr;
import com.redtact.live.live.LiveQuery;
import com.redtact.live.state.CircuitDiff;
import com.redtact.live.state.CircuitState;
import com.redtact.live.state.Gzip;
import com.redtact.live.state.StateJson;
import org.jspecify.annotations.Nullable;

/**
 * 中継サーバの REST (protocol.md)。呼び出しは同期で、呼ぶ側が通信用のスレッドで使う。
 */
public final class LiveApi {
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration SEND_TIMEOUT = Duration.ofMinutes(2);
	/** これより大きい差分は gzip して送る。 */
	private static final int GZIP_THRESHOLD = 1024;

	private final HttpClient http;
	private final Supplier<String> baseUrl;
	private final Supplier<String> user;

	public LiveApi(HttpClient http, Supplier<String> baseUrl, Supplier<String> user) {
		this.http = http;
		this.baseUrl = baseUrl;
		this.user = user;
	}

	public static HttpClient newHttpClient() {
		return HttpClient.newBuilder()
				// Go の net/http は h2c のアップグレードを受けないので 1.1 に固定する
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public HttpClient http() {
		return http;
	}

	public String baseUrl() {
		return baseUrl.get();
	}

	public Api.CreateSessionResponse createSession(Api.CreateSessionRequest req) throws ApiException {
		HttpRequest request = json("/api/sessions", null).POST(body(req)).build();
		return send(request, Api.CreateSessionResponse.class, 201);
	}

	/** 404 なら empty (終了済み)。 */
	public Optional<Api.Session> getSession(String sessionId) throws ApiException {
		HttpRequest request = json("/api/sessions/" + seg(sessionId), null).GET().build();

		try {
			return Optional.of(send(request, Api.Session.class, 200));
		} catch (ApiException e) {
			if (e.isNotFound()) {
				return Optional.empty();
			}

			throw e;
		}
	}

	/** マイクラアカウントの確認に使うチャレンジ (60 秒・1 回だけ)。認証なし。 */
	public Api.Challenge minecraftChallenge() throws ApiException {
		HttpRequest request = json("/api/auth/minecraft/challenge", null).POST(HttpRequest.BodyPublishers.noBody()).build();
		return send(request, Api.Challenge.class, 200);
	}

	/**
	 * ゲームのアカウントで join したチャレンジを、閲覧者のトークンに替える (#74。Web のコードの入力と同じトークン)。認証なし。
	 * この入口が無い古い中継サーバは 404 (本文は JSON でないので code は http_404)。
	 */
	public Api.ViewerToken viewerToken(Api.MinecraftProof proof) throws ApiException {
		HttpRequest request = json("/api/auth/minecraft/token", null).POST(body(proof)).build();
		return send(request, Api.ViewerToken.class, 200);
	}

	/**
	 * コメントを書く (#74)。viewerToken が null なら付けずに送る (X-Live-User の表示名。開発用の設定の中継サーバだけが受け、
	 * ほかは 401 signin_required)。
	 */
	public Api.Comment postComment(String sessionId, String viewerToken, String text) throws ApiException {
		HttpRequest request = json("/api/sessions/" + seg(sessionId) + "/comments", viewerToken)
				.POST(body(new Api.PostCommentRequest(text)))
				.build();
		Api.Comment comment = send(request, Api.CommentResponse.class, 201).comment;

		if (comment == null || comment.id == null) {
			throw new ApiException(201, "bad_response", Tr.t("中継サーバの応答にコメントがありません"));
		}

		return comment;
	}

	/**
	 * セッション (comments は空) と、after より後のコメント (古い順) (#74)。after が null か空なら全部。認証なし。
	 * セッションが終わっていれば 404。この入口が無い古い中継サーバは 405 (code は http_405)。
	 */
	public Api.CommentsResponse comments(String sessionId, String after) throws ApiException {
		String query = after == null || after.isEmpty() ? "" : "?after=" + URLEncoder.encode(after, StandardCharsets.UTF_8);
		HttpRequest request = json("/api/sessions/" + seg(sessionId) + "/comments" + query, null).GET().build();
		Api.CommentsResponse res = send(request, Api.CommentsResponse.class, 200);

		if (res.comments == null) {
			res.comments = List.of();
		}

		return res;
	}

	/** 開催中の一覧 (listed のものだけ、新しい順)。認証なし。 */
	public List<Api.Session> listSessions() throws ApiException {
		return listSessions(LiveQuery.DEFAULT, null).sessions;
	}

	/**
	 * 開催中の一覧 (listed のものだけ。#90)。探す字・並べ替え・フォロー中は query の問い合わせ ({@link LiveQuery#queryString()}。
	 * 既定のものは付けない) で付ける。閲覧者のトークンはフォロー中のときだけ付ける (無ければ 401 signin_required、
	 * 期限切れは 401 signin_expired)。古い中継サーバは知らない問い合わせを無視して、新しい順の全部を following なしで返す。
	 */
	public Api.SessionList listSessions(LiveQuery query, @Nullable String viewerToken) throws ApiException {
		HttpRequest request = json("/api/sessions" + query.queryString(), query.following() ? viewerToken : null).GET().build();
		Api.SessionList list = send(request, Api.SessionList.class, 200);

		if (list.sessions == null) {
			list.sessions = List.of();
		}

		return list;
	}

	/** 一覧に出すかを変える (ホストだけ)。 */
	public Api.Session setListed(String sessionId, String hostToken, boolean listed) throws ApiException {
		HttpRequest request = json("/api/sessions/" + seg(sessionId), hostToken)
				.method("PATCH", body(new Api.UpdateSessionRequest(listed, null)))
				.build();
		return send(request, Api.SessionResponse.class, 200).session;
	}

	/** 複製 (.litematic の取得・読み込み・分岐) を許すかを変える (ホストだけ)。 */
	public Api.Session setAllowCopy(String sessionId, String hostToken, boolean allowCopy) throws ApiException {
		HttpRequest request = json("/api/sessions/" + seg(sessionId), hostToken)
				.method("PATCH", body(new Api.UpdateSessionRequest(null, allowCopy)))
				.build();
		return send(request, Api.SessionResponse.class, 200).session;
	}

	/** 最新の版の .litematic。fileName は Content-Disposition から (無ければ null)。 */
	public record Litematic(byte[] data, int version, String fileName) {
	}

	/** GET /api/sessions/{id}/litematic (認証なし)。403 copy_not_allowed・404 not_found は ApiException。 */
	public Litematic downloadLitematic(String sessionId) throws ApiException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/sessions/" + seg(sessionId) + "/litematic"))
				.timeout(SEND_TIMEOUT)
				.header("Accept", "application/octet-stream")
				.GET()
				.build();
		HttpResponse<byte[]> response;

		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		} catch (IOException e) {
			throw new ApiException(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(Tr.t("中断されました"), e);
		}

		if (response.statusCode() != 200) {
			throw errorOf(request, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
		}

		int version = response.headers().firstValue("X-Live-Version").map(v -> {
			try {
				return Integer.parseInt(v.trim());
			} catch (NumberFormatException e) {
				return 0;
			}
		}).orElse(0);
		String name = response.headers().firstValue("Content-Disposition").flatMap(com.redtact.live.copy.CopyLinks::fileName).orElse(null);
		return new Litematic(response.body(), version, name);
	}

	public void deleteSession(String sessionId, String hostToken) throws ApiException {
		HttpRequest request = json("/api/sessions/" + seg(sessionId), hostToken).DELETE().build();
		send(request, Void.class, 200, 204);
	}

	/** 回路の全体を送る (gzip)。最初・範囲の変更・再開・409 の後。 */
	public Api.Snapshot putState(String sessionId, String hostToken, CircuitState state) throws ApiException {
		byte[] json = Json.GSON.toJson(state.toJson()).getBytes(StandardCharsets.UTF_8);
		HttpRequest request = json("/api/sessions/" + seg(sessionId) + "/state", hostToken)
				.timeout(SEND_TIMEOUT)
				.header("Content-Encoding", "gzip")
				.PUT(HttpRequest.BodyPublishers.ofByteArray(Gzip.compress(json)))
				.build();
		return send(request, Api.SnapshotResponse.class, 200).snapshot;
	}

	/** 差分を送る。baseVersion がサーバと合わなければ 409 resync_required。 */
	public Api.Snapshot patchState(String sessionId, String hostToken, CircuitDiff diff) throws ApiException {
		byte[] json = Json.GSON.toJson(diff.toJson()).getBytes(StandardCharsets.UTF_8);
		HttpRequest.Builder b = json("/api/sessions/" + seg(sessionId) + "/state", hostToken).timeout(SEND_TIMEOUT);

		if (json.length > GZIP_THRESHOLD) {
			b.header("Content-Encoding", "gzip").method("PATCH", HttpRequest.BodyPublishers.ofByteArray(Gzip.compress(json)));
		} else {
			b.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(json));
		}

		return send(b.build(), Api.SnapshotResponse.class, 200).snapshot;
	}

	/** サーバが持っている回路の全体 (確認用)。404 なら empty。 */
	public Optional<StateJson.State> getState(String sessionId) throws ApiException {
		HttpRequest request = json("/api/sessions/" + seg(sessionId) + "/state", null).GET().build();

		try {
			return Optional.of(send(request, StateJson.State.class, 200));
		} catch (ApiException e) {
			if (e.isNotFound()) {
				return Optional.empty();
			}

			throw e;
		}
	}

	/** プレビュー用の全体とその版 (#34)。 */
	public record Preview(int version, StateJson.State state) {
	}

	/** 展開したプレビューの本文の上限 (範囲の上限 256×256×256 の 2 バイト × base64 より少し大きい)。 */
	private static final long MAX_PREVIEW_BYTES = 64L << 20;

	/**
	 * GET /api/sessions/{id}/preview (認証なし)。複製を許可していないセッションも返る (見るだけ)。
	 * 404 (まだ回路が無い・セッションが無い) は ApiException。
	 */
	public Preview getPreview(String sessionId) throws ApiException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/sessions/" + seg(sessionId) + "/preview"))
				.timeout(SEND_TIMEOUT)
				.header("Accept", "application/json")
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();
		HttpResponse<byte[]> response;
		byte[] body;

		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			body = response.body();

			if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) {
				body = Gzip.decompress(body, MAX_PREVIEW_BYTES);
			}
		} catch (IOException e) {
			throw new ApiException(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(Tr.t("中断されました"), e);
		}

		String text = new String(body, StandardCharsets.UTF_8);

		if (response.statusCode() != 200) {
			throw errorOf(request, response.statusCode(), text);
		}

		StateJson.State state;

		try {
			state = Json.GSON.fromJson(text, StateJson.State.class);
		} catch (JsonParseException e) {
			throw new ApiException(200, "bad_response", Tr.f("サーバの応答を読めません: {0}", e.getMessage()));
		}

		if (state == null) {
			throw new ApiException(200, "bad_response", Tr.t("サーバの応答が空です"));
		}

		int version = state.version != null ? state.version : response.headers().firstValue("X-Live-Version").map(v -> {
			try {
				return Integer.parseInt(v.trim());
			} catch (NumberFormatException e) {
				return 0;
			}
		}).orElse(0);
		return new Preview(version, state);
	}

	/** ws:// または wss:// の URI。 */
	public URI hostSocketUri(String sessionId) {
		String base = baseUrl();

		if (base.startsWith("https://")) {
			base = "wss://" + base.substring("https://".length());
		} else if (base.startsWith("http://")) {
			base = "ws://" + base.substring("http://".length());
		}

		return URI.create(base + "/ws/host/" + seg(sessionId));
	}

	private HttpRequest.Builder json(String path, String hostToken) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("X-Live-User", encodeUser(user.get()));

		if (hostToken != null) {
			b.header("Authorization", "Bearer " + hostToken);
		}

		return b;
	}

	/**
	 * X-Live-User の値。UTF-8 を percent-encode する (日本語の表示名をヘッダに載せるため。
	 * 空白は + になるが、サーバは QueryUnescape で戻す)。
	 */
	public static String encodeUser(String user) {
		return URLEncoder.encode(user, StandardCharsets.UTF_8);
	}

	private static HttpRequest.BodyPublisher body(Object o) {
		return HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(o), StandardCharsets.UTF_8);
	}

	private static String seg(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** 4xx/5xx の本文 ({error, message, currentVersion}) から ApiException を作る。 */
	private static ApiException errorOf(HttpRequest request, int status, String body) {
		String code = "http_" + status;
		String message = Tr.f("{0} {1} が失敗しました", request.method(), request.uri().getPath());
		Integer currentVersion = null;

		try {
			Api.ErrorBody err = Json.GSON.fromJson(body, Api.ErrorBody.class);

			if (err != null && err.error != null) {
				code = err.error;
				// サーバの文は日本語。英語の画面では知っている文を英語にする (#79)
				message = err.message != null ? ServerMessages.localize(err.message) : message;
				currentVersion = err.currentVersion;
			}
		} catch (JsonParseException ignored) {
			// エラー本文が JSON でないときはステータスだけ伝える
		}

		return new ApiException(status, code, message, currentVersion);
	}

	private <T> T send(HttpRequest request, Class<T> type, int... okStatuses) throws ApiException {
		HttpResponse<String> response;

		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new ApiException(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(Tr.t("中断されました"), e);
		}

		int status = response.statusCode();

		for (int ok : okStatuses) {
			if (status == ok) {
				if (type == Void.class) {
					return null;
				}

				try {
					T value = Json.GSON.fromJson(response.body(), type);

					if (value == null) {
						throw new ApiException(status, "bad_response", Tr.t("サーバの応答が空です"));
					}

					return value;
				} catch (JsonParseException e) {
					throw new ApiException(status, "bad_response", Tr.f("サーバの応答を読めません: {0}", e.getMessage()));
				}
			}
		}

		throw errorOf(request, status, response.body());
	}
}
