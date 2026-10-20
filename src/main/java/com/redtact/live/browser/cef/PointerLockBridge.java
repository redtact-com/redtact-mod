package com.redtact.live.browser.cef;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import com.redtact.live.login.LoopbackServer;
import org.cef.browser.CefBrowser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

/**
 * ゲーム内の redtact の Pointer Lock の肩代わり (#88)。ゲーム内の Chromium は画面外に描く方式 (OSR) で、CEF の OSR は
 * Pointer Lock を必ず断る ({@code CefRenderWidgetHostViewOSR::LockPointer} が kPermissionDenied を返すだけ)。そのため
 * redtact の 3D プレビューのフライ (押すとマウスを閉じ込めて見回す) が始まらない。
 *
 * <ul>
 * <li>読み込みが終わるたびに、redtact のページへ代役 ({@link #SCRIPT}) を差し込む。{@code Element#requestPointerLock}・
 * {@code Document#exitPointerLock}・{@code document.pointerLockElement} を置き換え、{@code pointerlockchange} /
 * {@code pointerlockerror} もブラウザと同じく出す
 * <li>頼み (閉じ込める・解く) は、ログインの受け口 ({@link GameLoginBridge}) と同じく、隠した iframe を
 * {@code <同じオリジン>/__redtact_live/pointer-lock?op=&seq=} へ移し、それを移動の受け口 (onBeforeBrowse) で止めて受ける
 * <li>MOD は {@link RedtactBrowserScreen} でマウスをゲームの窓に閉じ込め (SDL の相対モード)、答えと動いた分を
 * {@code executeJavaScript} でページへ返す (動きは movementX / movementY 付きの mousemove。ブラウザと同じく、閉じ込めた
 * ところの clientX / clientY のまま)
 * </ul>
 * 番号 (seq) はページが頼むたびに増やす。古い番号の答えは捨て、ページが持っていない閉じ込めは解かせる (行き違いで閉じ込めが残らない)。
 */
