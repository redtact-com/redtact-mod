package com.redtact.live.gui.kit;

/**
 * 開催中のカードのグリッドの寸法 (#56)。ゲームのクラスに触れないので単体テストで確かめられる。
 * カードは redtact の回路カードと同じく、上に 16:9 のサムネ、下に題 (2 行) と足 (ホストと時間)。
 */
public final class LiveGrid {
	/** カードとカードの間 (横も縦も)。 */
	public static final int GAP = 4;
	/** これより狭いカードにならないよう列を減らす (ただし 2 列より減らさない)。 */
	public static final int MIN_CARD_W = 120;
	public static final int MIN_COLS = 2;
	public static final int MAX_COLS = 4;
	/** サムネの周りの縁 (カードの黒い縁 1 + 内側の余白 1)。 */
	public static final int THUMB_INSET = 2;
	/** サムネの下の部分: 余白 3・題 2 行 (10 × 2)・間 2・足 10・余白 3。 */
	public static final int BODY_H = 3 + 20 + 2 + 10 + 3;

	private LiveGrid() {
	}

	/** 幅 width に並べる列の数。 */
	public static int columns(int width) {
		int cols = (width + GAP) / (MIN_CARD_W + GAP);
		return Math.max(MIN_COLS, Math.min(MAX_COLS, cols));
	}

	/** cols 列のときのカードの幅 (余りは右に寄せない。左から詰める)。 */
	public static int cardWidth(int width, int cols) {
		return Math.max(1, (width - GAP * (cols - 1)) / cols);
	}

	/** サムネの幅 (カードの内側)。 */
	public static int thumbWidth(int cardW) {
		return Math.max(1, cardW - THUMB_INSET * 2);
	}

	/** サムネの高さ (16:9)。 */
	public static int thumbHeight(int cardW) {
		return Math.max(1, Math.round(thumbWidth(cardW) * 9 / 16.0F));
	}

	/** カードの高さ。 */
	public static int cardHeight(int cardW) {
		return THUMB_INSET + thumbHeight(cardW) + BODY_H;
	}

	/** 詳細の左 (プレビューと操作) と右 (コメント) の間 (#74)。 */
	public static final int DETAIL_GAP = 6;
	/** 詳細のコメントの欄のいちばん狭い幅 (#74)。 */
	public static final int MIN_COMMENT_W = 150;
	/** 詳細の左の板のいちばん狭い幅 (#74)。 */
	public static final int MIN_PREVIEW_W = 120;

	/**
	 * 詳細を左右に分けたときの幅 (#74)。左はプレビューと操作で 52%、右はコメント。コメントが {@link #MIN_COMMENT_W} より
	 * 狭くなるなら左を削る。{左の幅, 右の幅} を返す。
	 */
	public static int[] detailWidths(int contentWidth) {
		int left = contentWidth * 52 / 100;

		if (contentWidth - DETAIL_GAP - left < MIN_COMMENT_W) {
			left = contentWidth - DETAIL_GAP - MIN_COMMENT_W;
		}

		left = Math.max(MIN_PREVIEW_W, left);
		return new int[] {left, Math.max(0, contentWidth - DETAIL_GAP - left)};
	}

	/**
	 * 幅 widths のボタンを左から gap で並べ、maxWidth に入らなければ次の段へ送る (#74。詳細の左の板の操作)。
	 * ボタンごとの段 (0 始まり) を返す。1 つで maxWidth を超えるボタンもその段に置く。
	 */
	public static int[] buttonRows(int[] widths, int maxWidth, int gap) {
		int[] rowOf = new int[widths.length];
		int row = 0;
		int x = 0;

		for (int i = 0; i < widths.length; i++) {
			if (x > 0 && x + widths[i] > maxWidth) {
				row++;
				x = 0;
			}

			rowOf[i] = row;
			x += widths[i] + gap;
		}

		return rowOf;
	}

	/**
	 * 拡大したとき (#77) のプレビューの下の高さ: 操作の行 (「←」・題とコメントの件数・操作・縮小)・入力欄と、1 行のコメント 1 件ぶん
	 * (一覧はその中でスクロール)。回路は既定の斜めの向きでは高さで大きさが決まるので、残りは全部プレビューに回す。
	 */
	public static final int EXPANDED_COMMENTS = 72;

	/**
	 * 詳細を拡大したとき (#77) の高さの分け方: 上の板 (全幅のプレビュー) と、下の操作の行とコメントの欄。
	 * 間は {@link #DETAIL_GAP}。{板, コメントの欄} を返す。
	 */
	public static int[] expandedHeights(int avail) {
		int panel = Math.max(60, avail - EXPANDED_COMMENTS - DETAIL_GAP);
		return new int[] {panel, Math.max(0, avail - panel - DETAIL_GAP)};
	}

	/** 一覧の上の検索欄 (#90) のいちばん広い幅・いちばん狭い幅。 */
	public static final int SEARCH_MAX_W = 200;
	public static final int SEARCH_MIN_W = 80;
	/** 検索欄の右 (並べ替えとフォロー中の後ろ) に残したい、件数と時刻の字の幅。 */
	public static final int LIST_INFO_W = 150;

	/**
	 * 一覧の上の検索欄の幅 (#90)。avail は検索欄と、その後ろの件数と時刻の字に使える幅 (更新・並べ替え・フォロー中は除く)。
	 * 件数と時刻の字に {@link #LIST_INFO_W} を残し、狭い画面では検索欄から {@link #SEARCH_MIN_W} まで縮める
	 * (件数と時刻の字は切って、重ねると全文)。それでも入らなければ avail いっぱい (40 は残す)。
	 */
	public static int searchWidth(int avail) {
		if (avail < SEARCH_MIN_W) {
			return Math.max(40, avail);
		}

		return Math.max(SEARCH_MIN_W, Math.min(SEARCH_MAX_W, avail - LIST_INFO_W));
	}

	/** count 枚を cols 列に並べたときの段の数。 */
	public static int rows(int count, int cols) {
		return count <= 0 ? 0 : (count + cols - 1) / cols;
	}
}
