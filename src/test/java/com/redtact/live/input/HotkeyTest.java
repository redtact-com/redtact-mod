package com.redtact.live.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class HotkeyTest {
	private static final int R = 21;
	private static final int L = 15;
	private static final int A = 4;
	private static final int K = 14;
	private static final int W = 26;
	private static final int LEFT_SHIFT = 225;

	@Test
	void namesMatchMalilibScanCodes() {
		assertEquals(R, KeyNames.code("R").getAsInt());
		assertEquals(47, KeyNames.code("LEFT_BRACKET").getAsInt());
		assertEquals(48, KeyNames.code("right_bracket").getAsInt());
		assertEquals(-99, KeyNames.code("MOUSE_LEFT").getAsInt());
		assertEquals(-97, KeyNames.code("MOUSE_RIGHT").getAsInt());
		assertEquals(40, KeyNames.code("ENTER").getAsInt()); // 別名
		assertEquals("RETURN", KeyNames.name(40));
		assertTrue(KeyNames.code("NO_SUCH_KEY").isEmpty());
		assertEquals(3, KeyNames.mouseButton(KeyNames.MOUSE_RIGHT));
		assertEquals(KeyNames.MOUSE_LEFT, KeyNames.mouseCode(1));
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
