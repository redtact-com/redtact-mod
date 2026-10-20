package com.redtact.live.browser.cef;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Canvas;
import java.awt.event.KeyEvent;

import org.junit.jupiter.api.Test;

/** Windows の jcef へ渡すスキャンコード (#61)。 */
class WindowsScancodesTest {
	// SDL のスキャンコード (USB の HID の番号。マイクラ 26.3 の InputConstants と同じ)
	private static final int A = 4;
	private static final int ENTER = 40;
	private static final int BACKSPACE = 42;
	private static final int DELETE = 76;
	private static final int UP = 82;
	private static final int KP_1 = 89;
	private static final int LEFT_CTRL = 224;

	@Test
	void editingKeysHaveTheirPs2Scancodes() {
		// Windows の MapVirtualKey(sc, MAPVK_VSC_TO_VK) が VK_BACK・'A'・VK_RETURN・VK_DELETE・VK_UP・VK_CONTROL にする値
		assertEquals(0x0E, WindowsScancodes.of(BACKSPACE));
		assertEquals(0x1E, WindowsScancodes.of(A));
		assertEquals(0x2C, WindowsScancodes.of(A + 25), "Z");
		assertEquals(0x02, WindowsScancodes.of(30), "1");
		assertEquals(0x0B, WindowsScancodes.of(39), "0");
		assertEquals(0x1C, WindowsScancodes.of(ENTER));
		assertEquals(0x53, WindowsScancodes.of(DELETE));
		assertEquals(0x48, WindowsScancodes.of(UP), "拡張キーは 0xE0 を付けない (jcef は 1 バイトしか渡せない)");
		assertEquals(0x1D, WindowsScancodes.of(LEFT_CTRL));
	}

	@Test
	void unknownKeysAndNumpadDigitsAreZero() {
		assertEquals(0, WindowsScancodes.of(KP_1), "テンキーの数字は End などになるので送らない");
		assertEquals(0, WindowsScancodes.of(0));
		assertEquals(0, WindowsScancodes.of(-1));
		assertEquals(0, WindowsScancodes.of(512));
	}

	@Test
	void keyEventCarriesTheScancodeUnderTheNameJcefReads() throws NoSuchFieldException {
		// jcef (Windows) は KeyEvent の long の欄 scancode を名前で読む。子のクラスに同じ名前・型の欄があれば、そちらが読まれる
		assertEquals(long.class, KeyEvent.class.getDeclaredField("scancode").getType());
		assertEquals(long.class, CefKeys.ScancodeKeyEvent.class.getDeclaredField("scancode").getType());

		var e = new CefKeys.ScancodeKeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_A, 'A', 0x1E);
		assertEquals(0x1E, e.scancode());
		assertEquals(KeyEvent.VK_A, e.getKeyCode());
		assertEquals(KeyEvent.CTRL_DOWN_MASK, e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK);
	}
}
