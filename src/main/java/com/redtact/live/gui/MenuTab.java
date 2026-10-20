package com.redtact.live.gui;

import com.redtact.live.i18n.Tr;

/**
 * メニューの画面。どれかのメインタブ ({@link MenuSection}) に入り、live の中の 4 つは子タブになる (#51)。
 */
public enum MenuTab {
	/** redtact をゲーム内で開く (#46)。 */
	REDTACT(MenuSection.REDTACT, "redtact"),
	SESSION(MenuSection.LIVE, Tr.ja("セッション")),
	/** 一覧に出ている開催中のセッション。 */
	LIVE_LIST(MenuSection.LIVE, Tr.ja("開催中")),
	RANGE(MenuSection.LIVE, Tr.ja("範囲")),
	COMMENTS(MenuSection.LIVE, Tr.ja("コメント")),
	/** redtact で取ってきた回路の保管庫 (#46)。 */
	LIBRARY(MenuSection.LIBRARY, Tr.ja("保管庫")),
	SETTINGS(MenuSection.SETTINGS, Tr.ja("設定"));

	private final MenuSection section;
	private final String label;

	MenuTab(MenuSection section, String label) {
		this.section = section;
		this.label = label;
	}

	public MenuSection section() {
		return section;
	}

	/** 子タブの名前。 */
	public String label() {
		return Tr.t(label);
	}
}
