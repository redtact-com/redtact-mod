package com.redtact.live.preview.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 撮影の画素 (#102): 透明の上に描いた半透明は色に透明度が掛かったままなので、PNG のふつうの形に戻す。 */
class PreviewSceneTest {
	@Test
	void keepsOpaqueAndClearsTransparent() {
		assertEquals(0xFF336699, PreviewScene.straightAlpha(0xFF336699));
		assertEquals(0, PreviewScene.straightAlpha(0x00112233)); // 透明は色も 0
	}

	@Test
	void undoesPremultipliedAlpha() {
		// 半分の透明度で (r, g, b) = (200, 100, 50) を描くと、絵には (100, 50, 25) が残る (ABGR)
		int premultiplied = 0x80 << 24 | 25 << 16 | 50 << 8 | 100;
		int straight = PreviewScene.straightAlpha(premultiplied);
		assertEquals(0x80, straight >>> 24);
		assertEquals(199, straight & 0xFF, 1);
		assertEquals(99, (straight >> 8) & 0xFF, 1);
		assertEquals(49, (straight >> 16) & 0xFF, 1);
		// 丸めで 255 を超えない
		assertEquals(255, PreviewScene.straightAlpha(0x10 << 24 | 0x10) & 0xFF);
	}
}
