package com.redtact.live.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** 開催中の一覧の問い合わせ (#90): {@code GET /api/sessions} の q・sort・following。 */
class LiveQueryTest {
	@Test
	void defaultsAreOmitted() {
		assertEquals("", LiveQuery.DEFAULT.queryString());
		assertEquals("", new LiveQuery("   ", LiveQuery.Sort.NEW, false).queryString(), "空白だけの字は探さない");
		assertEquals("", new LiveQuery(null, null, false).queryString(), "null は既定");
		assertFalse(LiveQuery.DEFAULT.searching());
		assertFalse(LiveQuery.DEFAULT.narrowed());
	}

	@Test
	void eachParameter() {
		assertEquals("?q=hana", LiveQuery.DEFAULT.withQ("hana").queryString());
		assertEquals("?sort=viewers", LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWERS).queryString());
		assertEquals("?sort=views", LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWS).queryString());
		assertEquals("?following=1", LiveQuery.DEFAULT.withFollowing(true).queryString());
		assertEquals("?q=hana&sort=views&following=1", new LiveQuery("hana", LiveQuery.Sort.VIEWS, true).queryString(), "q・sort・following の順");
		assertTrue(LiveQuery.DEFAULT.withFollowing(true).narrowed());
		assertTrue(LiveQuery.DEFAULT.withQ("a").narrowed());
		assertFalse(LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWS).narrowed(), "並べ替えだけなら絞っていない");
	}

	@Test
	void textIsEncodedAndRoundTrips() {
		String q = "テスト ホスト & a=b+c/?#%";
		String s = LiveQuery.DEFAULT.withQ(q).queryString();
		assertTrue(s.startsWith("?q="), s);
		assertFalse(s.contains(" ") || s.contains("+") || s.contains("&a") || s.contains("#"), "空白・記号は %xx に: " + s);
		// URI として読めて、サーバと同じく decode すると元の字 (前後の空白を除く) に戻る
		URI uri = URI.create("http://127.0.0.1:8797/api/sessions" + s);
		assertEquals(q, URLDecoder.decode(uri.getRawQuery().substring(2), StandardCharsets.UTF_8));
	}

	@Test
	void textIsTrimmedAndCapped() {
		assertEquals("hana", LiveQuery.DEFAULT.withQ("  hana \t").q());
		String longText = "あ".repeat(70);
		assertEquals(LiveQuery.MAX_Q, LiveQuery.normalize(longText).length());
		// サロゲートペア (絵文字) は 1 文字に数え、途中で切らない
		String emoji = "😀".repeat(70);
		String cut = LiveQuery.normalize(emoji);
		assertEquals(LiveQuery.MAX_Q, cut.codePointCount(0, cut.length()));
		assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "サロゲートペアの片方で終わらない");
		assertEquals("a", LiveQuery.normalize("a" + " ".repeat(63) + "b"), "切ったあとの後ろの空白も除く");
	}

	@Test
	void sortCycles() {
		assertEquals(LiveQuery.Sort.VIEWERS, LiveQuery.Sort.NEW.next());
		assertEquals(LiveQuery.Sort.VIEWS, LiveQuery.Sort.VIEWERS.next());
		assertEquals(LiveQuery.Sort.NEW, LiveQuery.Sort.VIEWS.next());
		assertEquals(LiveQuery.Sort.VIEWS, LiveQuery.Sort.NEW.previous());
		assertEquals(LiveQuery.Sort.NEW, LiveQuery.Sort.VIEWERS.previous());
		assertEquals("新しい順", LiveQuery.Sort.NEW.label());
		assertEquals("視聴中", LiveQuery.Sort.VIEWERS.label());
		assertEquals("閲覧数", LiveQuery.Sort.VIEWS.label());
	}

	@Test
	void equalityIgnoresSurroundingSpaces() {
		// 打ちかけの字の後ろの空白で問い合わせ直さない
		assertEquals(LiveQuery.DEFAULT.withQ("hana"), LiveQuery.DEFAULT.withQ("hana "));
	}
}
