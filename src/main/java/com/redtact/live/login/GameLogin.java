package com.redtact.live.login;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.redtact.live.i18n.Tr;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ゲーム内の redtact のログイン (#50 / redtact#738)。ゲーム内のページが頼むと、127.0.0.1 の受け取り口
 * ({@link LoopbackServer}) を開き、外のブラウザで {@code <redtact>/login/game?method=&port=&state=} を開く。
 * 外のブラウザで Google / Apple のログインが済むと、redtact がその資格情報を受け取り口へ渡してくるので、頼んだページへ返す
 * (ページが signInWithCredential する)。同時に 1 つだけ。5 分で諦める。
 *
 * <p>このクラスは CEF の型を使わない (ページへ返すのは呼び出し側の {@link Reply} の受け取り手)。クライアントスレッドから呼ぶ。
 */
public final class GameLogin {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	/** 外のブラウザでのログインを待つ上限。 */
	public static final long TIMEOUT_MS = 5 * 60 * 1000;
	private static final Set<String> METHODS = Set.of("google", "apple");
	private static final SecureRandom RANDOM = new SecureRandom();

	/** ページへ返す結果。 */
	public enum Outcome {
		/** 資格情報を受け取った。 */
		CREDENTIAL,
		/** 外のブラウザで取り消した。 */
		CANCELLED,
		/** 失敗した (受け取り口を開けない・形が崩れている・redtact が失敗を返した)。 */
		FAILED,
		/** 時間内に終わらなかった。 */
		TIMEOUT,
		/** もう 1 つ待っている。 */
		BUSY;

		/** ページの受け口の reject の code (redtact の lib/gameShell.ts)。 */
		public String code() {
			return switch (this) {
				case CREDENTIAL -> "";
				case CANCELLED -> "game_cancelled";
				case TIMEOUT -> "game_timeout";
				case BUSY -> "game_busy";
				case FAILED -> "game_failed";
			};
		}
	}

	/** 結果。CREDENTIAL のときだけ method・idToken・rawNonce がある。 */
	public record Reply(Outcome outcome, @Nullable String method, @Nullable String idToken, @Nullable String rawNonce) {
		static Reply of(Outcome outcome) {
			return new Reply(outcome, null, null, null);
		}
	}

	private record Pending(String state, String method, LoopbackServer server, Consumer<Reply> reply, long deadline) {
	}

	/** 利用者への知らせ (既定は {@link Messages}。テストで差し替える)。 */
	public interface Notices {
		void notice(String title, String text);

		void info(String text);

		void error(String text);

		Notices TOASTS = new Notices() {
			@Override
			public void notice(String title, String text) {
				Messages.notice(title, text);
			}

			@Override
			public void info(String text) {
				Messages.info(text);
			}

			@Override
			public void error(String text) {
				Messages.error(text);
			}
		};
	}

	private final Consumer<String> opener;
	private final Executor clientThread;
	private final LongSupplier clock;
	private final Notices notices;
	private @Nullable Pending pending;

	/**
	 * @param opener 外のブラウザで URL を開く
	 * @param clientThread 受け取り口のスレッドからクライアントスレッドへ移す
	 * @param clock 今の時刻 (ミリ秒。テストで差し替える)
	 */
	public GameLogin(Consumer<String> opener, Executor clientThread, LongSupplier clock) {
		this(opener, clientThread, clock, Notices.TOASTS);
	}

	public GameLogin(Consumer<String> opener, Executor clientThread, LongSupplier clock, Notices notices) {
		this.opener = opener;
		this.clientThread = clientThread;
		this.clock = clock;
		this.notices = notices;
	}

	/** 待っているか。 */
	public boolean pending() {
		return pending != null;
	}

	/** 待っている受け取り口のポート (テスト用)。待っていなければ -1。 */
	public int port() {
		Pending p = pending;
		return p == null ? -1 : p.server().port();
	}

	/**
	 * ログインを始める。origin は頼んだページの redtact (例 https://redtact.com)。reply は 1 回だけ、クライアントスレッドで呼ぶ。
	 */
	public void start(String origin, String method, Consumer<Reply> reply) {
		if (!METHODS.contains(method)) {
			reply.accept(Reply.of(Outcome.FAILED));
			return;
		}

		if (pending != null) {
			reply.accept(Reply.of(Outcome.BUSY));
			notices.notice(Tr.t("外のブラウザでログインの途中です"), Tr.t("そちらを済ませてから、もう一度お試しください"));
			return;
		}

		String state = randomState();
		LoopbackServer server;

		try {
			server = new LoopbackServer(state, r -> clientThread.execute(() -> finish(state, r)));
		} catch (IOException e) {
			LOGGER.warn("[redtact-live] ログインの受け取り口を開けません", e);
			reply.accept(Reply.of(Outcome.FAILED));
			notices.error(Tr.f("ログインの受け取り口を開けません: {0}", e.getMessage()));
			return;
		}

		pending = new Pending(state, method, server, reply, clock.getAsLong() + TIMEOUT_MS);
		String url = origin + "/login/game?method=" + method + "&port=" + server.port() + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
		LOGGER.info("[redtact-live] ゲーム内のログイン: 外のブラウザで {}/login/game を開く (port {})", origin, server.port());
		opener.accept(url);
		notices.notice(Tr.t("外のブラウザでログインしてください"),
				Tr.f("{0} のログインが済んだらゲームに戻ってください (5 分以内)", method.equals("google") ? "Google" : "Apple"));
	}

	/** クライアントの tick ごとに呼ぶ。時間切れなら諦める。 */
	public void tick() {
		Pending p = pending;

		if (p != null && clock.getAsLong() > p.deadline()) {
			end(p, Reply.of(Outcome.TIMEOUT));
			notices.error(Tr.t("外のブラウザでのログインが時間内に終わりませんでした。もう一度お試しください"));
		}
	}

	/** やめる (ゲームを閉じるときなど)。 */
	public void cancel() {
		Pending p = pending;

		if (p != null) {
			end(p, Reply.of(Outcome.CANCELLED));
		}
	}

	private void finish(String state, LoopbackServer.Result r) {
		Pending p = pending;

		if (p == null || !p.state().equals(state)) {
			return; // 時間切れのあとに届いた
		}

		String result = r.get("result");
		String method = r.get("method");

		if ("credential".equals(result)) {
			String idToken = r.get("id_token");
			String rawNonce = r.get("raw_nonce");

			if (!p.method().equals(method) || idToken == null || idToken.isBlank() || rawNonce == null) {
				end(p, Reply.of(Outcome.FAILED));
				notices.error(Tr.t("外のブラウザから受け取ったログインの情報が崩れていました。もう一度お試しください"));
				return;
			}

			end(p, new Reply(Outcome.CREDENTIAL, method, idToken, rawNonce));
			notices.notice(Tr.t("ログインの情報を受け取りました"), Tr.t("ゲーム内の redtact でログインしています"));
			return;
		}

		if ("cancelled".equals(result)) {
			end(p, Reply.of(Outcome.CANCELLED));
			notices.info(Tr.t("ログインを中止しました"));
			return;
		}

		LOGGER.info("[redtact-live] 外のブラウザでのログインに失敗: {}", r.get("reason"));
		end(p, Reply.of(Outcome.FAILED));
		notices.error(Tr.t("外のブラウザでのログインに失敗しました。もう一度お試しください"));
	}

	private void end(Pending p, Reply reply) {
		pending = null;
		p.server().close();

		try {
			p.reply().accept(reply);
		} catch (RuntimeException e) {
			LOGGER.warn("[redtact-live] ログインの結果をページへ返せません", e);
		}
	}

	/** 32 バイトの乱数 (URL に載せられる形)。 */
	static String randomState() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}
}
