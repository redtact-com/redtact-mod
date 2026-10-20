package com.redtact.live.comment;

import java.util.function.LongSupplier;

import com.redtact.live.auth.HostAuth;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import org.jspecify.annotations.Nullable;

/**
 * MOD (ゲームの中) で閲覧者としてサインインする (#74)。ホストの確認と同じく、ゲームのアカウントで Mojang に join し、
 * 中継サーバが hasJoined で確かめて Web と同じ閲覧者のトークン (30 日) を返す。利用者の操作は要らない。
 *
 * <p>トークンはメモリだけに持つ (ゲームを起動して最初のコメントか、開催中の一覧のフォロー中 (#90) で 1 回 join する)。中継サーバの URL が変われば取り直す。
 * 確かめられないアカウント (オフライン・開発環境) はトークンを持たず、表示名 (X-Live-User) で送る
 * (中継サーバが開発用の設定のときだけ通る)。join に失敗したとき (Mojang に届かない・BAN など) も同じく表示名で送る
 * (本番の中継サーバでは 401 signin_required になり、理由を出す)。通信するので、ゲームのスレッドでは呼ばない。
 *
 * <p>トークンの入口が無い古い中継サーバは {@link #RETRY_UNSUPPORTED_MS} のあいだ覚え、送るたびに join しない。
 */
public final class ViewerSignIn {
	/** join の部分 (テストで差し替える。本物は {@link GameJoiner})。 */
	public interface Joiner {
		/** 確かめられそうか。false (オフラインのアカウント・開発環境) なら join しない。 */
		boolean looksVerifiable();

		/** Mojang に join する。 */
		JoinResult join(String serverId);
	}

	/**
	 * @param name          join できたときのプレイヤー名
	 * @param failureReason できなかったときの理由 (人向けの文)
	 */
	public record JoinResult(@Nullable String name, @Nullable String failureReason) {
		public boolean ok() {
			return failureReason == null && name != null;
		}
	}

	/**
	 * コメントを送るときの本人。
	 *
	 * @param token            閲覧者のトークン (確かめられなければ null。表示名で送る)
	 * @param uuid             サインインしたマイクラの UUID (確かめられなければ null)
	 * @param unverifiedReason 確かめられなかった理由 (確かめられたら null)
	 */
	public record Identity(@Nullable String token, @Nullable String uuid, @Nullable String unverifiedReason) {
	}

	/** トークンの入口が無かった中継サーバを、次に試すまで覚えておく時間。 */
	static final long RETRY_UNSUPPORTED_MS = 10 * 60_000;

	private final LiveApi api;
	private final Joiner joiner;
	private final LongSupplier clock;
	/** トークンの入口が無かった中継サーバの URL と、次に試してよい時刻。 */
	private @Nullable String unsupportedServer;
	private long unsupportedUntil;
	private @Nullable String token;
	/** token を取った中継サーバの URL。 */
	private @Nullable String tokenServer;
	private volatile @Nullable String uuid;

	public ViewerSignIn(LiveApi api, Joiner joiner) {
		this(api, joiner, System::currentTimeMillis);
	}

	ViewerSignIn(LiveApi api, Joiner joiner, LongSupplier clock) {
		this.api = api;
		this.joiner = joiner;
		this.clock = clock;
	}

	/**
	 * いまの本人。トークンを持っていればそれを、無ければチャレンジ → join → トークンの順で取る。
	 * 確かめられないアカウントは unverifiedReason 付きで返す (例外にしない。表示名で送れる中継サーバもあるため)。
	 *
	 * @throws ApiException チャレンジかトークンの取得に失敗した (Mojang に届かない・古い中継サーバなど)
	 */
	public synchronized Identity identity() throws ApiException {
		String server = api.baseUrl();

		if (token != null && server.equals(tokenServer)) {
			return new Identity(token, uuid, null);
		}

		token = null;

		if (!joiner.looksVerifiable()) {
			return new Identity(null, null, HostAuth.JoinFailure.OFFLINE_ACCOUNT.reason());
		}

		if (server.equals(unsupportedServer) && clock.getAsLong() < unsupportedUntil) {
			throw new ApiException(404, "http_404", Tr.t("中継サーバにトークンの入口がありません"));
		}

		Api.ViewerToken t;

		// join から中継サーバの確認 (hasJoined) までは、セッションの開始と重ねない
		synchronized (HostAuth.JOIN_LOCK) {
			Api.Challenge challenge = api.minecraftChallenge();
			JoinResult joined = joiner.join(challenge.serverId);

			if (!joined.ok()) {
				return new Identity(null, null, joined.failureReason() == null ? HostAuth.JoinFailure.OTHER.reason() : joined.failureReason());
			}

			try {
				t = api.viewerToken(new Api.MinecraftProof(challenge.challengeId, joined.name()));
			} catch (ApiException e) {
				if ("http_404".equals(e.code()) || "http_405".equals(e.code())) {
					unsupportedServer = server;
					unsupportedUntil = clock.getAsLong() + RETRY_UNSUPPORTED_MS;
				}

				throw e;
			}
		}

		if (t.token == null || t.token.isBlank()) {
			throw new ApiException(200, "bad_response", Tr.t("中継サーバがトークンを返しませんでした"));
		}

		token = t.token;
		tokenServer = server;
		uuid = t.viewer == null ? null : t.viewer.uuid;
		return new Identity(token, uuid, null);
	}

	/** トークンが通らなかった (期限切れ・中継サーバの鍵が変わった) ので捨てる。次の {@link #identity()} で取り直す。 */
	public synchronized void invalidate(@Nullable String rejected) {
		if (rejected != null && rejected.equals(token)) {
			token = null;
		}
	}

	/** サインインしたマイクラの UUID (まだサインインしていなければ null)。自分のコメントの印に使う。 */
	public @Nullable String uuid() {
		return uuid;
	}
}
