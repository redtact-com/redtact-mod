package com.redtact.live.config;

import com.redtact.live.i18n.Tr;

/** HUD を出す画面の角。設定ファイルの名前は malilib の HudAlignment と同じ。 */
public enum HudCorner implements ConfigChoice.Choice {
	TOP_LEFT("top_left", Tr.ja("左上")),
	TOP_RIGHT("top_right", Tr.ja("右上")),
	BOTTOM_LEFT("bottom_left", Tr.ja("左下")),
	BOTTOM_RIGHT("bottom_right", Tr.ja("右下"));

	private final String storageName;
	private final String label;

	HudCorner(String storageName, String label) {
		this.storageName = storageName;
		this.label = label;
	}

	@Override
	public String storageName() {
		return storageName;
	}

	@Override
	public String label() {
		return Tr.t(label);
	}

	public boolean bottom() {
		return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
	}

	public boolean right() {
		return this == TOP_RIGHT || this == BOTTOM_RIGHT;
	}
}
