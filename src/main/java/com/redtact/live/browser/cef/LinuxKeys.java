package com.redtact.live.browser.cef;

import java.awt.event.KeyEvent;
import java.util.Locale;

/**
 * Linux の jcef のキーの取り違えの補正 (#88)。jcef の Linux は、9 つのキー (BackSpace・Delete・矢印・Enter・Esc・Tab) のほかは
 * keyChar を X の keysym とみなして native_key_code に入れ、keyCode (windows_key_code) もそこから決める。修飾キーは MCEF Modern の
 * keyChar ({@code (char) VK_SHIFT} など) が keysym にならず、keyCode が 0 になるので、keysym にして送る ({@link #keyChar})。
 * keyCode と key が正しくなる。
 *
 * <p>ページの {@code KeyboardEvent.code} のずれ (W → Numpad1・Space → KeyO) は、keyCode から出し直す ({@link PageKeyCodes}。
 * Windows も同じ補正を使う)。MCEF Modern の型に触れない (単体テストのため)。
 */
final class LinuxKeys {
	/** Linux か。 */
	static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

	private LinuxKeys() {
	}

	/**
	 * jcef へ渡す keyChar。修飾キーは左の keysym (右の Shift なども左として届く。jcef は左右を渡せない)。ほかのキーは MCEF Modern と
	 * 同じ {@code (char) keyCode}。
	 */
	static char keyChar(int awtKeyCode) {
		return switch (awtKeyCode) {
			case KeyEvent.VK_SHIFT -> (char) 0xffe1; // XK_Shift_L
			case KeyEvent.VK_CONTROL -> (char) 0xffe3; // XK_Control_L
			case KeyEvent.VK_ALT -> (char) 0xffe9; // XK_Alt_L
			case KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> (char) 0xffeb; // XK_Super_L
			default -> (char) awtKeyCode;
		};
	}
}
