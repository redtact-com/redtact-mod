package com.redtact.live.browser.cef;

import java.awt.Component;
import java.lang.reflect.Method;

import net.dimaskama.mcef.api.MCEFBrowser;
import org.cef.browser.CefBrowser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.input.KeyEvent;

/**
 * キーの押下と離しを Chromium へ送る (#61)。MCEF Modern の {@code onKeyPressed} / {@code onKeyReleased} と同じ
 * AWT の {@code KeyEvent} を作り、Windows のスキャンコード ({@link WindowsScancodes}) を足して送る。
 *
 * <p>Windows の jcef は、押下の仮想キーを {@code KeyEvent} の非公開の欄 {@code scancode} から決める (JNI で名前で読む)。
 * MCEF Modern はそこを埋めないので、Windows では BackSpace・Ctrl+A・矢印・Enter などが効かない (字は文字入力で別に届く)。
 * {@link ScancodeKeyEvent} は同じ名前の欄を持ち、JNI はそちらを読む。Linux・Mac の jcef はこの欄を読まないので、
 * 今までと同じ。
 *
 * <p>Linux の jcef は keyChar を X の keysym とみなすので、修飾キーは keysym にして送る ({@link LinuxKeys}。#88)。
 *
 * <p>どちらの OS でも、ページの {@code KeyboardEvent.code} は jcef が正しく渡せない (Linux は違うキー・Windows は空)。
 * redtact のページには keyCode から code を出し直す補正を差し込む ({@link PageKeyCodes}。#88・#94)。
 *
 * <p>送るのは jcef の {@code CefBrowser_N#sendKeyEvent} (protected) を反射で。キーの番号と修飾キーは MCEF Modern の
 * 変換 (private) をそのまま使う。どれかが無ければ (MCEF の版が違う) false を返し、呼ぶ側が MCEF のふつうの口で送る。
 */
final class CefKeys {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static @Nullable Method sendKeyEvent;
	private static @Nullable Method toAwtKeyCode;
	private static @Nullable Method toAwtModifiers;
	private static boolean resolved;
	private static boolean failed;

	private CefKeys() {
	}

	/** 押下 (pressed) か離しを送る。送れなければ false。 */
	static boolean send(MCEFBrowser browser, boolean pressed, KeyEvent event) {
		CefBrowser cef = browser.getCefBrowser();

		if (failed || !resolve(browser, cef)) {
			return false;
		}

		try {
			int keyCode = (int) toAwtKeyCode.invoke(null, event.key());
			int modifiers = (int) toAwtModifiers.invoke(null, event.modifiers());
			int id = pressed ? java.awt.event.KeyEvent.KEY_PRESSED : java.awt.event.KeyEvent.KEY_RELEASED;
			// keyChar は MCEF Modern と同じ (Linux の jcef はこれで X のキーを決める)。Linux の修飾キーだけ X の keysym にする (#88)
			char keyChar = LinuxKeys.LINUX ? LinuxKeys.keyChar(keyCode) : (char) keyCode;
			sendKeyEvent.invoke(cef, new ScancodeKeyEvent(cef.getUIComponent(), id, System.currentTimeMillis(), modifiers, keyCode, keyChar,
					WindowsScancodes.of(event.key())));
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			failed = true;
			LOGGER.warn("[redtact-live] ゲーム内の redtact へキーを送れないので、MCEF Modern の口で送る (Windows では BackSpace などが効かない)", e);
			return false;
		}
	}

	private static boolean resolve(MCEFBrowser browser, CefBrowser cef) {
		if (resolved) {
			return sendKeyEvent != null && toAwtKeyCode != null && toAwtModifiers != null;
		}

		resolved = true;
		sendKeyEvent = find(cef.getClass(), "sendKeyEvent", java.awt.event.KeyEvent.class);
		toAwtKeyCode = find(browser.getClass(), "toAwtKeyCode", int.class);
		toAwtModifiers = find(browser.getClass(), "toAwtInputModifiers", int.class);

		if (sendKeyEvent == null || toAwtKeyCode == null || toAwtModifiers == null) {
			LOGGER.warn("[redtact-live] MCEF Modern の版が違い、キーを自前で送れない (Windows では BackSpace などが効かない)");
			return false;
		}

		return true;
	}

	/** c とその親から、名前と引数の型が合うメソッドを探して使えるようにする。 */
	private static @Nullable Method find(Class<?> c, String name, Class<?> param) {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			try {
				Method m = k.getDeclaredMethod(name, param);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException e) {
				// 親へ
			} catch (RuntimeException e) {
				return null; // 使えない (モジュールなど)
			}
		}

		return null;
	}

	/**
	 * Windows のスキャンコードを持つ {@code KeyEvent}。jcef は {@code GetFieldID(実際のクラス, "scancode", "J")} で読み、
	 * JNI は自分のクラスの欄から探すので、親 (AWT) の非公開の同じ名前の欄より先にこちらが見つかる。
	 */
	static final class ScancodeKeyEvent extends java.awt.event.KeyEvent {
		private static final long serialVersionUID = 1L;
		private final long scancode;

		ScancodeKeyEvent(Component source, int id, long when, int modifiers, int keyCode, char keyChar, long scancode) {
			super(source, id, when, modifiers, keyCode, keyChar);
			this.scancode = scancode;
		}

		long scancode() {
			return scancode;
		}
	}
}
