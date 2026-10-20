package com.redtact.live.gui.kit;

import org.jspecify.annotations.Nullable;

/** 「…」で切った字の全文を出すかの判定 (描くのとテストで同じものを使う。#41)。 */
public final class ClipText {
	private ClipText() {
	}

	/** 全文 (full) を切って shown にしたか。 */
	public static boolean clipped(@Nullable String full, String shown) {
		return full != null && !full.equals(shown);
	}

	/** (mouseX, mouseY) が (x, y, w, h) の中か。 */
	public static boolean inside(int x, int y, int w, int h, int mouseX, int mouseY) {
		return w > 0 && h > 0 && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	/**
	 * ボタンのツールチップの字。名前を切ったときは先頭に全文 (clippedLabel) を置き、続けて押せない理由 (あれば) か
	 * ふだんの説明を出す。何も無ければ null。
	 */
	public static @Nullable String buttonTooltip(@Nullable String clippedLabel, @Nullable String disabledReason, @Nullable String normal) {
		String rest = disabledReason != null && !disabledReason.isEmpty() ? disabledReason : normal;

		if (clippedLabel == null) {
			return rest;
		}

		return rest == null || rest.isEmpty() ? clippedLabel : clippedLabel + "\n" + rest;
	}
}
