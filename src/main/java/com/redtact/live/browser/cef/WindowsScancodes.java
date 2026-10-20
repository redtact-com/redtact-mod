package com.redtact.live.browser.cef;

/**
 * SDL のスキャンコード (マイクラ 26.3 の {@code KeyEvent#key()}。USB の HID の番号) から、Windows のスキャンコード
 * (PS/2 の set 1) へ (#61)。
 *
 * <p>Windows の jcef はキーの押下・離しを、AWT の {@code KeyEvent} の非公開の {@code scancode} から
 * {@code MapVirtualKey(scancode, MAPVK_VSC_TO_VK)} で仮想キーにして Chromium へ渡す。MCEF Modern はここを埋めないので、
 * Windows では BackSpace・Ctrl+A・矢印などがすべて「キー 0」になり効かない ({@link CefKeys})。これで仮想キー (ページの keyCode と
 * key) は正しくなるが、code は空のまま (jcef が native_key_code を lParam の形で渡すため。{@link PageKeyCodes}。#94)。
 *
 * <p>拡張キー (矢印・Home・Delete など。本来は 0xE0 が付く) は下の 1 バイトだけを返す。jcef は 1 バイトしか正しく
 * 渡せず、Windows の AWT も同じ値を入れる (MapVirtualKey は 0x48 を VK_UP にする)。テンキーの数字は NumLock が
 * 点いていても MapVirtualKey が矢印などにしてしまう (1 を押すと End で字が末尾へ飛ぶ) ので 0 (送らない。字は文字入力で届く)。
 */
final class WindowsScancodes {
	private static final int[] TABLE = new int[232];

	static {
		// A〜Z (SDL 4〜29)
		int[] letters = {0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16,
				0x2F, 0x11, 0x2D, 0x15, 0x2C};

		for (int i = 0; i < letters.length; i++) {
			TABLE[4 + i] = letters[i];
		}

		// 1〜9, 0 (SDL 30〜39)
		for (int i = 0; i < 10; i++) {
			TABLE[30 + i] = 0x02 + i;
		}

		put(40, 0x1C); // Enter
		put(41, 0x01); // Esc
		put(42, 0x0E); // BackSpace
		put(43, 0x0F); // Tab
		put(44, 0x39); // Space
		put(45, 0x0C); // -
		put(46, 0x0D); // = (日本語配列では ^)
		put(47, 0x1A); // [ (@)
		put(48, 0x1B); // ] ([)
		put(49, 0x2B); // \ (])
		put(50, 0x2B); // # (US 以外の \ の位置)
		put(51, 0x27); // ;
		put(52, 0x28); // ' (:)
		put(53, 0x29); // ` (半角/全角)
		put(54, 0x33); // ,
		put(55, 0x34); // .
		put(56, 0x35); // /
		put(57, 0x3A); // CapsLock

		// F1〜F10 (SDL 58〜67)・F11・F12
		for (int i = 0; i < 10; i++) {
			TABLE[58 + i] = 0x3B + i;
		}

		put(68, 0x57);
		put(69, 0x58);
		put(71, 0x46); // ScrollLock
		put(73, 0x52); // Insert
		put(74, 0x47); // Home
		put(75, 0x49); // PageUp
		put(76, 0x53); // Delete
		put(77, 0x4F); // End
		put(78, 0x51); // PageDown
		put(79, 0x4D); // →
		put(80, 0x4B); // ←
		put(81, 0x50); // ↓
		put(82, 0x48); // ↑
		put(83, 0x45); // NumLock
		put(84, 0x35); // テンキーの /
		put(85, 0x37); // テンキーの *
		put(86, 0x4A); // テンキーの -
		put(87, 0x4E); // テンキーの +
		put(88, 0x1C); // テンキーの Enter
		put(100, 0x56); // US 以外の \ (ISO の左下)
		put(135, 0x73); // 日本語配列の \ (ろ)
		put(137, 0x7D); // 日本語配列の ¥
		put(224, 0x1D); // 左 Ctrl
		put(225, 0x2A); // 左 Shift
		put(226, 0x38); // 左 Alt
		put(228, 0x1D); // 右 Ctrl
		put(229, 0x36); // 右 Shift
		put(230, 0x38); // 右 Alt
	}

	private WindowsScancodes() {
	}

	private static void put(int sdl, int scancode) {
		TABLE[sdl] = scancode;
	}

	/** Windows のスキャンコード。分からないキーは 0 (jcef は仮想キー 0 で送り、Chromium は何もしない。今までと同じ)。 */
	static int of(int sdlScancode) {
		return sdlScancode >= 0 && sdlScancode < TABLE.length ? TABLE[sdlScancode] : 0;
	}
}
