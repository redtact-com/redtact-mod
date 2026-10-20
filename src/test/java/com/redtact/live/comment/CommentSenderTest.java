package com.redtact.live.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.redtact.live.net.ApiException;
import org.junit.jupiter.api.Test;

/** MOD からのコメント (#74): ゲームのアカウントでのサインイン・送り直し・表示名・古い中継サーバ・文言。 */
class CommentSenderTest {
	/** join の偽物。verifiable なら join は名前を返す。 */
	private static final class FakeJoiner implements ViewerSignIn.Joiner {
		final boolean verifiable;
		final String failure;
		final AtomicInteger joins = new AtomicInteger();

		FakeJoiner(boolean verifiable, String failure) {
			this.verifiable = verifiable;
			this.failure = failure;
		}

		@Override
		public boolean looksVerifiable() {
			return verifiable;
		}

		@Override
		public ViewerSignIn.JoinResult join(String serverId) {
			joins.incrementAndGet();
			assertEquals(40, serverId.length(), "チャレンジの serverId で join する");
			return failure == null ? new ViewerSignIn.JoinResult("Steve", null) : new ViewerSignIn.JoinResult(null, failure);
		}
	}

	private static CommentSender sender(FakeRelay relay, FakeJoiner joiner) {
		var api = relay.api("表示名");
		return new CommentSender(api, new ViewerSignIn(api, joiner), Runnable::run);
	}

	@Test
	void signsInOnceWithTheGameAccountAndReusesTheToken() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			FakeJoiner joiner = new FakeJoiner(true, null);
			CommentSender s = sender(relay, joiner);

			CommentSender.Result r1 = s.sendNow("s1", "ここのリピーター 2 tick にすると…");
			assertTrue(r1.ok(), r1.error());
			assertEquals("Steve", r1.comment().author);
			assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", s.signIn().uuid());
			CommentSender.Result r2 = s.sendNow("s2", "2 件目");
			assertTrue(r2.ok(), r2.error());

