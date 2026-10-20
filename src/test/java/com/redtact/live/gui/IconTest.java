package com.redtact.live.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;

/** 帯のボタンのドット絵 (#69)。 */
class IconTest {
	@Test
	void iconsFitTheSquareButton() {
		for (Icon i : Icon.values()) {
			assertEquals(7, i.height(), i + " の高さは字と同じ 7");
			// ボタンの縁 (黒 1 + 明暗 1) の内側に、左右 1 以上の余白を残して入る
			assertTrue(i.width() <= RButton.H - 6, i + " の幅 " + i.width());
		}
	}

	@Test
	void everyRowAndColumnIsUsed() {
		// 端に空の行・列があると真ん中からずれて見える
		for (Icon i : Icon.values()) {
			for (int r = 0; r < i.height(); r++) {
				boolean any = false;

				for (int c = 0; c < i.width(); c++) {
					any |= i.dot(c, r);
				}

				assertTrue(any, i + " の " + r + " 行目が空");
			}

			for (int c : new int[] {0, i.width() - 1}) {
				boolean any = false;

				for (int r = 0; r < i.height(); r++) {
					any |= i.dot(c, r);
				}

				assertTrue(any, i + " の端の列 " + c + " が空");
			}
		}
	}

	@Test
	void forwardIsBackMirrored() {
		assertEquals(Icon.BACK.width(), Icon.FORWARD.width());

		for (int r = 0; r < Icon.BACK.height(); r++) {
			for (int c = 0; c < Icon.BACK.width(); c++) {
				assertEquals(Icon.BACK.dot(c, r), Icon.FORWARD.dot(Icon.BACK.width() - 1 - c, r), "(" + c + ", " + r + ")");
			}
		}
	}

	@Test
	void rotateRightIsRotateLeftMirrored() {
		assertEquals(Icon.ROTATE_LEFT.width(), Icon.ROTATE_RIGHT.width());

		for (int r = 0; r < Icon.ROTATE_LEFT.height(); r++) {
			for (int c = 0; c < Icon.ROTATE_LEFT.width(); c++) {
				assertEquals(Icon.ROTATE_LEFT.dot(c, r), Icon.ROTATE_RIGHT.dot(Icon.ROTATE_LEFT.width() - 1 - c, r), "(" + c + ", " + r + ")");
			}
		}
	}

	@Test
	void textSizeIconsShareTheLetterA() {
		// 左の 5 列 (マイクラの字の A) は同じで、右上の − と + だけが違う
		for (int r = 0; r < 7; r++) {
			for (int c = 0; c < 5; c++) {
				assertEquals(Icon.TEXT_SMALLER.dot(c, r), Icon.TEXT_BIGGER.dot(c, r), "(" + c + ", " + r + ")");
			}
		}

		// − と + の横棒は同じ高さ (1 行目の 6〜8 列)。+ だけ縦棒がある
		for (int c = 6; c <= 8; c++) {
			assertTrue(Icon.TEXT_SMALLER.dot(c, 1) && Icon.TEXT_BIGGER.dot(c, 1), "横棒 (" + c + ", 1)");
		}

		assertFalse(Icon.TEXT_SMALLER.dot(7, 0) || Icon.TEXT_SMALLER.dot(7, 2), "− に縦棒がある");
		assertTrue(Icon.TEXT_BIGGER.dot(7, 0) && Icon.TEXT_BIGGER.dot(7, 2), "+ に縦棒が無い");
	}

	@Test
	void iconsAreDistinct() {
		Set<String> seen = new HashSet<>();

		for (Icon i : Icon.values()) {
			StringBuilder s = new StringBuilder();

			for (int r = 0; r < i.height(); r++) {
				for (int c = 0; c < i.width(); c++) {
					s.append(i.dot(c, r) ? '#' : '.');
				}

				s.append('/');
			}

			assertTrue(seen.add(s.toString()), i + " がほかのアイコンと同じ形");
		}
	}

	@Test
	void resetIsReloadMirrored() {
		// 既定に戻す (#77) は更新の左右反転 (回る向きが逆)
		assertEquals(Icon.RELOAD.width(), Icon.RESET.width());

		for (int r = 0; r < Icon.RELOAD.height(); r++) {
			for (int c = 0; c < Icon.RELOAD.width(); c++) {
				assertEquals(Icon.RELOAD.dot(c, r), Icon.RESET.dot(Icon.RELOAD.width() - 1 - c, r), "(" + c + ", " + r + ")");
			}
		}
	}
}
