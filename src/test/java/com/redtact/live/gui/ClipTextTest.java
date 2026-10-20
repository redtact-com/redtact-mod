package com.redtact.live.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.redtact.live.gui.kit.ClipText;

/** 「…」で切った字の全文を出すかの判定 (#41)。 */
class ClipTextTest {
	@Test
	void clippedOnlyWhenShownDiffers() {
		assertTrue(ClipText.clipped("とても長いセッション名", "とても長い…"));
		assertTrue(ClipText.clipped("長い", ""), "幅が無くて何も描けないときも切ったとみなす");
		assertFalse(ClipText.clipped("短い", "短い"));
		assertFalse(ClipText.clipped(null, ""), "null は Draw.clip が空にするだけ");
	}

	@Test
	void insideIsHalfOpen() {
		assertTrue(ClipText.inside(10, 20, 30, 9, 10, 20));
		assertTrue(ClipText.inside(10, 20, 30, 9, 39, 28));
		assertFalse(ClipText.inside(10, 20, 30, 9, 40, 25), "右端は外");
		assertFalse(ClipText.inside(10, 20, 30, 9, 15, 29), "下端は外");
		assertFalse(ClipText.inside(10, 20, 30, 9, 9, 25));
		assertFalse(ClipText.inside(10, 20, 0, 9, 10, 20), "幅 0 (何も描いていない) なら出さない");
	}

	@Test
	void buttonTooltipPutsFullLabelFirst() {
		// 切っていなければ今まで通り
		assertNull(ClipText.buttonTooltip(null, null, null));
		assertEquals("説明", ClipText.buttonTooltip(null, null, "説明"));
		assertEquals("理由", ClipText.buttonTooltip(null, "理由", "説明"), "押せない理由があれば説明より先");
		assertEquals("説明", ClipText.buttonTooltip(null, "", "説明"), "空の理由 (enabled(false)) は出さない");
		// 切ったら先頭に全文
		assertEquals("長い名前のボタン", ClipText.buttonTooltip("長い名前のボタン", null, null));
		assertEquals("長い名前のボタン", ClipText.buttonTooltip("長い名前のボタン", "", ""));
		assertEquals("長い名前のボタン\n説明", ClipText.buttonTooltip("長い名前のボタン", null, "説明"));
		assertEquals("長い名前のボタン\n理由", ClipText.buttonTooltip("長い名前のボタン", "理由", "説明"));
	}
}
