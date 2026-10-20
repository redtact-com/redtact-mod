package com.redtact.live.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.redtact.live.auth.HostAuth;
import com.redtact.live.comment.ViewerSignIn;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

/**
 * 開催中の一覧の問い合わせ (#90): 問い合わせの付け方・閲覧者のトークン (フォロー中だけ)・応答の following の読み方・
 * 古い中継サーバ・確かめられないアカウント、問い合わせを変えたときに古い答えで上書きしないこと。
 */
class LiveDirectoryTest {
	// ---- 中継サーバとのやり取り

	@Test
	void plainQueryHasNoTokenAndReadsViewsAndHostUser() throws Exception {
		try (Relay relay = new Relay()) {
			FakeJoiner joiner = new FakeJoiner(true);
			LiveDirectory.Result r = LiveDirectory.fetch(relay.api(), new ViewerSignIn(relay.api(), joiner),
					new LiveQuery("Hana 加算", LiveQuery.Sort.VIEWS, false));

			assertEquals(List.of("GET /api/sessions?q=Hana%20%E5%8A%A0%E7%AE%97&sort=views"), relay.requests, "フォロー中でなければトークンを付けない");
			assertEquals(0, joiner.joins.get(), "フォロー中でなければ join しない");
			assertNull(r.following());
			assertEquals(2, r.sessions().size());
			Api.Session s = r.sessions().get(0);
			assertEquals(12, s.views);
			assertEquals(3, s.viewerCount);
			assertEquals("はな", s.hostUser.name);
			assertEquals("https://redtact.com/users/u1", s.hostUser.url);
			assertNull(r.sessions().get(1).hostUser, "公開の連携が無いホストは null");
		}
	}

	@Test
	void followingAsksWithTheViewerToken() throws Exception {
		try (Relay relay = new Relay()) {
			FakeJoiner joiner = new FakeJoiner(true);
			ViewerSignIn signIn = new ViewerSignIn(relay.api(), joiner);
			LiveQuery q = LiveQuery.DEFAULT.withFollowing(true);

			LiveDirectory.Result r = LiveDirectory.fetch(relay.api(), signIn, q);
			assertEquals(LiveDirectory.FollowStatus.OK, r.following().status());
			assertEquals(3, r.following().accounts());
			assertEquals(2, r.sessions().size());
			// 2 回目はトークンを使い回す (join は 1 回)
			LiveDirectory.fetch(relay.api(), signIn, q.withQ("hana"));
			assertEquals(List.of("POST /api/auth/minecraft/challenge", "POST /api/auth/minecraft/token", "GET /api/sessions?following=1 Bearer lv1.t1",
					"GET /api/sessions?q=hana&following=1 Bearer lv1.t1"), relay.requests);
			assertEquals(1, joiner.joins.get());
		}
	}

	@Test
	void followingStatuses() throws Exception {
		try (Relay relay = new Relay()) {
			ViewerSignIn signIn = new ViewerSignIn(relay.api(), new FakeJoiner(true));
			LiveQuery q = LiveQuery.DEFAULT.withFollowing(true);

			relay.following = "{\"status\":\"not_linked\",\"accounts\":0}";
			LiveDirectory.Result notLinked = LiveDirectory.fetch(relay.api(), signIn, q);
			assertEquals(LiveDirectory.FollowStatus.NOT_LINKED, notLinked.following().status());
			assertTrue(notLinked.sessions().isEmpty(), "ok でなければ一覧は出さない");

			relay.following = "{\"status\":\"unavailable\",\"accounts\":0}";
			assertEquals(LiveDirectory.FollowStatus.UNAVAILABLE, LiveDirectory.fetch(relay.api(), signIn, q).following().status());

			relay.following = "{\"status\":\"something_new\"}";
			assertEquals(LiveDirectory.FollowStatus.UNAVAILABLE, LiveDirectory.fetch(relay.api(), signIn, q).following().status(), "知らない status");

			// 古い中継サーバ: following=1 を無視して全部を返す (following が無い) → 絞れていないので出さない
			relay.following = null;
			LiveDirectory.Result old = LiveDirectory.fetch(relay.api(), signIn, q);
			assertEquals(LiveDirectory.FollowStatus.UNSUPPORTED, old.following().status());
			assertTrue(old.sessions().isEmpty());
		}
	}

