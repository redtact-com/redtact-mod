package com.redtact.live.config;

/** ゲーム内の redtact のコマ数の上限 (Chromium の windowless_frame_rate。#55)。上げるとなめらかで、その分だけ重い。既定は 60 (#69)。 */
public enum BrowserFrameRate implements ConfigChoice.Choice {
	FPS_30("30", "30", 30),
	FPS_60("60", "60", 60);

	private final String storageName;
	private final String label;
	private final int fps;

	BrowserFrameRate(String storageName, String label, int fps) {
		this.storageName = storageName;
		this.label = label;
		this.fps = fps;
	}

	@Override
	public String storageName() {
		return storageName;
	}

	@Override
	public String label() {
		return label;
	}

	public int fps() {
		return fps;
	}
}
