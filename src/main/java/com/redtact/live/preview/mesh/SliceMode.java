package com.redtact.live.preview.mesh;

import com.redtact.live.i18n.Tr;

/**
 * 高さで切って見る (#102。redtact の web の「レイヤー表示」と同じ 4 つ。deepslate-extras の getSliceRange)。
 * 何層もある回路の中を見る。
 */
public enum SliceMode {
	/** 全体。 */
	ALL,
	/** その層だけ。 */
	SINGLE,
	/** 下からその層まで。 */
	BOTTOM_UP,
	/** その層から上。 */
	TOP_DOWN;

	/** 見せる高さ {@code [min, max]} (両端を含む)。layer は 0〜maxY に収める。 */
	public int[] range(int layer, int maxY) {
		int l = Math.max(0, Math.min(maxY, layer));

		return switch (this) {
			case ALL -> new int[] {0, maxY};
			case SINGLE -> new int[] {l, l};
			case BOTTOM_UP -> new int[] {0, l};
			case TOP_DOWN -> new int[] {l, maxY};
		};
	}

	/** 切り替えの名前 (web と同じ字)。 */
	public String label() {
		return switch (this) {
			case ALL -> Tr.t("全体");
			case SINGLE -> Tr.t("1層");
			case BOTTOM_UP -> Tr.t("下から");
			case TOP_DOWN -> Tr.t("上から");
		};
	}
}