	@Test
	void unverifiedAccountDoesNotAsk() throws Exception {
		try (Relay relay = new Relay()) {
			LiveDirectory.Result r = LiveDirectory.fetch(relay.api(), new ViewerSignIn(relay.api(), new FakeJoiner(false)),
					LiveQuery.DEFAULT.withFollowing(true));
			assertEquals(LiveDirectory.FollowStatus.UNVERIFIED, r.following().status());
			assertEquals(HostAuth.JoinFailure.OFFLINE_ACCOUNT.reason(), r.following().reason());
			assertTrue(r.sessions().isEmpty());
			assertEquals(List.of(), relay.requests, "確かめられないアカウントは問い合わせない (401 signin_required になるだけ)");
		}
	}

	@Test
	void expiredTokenIsRefreshedOnce() throws Exception {
		try (Relay relay = new Relay()) {
			FakeJoiner joiner = new FakeJoiner(true);
			ViewerSignIn signIn = new ViewerSignIn(relay.api(), joiner);
			LiveQuery q = LiveQuery.DEFAULT.withFollowing(true);
			LiveDirectory.fetch(relay.api(), signIn, q);

			// 中継サーバの鍵が変わった (再起動など): 取り直して 1 回だけ問い合わせ直す
			relay.rejectUpTo = 1;
			LiveDirectory.Result r = LiveDirectory.fetch(relay.api(), signIn, q);
			assertEquals(LiveDirectory.FollowStatus.OK, r.following().status());
			assertEquals(2, joiner.joins.get());
			assertEquals("GET /api/sessions?following=1 Bearer lv1.t2", relay.requests.getLast());
		}
	}

	@Test
	void relayWithoutTheTokenEndpointIsUnsupported() throws Exception {
		try (Relay relay = new Relay()) {
			relay.noTokenEndpoint = true;
			LiveDirectory.Result r = LiveDirectory.fetch(relay.api(), new ViewerSignIn(relay.api(), new FakeJoiner(true)),
					LiveQuery.DEFAULT.withFollowing(true));
			assertEquals(LiveDirectory.FollowStatus.UNSUPPORTED, r.following().status());
			assertTrue(relay.requests.stream().noneMatch(x -> x.startsWith("GET /api/sessions")), relay.requests.toString());
		}
	}

	@Test
	void followingReading() {
		assertEquals(new LiveDirectory.Following(LiveDirectory.FollowStatus.UNSUPPORTED, 0, null), LiveDirectory.following(null));
		Api.Following ok = new Api.Following();
		ok.status = "ok";
		ok.accounts = -1;
		assertEquals(0, LiveDirectory.following(ok).accounts(), "負の数は 0");
		Api.SessionList list = new Api.SessionList();
		list.sessions = List.of(session("x"));
		assertNull(LiveDirectory.result(LiveQuery.DEFAULT, list).following(), "フォロー中でなければ following を見ない");
		assertEquals(1, LiveDirectory.result(LiveQuery.DEFAULT, list).sessions().size());
	}

	// ---- 問い合わせを変えたとき

	@Test
	void queuedOlderQueryIsSkipped() {
		ManualExecutor ex = new ManualExecutor();
		List<LiveQuery> fetched = new ArrayList<>();
		LiveDirectory d = new LiveDirectory(q -> {
			fetched.add(q);
			return new LiveDirectory.Result(List.of(session(q.q())), null);
		}, ex);
		LiveQuery a = LiveQuery.DEFAULT.withQ("a");
		LiveQuery b = LiveQuery.DEFAULT.withQ("b");

		d.refresh(a);
		d.refresh(b); // A がまだ順番を待っている間に打ち直した
		assertTrue(d.state().loading());
		ex.runAll();
		assertEquals(List.of(b), fetched, "順番を待つ間に古くなった問い合わせは取りにいかない");
		assertEquals(b, d.state().query());
		assertEquals("b", d.state().sessions().getFirst().title);
		assertFalse(d.state().loading());
		assertEquals(1, d.state().generation());
	}

	@Test
	void olderAnswerArrivingLateIsDropped() {
		ManualExecutor ex = new ManualExecutor();
		AtomicReference<LiveDirectory> ref = new AtomicReference<>();
		LiveQuery a = LiveQuery.DEFAULT.withQ("a");
		LiveQuery b = LiveQuery.DEFAULT.withFollowing(true);
		LiveDirectory d = new LiveDirectory(q -> {
			if (q.equals(a)) {
				ref.get().refresh(b); // A を取っている間に「フォロー中」を押した
			}

			return new LiveDirectory.Result(List.of(session(q.q())), q.following()
					? new LiveDirectory.Following(LiveDirectory.FollowStatus.OK, 2, null) : null);
		}, ex);
		ref.set(d);

		d.refresh(a);
		ex.runNext(); // A の答えはあとから届いたので捨てる
		assertEquals(0, d.state().generation(), "古い問い合わせの答えで一覧を変えない");
		assertEquals(LiveQuery.DEFAULT, d.state().query());
		assertTrue(d.state().loading(), "新しい問い合わせを取り終わるまで取得中");
		ex.runNext();
		assertEquals(b, d.state().query());
		assertEquals(LiveDirectory.FollowStatus.OK, d.state().following().status());
		assertFalse(d.state().loading());
		assertEquals(1, d.state().generation());
	}

