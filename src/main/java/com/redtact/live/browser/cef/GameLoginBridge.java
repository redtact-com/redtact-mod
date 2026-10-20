package com.redtact.live.browser.cef;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.login.GameLogin;
import com.redtact.live.login.LoopbackServer;
import org.cef.browser.CefBrowser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

/**
 * ゲーム内の redtact とのログインのやり取り (#50 / redtact#738)。
 *
 * <ul>
 * <li>読み込みが終わるたびに、redtact のページへ受け口 {@code window.__redtactLive} を差し込む ({@link #SCRIPT})。
 * 約束は redtact の {@code frontend/app/lib/gameShell.ts} (版 {@value #VERSION})
 * <li>ページの requestCredential は、隠した iframe を {@code <同じオリジン>/__redtact_live/login?method=&req=} へ移す。
 * それを移動の受け口 (onBeforeBrowse) で止めて受ける (Linux の MCEF でも Java に届く経路。ポップアップや
 * CefMessageRouter の呼び返しは届かないことがある)
 * <li>{@link GameLogin} の結果を {@code executeJavaScript} で {@code window.__redtactLive._settle(req, ok, value)} に返す
 * </ul>
 */
final class GameLoginBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final Gson GSON = new Gson();
	static final int VERSION = 1;
	static final String PATH = "/__redtact_live/login";
	private static final Pattern REQ = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	/** ページへ差し込む受け口。同じページに 2 回入れても何もしない。 */
	static final String SCRIPT = """
			(function () {
			  if (window.__redtactLive) return;
			  var pending = {};
			  var seq = 0;
			  function requestCredential(method) {
			    return new Promise(function (resolve, reject) {
			      var req = (++seq) + "-" + Math.random().toString(36).slice(2, 12);
			      pending[req] = { resolve: resolve, reject: reject };
			      var f = document.createElement("iframe");
			      f.style.display = "none";
			      f.src = "%s?method=" + encodeURIComponent(method) + "&req=" + encodeURIComponent(req);
			      (document.body || document.documentElement).appendChild(f);
			      setTimeout(function () { f.remove(); }, 2000);
			    });
			  }
			  function settle(req, ok, value) {
			    var p = pending[req];
			    if (!p) return;
			    delete pending[req];
			    if (ok) p.resolve(value);
			    else p.reject(Object.assign(new Error(value && value.code || "game_failed"), { code: value && value.code || "game_failed" }));
			  }
			  Object.defineProperty(window, "__redtactLive", {
			    value: Object.freeze({ version: %d, requestCredential: requestCredential, _settle: settle }),
			    configurable: false,
			    writable: false
			  });
			})();
			""".formatted(PATH, VERSION);

	private GameLoginBridge() {
	}

	/** 受け口を差し込んでよいページ (redtact) か。 */
	static boolean shouldInject(@Nullable String pageUrl) {
		URI u = parse(pageUrl);
		return u != null && u.getHost() != null && isRedtact(u);
	}

	/** 受け口の頼み (iframe の移動) か。 */
	static boolean isRequest(@Nullable String url) {
		URI u = parse(url);
		return u != null && u.getHost() != null && PATH.equals(u.getPath()) && isRedtact(u);
	}

	/** 頼みを受ける (CEF のスレッドから)。ログインはクライアントスレッドで始める。 */
	static void handle(CefBrowser browser, String url) {
		URI u = parse(url);

		if (u == null) {
			return;
		}

		Map<String, String> q = LoopbackServer.parseForm(u.getRawQuery() == null ? "" : u.getRawQuery());
		String method = q.getOrDefault("method", "");
		String req = q.getOrDefault("req", "");

		if (!REQ.matcher(req).matches()) {
			return;
		}

		String origin = u.getScheme() + "://" + u.getRawAuthority();
		LOGGER.info("[redtact-live] ゲーム内の redtact からログインの頼み: {} ({})", method, origin);
		Minecraft.getInstance().execute(() -> RedtactLive.get().gameLogin().start(origin, method, reply -> settle(browser, req, reply)));
	}

	/** 結果をページへ返す (クライアントスレッドから)。ページが redtact でなくなっていれば返さない。 */
	private static void settle(CefBrowser browser, String req, GameLogin.Reply reply) {
		if (!CefHooks.tracked(browser) || !shouldInject(browser.getURL())) {
			LOGGER.info("[redtact-live] ログインの結果を返すページがありません (閉じたか移った)");
			return;
		}

		boolean ok = reply.outcome() == GameLogin.Outcome.CREDENTIAL;
		Object value = ok ? Map.of("method", reply.method(), "idToken", reply.idToken(), "rawNonce", reply.rawNonce())
				: Map.of("code", reply.outcome().code());
		String js = "window.__redtactLive && window.__redtactLive._settle(" + GSON.toJson(req) + "," + ok + "," + GSON.toJson(value) + ");";
		browser.executeJavaScript(js, browser.getURL(), 0);
	}

	private static boolean isRedtact(URI u) {
		String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);

		if (!scheme.equals("https") && !scheme.equals("http")) {
			return false;
		}

		String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
		URI home = BrowserSupport.home();
		// ファイルの置き場 (R2) やほかのホストには入れない
		return RedtactLinks.isRedtactHost(host) || host.equals(home.getHost() == null ? "" : home.getHost().toLowerCase(java.util.Locale.ROOT));
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
