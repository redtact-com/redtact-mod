package com.redtact.live.gui.rows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.redtact.live.net.Api;

/** 開催中のカードの字とサムネを取りにいくか (#56)。 */
class LiveRowTest {
	private static LiveRow row(String title, int[] size) {
		Api.Session s = new Api.Session();
		s.sessionId = "a1B2c3D4e5";
		s.title = title;
		s.host = "Steve";

		if (size != null) {
			s.snapshot = new Api.Snapshot();
			s.snapshot.version = 3;
			s.snapshot.size = size;
			s.snapshot.blockCount = 10;
		}

		return new LiveRow(s, false, null, "5 分前");
	}

	@Test
	void titleFallsBackToUntitled() {
		assertEquals("(無題)", row("", new int[] {1, 1, 1}).title());
		assertEquals("(無題)", row("  ", new int[] {1, 1, 1}).title());
		assertEquals("加算器", row("加算器", new int[] {1, 1, 1}).title());
	}

	@Test
	void sizeText() {
		assertEquals("12×5×8", row("a", new int[] {12, 5, 8}).sizeText());
		assertNull(row("a", null).sizeText());
		assertNull(row("a", new int[] {1, 2}).sizeText());
	}

	@Test
	void hostTooltipShowsTheRedtactName() {
		LiveRow r = row("a", null);
		assertEquals("Steve", r.hostLabel());
		assertNull(r.hostTooltip(), "公開の連携が無ければ出さない (切ったときの全文だけ)");
		r.session().hostVerified = true;
		r.session().hostUser = new Api.HostUser();
		r.session().hostUser.name = "すてぃーぶ";
		assertEquals("✔ Steve", r.hostLabel());
		assertEquals("すてぃーぶ", r.redtactName());
		assertEquals("✔ Steve\nredtact: すてぃーぶ", r.hostTooltip());
		r.session().hostUser.name = " ";
		assertNull(r.hostTooltip(), "名前が空なら出さない");
	}

	@Test
	void audienceText() {
		LiveRow r = row("a", null);
		r.session().viewerCount = 3;
		assertEquals("視聴中 3", r.audienceText(), "古い中継サーバ (views が無い) は視聴中だけ");
		assertEquals("視聴中 3 人 (Web の画面と、MOD で詳細を開いている人)", r.audienceTooltip());
		r.session().views = 12;
		assertEquals("視聴中 3・閲覧数 12", r.audienceText());
		assertEquals("視聴中 3 人 (Web の画面と、MOD で詳細を開いている人)\n閲覧数 12 (開始からの延べ人数。同じ回線は 1 回・ホストは数えない)", r.audienceTooltip());
	}

	@Test
	void thumbnailIsSkippedForUnsyncedOrHugeRanges() {
		assertNull(row("a", new int[] {12, 5, 8}).thumbBlockedReason());
		assertEquals("まだ同期されていません", row("a", null).thumbBlockedReason());
		assertEquals("範囲が大きいので絵はありません", row("a", new int[] {256, 256, 256}).thumbBlockedReason());
	}
}
