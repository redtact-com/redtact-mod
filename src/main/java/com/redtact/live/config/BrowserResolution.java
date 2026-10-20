package com.redtact.live.config;

import com.redtact.live.i18n.Tr;

/**
 * ゲーム内の redtact を描かせる解像度 (#55)。MCEF Modern は描き直すたびに全画素を並べ替えて送るので
 * (1080p で 1 回 2〜4 ms)、画素が少ないほど軽い。字は少しぼやける。
 * 既定の「自動」は 1080p 相当の画素数まで: それより大きい画面 (1440p・4K) では画素数がそれに収まるように下げる。
 */
public enum BrowserResolution implements ConfigChoice.Choice {
	AUTO("auto", Tr.ja("自動"), 0),
	FULL("100", "100%", 1.0),
	HIGH("75", "75%", 0.75),
	HALF("50", "50%", 0.5);

	/** 「自動」で描かせる画素数の上限 (1920×1080)。 */
	public static final double AUTO_MAX_PIXELS = 1920.0 * 1080.0;
	private final String storageName;
	private final String label;
	private final double factor;

	BrowserResolution(String storageName, String label, double factor) {
		this.storageName = storageName;
		this.label = label;
		this.factor = factor;
	}

	@Override
	public String storageName() {
		return storageName;
	}

	@Override
	public String label() {
		return Tr.t(label);
	}

	/** 実際の画素 (w × h) に掛ける割合 (縦横それぞれ)。 */
	public double factor(double realWidth, double realHeight) {
		if (this != AUTO) {
			return factor;
		}

		double pixels = realWidth * realHeight;
		return pixels <= AUTO_MAX_PIXELS ? 1.0 : Math.sqrt(AUTO_MAX_PIXELS / pixels);
	}
}
