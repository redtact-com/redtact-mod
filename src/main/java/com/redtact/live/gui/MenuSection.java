package com.redtact.live.gui;

import java.util.Arrays;
import java.util.List;

import com.redtact.live.i18n.Tr;

/**
 * メニューのメインタブ (ヘッダの階)。redtact と live を同じ重さで並べる (#51)。
 * live の中はセッション・開催中・範囲・コメントを子タブ ({@link MenuTab}) で切り替える。
 */
public enum MenuSection {
	/** ゲーム内の redtact (#46)。 */
	REDTACT("redtact"),
	/** 回路のライブ共有 (セッション・開催中・範囲・コメント)。 */
	LIVE("live"),
	/** redtact で取ってきた回路の保管庫 (schematics/redtact/)。 */
	LIBRARY(Tr.ja("保管庫")),
	SETTINGS(Tr.ja("設定"));

	private final String label;

	MenuSection(String label) {
		this.label = label;
	}

	public String label() {
		return Tr.t(label);
	}

	/** このメインタブの画面 (子タブの並び順)。 */
	public List<MenuTab> pages() {
		return Arrays.stream(MenuTab.values()).filter(t -> t.section() == this).toList();
	}

	/** 子タブがあるか (live だけ)。 */
	public boolean hasSubTabs() {
		return pages().size() > 1;
	}
}
