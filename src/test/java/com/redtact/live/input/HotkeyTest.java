package com.redtact.live.input;

import com.mojang.blaze3d.platform.InputConstants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class HotkeyTest {
	// キーの番号は版で違う (26.3 は SDL のスキャンコード、26.2 までは GLFW のキー。#112) ので、ゲームの定数で書く
	private static final int R = InputConstants.KEY_R;
	private static final int L = InputConstants.KEY_L;
	private static final int A = InputConstants.KEY_A;
	private static final int K = InputConstants.KEY_K;
	private static final int W = InputConstants.KEY_W;
	private static final int LEFT_SHIFT = InputConstants.KEY_LSHIFT;

	@Test
	void namesMatchMalilibScanCodes() {
		assertEquals(R, KeyNames.code("R").getAsInt());
		assertEquals(InputConstants.KEY_LBRACKET, KeyNames.code("LEFT_BRACKET").getAsInt());
		assertEquals(InputConstants.KEY_RBRACKET, KeyNames.code("right_bracket").getAsInt());
		assertEquals(-99, KeyNames.code("MOUSE_LEFT").getAsInt());
		assertEquals(-97, KeyNames.code("MOUSE_RIGHT").getAsInt());
		assertEquals(InputConstants.KEY_RETURN, KeyNames.code("ENTER").getAsInt()); // 別名
		assertEquals("RETURN", KeyNames.name(InputConstants.KEY_RETURN));
		assertTrue(KeyNames.code("NO_SUCH_KEY").isEmpty());
		assertEquals(3, KeyNames.mouseButton(KeyNames.MOUSE_RIGHT));
		assertEquals(KeyNames.MOUSE_LEFT, KeyNames.mouseCode(1));
	}

	@Test
	void gameMouseButtonsMapToLeftMiddleRight() {
		// 26.3 (SDL) と 26.2 まで (GLFW) で番号の振り方が違う (GLFW は右が 1・中が 2。#112)。ゲームの定数から見る
		assertEquals(1, KeyNames.fromGameButton(InputConstants.MOUSE_BUTTON_LEFT));
		assertEquals(2, KeyNames.fromGameButton(InputConstants.MOUSE_BUTTON_MIDDLE));
		assertEquals(3, KeyNames.fromGameButton(InputConstants.MOUSE_BUTTON_RIGHT));

		for (int b : new int[] {InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOUSE_BUTTON_MIDDLE, InputConstants.MOUSE_BUTTON_RIGHT}) {
			assertEquals(b, KeyNames.gameButton(KeyNames.fromGameButton(b)));
		}
	}

	@Test
	void parsesAndFormatsCombos() {
		assertEquals(List.of(R, L), Keybind.parse("R,L"));
		assertEquals(List.of(R, L), Keybind.parse(" r , l "));
		assertEquals(List.of(), Keybind.parse(""));
		assertNull(Keybind.parse("R,NOPE"));
		assertEquals("R,L", Keybind.format(List.of(R, L)));
		assertEquals("MOUSE_LEFT", Keybind.format(List.of(KeyNames.MOUSE_LEFT)));
	}

	@Test
	void keybindKeepsDefaultAndRejectsUnknownNames() {
		Keybind k = new Keybind("R,L");
		assertTrue(k.isDefault());
		assertFalse(k.setValueFromString("R,NOPE"));
		assertEquals("R,L", k.getStringValue());
		assertTrue(k.setValueFromString("K"));
		assertFalse(k.isDefault());
		k.resetToDefault();
		assertEquals(List.of(R, L), k.getKeys());
		assertTrue(k.setValueFromString(""));
		assertTrue(k.isUnbound());
	}

	@Test
	void comboMatchesOnlyAtTheEndInOrder() {
		assertTrue(HotkeyMatcher.matches(List.of(R, L), List.of(R, L)));
		assertTrue(HotkeyMatcher.matches(List.of(W, R, L), List.of(R, L)), "前から押していたキー (W) は気にしない");
		assertFalse(HotkeyMatcher.matches(List.of(L, R), List.of(R, L)), "順番が逆");
		assertFalse(HotkeyMatcher.matches(List.of(R, W, L), List.of(R, L)), "間に別のキー");
		assertFalse(HotkeyMatcher.matches(List.of(L), List.of(R, L)));
		assertFalse(HotkeyMatcher.matches(List.of(R, L), List.of()), "割り当てなしは当たらない");
		assertTrue(HotkeyMatcher.matches(List.of(W, K), List.of(K)), "歩きながら K");
		assertTrue(HotkeyMatcher.matches(List.of(W, LEFT_SHIFT, KeyNames.MOUSE_LEFT), List.of(KeyNames.MOUSE_LEFT)), "しゃがみながら左クリック");
	}

	@Test
	void longestComboWins() {
		record H(String name, List<Integer> keys) {
		}

		List<H> hotkeys = List.of(new H("L", List.of(L)), new H("R+L", List.of(R, L)), new H("R+A", List.of(R, A)));
		Function<H, List<Integer>> keys = H::keys;
		assertEquals("R+L", HotkeyMatcher.best(List.of(R, L), hotkeys, keys).name());
		assertEquals("L", HotkeyMatcher.best(List.of(L), hotkeys, keys).name());
		assertEquals("R+A", HotkeyMatcher.best(List.of(W, R, A), hotkeys, keys).name());
		assertNull(HotkeyMatcher.best(List.of(R), hotkeys, keys));
	}
}
