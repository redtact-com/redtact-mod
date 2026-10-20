package com.redtact.live.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** ゲーム内の redtact を描かせる解像度 (#55)。 */
class BrowserResolutionTest {
	@Test
	void autoKeepsUpTo1080pPixels() {
		assertEquals(1.0, BrowserResolution.AUTO.factor(1920, 1040), 1e-9, "1080p はそのまま");
		assertEquals(1.0, BrowserResolution.AUTO.factor(854, 400), 1e-9);
		// 1440p (2560×1400) は画素数が 1080p 相当になるまで下げる
		double f = BrowserResolution.AUTO.factor(2560, 1400);
		assertEquals(1920.0 * 1080.0, 2560 * f * 1400 * f, 1.0);
		// 4K (3840×2100) はおよそ半分
		assertEquals(0.5, BrowserResolution.AUTO.factor(3840, 2100), 0.02);
	}

	@Test
	void fixedFactorsIgnoreTheScreen() {
		assertEquals(1.0, BrowserResolution.FULL.factor(3840, 2100), 1e-9);
		assertEquals(0.75, BrowserResolution.HIGH.factor(1920, 1040), 1e-9);
		assertEquals(0.5, BrowserResolution.HALF.factor(854, 400), 1e-9);
	}

	@Test
	void defaultIsAutoAt60() {
		assertEquals(BrowserResolution.AUTO, Configs.Generic.BROWSER_RESOLUTION.getOptionListValue());
		assertEquals(BrowserFrameRate.FPS_60, Configs.Generic.BROWSER_FRAME_RATE.getOptionListValue(), "コマ数の既定は 60 (#69)");
	}
}
