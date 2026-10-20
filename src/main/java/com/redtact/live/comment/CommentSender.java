package com.redtact.live.comment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MOD からコメントを書く (#74。自分のセッションにも他人のセッションにも)。通信は専用のスレッドで 1 件ずつ行い、
 * 結果は callback (ゲームのスレッド) で返す。本人は {@link ViewerSignIn} (ゲームのアカウント) で、
 * トークンが通らなければ (401 signin_expired) 1 回だけ取り直して送り直す。
 */
public final class CommentSender {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	/** 中継サーバが受ける長さ (protocol.md の Comment.text)。 */
	public static final int MAX_CHARS = 1000;

	/** 送った結果。comment か error のどちらか。 */
	public record Result(Api.@Nullable Comment comment, @Nullable String error) {
		public boolean ok() {
			return comment != null;
		}
	}

	private final LiveApi api;
	private final ViewerSignIn signIn;
	private final Executor callback;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-comment").factory());

	public CommentSender(LiveApi api, ViewerSignIn signIn, Executor callback) {
		this.api = api;
		this.signIn = signIn;
		this.callback = callback;
	}

	public ViewerSignIn signIn() {
		return signIn;
	}

	/** 送る (前後の空白は除く)。done はゲームのスレッドで 1 回だけ呼ばれる。 */
	public void send(String sessionId, String text, Consumer<Result> done) {
		String body = text.strip();
		executor.execute(() -> {
			Result r;

			try {
				r = sendNow(sessionId, body);
			} catch (Throwable t) {
				// 何が起きても結果は返す (返さないと入力欄が「送信中…」のまま残る)
				LOGGER.error("[redtact-live] コメントを送れません", t);
				r = new Result(null, Tr.f("コメントを送れませんでした: {0}", t));
			}

			Result result = r;
			callback.execute(() -> done.accept(result));
		});
	}

	/** 送る (呼んだスレッドで通信する。テストからも呼ぶ)。 */
	Result sendNow(String sessionId, String text) {
		int n = text.codePointCount(0, text.length());

		if (n == 0 || n > MAX_CHARS) {
			return new Result(null, Tr.f("コメントは 1〜{0} 文字で書いてください", MAX_CHARS));
		}

		ViewerSignIn.Identity id = null;

		try {
			id = signIn.identity();

			try {
				return new Result(api.postComment(sessionId, id.token(), text), null);
			} catch (ApiException e) {
				if (id.token() == null || !"signin_expired".equals(e.code())) {
					throw e;
				}

				// トークンの期限切れ・中継サーバの鍵が変わった: 取り直して 1 回だけ送り直す
				signIn.invalidate(id.token());
				id = signIn.identity();
				return new Result(api.postComment(sessionId, id.token(), text), null);
			}
		} catch (ApiException e) {
			LOGGER.info("[redtact-live] コメントを送れません: {}", e.describe());
			return new Result(null, errorText(e, id == null ? null : id.unverifiedReason()));
		} catch (RuntimeException e) {
			LOGGER.warn("[redtact-live] コメントを送れません", e);
			return new Result(null, Tr.f("コメントを送れませんでした: {0}", e));
		}
	}

	/**
	 * 送れなかったときの文言 (MC に依存しない。テストで確かめる)。
	 *
	 * @param unverifiedReason ゲームのアカウントで確かめられなかった理由 (確かめられたなら null)
	 */
	public static String errorText(ApiException e, @Nullable String unverifiedReason) {
		if (e.status() == 0) {
			return Tr.f("中継サーバに届きません ({0})", e.getMessage());
		}

		return switch (e.code()) {
			case "bad_request" -> Tr.f("送れませんでした: {0}", e.getMessage());
			case "signin_required" -> Tr.f("マイクラアカウントで確認できないため送れません ({0})。中継サーバが確認なしのコメントを受け付けていません",
					unverifiedReason == null ? Tr.t("理由不明") : unverifiedReason);
			case "signin_expired" -> Tr.t("サインインし直しても通りませんでした。しばらくしてから送ってください");
			case "minecraft_unverified" -> Tr.t("マイクラアカウントの確認に失敗しました (中継サーバが Mojang で確かめられませんでした)。もう一度送ってください");
			case "minecraft_unavailable" -> Tr.t("中継サーバが Mojang に問い合わせられませんでした。しばらくしてから送ってください");
			case "capacity_exceeded" -> Tr.t("確認の受付が混み合っています。しばらくしてから送ってください");
			case "not_found" -> Tr.t("セッションが見つからないか、終了しています");
			// 入口が無い古い中継サーバ (本文が JSON でない 404・405)
			case "http_404", "http_405" -> Tr.t("中継サーバがゲームからのコメントに対応していません (中継サーバの更新待ち)");
			case "moderation_rejected" -> Tr.t("コメントが投稿の基準に合わないため送れませんでした");
			case "moderation_unavailable" -> Tr.t("いまはコメントを受け付けていません。しばらくしてから送ってください");
			case "rate_limited" -> Tr.t("続けて送れるのは 2 秒に 1 件までです");
			case "limit_reached" -> Tr.t("このセッションのコメントが上限に達しました");
			default -> e.describe();
		};
	}

	public void shutdown() {
		executor.shutdownNow();
	}
}