	@Test
	void backAndForthFetchesOnlyTheLatest() {
		ManualExecutor ex = new ManualExecutor();
		List<LiveQuery> fetched = new ArrayList<>();
		LiveDirectory d = new LiveDirectory(q -> {
			fetched.add(q);
			return new LiveDirectory.Result(List.of(), null);
		}, ex);
		LiveQuery a = LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWS);

		d.refresh(a);
		d.refresh(LiveQuery.DEFAULT);
		d.refresh(a); // 押して戻した
		ex.runAll();
		assertEquals(List.of(a), fetched);
		assertEquals(a, d.state().query());
	}

	@Test
	void sameQueryWhileLoadingIsNotRepeated() {
		ManualExecutor ex = new ManualExecutor();
		LiveDirectory d = new LiveDirectory(q -> new LiveDirectory.Result(List.of(), null), ex);
		LiveQuery a = LiveQuery.DEFAULT.withQ("a");

		d.refresh(a);
		d.refresh(a); // 15 秒ごとの取り直しが取得中に来た
		assertEquals(1, ex.tasks.size());
		ex.runAll();
		d.refresh(a);
		assertEquals(1, ex.tasks.size(), "取り終わったら同じ問い合わせでも取り直す");
	}

	@Test
	void failureKeepsTheLastListAndItsQuery() {
		ManualExecutor ex = new ManualExecutor();
		LiveQuery bad = LiveQuery.DEFAULT.withQ("bad");
		LiveDirectory d = new LiveDirectory(q -> {
			if (q.equals(bad)) {
				throw new ApiException(400, "bad_request", "q が長すぎる");
			}

			return new LiveDirectory.Result(List.of(session("ok")), null);
		}, ex);

		d.refresh(LiveQuery.DEFAULT);
		ex.runAll();
		d.refresh(bad);
		ex.runAll();
		LiveDirectory.State s = d.state();
		assertNotNull(s.error());
		assertTrue(s.error().contains("q が長すぎる"), s.error());
		assertEquals(LiveQuery.DEFAULT, s.query(), "失敗したら前に取れた一覧とその問い合わせのまま");
		assertEquals("ok", s.sessions().getFirst().title);
		assertFalse(s.loading());
		assertEquals(2, s.generation());
		d.refresh(bad);
		assertEquals(1, ex.tasks.size(), "失敗したあとも同じ問い合わせで取り直せる");
	}

	@Test
	void nullSessionsAreIgnored() {
		ManualExecutor ex = new ManualExecutor();
		List<Api.Session> withNull = new ArrayList<>();
		withNull.add(session("a"));
		withNull.add(null);
		LiveDirectory d = new LiveDirectory(q -> new LiveDirectory.Result(withNull, null), ex);
		d.refresh(LiveQuery.DEFAULT);
		ex.runAll();
		assertEquals(1, d.state().sessions().size());
		assertNull(d.state().error());
	}

	@Test
	void searchUnsupportedOnOldRelay() {
		Api.Session old = session("a");
		Api.Session fresh = session("b");
		fresh.views = 0;
		LiveQuery q = LiveQuery.DEFAULT.withQ("a");
		assertTrue(state(q, List.of(old)).searchUnsupported(), "views の無い一覧は #90 より前の中継サーバ");
		assertTrue(state(LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWERS), List.of(old)).searchUnsupported());
		assertFalse(state(q, List.of(fresh)).searchUnsupported());
		assertFalse(state(q, List.of()).searchUnsupported(), "空なら分からない");
		assertFalse(state(LiveQuery.DEFAULT, List.of(old)).searchUnsupported(), "探していなければ出さない");
	}

	// ---- 補助

	private static LiveDirectory.State state(LiveQuery q, List<Api.Session> sessions) {
		return new LiveDirectory.State(sessions, null, null, false, 1, q, null);
	}

	private static Api.Session session(String title) {
		Api.Session s = new Api.Session();
		s.sessionId = "s" + Math.abs(title.hashCode() % 1000);
		s.title = title;
		return s;
	}

	/** 頼まれた取得を並べておき、テストが好きなときに走らせる。 */
	private static final class ManualExecutor implements Executor {
		final List<Runnable> tasks = new ArrayList<>();

		@Override
		public void execute(Runnable r) {
			tasks.add(r);
		}

		void runNext() {
			tasks.removeFirst().run();
		}

		void runAll() {
			while (!tasks.isEmpty()) {
				runNext();
			}
		}
	}

	/** join の偽物。verifiable なら join は名前を返す。 */
	private static final class FakeJoiner implements ViewerSignIn.Joiner {
		final boolean verifiable;
		final AtomicInteger joins = new AtomicInteger();

		FakeJoiner(boolean verifiable) {
			this.verifiable = verifiable;
		}

		@Override
		public boolean looksVerifiable() {
			return verifiable;
		}

		@Override
		public ViewerSignIn.JoinResult join(String serverId) {
			joins.incrementAndGet();
			return new ViewerSignIn.JoinResult("Steve", null);
		}
	}

	/** テスト用の中継サーバ (チャレンジ・閲覧者のトークン・開催中の一覧だけ。protocol.md の形)。 */
	private static final class Relay implements AutoCloseable {
		/** 受けたリクエスト (「メソッド パス?問い合わせ Authorization」)。 */
		final List<String> requests = new CopyOnWriteArrayList<>();
		final AtomicInteger tokens = new AtomicInteger();
		/** トークンの入口が無い古い中継サーバ (#74 より前。本文が JSON でない 404)。 */
		volatile boolean noTokenEndpoint;
		/** これ以前に出したトークンは signin_expired。 */
		volatile int rejectUpTo;
		/** following=1 への following (null なら付けない = #90 より前の中継サーバ)。 */
		volatile String following = "{\"status\":\"ok\",\"accounts\":3}";
		private final HttpServer server;

		Relay() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", this::handle);
			server.start();
		}

		LiveApi api() {
			return new LiveApi(LiveApi.newHttpClient(), () -> "http://127.0.0.1:" + server.getAddress().getPort(), () -> "表示名");
		}

		private void handle(HttpExchange ex) throws IOException {
			String method = ex.getRequestMethod();
			String path = ex.getRequestURI().getRawPath();
			String query = ex.getRequestURI().getRawQuery();
			String auth = ex.getRequestHeaders().getFirst("Authorization");
			requests.add(method + " " + path + (query == null ? "" : "?" + query) + (auth == null ? "" : " " + auth));
			ex.getRequestBody().readAllBytes();

			if (method.equals("POST") && path.equals("/api/auth/minecraft/challenge")) {
				send(ex, 200, "{\"challengeId\":\"mc_1\",\"serverId\":\"" + "ab".repeat(20) + "\",\"expiresAt\":\"x\"}");
				return;
			}

			if (method.equals("POST") && path.equals("/api/auth/minecraft/token")) {
				if (noTokenEndpoint) {
					write(ex, 404, "404 page not found\n");
					return;
				}

				send(ex, 200, "{\"token\":\"lv1.t" + tokens.incrementAndGet() + "\",\"expiresAt\":\"x\",\"viewer\":{\"name\":\"Steve\",\"verified\":true,"
						+ "\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}}");
				return;
			}

			if (method.equals("GET") && path.equals("/api/sessions")) {
				boolean follow = query != null && query.contains("following=1");

				if (follow && auth == null) {
					send(ex, 401, "{\"error\":\"signin_required\",\"message\":\"マイクラアカウントでのサインインが必要\"}");
					return;
				}

				if (follow && Integer.parseInt(auth.substring("Bearer lv1.t".length())) <= rejectUpTo) {
					send(ex, 401, "{\"error\":\"signin_expired\",\"message\":\"x\"}");
					return;
				}

				String f = following;
				send(ex, 200, "{\"sessions\":["
						+ "{\"sessionId\":\"a1B2c3D4e5\",\"title\":\"hana の 4bit 加算器\",\"host\":\"hana\",\"hostOnline\":true,\"listed\":true,\"viewerCount\":3,"
						+ "\"views\":12,\"hostUser\":{\"id\":\"u1\",\"name\":\"はな\",\"picture\":\"\",\"url\":\"https://redtact.com/users/u1\"},\"comments\":[]},"
						+ "{\"sessionId\":\"Zz9Yy8Xx7W\",\"title\":\"クロック\",\"host\":\"taro\",\"hostOnline\":true,\"listed\":true,\"viewerCount\":0,"
						+ "\"views\":1,\"hostUser\":null,\"comments\":[]}]"
						+ (follow && f != null ? ",\"following\":" + f : "") + "}");
				return;
			}

			write(ex, 404, "404 page not found\n");
		}

		private static void send(HttpExchange ex, int status, String json) throws IOException {
			ex.getResponseHeaders().set("Content-Type", "application/json");
			write(ex, status, json);
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
}