			// join とトークンは 1 回だけ。コメントは Bearer のトークンで送る
			assertEquals(1, joiner.joins.get());
			assertEquals(1, relay.tokensIssued.get());
			assertEquals(List.of("POST /api/auth/minecraft/challenge", "POST /api/auth/minecraft/token",
					"POST /api/sessions/s1/comments Bearer lv1.t1", "POST /api/sessions/s2/comments Bearer lv1.t1"), relay.requests);
		}
	}

	@Test
	void expiredTokenIsRefreshedOnce() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			FakeJoiner joiner = new FakeJoiner(true, null);
			CommentSender s = sender(relay, joiner);
			assertTrue(s.sendNow("s1", "1 件目").ok());

			// 中継サーバの鍵が変わった (再起動など): 取り直して送り直す
			relay.rejectTokensUpTo = 1;
			CommentSender.Result r = s.sendNow("s1", "2 件目");
			assertTrue(r.ok(), r.error());
			assertEquals(2, relay.tokensIssued.get());
			assertEquals(2, joiner.joins.get());
			assertTrue(relay.requests.contains("POST /api/sessions/s1/comments Bearer lv1.t2"), relay.requests.toString());

			// 取り直しても通らなければ送れない (無限には繰り返さない)
			relay.rejectTokensUpTo = 100;
			CommentSender.Result again = s.sendNow("s1", "3 件目");
			assertFalse(again.ok());
			assertEquals("サインインし直しても通りませんでした。しばらくしてから送ってください", again.error());
			assertEquals(3, relay.tokensIssued.get());
		}
	}

	@Test
	void unverifiableAccountSendsTheDisplayName() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			FakeJoiner joiner = new FakeJoiner(false, null);
			CommentSender s = sender(relay, joiner);

			// 開発用の設定の中継サーバだけが受ける
			CommentSender.Result denied = s.sendNow("s1", "こんにちは");
			assertFalse(denied.ok());
			assertTrue(denied.error().contains("マイクラアカウントで確認できないため送れません (オフラインのアカウントか開発環境のため)"), denied.error());
			relay.allowUnverified = true;
			CommentSender.Result ok = s.sendNow("s1", "こんにちは");
			assertTrue(ok.ok(), ok.error());
			assertEquals("表示名", ok.comment().author);

			// join もチャレンジもしない
			assertEquals(0, joiner.joins.get());
			assertEquals(List.of("POST /api/sessions/s1/comments", "POST /api/sessions/s1/comments"), relay.requests);
			assertNull(s.signIn().uuid());
		}
	}

	@Test
	void failedJoinFallsBackToTheDisplayName() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			CommentSender s = sender(relay, new FakeJoiner(true, "Mojang の認証サーバに接続できないため"));
			CommentSender.Result r = s.sendNow("s1", "x");
			assertTrue(r.error().contains("(Mojang の認証サーバに接続できないため)"), r.error());
			assertEquals(0, relay.tokensIssued.get());
		}
	}

	@Test
	void oldServerWithoutTheTokenEndpoint() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			relay.oldServer = true;
			FakeJoiner joiner = new FakeJoiner(true, null);
			AtomicLong now = new AtomicLong(0);
			var api = relay.api("表示名");
			CommentSender s = new CommentSender(api, new ViewerSignIn(api, joiner, now::get), Runnable::run);
			CommentSender.Result r = s.sendNow("s1", "x");
			assertEquals("中継サーバがゲームからのコメントに対応していません (中継サーバの更新待ち)", r.error());
			assertEquals(1, joiner.joins.get());

			// 古い中継サーバはしばらく覚えて、送るたびに join しない
			assertEquals(r.error(), s.sendNow("s1", "y").error());
			assertEquals(1, joiner.joins.get());
			// 時間がたったら試し直す (中継サーバを入れ替えたあと)
			relay.oldServer = false;
			now.set(ViewerSignIn.RETRY_UNSUPPORTED_MS);
			assertTrue(s.sendNow("s1", "z").ok());
			assertEquals(2, joiner.joins.get());
		}
	}

	@Test
	void sendAlwaysCallsBack() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			ViewerSignIn.Joiner broken = new ViewerSignIn.Joiner() {
				@Override
				public boolean looksVerifiable() {
					throw new AssertionError("壊れた join");
				}

				@Override
				public ViewerSignIn.JoinResult join(String serverId) {
					throw new AssertionError();
				}
			};
			var api = relay.api("表示名");
			CommentSender s = new CommentSender(api, new ViewerSignIn(api, broken), Runnable::run);
			java.util.concurrent.CompletableFuture<CommentSender.Result> done = new java.util.concurrent.CompletableFuture<>();
			s.send("s1", "x", done::complete);
			CommentSender.Result r = done.get(5, java.util.concurrent.TimeUnit.SECONDS);
			assertFalse(r.ok());
			assertTrue(r.error().contains("壊れた join"), r.error());
			s.shutdown();
		}
	}

	@Test
	void textIsCheckedBeforeSending() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			CommentSender s = sender(relay, new FakeJoiner(true, null));
			assertEquals("コメントは 1〜1000 文字で書いてください", s.sendNow("s1", "").error());
			assertEquals("コメントは 1〜1000 文字で書いてください", s.sendNow("s1", "あ".repeat(1001)).error());
			// 絵文字 (サロゲートペア) は 1 文字に数える
			assertTrue(s.sendNow("s1", "🔴".repeat(1000)).ok());
			assertEquals(1, relay.requests.stream().filter(x -> x.startsWith("POST /api/sessions/")).count());
		}
	}

	@Test
	void serverErrorsBecomeMessages() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			CommentSender s = sender(relay, new FakeJoiner(true, null));
			relay.nextPostError = new String[] {"429", "rate_limited"};
			assertEquals("続けて送れるのは 2 秒に 1 件までです", s.sendNow("s1", "x").error());
			relay.nextPostError = new String[] {"422", "moderation_rejected"};
			assertEquals("コメントが投稿の基準に合わないため送れませんでした", s.sendNow("s1", "x").error());
			relay.ended = true;
			assertEquals("セッションが見つからないか、終了しています", s.sendNow("s1", "x").error());
		}
	}

	@Test
	void errorTexts() {
		assertEquals("中継サーバに届きません (ConnectException)", CommentSender.errorText(new ApiException("ConnectException", new RuntimeException()), null));
		assertEquals("このセッションのコメントが上限に達しました", CommentSender.errorText(new ApiException(429, "limit_reached", "x"), null));
		assertEquals("いまはコメントを受け付けていません。しばらくしてから送ってください",
				CommentSender.errorText(new ApiException(503, "moderation_unavailable", "x"), null));
		assertEquals("中継サーバが Mojang に問い合わせられませんでした。しばらくしてから送ってください",
				CommentSender.errorText(new ApiException(503, "minecraft_unavailable", "x"), null));
		assertEquals("中継サーバがゲームからのコメントに対応していません (中継サーバの更新待ち)", CommentSender.errorText(new ApiException(405, "http_405", "x"), null));
		assertTrue(CommentSender.errorText(new ApiException(401, "signin_required", "x"), null).contains("(理由不明)"));
		assertEquals("何か [500 http_500]", CommentSender.errorText(new ApiException(500, "http_500", "何か"), null));
	}
}
