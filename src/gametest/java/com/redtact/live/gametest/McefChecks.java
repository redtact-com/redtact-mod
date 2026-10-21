package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.browser.cef.McefBridge;
import com.redtact.live.browser.cef.RedtactBrowserScreen;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.MenuSection;
import com.redtact.live.gui.RedtactScreen;
import com.redtact.live.library.LibraryActions;
import com.redtact.live.library.LibraryEntry;
import com.sun.net.httpserver.HttpExchange;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpServer;
//? if >=26.3 {
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLKeycode;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
//? if >=26.1 {
import net.minecraft.client.input.PreeditEvent;
//?}
import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 本物の Chromium (MCEF Modern) での確かめ (#46)。MCEF Modern が入っているとき ({@code -Pmcef}) だけ読み込まれる。
 * redtact の代わりに手元の HTTP サーバのページを開き (redtact の URL の設定を手元にする)、ページの上の 4 つの面を本物のマウスで押す:
 * <ul>
 * <li>左上 = ダウンロード (R2 と同じ形のパス・Content-Disposition の filename*) → 保管庫に入り、配置できる
 * <li>右上 = 外のサイト → ゲーム内では開かず外のブラウザへ (外のブラウザは差し替えて記録だけ)
 * <li>左下 = target=_blank → 窓は作らずこの画面で開く
 * <li>右下 = 入力欄 → 字が届く (入力で URL の # を変えるページ)
 * </ul>
 * 字の入力は、SDL の文字入力が有効になっていること (擬似入力の {@code typeChars} は SDL を通らないので、これを見ないと
 * 実機で字が入らないのを見逃す。#61) と、日本語の変換 (変換中のキーはページへ送らず、確定した字が届く) も見る。
 * ログインの画面 (accounts.google.com) へは移らないことも見る。
 * redtact タブを開くと Chromium の用意を始め、済んだらそのタブにブラウザが出ること、保管庫タブへ移って戻っても同じブラウザの
 * 同じページのままで、メニューを閉じるとブラウザを閉じることも見る (#51)。
 * 保管庫タブの「redtact に投稿」から、左半分の保管庫のファイルをページへドロップする・「ファイルを追加」で入れることも見る
 * ({@link DropChecks}。#60)。帯のアイコンのボタン・ツールチップ・押した動き・保存中の数も見る ({@link BarChecks}。#69)。
 * 押すとマウスを閉じ込めるページ (3D プレビューのフライと同じ作り) で、Pointer Lock の肩代わりも見る ({@link PointerLockChecks}。#88)。
 */
final class McefChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	/** 初回は Chromium を取ってくるので長く待つ。 */
	private static final int PREPARE_TIMEOUT = 20 * 60 * 20;
	private static final String STORED = "0f0f0f0f.litematic";
	private static final String FILE_NAME = "テスト回路.litematic";
	/** 半分で止めるダウンロード (#69)。最初のダウンロードと同じ回路と名前 (保管庫では同じ行)。 */
	private static final String SLOW_PATH = "/slow/" + LibraryClientGameTest.CIRCUIT_ID + "/" + STORED;
	private static final String PAGE = """
			<!doctype html><html><head><meta charset="utf-8"><title>redtact (gametest)</title><style>
			body{margin:0;font:24px sans-serif} a,input{position:fixed;display:block;box-sizing:border-box}
			</style></head><body>
			<a id="dl" href="/bucket/%s/%s" style="left:0;top:0;width:50vw;height:30vh;background:#c33">download</a>
			<a id="ext" href="https://example.com/" style="left:50vw;top:0;width:50vw;height:30vh;background:#33c">external</a>
			<a id="tab" href="/page2" target="_blank" style="left:0;top:40vh;width:50vw;height:30vh;background:#3c3">new tab</a>
			<form onsubmit="sent=(window.sent||0)+1;location.hash='sent'+sent+':'+q.value;return false">
			<input id="q" oninput="location.hash=this.value" style="left:50vw;top:40vh;width:50vw;height:30vh;font-size:40px">
			</form>
			</body></html>
			""";

	private McefChecks() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext sp, boolean litematica) {
		byte[] bytes = LibraryClientGameTest.litematic("from chromium");
		List<String> external = new CopyOnWriteArrayList<>();
		List<String> served = new CopyOnWriteArrayList<>();
		HttpServer server;

		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		String home = "http://localhost:" + server.getAddress().getPort();
		server.createContext("/", ex -> {
			String path = ex.getRequestURI().getPath();
			LOGGER.info("[mcef] 手元のサーバ: {} {}", ex.getRequestMethod(), path);
			served.add(path);

			if (path.equals("/bucket/" + LibraryClientGameTest.CIRCUIT_ID + "/" + STORED)) {
				ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
				ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"test.litematic\"; filename*=UTF-8''"
						+ java.net.URLEncoder.encode(FILE_NAME, StandardCharsets.UTF_8).replace("+", "%20"));
				send(ex, 200, bytes);
			} else if (path.equals(SLOW_PATH)) {
				// 保存中の数を撮るため、半分送ったところで止める (#69。保管庫の行は同じ目印なので増えない)
				BarChecks.slowDownload(ex, bytes, FILE_NAME);
			} else if (path.equals(PointerLockChecks.PATH)) {
				// 押すとマウスを閉じ込めるページ (redtact の 3D プレビューのフライの代わり。#88)
				ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
				send(ex, 200, PointerLockChecks.PAGE.getBytes(StandardCharsets.UTF_8));
			} else if (path.equals("/circuits/new")) {
				// redtact の投稿の画面の代わり (#60)
				ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
				send(ex, 200, DropChecks.NEW_PAGE.getBytes(StandardCharsets.UTF_8));
			} else if (path.equals("/") || path.equals("/page2")) {
				ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
				send(ex, 200, PAGE.formatted(LibraryClientGameTest.CIRCUIT_ID, STORED).getBytes(StandardCharsets.UTF_8));
			} else {
				send(ex, 404, new byte[0]);
			}
		});
		server.start();
		String savedUrl = context.computeOnClient(mc -> Configs.Generic.REDTACT_URL.getStringValue());

		try {
			context.runOnClient(mc -> {
				Configs.Generic.REDTACT_URL.setValueFromString(home);
				BrowserSupport.setExternalOpener(external::add);
			});

			// ---- メインタブの redtact (本物のクリック) → 入口が Chromium の用意を始め、済んだらそのタブにブラウザが出る (#51)
			LibraryClientGameTest.clickSection(context, MenuSection.REDTACT);
			context.waitFor(mc -> mc.gui.screen() instanceof RedtactScreen || mc.gui.screen() instanceof RedtactBrowserScreen, LibraryClientGameTest.TIMEOUT);
			context.takeScreenshot("redtact-live-library-4a-redtact-preparing");
			int[] waited = {0};
			context.waitFor(mc -> {
				if (++waited[0] % 200 == 0) {
					LOGGER.info("[mcef] {}", BrowserSupport.status().text());
				}

				return mc.gui.screen() instanceof RedtactBrowserScreen;
			}, PREPARE_TIMEOUT);
			context.waitFor(mc -> browser(mc) != null && browser(mc).painted() && browser(mc).pageUrl().startsWith(home), LibraryClientGameTest.TIMEOUT);
			context.waitFor(mc -> served.contains("/") && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
			context.waitTicks(20);
			context.takeScreenshot("redtact-live-library-4-browser");

			// ---- 外のサイト: 外のブラウザへ回し、この画面は移らない
			clickPage(context, 0.75, 0.15);
			context.waitFor(mc -> external.contains("https://example.com/"), LibraryClientGameTest.TIMEOUT);
			context.waitTicks(10);
			check(context.computeOnClient(mc -> browser(mc).pageUrl()).equals(home + "/"), "外のサイトへ移ってしまった");

			// ---- target=_blank: 窓は作らず、この画面で開く
			clickPage(context, 0.25, 0.55);
			context.waitFor(mc -> browser(mc).pageUrl().equals(home + "/page2"), LibraryClientGameTest.TIMEOUT);

			// ---- 字の入力 (右下の欄を押してから打つ)
			context.waitTicks(10);
			clickPage(context, 0.75, 0.55);
			context.waitTicks(5);
			context.getInput().typeChars("abc");
			context.waitFor(mc -> browser(mc).pageUrl().endsWith("#abc"), LibraryClientGameTest.TIMEOUT);
			checkTextInput(context);

			// ---- ログインの画面には移らない (外のブラウザにも出さない)
			int before = external.size();
			context.runOnClient(mc -> browser(mc).load("https://accounts.google.com/o/oauth2/auth?client_id=x"));
			context.waitTicks(40);
			check(context.computeOnClient(mc -> browser(mc).pageUrl()).startsWith(home + "/page2"), "ログインの画面へ移った");
			check(external.size() == before, "ログインの画面を外のブラウザで開いた: " + external);

			// ---- ゲーム内のログイン (#50): ページの受け口 → 外のブラウザ (/login/game) → 127.0.0.1 の受け取り口 → ページ
			checkGameLogin(context, home, external);

			// ---- ダウンロード → 保管庫
			clickPage(context, 0.25, 0.15);
			context.waitFor(mc -> {
				LibraryEntry e = RedtactLive.get().downloads().lastSaved();
				return e != null && e.storedName.equals(FILE_NAME);
			}, LibraryClientGameTest.TIMEOUT);
			context.takeScreenshot("redtact-live-library-5-downloaded");
			LibraryEntry e = context.computeOnClient(mc -> RedtactLive.get().downloads().lastSaved());
			check((LibraryClientGameTest.CIRCUIT_ID + "/" + STORED).equals(e.sourceKey), "目印が違う: " + e.sourceKey);
			check(LibraryClientGameTest.CIRCUIT_ID.equals(e.circuitId), "回路 ID が違う: " + e.circuitId);
			check(e.label.equals("テスト回路"), "表示名が違う: " + e.label);
			check(e.bytes == bytes.length, "大きさが違う: " + e.bytes);
			Path file = context.computeOnClient(mc -> LibraryClientGameTest.library().file(e));
			check(java.util.Arrays.equals(read(file), bytes), "保存したファイルの中身が違う");
			check(context.computeOnClient(mc -> RedtactLive.get().downloads().inFlight()) == 0, "取り途中が残っている");

			try (var temp = Files.list(context.computeOnClient(mc -> RedtactLive.get().dataDir().resolve("downloads")))) {
				check(temp.findAny().isEmpty(), "一時ファイルが残っている");
			} catch (IOException ex) {
				throw new AssertionError(ex);
			}

			// ---- 保管庫タブへ移り (本物のクリック)、取ってきたものを配置する。ブラウザは閉じずに置いてある (#51)
			String pageBefore = context.computeOnClient(mc -> browser(mc).pageUrl());
			int serialBefore = context.computeOnClient(mc -> McefBridge.tabBrowserSerial());
			LibraryClientGameTest.clickSection(context, MenuSection.LIBRARY);
			context.waitForScreen(LibraryScreen.class);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> LibraryClientGameTest.screen(mc).libraryCards().size()) == 1, "保管庫タブに取ってきたものが出ていない");
			check(context.computeOnClient(mc -> McefBridge.hasTabBrowser()), "ほかのタブへ移ったらブラウザを閉じた");

			if (litematica) {
				sp.getServer().runCommand("tp @a 9.5 -60 1.5 0 0");
				context.waitTicks(5);
				LibraryClientGameTest.clickOnTab(context, s -> s.libraryCards().getFirst().placeButton());
				String key = LibraryActions.KEY_PREFIX + FILE_NAME;
				context.waitFor(mc -> LitematicaChecks.libraryPlacement(key) != null, LibraryClientGameTest.TIMEOUT);
				check(context.computeOnClient(mc -> LitematicaChecks.libraryPlacement(key).origin()).equals(new BlockPos(9, -60, 1)), "配置の原点が足元でない");
			}

			context.takeScreenshot("redtact-live-library-6-after-browser");

			// ---- redtact タブに戻ると、同じブラウザの同じページ
			LibraryClientGameTest.clickSection(context, MenuSection.REDTACT);
			context.waitForScreen(RedtactBrowserScreen.class);
			context.waitTicks(10);
			check(context.computeOnClient(mc -> McefBridge.tabBrowserSerial()) == serialBefore, "redtact タブに戻ったらブラウザを作り直した");
			String pageAfter = context.computeOnClient(mc -> browser(mc).pageUrl());
			check(pageAfter.equals(pageBefore), "redtact タブに戻ったらページが変わった: " + pageBefore + " → " + pageAfter);
			context.takeScreenshot("redtact-live-library-6b-back-to-redtact");

			// ---- ヘッダの右端のトグルで帯を隠すとページが広がる。同じブラウザの同じページのままで、クリックの位置もずれない (#51)
			int pageTopShown = context.computeOnClient(mc -> browser(mc).pageTop());
			LibraryClientGameTest.clickSubBarToggle(context);
			context.waitFor(mc -> browser(mc) != null && browser(mc).pageTop() == MenuScreen.BAR_H + 1, LibraryClientGameTest.TIMEOUT);
			check(context.computeOnClient(mc -> McefBridge.tabBrowserSerial()) == serialBefore, "帯を隠したらブラウザを作り直した");
			check(context.computeOnClient(mc -> browser(mc).pageUrl()).equals(pageBefore), "帯を隠したらページが変わった");
			context.waitTicks(10);
			clickPage(context, 0.75, 0.55);
			context.waitTicks(5);
			context.getInput().typeChars("xyz");
			context.waitFor(mc -> browser(mc).pageUrl().contains("xyz"), LibraryClientGameTest.TIMEOUT);
			context.takeScreenshot("redtact-live-library-6c-redtact-bar-hidden");
			LibraryClientGameTest.clickSubBarToggle(context);
			context.waitFor(mc -> browser(mc) != null && browser(mc).pageTop() == pageTopShown, LibraryClientGameTest.TIMEOUT);

			// ---- 帯のアイコンのボタン・ツールチップ・押した動き・いまのページの欄・保存中の数 (#69)
			BarChecks.run(context, home, external, served, SLOW_PATH);

			// ---- 保管庫から投稿の画面へ: 左半分の保管庫からドロップ・「ファイルを追加」(#60)
			DropChecks.run(context, home, external);

			// ---- ページの JS のダイアログ (confirm・alert・prompt・離脱の確認) を窓で出して答える (#65)
			DialogChecks.run(context, home + "/page2");

			// ---- Pointer Lock の肩代わり: 押すとマウスを閉じ込め、動きとキーがページへ届き、Esc で解ける (#88)
			PointerLockChecks.run(context, home + PointerLockChecks.PATH, home + "/page2");

			// ---- Esc でメニューを閉じると、ブラウザも閉じる。ページが離脱を止めていれば確認の窓 (#67)
			CloseChecks.run(context, home + "/page2");
			LOGGER.info("[mcef] 本物の Chromium で開く・外のサイト・別タブ・入力・ログイン・ダウンロード・配置を確かめた");

			String real = System.getenv("REDTACT_LIVE_REAL_CIRCUIT");

			if (real != null && !real.isBlank()) {
				checkReal(context, real.strip());
			}
		} finally {
			server.stop(0);
			context.runOnClient(mc -> {
				Configs.Generic.REDTACT_URL.setValueFromString(savedUrl);
				BrowserSupport.setExternalOpener(null);
			});
		}
	}

	/**
	 * ゲーム内のログイン (#50)。ページで受け口 window.__redtactLive.requestCredential を呼び、外のブラウザの代わりに
	 * テストが受け取り口へ結果を渡す。資格情報はページの Promise に届き、取り消しは code game_cancelled で届く。
	 * 結果はページが URL のフラグメントに書く (それを読んで確かめる)。
	 */
	private static void checkGameLogin(ClientGameTestContext context, String home, List<String> external) {
		String before = context.computeOnClient(mc -> browser(mc).pageUrl()).replaceAll("#.*$", "");
		context.waitFor(mc -> browser(mc).hasGameBridge(), LibraryClientGameTest.TIMEOUT);
		String ask = """
				window.__redtactLive.requestCredential("%s").then(
				  function (c) { location.hash = "cred=" + c.method + "." + c.idToken + "." + c.rawNonce; },
				  function (e) { location.hash = "err=" + e.code; });
				""";

		// 資格情報が届く
		int opened = external.size();
		context.runOnClient(mc -> browser(mc).executeScript(ask.formatted("google")));
		context.waitFor(mc -> external.size() > opened, LibraryClientGameTest.TIMEOUT);
		java.net.URI loginGame = java.net.URI.create(external.getLast());
		check(external.getLast().startsWith(home + "/login/game?"), "外のブラウザで /login/game を開いていない: " + external.getLast());
		java.util.Map<String, String> q = com.redtact.live.login.LoopbackServer.parseForm(loginGame.getRawQuery());
		check("google".equals(q.get("method")), "頼んだ方法が違う: " + q);
		String port = q.get("port");
		String state = q.get("state");
		check(http("GET", port, "/callback", null) == 200, "受け取り口のページが出ない");
		check(http("POST", port, "/complete", "state=wrong&result=credential") == 400, "state が違うのに受け取った");
		check(http("POST", port, "/complete", "state=" + state + "&result=credential&method=google&id_token=fake.id.token&raw_nonce=n1") == 200,
				"受け取り口に渡せない");
		context.waitFor(mc -> browser(mc).pageUrl().endsWith("#cred=google.fake.id.token.n1"), LibraryClientGameTest.TIMEOUT);
		check(!context.computeOnClient(mc -> RedtactLive.get().gameLogin().pending()), "受け取ったのに待ち続けている");

		// 取り消しも届く
		int opened2 = external.size();
		context.runOnClient(mc -> browser(mc).executeScript(ask.formatted("apple")));
		context.waitFor(mc -> external.size() > opened2, LibraryClientGameTest.TIMEOUT);
		java.util.Map<String, String> q2 = com.redtact.live.login.LoopbackServer.parseForm(java.net.URI.create(external.getLast()).getRawQuery());
		check(http("POST", q2.get("port"), "/complete", "state=" + q2.get("state") + "&result=cancelled&method=apple") == 200, "取り消しを渡せない");
		context.waitFor(mc -> browser(mc).pageUrl().endsWith("#err=game_cancelled"), LibraryClientGameTest.TIMEOUT);
		context.takeScreenshot("redtact-live-library-5b-game-login");
		LOGGER.info("[mcef] ゲーム内のログイン: 受け口 → /login/game → 受け取り口 → ページ (資格情報・取り消し) を確かめた");
		context.runOnClient(mc -> browser(mc).load(before));
		context.waitFor(mc -> browser(mc).pageUrl().equals(before) && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(10);
	}

	/** 受け取り口へ HTTP を送って状態コードを返す (外のブラウザの代わり)。 */
	private static int http(String method, String port, String path, String body) {
		try {
			java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path));
			b = body == null ? b.GET() : b.header("Content-Type", "application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
			return java.net.http.HttpClient.newHttpClient().send(b.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (Exception e) {
			throw new AssertionError(method + " " + path, e);
		}
	}

	/**
	 * 本物の redtact (dev を勧める。本番だとダウンロード数が増える) の回路のページを開き、ダウンロードのタブの最初のファイルを
	 * 押して、期限付き URL (R2) から保管庫に入り、表示名と fileId が API から入ることを見る。
	 * 例: {@code REDTACT_LIVE_REAL_CIRCUIT=https://dev.redtact.com/circuits/PiiSOMn0SRED}
	 */
	private static void checkReal(ClientGameTestContext context, String pageUrl) {
		java.net.URI page = java.net.URI.create(pageUrl);
		String circuitId = com.redtact.live.library.RedtactLinks.circuitIdFromPage(pageUrl);
		check(circuitId != null, "回路のページの URL ではない: " + pageUrl);
		context.runOnClient(mc -> {
			Configs.Generic.REDTACT_URL.setValueFromString(page.getScheme() + "://" + page.getHost());
			BrowserSupport.open(pageUrl);
		});
		context.waitFor(mc -> browser(mc) != null && browser(mc).pageUrl().startsWith(pageUrl) && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(100); // 画面の組み立て (hydration) を待つ
		context.takeScreenshot("redtact-live-library-7-real-page");
		// 画面の広さを変えて帯といまのページの欄を撮る (#69)
		BarChecks.sizeShots(context, "redtact-live-bar-real");
		// 帯を隠すと、本物の redtact のページもヘッダの下から全部使える (#51)
		LibraryClientGameTest.clickSubBarToggle(context);
		context.waitFor(mc -> browser(mc) != null && browser(mc).pageTop() == MenuScreen.BAR_H + 1, LibraryClientGameTest.TIMEOUT);
		context.waitTicks(20);
		context.takeScreenshot("redtact-live-library-7b-real-page-bar-hidden");
		LibraryClientGameTest.clickSubBarToggle(context);
		context.waitFor(mc -> browser(mc) != null && browser(mc).pageTop() > MenuScreen.BAR_H + 1, LibraryClientGameTest.TIMEOUT);
		LibraryEntry before = context.computeOnClient(mc -> RedtactLive.get().downloads().lastSaved());
		// ダウンロードのタブを開き、最初のファイルのダウンロードを押す (日本語・英語のどちらの表示でも)
		context.runOnClient(mc -> browser(mc).executeScript("""
				(async () => {
				  const buttons = () => [...document.querySelectorAll('button')];
				  const tab = buttons().find(b => /^(ダウンロード|Downloads?) [(]/.test(b.textContent.trim()));
				  if (tab) tab.click();
				  await new Promise(r => setTimeout(r, 1000));
				  const dl = buttons().find(b => /をダウンロード$|^Download /.test(b.getAttribute('aria-label') || ''));
				  if (dl) dl.click();
				})();
				"""));
		context.waitFor(mc -> {
			LibraryEntry e = RedtactLive.get().downloads().lastSaved();
			return e != null && e != before && circuitId.equals(e.circuitId);
		}, LibraryClientGameTest.TIMEOUT * 2);
		LibraryEntry e = context.computeOnClient(mc -> RedtactLive.get().downloads().lastSaved());
		LOGGER.info("[mcef] 本物の redtact から保存: storedName={} label={} fileName={} fileId={} sourceKey={} bytes={}", e.storedName, e.label,
				e.fileName, e.fileId, e.sourceKey, e.bytes);
		check(e.fileId != null && !e.fileId.isBlank(), "API から fileId が入っていない");
		check(e.sourceKey != null && e.sourceKey.startsWith(circuitId + "/"), "期限付き URL から目印が取れていない: " + e.sourceKey);
		check(!e.label.equals(LibraryDownloads_stem(e.fileName)), "表示名が API の題になっていない: " + e.label);
		context.takeScreenshot("redtact-live-library-8-real-saved");
	}

	private static String LibraryDownloads_stem(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static RedtactBrowserScreen browser(Minecraft mc) {
		return mc.gui.screen() instanceof RedtactBrowserScreen s ? s : null;
	}

	/**
	 * 字の入力 (#61)。入力欄に "abc" が入っている前提。SDL の文字入力が有効なこと (無効だと実機では字が届かない) と、
	 * 日本語の変換: 変換中のキーはページへ送らず (Esc でメニューも閉じない)、確定した字が届き、確定のあとのキーはまたページへ届くこと。
	 * Ctrl+A (修飾キー付きの押下) と Enter (フォームの送信が 1 回) も見る。Windows だけのスキャンコードは単体テストで見る。
	 * 変換の知らせ (SDL の TEXT_EDITING) と確定 (TEXT_INPUT) は、バニラの KeyboardHandler の入口から入れる。
	 */
	private static void checkTextInput(ClientGameTestContext context) {
		check(context.computeOnClient(mc -> textInputActive(mc)), "SDL の文字入力が有効になっていない (実機で字が入らない)");

		//? if >=26.3 {
		context.runOnClient(mc -> mc.keyboardHandler.textEditing(mc.getWindow().handle(), PreeditEvent.fromSdlTextEditing("にほん", -1, 0)));
		check(context.computeOnClient(mc -> browser(mc) != null && browser(mc).composing()), "変換中の字を受け取っていない");
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-library-4b-ime-preedit");
		context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(10);
		check(context.computeOnClient(mc -> browser(mc) != null), "変換中の Esc でメニューを閉じた");
		check(context.computeOnClient(mc -> browser(mc).pageUrl()).endsWith("#abc"), "変換中のキーがページへ届いた");

		context.runOnClient(mc -> mc.keyboardHandler.textInput(mc.getWindow().handle(), "日本"));
		context.waitFor(mc -> pageHash(mc).equals("abc日本"), LibraryClientGameTest.TIMEOUT);
		check(!context.computeOnClient(mc -> browser(mc).composing()), "確定しても変換中のまま");

		context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		context.waitFor(mc -> pageHash(mc).equals("abc日"), LibraryClientGameTest.TIMEOUT);

		// Ctrl+A で全部を選び、打つと置き換わる (Fabric の擬似入力は修飾キーを付けないので、バニラの入口へ直に入れる)
		context.runOnClient(mc -> {
			int key = InputConstants.KEY_A;
			KeyEvent ctrlA = new KeyEvent(key, SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false), SDLKeycode.SDL_KMOD_LCTRL);
			GameTestText.keyPress(mc, 1, ctrlA);
			GameTestText.keyPress(mc, 0, ctrlA);
		});
		//?} else {
		/*// 26.2 まで (GLFW) は変換中の字を作れないので、字の入力と Ctrl+A だけを見る (#112)
		context.getInput().typeChars("日本");
		context.waitFor(mc -> pageHash(mc).equals("abc日本"), LibraryClientGameTest.TIMEOUT);
		context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		context.waitFor(mc -> pageHash(mc).equals("abc日"), LibraryClientGameTest.TIMEOUT);
		context.runOnClient(mc -> {
			KeyEvent ctrlA = new KeyEvent(InputConstants.KEY_A, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);
			GameTestText.keyPress(mc, 1, ctrlA);
			GameTestText.keyPress(mc, 0, ctrlA);
		});
		*///?}
		context.getInput().typeChars("q");
		context.waitFor(mc -> pageHash(mc).equals("q"), LibraryClientGameTest.TIMEOUT);

		// Enter でフォームを送る (押下のあとの字 \r で。1 回だけ)
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitFor(mc -> pageHash(mc).startsWith("sent"), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(10);
		check(context.computeOnClient(McefChecks::pageHash).equals("sent1:q"), "Enter でフォームを送れていない (または 2 回送った): " + context.computeOnClient(McefChecks::pageHash));
	}

	private static boolean textInputActive(Minecraft mc) {
		return GameTestText.textInputActive(mc);
	}

	/** ページの URL の # のあと (入力欄の中身)。 */
	private static String pageHash(Minecraft mc) {
		String url = browser(mc) == null ? "" : browser(mc).pageUrl();
		int i = url.indexOf('#');
		return i < 0 ? "" : URLDecoder.decode(url.substring(i + 1), StandardCharsets.UTF_8);
	}

	/** ページの上の (横の割合, 縦の割合) を本物のマウスで押す。ページは帯の下 (左に保管庫を出していればその右。#60)。 */
	private static void clickPage(ClientGameTestContext context, double fx, double fy) {
		double[] g = context.computeOnClient(mc -> {
			RedtactBrowserScreen b = browser(mc);
			return new double[] {b.pageLeft() + b.pageWidth() * fx, b.pageTop() + (b.height - b.pageTop()) * fy};
		});
		LibraryClientGameTest.clickGui(context, g[0], g[1]);
	}

	private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
		ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);

		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}

	private static byte[] read(Path f) {
		try {
			return Files.readAllBytes(f);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
