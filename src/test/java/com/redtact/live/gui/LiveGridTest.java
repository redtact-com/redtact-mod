package com.redtact.live.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.redtact.live.gui.kit.LiveGrid;

/** 開催中のカードのグリッドの寸法 (#56)。 */
class LiveGridTest {
	@Test
	void columnsFollowWidth() {
		// 1080p・GUI 4 (480×270) の一覧の幅 (480 - 余白 16 - スクロールバー 7) で 3 列
		assertEquals(3, LiveGrid.columns(457));
		// 854×480・GUI 自動 (427×240。gametest の既定の窓) でも 3 列
		assertEquals(3, LiveGrid.columns(404));
		// 720p・GUI 2 / 1080p・GUI 3 (640 幅) で 4 列
		assertEquals(4, LiveGrid.columns(617));
		assertEquals(2, LiveGrid.columns(300));
		assertEquals(2, LiveGrid.columns(LiveGrid.MIN_CARD_W * 3 + LiveGrid.GAP * 2 - 1), "3 枚目が最小の幅に足りなければ 2 列");
		assertEquals(3, LiveGrid.columns(LiveGrid.MIN_CARD_W * 3 + LiveGrid.GAP * 2));
		assertEquals(2, LiveGrid.columns(120), "狭くても 2 列より減らさない");
		assertEquals(4, LiveGrid.columns(1900), "広くても 4 列まで");
	}

	@Test
	void searchFieldShrinksFirst() {
		// 広い画面: いちばん広い幅。件数と時刻の字の分は残す
		assertEquals(LiveGrid.SEARCH_MAX_W, LiveGrid.searchWidth(800));
		assertEquals(LiveGrid.SEARCH_MAX_W, LiveGrid.searchWidth(LiveGrid.SEARCH_MAX_W + LiveGrid.LIST_INFO_W));
		// 480×270 (1080p・GUI 4): 検索欄を縮めて件数と時刻の字を残す
		assertEquals(318 - LiveGrid.LIST_INFO_W, LiveGrid.searchWidth(318));
		// 320×180 (720p・GUI 4): 検索欄はいちばん狭い幅まで (件数と時刻の字は切る)
		assertEquals(LiveGrid.SEARCH_MIN_W, LiveGrid.searchWidth(158));
		assertEquals(LiveGrid.SEARCH_MIN_W, LiveGrid.searchWidth(LiveGrid.SEARCH_MIN_W));
		// それより狭ければ残りいっぱい (40 は残す)
		assertEquals(60, LiveGrid.searchWidth(60));
		assertEquals(40, LiveGrid.searchWidth(10));
	}

	@Test
	void cardsFillTheRow() {
		int w = 457;
		int cols = LiveGrid.columns(w);
		int cardW = LiveGrid.cardWidth(w, cols);
		assertEquals(149, cardW);
		assertTrue(cardW * cols + LiveGrid.GAP * (cols - 1) <= w, "はみ出さない");
		assertTrue(w - (cardW * cols + LiveGrid.GAP * (cols - 1)) < cols, "余りは列の数より小さい");
	}

	@Test
	void thumbIs16by9() {
		int cardW = 149;
		assertEquals(145, LiveGrid.thumbWidth(cardW));
		assertEquals(82, LiveGrid.thumbHeight(cardW)); // 145 × 9 / 16 = 81.6
		assertEquals(2 + 82 + LiveGrid.BODY_H, LiveGrid.cardHeight(cardW));
	}

	@Test
	void rowsRoundUp() {
		assertEquals(0, LiveGrid.rows(0, 3));
		assertEquals(1, LiveGrid.rows(1, 3));
		assertEquals(1, LiveGrid.rows(3, 3));
		assertEquals(2, LiveGrid.rows(4, 3));
		assertEquals(3, LiveGrid.rows(9, 4));
	}

	@Test
	void detailSplitsPreviewAndComments() {
		// 1080p・GUI 4 (480×270) の中身の幅 464: 左 241・右 217 (コメントの本文は 1 行に全角 20 字ほど)
		assertArrayEquals(new int[] {241, 217}, LiveGrid.detailWidths(464));
		// 854×480・GUI 自動 (427×240。gametest の既定の窓) の 411: 左 213・右 192
		assertArrayEquals(new int[] {213, 192}, LiveGrid.detailWidths(411));
		// 52% だと右が最小の幅に足りないときは左を削る
		assertArrayEquals(new int[] {144, LiveGrid.MIN_COMMENT_W}, LiveGrid.detailWidths(300));
		assertArrayEquals(new int[] {LiveGrid.MIN_PREVIEW_W, 0}, LiveGrid.detailWidths(100), "狭すぎてもプレビューは残す");
		int[] wide = LiveGrid.detailWidths(944);
		assertEquals(944, wide[0] + LiveGrid.DETAIL_GAP + wide[1]);
	}

	@Test
	void detailButtonsWrapWhenTheLeftIsNarrow() {
		// 読み込む 60・分岐する 60・ブラウザで開く 96 (間 3)
		int[] widths = {60, 60, 96};
		assertArrayEquals(new int[] {0, 0, 0}, LiveGrid.buttonRows(widths, 231, 3), "入れば 1 段 (60+3+60+3+96 = 222)");
		assertArrayEquals(new int[] {0, 0, 1}, LiveGrid.buttonRows(widths, 221, 3), "入らないボタンから次の段");
		assertArrayEquals(new int[] {0, 1, 2}, LiveGrid.buttonRows(widths, 100, 3));
		assertArrayEquals(new int[] {0, 1}, LiveGrid.buttonRows(new int[] {200, 50}, 120, 3), "1 つで入らないボタンもその段に置く");
		// 1280×720・GUI 4 (320 幅) の詳細の左の板は 148。字の広いリソースパックなどでボタンが 60・60・96 になると 1 段に入らない
		// (マイクラの標準の字では 35・35・56 ほどで 1 段に入る)
		int[] narrow = LiveGrid.detailWidths(304);
		assertEquals(148, narrow[0]);
		assertEquals(1, LiveGrid.buttonRows(new int[] {60, 60, 96}, narrow[0] - 10, 3)[2]);
	}

	@Test
	void expandedGivesThePreviewMostOfTheHeight() {
		// 拡大すると帯を隠すので、1080p・GUI 4 (480×270) の詳細の高さは 259。下は操作の行・入力欄・1 件ぶんの 72
		assertArrayEquals(new int[] {181, 72}, LiveGrid.expandedHeights(259));
		// 960×540 (1080p・GUI 2) の 529: 残りは全部プレビューへ (回路は高さで大きさが決まる)
		assertArrayEquals(new int[] {451, 72}, LiveGrid.expandedHeights(529));
		// 低すぎるときは板の 60 を先に取り、コメントの欄が縮む
		assertArrayEquals(new int[] {60, 34}, LiveGrid.expandedHeights(100));
		for (int avail : new int[] {100, 197, 259, 349, 529, 1000}) {
			int[] h = LiveGrid.expandedHeights(avail);
			assertEquals(avail, h[0] + LiveGrid.DETAIL_GAP + h[1], "高さ " + avail + " を使い切る");
		}
	}
}
