package com.redtact.live.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import com.redtact.live.net.Api;
import org.junit.jupiter.api.Test;

/** 開催中の詳細のコメントの取得 (#74): 最初は全部・以後は新着だけ・書いたものはすぐ出す・終わったら止める。 */
class CommentWatchTest {
	private static Api.Comment comment(String id, String text) {
		Api.Comment c = new Api.Comment();
		c.id = id;
		c.text = text;
		return c;
	}

	private static List<String> texts(CommentWatch w) {
		return w.state().comments().stream().map(c -> c.text).toList();
	}

	private static void waitFor(CommentWatch w, Predicate<CommentWatch.State> p) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;

		while (!p.test(w.state())) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("待ちきれない: " + w.state());
			}

			Thread.sleep(10);
		}
	}

	@Test
	void fetchesAllThenOnlyNewOnes() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			AtomicLong now = new AtomicLong(0);
			CommentWatch w = new CommentWatch(relay.api("x"), now::get);
			relay.addComment("hana", "1", false);
			relay.addComment("taku", "2", true);

			w.watch("s1");
			waitFor(w, s -> s.loaded() && !s.loading());
			assertEquals(List.of("1", "2"), texts(w));
			assertEquals("hana", w.state().session().host);
			assertTrue(w.state().comments().get(1).byHost());

			// 間隔の前は取りにいかない
			int before = relay.requests.size();
			now.set(CommentWatch.INTERVAL_MS - 1);
			w.tick();
			assertEquals(before, relay.requests.size());

			// 間隔がたったら after 付きで新着だけ
			String third = relay.addComment("hana", "3", false);
			now.set(CommentWatch.INTERVAL_MS);
			w.tick();
			waitFor(w, s -> s.comments().size() == 3 && !s.loading());
			assertEquals(List.of("1", "2", "3"), texts(w));
			assertEquals(third, w.state().cursor());
			assertTrue(relay.requests.getLast().startsWith("GET /api/sessions/s1/comments"), relay.requests.toString());
		}
	}

	@Test
	void postedCommentShowsAtOnceWithoutSkippingEarlierOnes() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			CommentWatch w = new CommentWatch(relay.api("x"), System::currentTimeMillis);
			relay.addComment("hana", "1", false);
			w.watch("s1");
			waitFor(w, s -> s.loaded() && !s.loading());
			String cursor = w.state().cursor();

			// 書く前に別の人が書いた → 自分の (POST の応答) をすぐ出す → 次の取得で別の人のも入り、時刻順に並ぶ
			relay.addComment("alex", "割り込み", false);
			relay.addComment("Steve", "自分", false);
			Api.Comment mine = comment(relay.comments.getLast().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"), "自分");
			w.addPosted("s1", mine);
			assertEquals(List.of("1", "自分"), texts(w));
			assertEquals(cursor, w.state().cursor(), "書いたコメントの ID を after に使わない");
			w.addPosted("other", comment("c_zzz", "別のセッション"));
			assertEquals(List.of("1", "自分"), texts(w), "見ていないセッションのコメントは足さない");

			w.refresh();
			waitFor(w, s -> s.comments().size() == 3 && !s.loading());
			assertEquals(List.of("1", "割り込み", "自分"), texts(w));
		}
	}

	@Test
	void stopsWhenTheSessionEnds() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			CommentWatch w = new CommentWatch(relay.api("x"), System::currentTimeMillis);
			w.watch("s1");
			waitFor(w, s -> s.loaded() && !s.loading());
			relay.ended = true;
			w.refresh();
			waitFor(w, CommentWatch.State::ended);
			assertNull(w.state().error());
			int n = relay.requests.size();
			w.refresh();
			w.tick();
			assertEquals(n, relay.requests.size(), "終わったセッションは取りにいかない");

			// 見直すと取り直す
			relay.ended = false;
			w.watch("s1");
			waitFor(w, s -> s.loaded() && !s.ended());
		}
	}

	@Test
	void oldServerIsUnsupportedAndNotPolledAgain() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			relay.oldServer = true;
			AtomicLong now = new AtomicLong(0);
			CommentWatch w = new CommentWatch(relay.api("x"), now::get);
			w.watch("s1");
			waitFor(w, s -> s.error() != null && !s.loading());
			assertEquals("中継サーバがゲームからのコメントに対応していません (中継サーバの更新待ち)", w.state().error());
			assertTrue(w.state().unsupported());
			assertFalse(w.state().ended());
			// 古い中継サーバには取りにいかない (詳細は一覧の情報で描き、一覧から消えたら閉じる)
			int n = relay.requests.size();
			now.set(CommentWatch.INTERVAL_MS * 3);
			w.tick();
			w.refresh();
			assertEquals(n, relay.requests.size());
		}
	}

	@Test
	void switchingDropsTheOldResult() throws Exception {
		try (FakeRelay relay = new FakeRelay()) {
			CommentWatch w = new CommentWatch(relay.api("x"), System::currentTimeMillis);
			relay.addComment("hana", "1", false);
			w.watch("s1");
			w.stop();
			assertNull(w.state().sessionId());
			Thread.sleep(200);
			assertTrue(w.state().comments().isEmpty(), "やめたあとの結果を入れない");
		}
	}

	@Test
	void mergeSortsAndSkipsDuplicates() {
		List<Api.Comment> known = List.of(comment("c_1", "a"), comment("c_3", "c"));
		assertSame(known, CommentWatch.merge(known, List.of(comment("c_1", "a"))), "増えなければそのまま");
		assertSame(known, CommentWatch.merge(known, null));
		List<Api.Comment> merged = CommentWatch.merge(known, List.of(comment("c_2", "b"), comment("c_2", "b"), comment(null, "x")));
		assertNotSame(known, merged);
		assertEquals(List.of("a", "b", "c"), merged.stream().map(c -> c.text).toList());

		List<Api.Comment> many = new ArrayList<>();

		for (int i = 0; i < CommentWatch.MAX_COMMENTS + 5; i++) {
			many.add(comment(String.format("c_%05d", i), String.valueOf(i)));
		}

		List<Api.Comment> capped = CommentWatch.merge(List.of(), many);
		assertEquals(CommentWatch.MAX_COMMENTS, capped.size());
		assertEquals("5", capped.getFirst().text, "多すぎれば古い方を捨てる");
	}
}
