package com.redtact.live.gametest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.redtact.live.browser.cef.BrowserStats;
import com.redtact.live.browser.cef.RedtactBrowserScreen;
import com.redtact.live.config.BrowserFrameRate;
import com.redtact.live.config.BrowserResolution;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuTab;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * ゲーム内の redtact の重さを計る (#55)。MCEF Modern が入っているとき ({@code -Pmcef}) に {@code REDTACT_LIVE_PERF=1} で動く
 * ({@code REDTACT_LIVE_PERF_ONLY=1} を付けるとほかの gametest を飛ばす)。手元の HTTP サーバのページを redtact の代わりに出し、
 * 描き直しの回数・1 回の並べ替え (Chromium のスレッド) と転送 (ゲームの描画のスレッド) の時間・ゲームの FPS をログに出す。
 * <ul>
 * <li>全面が毎コマ動くページ (いちばん重い場合): 解像度 100 / 75 / 50% × コマ数 30 / 60
 * <li>止まったページ・ほかのタブにいる間・メニューを閉じたとき・ブラウザなし
 * <li>{@code REDTACT_LIVE_REAL_CIRCUIT} があれば、本物の redtact の回路のページを開いて何もしない間
 * </ul>
 * <pre>
 * REDTACT_LIVE_PERF=1 REDTACT_LIVE_PERF_ONLY=1 REDTACT_LIVE_LIBRARY_ONLY=1 JAVA_HOME=~/opt/jdk-25 ./gradlew runClientGameTest -Pmcef
 * </pre>
 */
@SuppressWarnings("UnstableApiUsage")
final class BrowserPerfChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final int TIMEOUT = 20 * 30;
	private static final int PREPARE_TIMEOUT = 20 * 60 * 20;
	/** 1 回の計測の長さ (tick)。 */
	private static final int MEASURE = 20 * 5;
	private static final String ANIM = """
			<!doctype html><html><head><meta charset="utf-8"><title>anim</title><style>
			html,body{margin:0;height:100%;overflow:hidden}canvas{display:block;width:100vw;height:100vh}
			</style></head><body><canvas id="c"></canvas><script>
			const c = document.getElementById('c'); const g = c.getContext('2d'); let t = 0;
			function fit() { c.width = innerWidth; c.height = innerHeight; }
			addEventListener('resize', fit); fit();
			function frame() {
			  const grad = g.createLinearGradient(0, 0, c.width, c.height);
			  grad.addColorStop(0, 'hsl(' + (t % 360) + ',70%,50%)'); grad.addColorStop(1, 'hsl(' + ((t + 180) % 360) + ',70%,50%)');
			  g.fillStyle = grad; g.fillRect(0, 0, c.width, c.height); t += 3; requestAnimationFrame(frame);
			}
			frame();
			</script></body></html>
			""";
	private static final String STATIC = """
			<!doctype html><html><head><meta charset="utf-8"><title>static</title></head>
			<body style="font:24px sans-serif;margin:40px">止まったページ (描き直しは起きないはず)</body></html>
			""";

	/** 1 回の計測の結果。 */
	private record Row(String name, double paintsPerSec, double paintMs, double uploadsPerSec, double uploadMs, int width, int height, int fps) {
		@Override
		public String toString() {
			return String.format("| %s | %.1f | %.2f | %.1f | %.2f | %d×%d | %d |", name, paintsPerSec, paintMs, uploadsPerSec, uploadMs, width, height, fps);
		}
	}

	private BrowserPerfChecks() {
	}

	static void run(ClientGameTestContext context) {
		HttpServer server;

		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		String home = "http://localhost:" + server.getAddress().getPort();
		server.createContext("/", ex -> {
			String path = ex.getRequestURI().getPath();
			String body = path.equals("/anim") ? ANIM : STATIC;
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			send(ex, body.getBytes(StandardCharsets.UTF_8));
		});
		server.start();
		String savedUrl = context.computeOnClient(mc -> Configs.Generic.REDTACT_URL.getStringValue());
		List<Row> rows = new ArrayList<>();

		// ゲームの FPS を比べるため、放置 (1 分入力なし) で 30 に絞るのと、垂直同期・FPS の上限を外す
		context.runOnClient(mc -> {
			mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
			mc.options.enableVsync().set(false);
			mc.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
		});

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			context.runOnClient(mc -> Configs.Generic.REDTACT_URL.setValueFromString(home));

			// ブラウザなし (ゲームだけ・メニューのほかのタブ)
			context.waitTicks(40);
			rows.add(measure(context, "ブラウザなし (ゲームだけ)"));
			context.runOnClient(mc -> Menu.open(MenuTab.LIBRARY));
			context.waitTicks(20);
			rows.add(measure(context, "ブラウザなし (メニューの保管庫タブ)"));

			// redtact タブ: 用意を待つ
			context.runOnClient(mc -> com.redtact.live.browser.BrowserSupport.open(home + "/anim")); // 用意の途中でもこのページを覚えて開く
			context.waitFor(mc -> mc.gui.screen() instanceof RedtactBrowserScreen, PREPARE_TIMEOUT);
			context.waitFor(mc -> browser(mc).painted() && browser(mc).pageUrl().startsWith(home + "/anim") && !browser(mc).loading(), TIMEOUT);

			for (BrowserResolution res : java.util.List.of(BrowserResolution.FULL, BrowserResolution.HIGH, BrowserResolution.HALF)) {
				for (BrowserFrameRate rate : BrowserFrameRate.values()) {
					context.runOnClient(mc -> {
						Configs.Generic.BROWSER_RESOLUTION.setOptionListValue(res);
						Configs.Generic.BROWSER_FRAME_RATE.setOptionListValue(rate);
						Menu.openRedtact(null); // 作り直す (同じブラウザの大きさとコマ数を変える)
					});
					context.waitTicks(60); // コマ数は tick ごとに掛け直すので、効くまで待つ
					rows.add(measure(context, "動くページ " + res.label() + "・" + rate.label() + " コマ"));

					if (res == BrowserResolution.FULL && rate == BrowserFrameRate.FPS_30) {
						// F3 を開くと、帯にいまのページの代わりに描き直しの計測とゲームの FPS が出る
						context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(true));
						context.waitTicks(30);
						context.takeScreenshot("redtact-live-perf-anim-100-30-f3");
						context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false));
					}
				}
			}

			// 既定のコマ数 (60。#69) と 100% に戻して、ほかのタブにいる間・止まったページ・閉じたとき
			context.runOnClient(mc -> {
				Configs.Generic.BROWSER_RESOLUTION.setOptionListValue(BrowserResolution.FULL);
				Configs.Generic.BROWSER_FRAME_RATE.resetToDefault();
				Menu.openRedtact(null);
			});
			context.waitTicks(40);
			context.runOnClient(mc -> Menu.open(MenuTab.LIBRARY));
			context.waitTicks(40);
			rows.add(measure(context, "動くページ・ほかのタブ (保管庫) にいる間"));
			context.runOnClient(mc -> Menu.openRedtact(home + "/static"));
			context.waitFor(mc -> mc.gui.screen() instanceof RedtactBrowserScreen b && b.pageUrl().startsWith(home + "/static") && !b.loading(), TIMEOUT);
			context.waitTicks(40);
			rows.add(measure(context, "止まったページ (100%・30)"));
			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(40);
			rows.add(measure(context, "メニューを閉じたあと"));

			String real = System.getenv("REDTACT_LIVE_REAL_CIRCUIT");

			if (real != null && !real.isBlank()) {
				java.net.URI page = java.net.URI.create(real.strip());
				context.runOnClient(mc -> {
					Configs.Generic.REDTACT_URL.setValueFromString(page.getScheme() + "://" + page.getHost());
					Menu.openRedtact(real.strip());
				});
				context.waitFor(mc -> mc.gui.screen() instanceof RedtactBrowserScreen b && b.pageUrl().startsWith(real.strip()) && !b.loading(), TIMEOUT);
				context.waitTicks(200); // 画面の組み立てと立体のプレビューの読み込みを待つ
				rows.add(measure(context, "本物の redtact の回路のページ (何もしない間)"));
				context.takeScreenshot("redtact-live-perf-real-page");
				context.runOnClient(mc -> mc.gui.setScreen(null));
				context.waitTicks(20);
			}
		} finally {
			server.stop(0);
			context.runOnClient(mc -> {
				Configs.Generic.REDTACT_URL.setValueFromString(savedUrl);
				Configs.Generic.BROWSER_RESOLUTION.resetToDefault();
				Configs.Generic.BROWSER_FRAME_RATE.resetToDefault();
			});
		}

		StringBuilder table = new StringBuilder("\n| 場面 | 描き直し/秒 | 並べ替え ms/回 | 転送/秒 | 転送 ms/回 | 大きさ | ゲーム FPS |\n|---|---|---|---|---|---|---|\n");
		rows.forEach(r -> table.append(r).append('\n'));
		LOGGER.info("[perf] ゲーム内の redtact の重さ{}", table);
	}

	/** MEASURE の間の描き直しと転送の回数・時間と、最後のゲームの FPS。 */
	private static Row measure(ClientGameTestContext context, String name) {
		long[] before = context.computeOnClient(mc -> new long[] {BrowserStats.paints(), BrowserStats.paintNanos(), BrowserStats.uploads(), BrowserStats.uploadNanos(),
				System.nanoTime()});
		context.waitTicks(MEASURE);
		long[] after = context.computeOnClient(mc -> new long[] {BrowserStats.paints(), BrowserStats.paintNanos(), BrowserStats.uploads(), BrowserStats.uploadNanos(),
				System.nanoTime()});
		int fps = context.computeOnClient(Minecraft::getFps);
		double sec = (after[4] - before[4]) / 1e9;
		long paints = after[0] - before[0];
		long uploads = after[2] - before[2];
		BrowserStats.Snapshot s = context.computeOnClient(mc -> BrowserStats.snapshot());
		Row r = new Row(name, paints / sec, paints == 0 ? 0 : (after[1] - before[1]) / 1e6 / paints, uploads / sec,
				uploads == 0 ? 0 : (after[3] - before[3]) / 1e6 / uploads, s.width(), s.height(), fps);
		LOGGER.info("[perf] {}", r);
		return r;
	}

	private static RedtactBrowserScreen browser(Minecraft mc) {
		return mc.gui.screen() instanceof RedtactBrowserScreen s ? s : null;
	}

	private static void send(HttpExchange ex, byte[] body) throws IOException {
		ex.sendResponseHeaders(200, body.length);

		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}
}
