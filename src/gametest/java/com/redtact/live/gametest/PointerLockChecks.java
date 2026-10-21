package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.browser.cef.RedtactBrowserScreen;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuSection;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.i18n.UiLanguage;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Pointer Lock の肩代わり (#88)。本物の Chromium (画面外に描く方式は Pointer Lock を必ず断る) で、redtact の 3D プレビューの
 * フライと同じく「押すとマウスを閉じ込めて見回す」ページを出し、本物のマウスとキーで確かめる。ページの様子は URL の # で見る。
 * <ul>
 * <li>押すと閉じ込める (pointerlockchange・document.pointerLockElement)。閉じ込めている間は文字入力を止める
 * <li>動かすと movementX / movementY 付きの mousemove が届く (カーソルを同じだけ動かしたときと同じ量)。キーもページに届く
 * <li>Esc は閉じ込めだけを解く (メニューは閉じない・ページに Esc は届かない)。文字入力は戻る
 * <li>もう一度押すと閉じ込め直す。ページの exitPointerLock でも解ける。ほかのタブへ移ると解ける
 * <li>押していないとき (スクリプトだけ) の頼みは断る (pointerlockerror)
 * </ul>
 * 閉じ込めそのもの (SDL の相対モード) は gametest の Fabric が止めているので、MOD の状態とページへの届き方で見る。
 * {@code REDTACT_LIVE_REAL_CIRCUIT} があれば、本物の redtact の回路のページの 3D プレビューのフライも確かめる ({@link #runReal})。
 * GPU の無い Xvfb では Chromium が WebGL を止めるので {@code REDTACT_LIVE_WEBGL=1} を付ける (gametest だけの mixin でソフトウェアの
 * WebGL を許す)。{@code REDTACT_LIVE_POINTER_LOCK_FRAMES=1} で PR の動作 GIF 用のコマも撮る。
 */
final class PointerLockChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	/** 初回は Chromium を取ってくるので長く待つ。 */
	private static final int PREPARE_TIMEOUT = 20 * 60 * 20;
	static final String PATH = "/pointer-lock";
	/** 押すと閉じ込めを頼み、閉じ込めている間の動きを足していくページ (redtact のフライと同じ作り)。 */
	static final String PAGE = """
			<!doctype html><html><head><meta charset="utf-8"><title>pointer lock (gametest)</title><style>
			html,body{margin:0;height:100%;overflow:hidden}canvas{display:block;width:100vw;height:100vh;background:#234}
			</style></head><body><canvas id="c"></canvas><script>
			var c = document.getElementById('c'), mx = 0, my = 0, changes = 0, errors = 0, keys = '';
			function state() { return document.pointerLockElement === c ? 'locked' : 'free'; }
			function show(tag) { location.hash = [tag, state(), mx + ',' + my, changes, errors, keys].join(':'); }
			function lock() { var r = c.requestPointerLock(); if (r && r.catch) r.catch(function () {}); }
			c.addEventListener('mousedown', function () { if (document.pointerLockElement !== c) lock(); });
			c.addEventListener('mousemove', function (e) {
			  if (document.pointerLockElement !== c) return;
			  mx += e.movementX; my += e.movementY; show('move');
			});
			document.addEventListener('pointerlockchange', function () { changes++; show('change'); });
			document.addEventListener('pointerlockerror', function () { errors++; show('error'); });
			document.addEventListener('keydown', function (e) {
			  keys += (keys ? ',' : '') + (e.code || '?' + e.key);
			  if (e.code === 'KeyQ') document.exitPointerLock();
			  show('key');
			});
			show('ready@' + devicePixelRatio);
			</script></body></html>
			""";

	private PointerLockChecks() {
	}

	/**
	 * これだけを確かめる ({@link PointerLockClientGameTest})。手元の HTTP サーバを redtact の代わりにして世界を作り、redtact タブを
	 * 開いて {@link #run}。{@code REDTACT_LIVE_REAL_CIRCUIT} があれば {@link #runReal} も。
	 */
	static void runStandalone(ClientGameTestContext context) {
		// 確かめる文は日本語 (#79。gametest のマイクラは英語なので、自動のままだと英語になる)
		context.runOnClient(mc -> Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.JA));
		HttpServer server;

		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		String home = "http://localhost:" + server.getAddress().getPort();
		server.createContext("/", ex -> {
			boolean page = ex.getRequestURI().getPath().equals(PATH);
			byte[] body = page ? PAGE.getBytes(StandardCharsets.UTF_8) : new byte[0];
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			ex.sendResponseHeaders(page ? 200 : 404, body.length == 0 ? -1 : body.length);

			try (OutputStream out = ex.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		String savedUrl = context.computeOnClient(mc -> Configs.Generic.REDTACT_URL.getStringValue());

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			context.runOnClient(mc -> {
				Configs.Generic.REDTACT_URL.setValueFromString(home);
				BrowserSupport.open(home + PATH); // 用意の途中でもこのページを覚えて開く
			});
			context.waitFor(mc -> mc.gui.screen() instanceof RedtactBrowserScreen, PREPARE_TIMEOUT);
			context.waitFor(mc -> browser(mc) != null && browser(mc).painted() && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
			run(context, home + PATH, null);
			String real = System.getenv("REDTACT_LIVE_REAL_CIRCUIT");

			if (real != null && !real.isBlank()) {
				URI page = URI.create(real.strip());
				context.runOnClient(mc -> Configs.Generic.REDTACT_URL.setValueFromString(page.getScheme() + "://" + page.getHost()));
				runReal(context, real.strip());
			}

			context.runOnClient(mc -> mc.gui.setScreen(null));
		} finally {
			server.stop(0);
			context.runOnClient(mc -> Configs.Generic.REDTACT_URL.setValueFromString(savedUrl));
		}
	}

	/** 手元のサーバのページ (redtact の代わり。同じオリジン) で確かめ、最後に next へ移る (null なら移らない)。 */
	static void run(ClientGameTestContext context, String pageUrl, String next) {
		context.runOnClient(mc -> browser(mc).load(pageUrl));
		context.waitFor(mc -> browser(mc) != null && browser(mc).pageUrl().startsWith(pageUrl) && !browser(mc).loading() && hash(mc).startsWith("ready@"),
				LibraryClientGameTest.TIMEOUT);
		context.waitFor(mc -> browser(mc).hasGameBridge(), LibraryClientGameTest.TIMEOUT); // 代役はログインの受け口と一緒に入る
		double dpr = Double.parseDouble(field(context, 0).substring("ready@".length()));
		context.waitTicks(10);

		// ---- 押していない (スクリプトだけの) 頼みは断る (読み込んだばかりのページはユーザの操作の直後ではない)
		context.runOnClient(mc -> browser(mc).executeScript("lock()"));
		waitPage(context, mc -> hash(mc).startsWith("error:free:"), "押していない頼みを断っていない");
		check(!context.computeOnClient(mc -> browser(mc).pointerLocked()), "押していないのに閉じ込めた");

		// ---- 押すと閉じ込める。文字入力は止まる
		clickPage(context, 0.5, 0.5);
		waitPage(context, mc -> hash(mc).startsWith("change:locked:0,0:1:1"), "押しても閉じ込めない");
		check(context.computeOnClient(mc -> browser(mc).pointerLocked()), "ページは閉じ込めたのに MOD が閉じ込めていない");
		check(!GameTestText.TEXT_INPUT_TOGGLES || !context.computeOnClient(PointerLockChecks::textInputActive),
				"閉じ込めている間も文字入力が有効 (日本語入力のままだと WASD が IME に取られる)");
		context.takeScreenshot("redtact-live-pointer-lock-1-locked");

		// ---- 動かすと movementX / movementY が届く。量はカーソルを同じだけ動かしたとき (CSS の画素) と同じ
		for (int i = 0; i < 3; i++) {
			GameTestMouse.move(context, 40, -16);
			context.waitTicks(2);
		}

		double[] expect = context.computeOnClient(mc -> {
			RedtactBrowserScreen b = browser(mc);
			double scale = (double) b.browserWidth() / b.pageWidth(); // Chromium の画素 / GUI の画素
			return new double[] {MouseHandler.getScaledXPos(mc.getWindow(), 120) * scale / dpr, MouseHandler.getScaledYPos(mc.getWindow(), -48) * scale / dpr};
		});
		waitPage(context, mc -> hash(mc).startsWith("move:locked:"), "動きが届かない");
		context.waitTicks(5);
		String[] m = field(context, 2).split(",");
		int mx = Integer.parseInt(m[0]);
		int my = Integer.parseInt(m[1]);
		LOGGER.info("[pointer-lock] 動き: 届いた ({}, {})・見込み ({}, {})・devicePixelRatio {}", mx, my, expect[0], expect[1], dpr);
		check(Math.abs(mx - expect[0]) <= 1.5 && Math.abs(my - expect[1]) <= 1.5, "動いた分が違う: (" + mx + ", " + my + ") 見込み (" + expect[0] + ", " + expect[1] + ")");
		check(context.computeOnClient(mc -> browser(mc).pointerLocked()), "動かしたら閉じ込めが解けた");

		// ---- 閉じ込めている間もキーはページへ。フライは code で見る (Linux の jcef は code を取り違えるので補正している)
		for (int key : new int[] {InputConstants.KEY_W, InputConstants.KEY_A, InputConstants.KEY_S, InputConstants.KEY_D, InputConstants.KEY_SPACE,
				InputConstants.KEY_LSHIFT, InputConstants.KEY_LCONTROL}) {
			context.getInput().pressKey(key);
		}

		String flyKeys = "KeyW,KeyA,KeyS,KeyD,Space,ShiftLeft,ControlLeft";
		waitPage(context, mc -> hash(mc).startsWith("key:locked:") && field(mc, 5).equals(flyKeys), "閉じ込めている間のフライのキーの code が違う (見込み " + flyKeys + ")");

		// ---- Esc は閉じ込めだけを解く (メニューは閉じない・ページに Esc は届かない)。文字入力は戻る
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		waitPage(context, mc -> browser(mc) == null || hash(mc).startsWith("change:free:"), "Esc で閉じ込めが解けない");
		context.waitTicks(5);
		check(context.computeOnClient(mc -> browser(mc) != null), "閉じ込めている間の Esc でメニューを閉じた");
		check(!context.computeOnClient(mc -> browser(mc).pointerLocked()), "Esc で閉じ込めが解けていない");
		check(context.computeOnClient(mc -> field(mc, 5)).equals(flyKeys), "閉じ込めを解いた Esc がページへ届いた: " + context.computeOnClient(mc -> field(mc, 5)));
		check(context.computeOnClient(PointerLockChecks::textInputActive), "閉じ込めを解いても文字入力が戻らない");

		// 解いたあとの動きは閉じ込めの動きにならない (ふつうのカーソルの動き)
		context.getInput().moveCursor(30, 0);
		context.waitTicks(5);
		check(field(context, 2).equals(mx + "," + my), "解いたあとの動きを閉じ込めの動きとして送った");

		// ---- もう一度押すと閉じ込め直す。ページの exitPointerLock (Q) でも解ける
		clickPage(context, 0.5, 0.5);
		context.waitFor(mc -> hash(mc).startsWith("change:locked:") && browser(mc).pointerLocked(), LibraryClientGameTest.TIMEOUT);
		context.getInput().pressKey(InputConstants.KEY_Q);
		context.waitFor(mc -> hash(mc).startsWith("change:free:") && !browser(mc).pointerLocked(), LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(PointerLockChecks::textInputActive), "ページが解いたあと文字入力が戻らない");

		// ---- ほかのタブへ移ると解ける。戻るとページも解けている (閉じ込めている間はカーソルが動かずタブを押せないので、
		// ホットキーで移ったときと同じくメニューを直に開く)
		clickPage(context, 0.5, 0.5);
		context.waitFor(mc -> hash(mc).startsWith("change:locked:") && browser(mc).pointerLocked(), LibraryClientGameTest.TIMEOUT);
		context.runOnClient(mc -> Menu.open(MenuTab.LIBRARY));
		context.waitFor(mc -> !(mc.gui.screen() instanceof RedtactBrowserScreen), LibraryClientGameTest.TIMEOUT);
		check(!context.computeOnClient(mc -> com.redtact.live.input.PointerCapture.active()), "ほかのタブへ移ってもマウスの動きを取ったまま");
		LibraryClientGameTest.clickSection(context, MenuSection.REDTACT);
		context.waitForScreen(RedtactBrowserScreen.class);
		context.waitFor(mc -> hash(mc).startsWith("change:free:"), LibraryClientGameTest.TIMEOUT);
		check(!context.computeOnClient(mc -> browser(mc).pointerLocked()), "ほかのタブから戻ったら閉じ込めたまま");
		LOGGER.info("[pointer-lock] 押して閉じ込め・動き・キー・Esc・閉じ込め直し・exitPointerLock・ほかのタブを確かめた");

		if (next != null) {
			context.runOnClient(mc -> browser(mc).load(next));
			context.waitFor(mc -> browser(mc).pageUrl().equals(next) && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
			context.waitTicks(10);
		}
	}

	/**
	 * 本物の redtact の回路のページで、3D プレビューをフライ (redtact#750 からは飛行機のアイコン) にして押し、見回して W・S で動き、Esc で抜ける (撮るだけでなく、
	 * MOD とページの閉じ込めの状態を見る)。まず案内の字の上 (真ん中) を押す。redtact#752 より前は字の箱が押せて
	 * (pointer-events-auto) 始まらなかったので、そのときは字の横を押す。
	 */
	static void runReal(ClientGameTestContext context, String pageUrl) {
		// PR の動作 GIF 用: REDTACT_LIVE_POINTER_LOCK_FRAMES があれば、広い窓で見回し・前進・Esc のコマを撮る
		boolean frames = System.getenv("REDTACT_LIVE_POINTER_LOCK_FRAMES") != null;
		Runnable frame = () -> {
			if (frames) {
				context.takeScreenshot("redtact-live-pointer-lock-frame");
			}
		};

		if (frames) {
			context.getInput().resizeWindow(1280, 720);
		}

		context.runOnClient(mc -> browser(mc).load(pageUrl));
		context.waitFor(mc -> browser(mc) != null && browser(mc).pageUrl().startsWith(pageUrl) && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(100); // 画面の組み立て (hydration) と 3D の読み込みを待つ
		// 3D プレビューのフライの切り替えを押し、キャンバスの位置を URL の # に出す (replaceState なので画面は移らない)
		String report = """
				(async () => {
				  const say = (s) => history.replaceState(history.state, '', location.pathname + location.search + '#' + s);
				  const buttons = () => [...document.querySelectorAll('button')];
				  let opened = false;
				  for (let i = 0; i < 60; i++) {
				    // 字のボタン (redtact#750 より前の「🎮 フライ」) か、アイコンだけのボタン (#750 から。aria-label が「フライ」)
				    const fly = buttons().find(b => /フライ|Fly/.test(b.textContent || '') || /^(フライ|Fly)$/.test(b.getAttribute('aria-label') || ''));
				    let box = fly;
				    while (box && !box.querySelector('canvas')) box = box.parentElement;
				    const canvas = box && box.querySelector('canvas');
				    if (!fly && !opened) {
				      // 回路のページは最初はサムネ。プレビューの帯の「3D View」で 3D を出す
				      const view = buttons().find(b => (b.textContent || '').includes('3D View'));
				      if (view) { view.click(); opened = true; }
				    }
				    if (fly && canvas) {
				      fly.click();
				      await new Promise(r => setTimeout(r, 500));
				      const r = canvas.getBoundingClientRect();
				      say('pl=' + [r.left, r.top, r.width, r.height, devicePixelRatio].join(','));
				      return;
				    }
				    await new Promise(r => setTimeout(r, 500));
				  }
				  const names = buttons().map(b => (b.textContent || '').trim() || (b.getAttribute('aria-label') || '').trim()).filter(t => t);
				  say('pl=none;' + encodeURIComponent(names.slice(0, 30).join('|')) + ';canvas=' + document.querySelectorAll('canvas').length);
				})();
				""";
		context.runOnClient(mc -> browser(mc).executeScript(report));
		context.waitFor(mc -> hash(mc).startsWith("pl="), LibraryClientGameTest.TIMEOUT * 2);
		String pl = context.computeOnClient(PointerLockChecks::hash).substring(3);

		if (pl.startsWith("none")) {
			context.takeScreenshot("redtact-live-pointer-lock-real-0-no-fly");
			throw new AssertionError("本物のページに 3D プレビューのフライの切り替えが無い: " + pl);
		}

		String[] r = pl.split(",");
		double left = Double.parseDouble(r[0]);
		double top = Double.parseDouble(r[1]);
		double w = Double.parseDouble(r[2]);
		double h = Double.parseDouble(r[3]);
		double dpr = Double.parseDouble(r[4]);
		context.waitTicks(60); // テクスチャの読み込みを待つ
		context.takeScreenshot("redtact-live-pointer-lock-real-1-fly-guide");

		for (int i = 0; i < 4; i++) {
			frame.run(); // 案内が出ている (押す前)
			context.waitTicks(2);
		}

		// 案内の字の上 (真ん中) を押す。redtact#752 より前は始まらなかった (字の箱が押せた)
		clickCss(context, left + w / 2, top + h / 2, dpr);
		context.waitTicks(20);
		boolean centerLocked = context.computeOnClient(mc -> browser(mc).pointerLocked());
		LOGGER.info("[pointer-lock] 本物のページ: 案内の字の上を押して閉じ込めたか = {}", centerLocked);

		if (!centerLocked) {
			// 字の横 (左から 1/8) を押すと始まる
			clickCss(context, left + w / 8, top + h / 2, dpr);
			context.waitFor(mc -> browser(mc).pointerLocked(), LibraryClientGameTest.TIMEOUT);
		}

		context.waitTicks(20);
		context.takeScreenshot("redtact-live-pointer-lock-real-2-locked");

		for (int i = 0; i < 12; i++) {
			GameTestMouse.move(context, 16, 4);
			context.waitTicks(1);
			frame.run();
		}

		context.waitTicks(20);
		context.takeScreenshot("redtact-live-pointer-lock-real-3-looked");

		// W で前へ、S で後ろへ (後ろへ下がると回路が遠ざかって見える)
		for (int key : new int[] {InputConstants.KEY_W, InputConstants.KEY_S}) {
			context.getInput().holdKey(key);

			for (int i = 0; i < (key == InputConstants.KEY_W ? 4 : 16); i++) {
				context.waitTicks(2);
				frame.run();
			}

			context.getInput().releaseKey(key);
		}

		context.waitTicks(20);
		context.takeScreenshot("redtact-live-pointer-lock-real-4-moved");
		check(context.computeOnClient(mc -> browser(mc).pointerLocked()), "本物のページで閉じ込めが途中で解けた");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> !browser(mc).pointerLocked(), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(20);
		check(context.computeOnClient(mc -> browser(mc) != null), "本物のページで閉じ込めを解く Esc がメニューを閉じた");
		context.takeScreenshot("redtact-live-pointer-lock-real-5-released");

		for (int i = 0; i < 6; i++) {
			frame.run(); // Esc で抜けて案内に戻る (メニューは開いたまま)
			context.waitTicks(2);
		}

		LOGGER.info("[pointer-lock] 本物の redtact のフライ: 押して閉じ込め・見回し・W・S・Esc を確かめた");
	}

	/** ページの様子 (#) が ok になるまで待つ。来なければ what と # を出して落とす。 */
	private static void waitPage(ClientGameTestContext context, java.util.function.Predicate<Minecraft> ok, String what) {
		for (int i = 0; i < LibraryClientGameTest.TIMEOUT; i++) {
			if (context.computeOnClient(ok::test)) {
				return;
			}

			context.waitTicks(1);
		}

		throw new AssertionError(what + " (ページの # = " + context.computeOnClient(PointerLockChecks::hash) + ")");
	}

	/** ページの CSS の座標 (x, y) を本物のマウスで押す。 */
	private static void clickCss(ClientGameTestContext context, double x, double y, double dpr) {
		double[] g = context.computeOnClient(mc -> {
			RedtactBrowserScreen b = browser(mc);
			double scale = (double) b.browserWidth() / b.pageWidth(); // Chromium の画素 / GUI の画素
			return new double[] {b.pageLeft() + x * dpr / scale, b.pageTop() + y * dpr / scale};
		});
		LibraryClientGameTest.clickGui(context, g[0], g[1]);
	}

	/** ページの上の (横の割合, 縦の割合) を本物のマウスで押す。 */
	private static void clickPage(ClientGameTestContext context, double fx, double fy) {
		double[] g = context.computeOnClient(mc -> {
			RedtactBrowserScreen b = browser(mc);
			return new double[] {b.pageLeft() + b.pageWidth() * fx, b.pageTop() + (b.height - b.pageTop()) * fy};
		});
		LibraryClientGameTest.clickGui(context, g[0], g[1]);
	}

	private static boolean textInputActive(Minecraft mc) {
		return GameTestText.textInputActive(mc);
	}

	private static RedtactBrowserScreen browser(Minecraft mc) {
		return mc.gui.screen() instanceof RedtactBrowserScreen s ? s : null;
	}

	/** ページの URL の # のあと。 */
	private static String hash(Minecraft mc) {
		String url = browser(mc) == null ? "" : browser(mc).pageUrl();
		int i = url.indexOf('#');
		return i < 0 ? "" : URLDecoder.decode(url.substring(i + 1), StandardCharsets.UTF_8);
	}

	/** # を : で分けた i 番目 (tag・state・mx,my・changes・errors・keys)。 */
	private static String field(Minecraft mc, int i) {
		String[] f = hash(mc).split(":", -1);
		return i < f.length ? f[i] : "";
	}

	private static String field(ClientGameTestContext context, int i) {
		return context.computeOnClient(mc -> field(mc, i));
	}
}
