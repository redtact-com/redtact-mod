package com.redtact.live.browser.cef;

import java.util.Locale;

/**
 * ゲーム内の redtact のページの {@code KeyboardEvent.code} の補正 (Linux は #88・Windows は #94)。jcef はキーの押下と離しの
 * native_key_code を CEF が読める形で渡せないので、ページの code が違うキーや空になる。redtact の 3D プレビューのフライは code
 * (KeyW・Space・ShiftLeft・ControlLeft) で動くので、押下と離しの code を keyCode から US 配列の名前で出し直す。
 *
 * <ul>
 * <li>Linux: jcef は 9 つのキーのほかは keyChar を X の keysym とみなして native_key_code に入れ、Chromium はそれを X のキーの
 * 番号として読むので、違うキーになる (W → Numpad1・Space → KeyO)。修飾キーは {@link LinuxKeys#keyChar} で keysym にして送り、
 * keyCode を正しくする
 * <li>Windows: jcef は native_key_code を WM_KEYDOWN の lParam の形 ({@code (scancode << 16) | 1}) で渡す。CEF はそれを
 * スキャンコードそのものとして {@code NativeKeycodeToDomCode} に渡すので一致せず、code が空になる (CEF の cefclient の同じ
 * 取り違えは chromiumembedded/cef#2228)。keyCode は #61 のスキャンコード ({@link WindowsScancodes}) で正しい
 * <li>Mac は試していないので入れない (jcef は字から Mac のキーの番号を作るので、字のキーの code は正しいはず)
 * </ul>
 * 字の入力そのものは変わらない (code を読むページだけに効く)。US 配列の名前なので、AZERTY など字の並びが違う配列では、物理的な
 * 位置ではなく字のキーになる。MCEF Modern の型に触れない (単体テストのため)。
 */
final class PageKeyCodes {
	/** この OS で補正を差し込むか。 */
	static final boolean NEEDED = needed(System.getProperty("os.name", ""));

	/**
	 * ページへ差し込む補正。ページが作ったイベント (isTrusted でない)・押下と離し以外・keyCode の分からないキーはそのまま。
	 * 同じページに 2 回入れても何もしない。
	 */
	static final String SCRIPT = """
			(function () {
			  if (window.__redtactLiveCodeFix) return;
			  window.__redtactLiveCodeFix = true;
			  var d = Object.getOwnPropertyDescriptor(KeyboardEvent.prototype, "code");
			  if (!d || !d.get) return;
			  var get = d.get;
			  var named = { 8: "Backspace", 9: "Tab", 13: "Enter", 16: "ShiftLeft", 17: "ControlLeft", 18: "AltLeft", 19: "Pause",
			    20: "CapsLock", 27: "Escape", 32: "Space", 33: "PageUp", 34: "PageDown", 35: "End", 36: "Home", 37: "ArrowLeft",
			    38: "ArrowUp", 39: "ArrowRight", 40: "ArrowDown", 45: "Insert", 46: "Delete", 91: "MetaLeft", 92: "MetaRight",
			    93: "ContextMenu", 106: "NumpadMultiply", 107: "NumpadAdd", 109: "NumpadSubtract", 110: "NumpadDecimal",
			    111: "NumpadDivide", 144: "NumLock", 145: "ScrollLock", 186: "Semicolon", 187: "Equal", 188: "Comma", 189: "Minus",
			    190: "Period", 191: "Slash", 192: "Backquote", 219: "BracketLeft", 220: "Backslash", 221: "BracketRight", 222: "Quote" };
			  function us(k, loc) {
			    if (k >= 65 && k <= 90) return "Key" + String.fromCharCode(k);
			    if (k >= 48 && k <= 57) return "Digit" + (k - 48);
			    if (k >= 96 && k <= 105) return "Numpad" + (k - 96);
			    if (k >= 112 && k <= 135) return "F" + (k - 111);
			    if (loc === 2 && (k === 16 || k === 17 || k === 18)) return named[k].replace("Left", "Right");
			    return named[k] || "";
			  }
			  Object.defineProperty(KeyboardEvent.prototype, "code", {
			    configurable: true,
			    enumerable: d.enumerable,
			    get: function () {
			      var c = get.call(this);
			      if (!this.isTrusted || (this.type !== "keydown" && this.type !== "keyup")) return c;
			      return us(this.keyCode, this.location) || c;
			    }
			  });
			})();
			""";

	private PageKeyCodes() {
	}

	/** os.name (例「Windows 11」「Linux」「Mac OS X」) で補正が要るか。 */
	static boolean needed(String osName) {
		String os = osName.toLowerCase(Locale.ROOT);
		return os.contains("linux") || os.startsWith("windows");
	}
}
