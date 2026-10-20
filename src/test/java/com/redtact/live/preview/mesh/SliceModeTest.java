package com.redtact.live.preview.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** 高さで切る (#102。web の「レイヤー表示」と同じ 4 つ)。 */
class SliceModeTest {
	@Test
	void ranges() {
		assertArrayEquals(new int[] {0, 9}, SliceMode.ALL.range(4, 9));
		assertArrayEquals(new int[] {4, 4}, SliceMode.SINGLE.range(4, 9));
		assertArrayEquals(new int[] {0, 4}, SliceMode.BOTTOM_UP.range(4, 9));
		assertArrayEquals(new int[] {4, 9}, SliceMode.TOP_DOWN.range(4, 9));
	}

	/** 高さは 0〜一番上に収める (回路が低くなった・高さを変えたあと)。 */
	@Test
	void clampsTheLayer() {
		assertArrayEquals(new int[] {9, 9}, SliceMode.SINGLE.range(20, 9));
		assertArrayEquals(new int[] {0, 0}, SliceMode.SINGLE.range(-3, 9));
		assertArrayEquals(new int[] {9, 9}, SliceMode.TOP_DOWN.range(20, 9));
		assertArrayEquals(new int[] {0, 0}, SliceMode.ALL.range(5, 0)); // 1 段だけの回路
	}
}