final class PointerLockBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	static final String PATH = "/__redtact_live/pointer-lock";
	/** ページの答えの口の名前。 */
	private static final String API = "window.__redtactLivePointerLock";

	/**
	 * ページへ差し込む代役。同じページに 2 回入れても何もしない。ページを押した直後 (ユーザの操作の直後) の頼みだけ MOD に送る
	 * (ブラウザと同じ。閉じ込めている間に別の要素へ移すときは要らない)。
	 */
	static final String SCRIPT = """
			(function () {
			  if (window.__redtactLivePointerLock) return;
			  var PATH = "%s";
			  var locked = null, lockedSeq = 0, pending = null, seq = 0, watch = 0;
			  var lastX = 0, lastY = 0, lastSX = 0, lastSY = 0, buttons = 0, restX = 0, restY = 0;
			  function fire(type) { document.dispatchEvent(new Event(type, { bubbles: true })); }
			  function tell(op, n) {
			    var f = document.createElement("iframe");
			    f.style.display = "none";
			    f.src = PATH + "?op=" + op + "&seq=" + n;
			    (document.body || document.documentElement).appendChild(f);
			    setTimeout(function () { f.remove(); }, 2000);
			  }
			  function fail(p, why) {
			    fire("pointerlockerror");
			    p.reject(new DOMException(why, "NotAllowedError"));
			  }
			  function lost() {
			    locked = null;
			    clearInterval(watch);
			    fire("pointerlockchange");
			  }
			  ["mousedown", "mouseup", "mousemove"].forEach(function (t) {
			    window.addEventListener(t, function (e) {
			      if (!e.isTrusted) return;
			      lastX = e.clientX; lastY = e.clientY; lastSX = e.screenX; lastSY = e.screenY; buttons = e.buttons;
			    }, true);
			  });
			  Element.prototype.requestPointerLock = function () {
			    var el = this;
			    return new Promise(function (resolve, reject) {
			      var p = { el: el, seq: ++seq, resolve: resolve, reject: reject };
			      var active = !navigator.userActivation || navigator.userActivation.isActive;
			      if (!el.isConnected || (!active && !locked)) {
			        setTimeout(function () { fail(p, "Pointer lock needs a click in the page"); }, 0);
			        return;
			      }
			      if (pending) fail(pending, "Pointer lock request was replaced");
			      pending = p;
			      tell("lock", p.seq);
			      setTimeout(function () { if (pending === p) { pending = null; fail(p, "Pointer lock was not answered"); } }, 3000);
			    });
			  };
			  Object.defineProperty(Document.prototype, "pointerLockElement", {
			    configurable: true,
			    enumerable: true,
			    get: function () { return locked && locked.isConnected ? locked : null; }
			  });
			  Document.prototype.exitPointerLock = function () {
			    if (!locked) return;
			    var n = lockedSeq;
			    locked = null;
			    clearInterval(watch);
			    tell("unlock", n);
			    setTimeout(function () { fire("pointerlockchange"); }, 0);
			  };
			  function granted(n) {
			    var p = pending;
			    if (!p || p.seq !== n) {
			      if (!(locked && lockedSeq === n)) tell("unlock", n);
			      return;
			    }
			    pending = null;
			    if (!p.el.isConnected) { tell("unlock", n); fail(p, "The element was removed"); return; }
			    locked = p.el; lockedSeq = n; restX = 0; restY = 0;
			    clearInterval(watch);
			    watch = setInterval(function () {
			      if (locked && !locked.isConnected) { tell("unlock", lockedSeq); lost(); }
			    }, 500);
			    fire("pointerlockchange");
			    p.resolve();
			  }
			  function denied(n) {
			    var p = pending;
			    if (p && p.seq === n) { pending = null; fail(p, "The game did not lock the pointer"); }
			  }
			  function released(n) { if (locked && lockedSeq === n) lost(); }
			  function holds(n) { return !!locked && lockedSeq === n && locked.isConnected; }
			  function move(dx, dy) {
			    var el = locked;
			    if (!el || !el.isConnected) return;
			    var r = window.devicePixelRatio || 1;
			    restX += dx / r; restY += dy / r;
			    var mx = Math.trunc(restX), my = Math.trunc(restY);
			    restX -= mx; restY -= my;
			    if (!mx && !my) return;
			    var init = { bubbles: true, cancelable: true, composed: true, view: window, clientX: lastX, clientY: lastY,
			      screenX: lastSX, screenY: lastSY, movementX: mx, movementY: my, buttons: buttons };
			    if (window.PointerEvent) {
			      el.dispatchEvent(new PointerEvent("pointermove", Object.assign({ pointerId: 1, pointerType: "mouse", isPrimary: true }, init)));
			    }
			    el.dispatchEvent(new MouseEvent("mousemove", init));
			  }
			  Object.defineProperty(window, "__redtactLivePointerLock", {
			    value: Object.freeze({ version: 1, _granted: granted, _denied: denied, _released: released, _holds: holds, _move: move }),
			    configurable: false,
			    writable: false
			  });
			})();
			""".formatted(PATH);

	/** ページの頼み。 */
	enum Op {
		LOCK,
		UNLOCK
	}

	private PointerLockBridge() {
	}

	/** 代役の頼み (iframe の移動) か。redtact のページから (ログインの受け口と同じページだけ)。 */
	static boolean isRequest(@Nullable String url) {
		URI u = parse(url);
		return u != null && PATH.equals(u.getPath()) && GameLoginBridge.shouldInject(url);
	}

	/** 頼みを受ける (CEF のスレッドから)。閉じ込めはクライアントスレッドで。 */
	static void handle(CefBrowser browser, String url) {
		URI u = parse(url);

		if (u == null) {
			return;
		}

		Map<String, String> q = LoopbackServer.parseForm(u.getRawQuery() == null ? "" : u.getRawQuery());
		Op op = switch (q.getOrDefault("op", "")) {
			case "lock" -> Op.LOCK;
			case "unlock" -> Op.UNLOCK;
			default -> null;
		};
		int seq = parseSeq(q.get("seq"));

		if (op == null || seq <= 0) {
			return;
		}

		LOGGER.debug("[redtact-live] ページの Pointer Lock の頼み: {} {}", op, seq);
		Minecraft.getInstance().execute(() -> RedtactBrowserScreen.pointerLockRequest(browser, op, seq));
	}

	static int parseSeq(@Nullable String s) {
		if (s == null || !s.matches("[0-9]{1,9}")) {
			return 0;
		}

		return Integer.parseInt(s);
	}

	/** 閉じ込めた (ページは pointerlockchange を出し、Promise を解決する)。 */
	static String granted(int seq) {
		return API + " && " + API + "._granted(" + seq + ");";
	}

	/** 閉じ込めなかった (pointerlockerror)。 */
	static String denied(int seq) {
		return API + " && " + API + "._denied(" + seq + ");";
	}

	/** MOD の側で解いた (Esc・ほかのタブ・窓から離れた・ダイアログ)。 */
	static String released(int seq) {
		return API + " && " + API + "._released(" + seq + ");";
	}

	/** 動いた分 (Chromium の画素)。ページで CSS の画素に直して mousemove を出す。 */
	static String move(double dx, double dy) {
		return String.format(Locale.ROOT, "%s && %s._move(%.3f,%.3f);", API, API, dx, dy);
	}

	/**
	 * 閉じ込めている間ときどき送る。ページが入れ替わった (読み直し・移動) などでその閉じ込めを持っていなければ、解く頼みを返させる
	 * (代役のないページでも動くように、iframe を自分で作る)。
	 */
	static String heartbeat(int seq) {
		return """
				(function () {
				  var p = window.__redtactLivePointerLock;
				  if (p && p._holds(%d)) return;
				  var f = document.createElement("iframe");
				  f.style.display = "none";
				  f.src = "%s?op=unlock&seq=%d";
				  (document.body || document.documentElement).appendChild(f);
				  setTimeout(function () { f.remove(); }, 2000);
				})();
				""".formatted(seq, PATH, seq);
	}

	private static @Nullable URI parse(@Nullable String s) {
		if (s == null || s.isBlank()) {
			return null;
		}

		try {
			return URI.create(s.strip());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
