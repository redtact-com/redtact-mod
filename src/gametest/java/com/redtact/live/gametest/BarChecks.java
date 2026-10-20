package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.redtact.live.RedtactLive;
import com.redtact.live.browser.cef.RedtactBrowserScreen;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.library.RedtactLinks;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * redtact タブの帯 (#69)。ボタンはアイコンの正方形 8 つで、名前はツールチップの先頭に出る。本物のマウスで押して動きを見る
 * (戻る・進む・更新・トップ・保管庫・字の大きさ・外のブラウザ)。いまのページの欄の幅を確かめ、保存中の数のバッジ
 * (帯とヘッダ) を、手元のサーバのダウンロードを半分で止めて撮る。{@link #sizeShots} は画面の広さを変えて撮る。
 */
final class BarChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	/** 左から並ぶ名前とアイコン。 */
	static final List<String> LABELS = List.of("戻る", "進む", "更新", "トップ", "保管庫", "字を小さく", "字を大きく", "外のブラウザで開く");
	static final List<Icon> ICONS = List.of(Icon.BACK, Icon.FORWARD, Icon.RELOAD, Icon.HOME, Icon.FOLDER, Icon.TEXT_SMALLER, Icon.TEXT_BIGGER, Icon.EXTERNAL);
	/** いまのページの欄の外側の幅の合計 (余白 8 × 2・ボタン 16 × 8・ボタンの間 2 × 6・欄の両脇 4 × 2)。 */
	static final int AROUND_URL = MenuScreen.MARGIN * 2 + RButton.H * 8 + 2 * 6 + 4 * 2;
	/** ゆっくりのダウンロードを半分で止めておく門 (テストが放すまで待つ)。 */
	private static volatile CountDownLatch gate = new CountDownLatch(0);

	private BarChecks() {
	}

	/**
	 * 帯を確かめる。ページは手元のサーバ (home)。終わったら始めのページに戻す。slowPath は {@link #slowDownload} で返す
	 * ダウンロードのパス (保管庫に入っているファイルと同じ目印にして、行を増やさない)。
	 */
	static void run(ClientGameTestContext context, String home, List<String> external, List<String> served, String slowPath) {
		String start = context.computeOnClient(mc -> browser(mc).pageUrl());

		// ---- 形: 帯の段にアイコンの正方形が 8 つ並び、名前はツールチップの先頭。間がいまのページの欄
		context.runOnClient(mc -> {
			RedtactBrowserScreen s = browser(mc);
			List<RButton> bar = sorted(s);
			List<String> labels = bar.stream().map(b -> b.getMessage().getString()).toList();
			check(labels.equals(LABELS), "帯のボタンの名前と並び: " + labels);
			check(bar.stream().map(RButton::icon).toList().equals(ICONS), "帯のアイコン: " + bar.stream().map(RButton::icon).toList());

			for (int i = 0; i < bar.size(); i++) {
				RButton b = bar.get(i);
				String name = labels.get(i);
				check(b.getWidth() == RButton.H && b.getHeight() == RButton.H, name + " が正方形でない: " + b.getWidth() + "×" + b.getHeight());
				check(b.getY() >= MenuScreen.SUB_Y && b.getY() + b.getHeight() <= MenuScreen.SUB_Y + MenuScreen.SUB_H, name + " が帯の段からはみ出す");
				check(b.tooltipText() != null && b.tooltipText().startsWith(name), name + " のツールチップの先頭が名前でない: " + b.tooltipText());
				check(i == 0 || bar.get(i - 1).getX() + RButton.H <= b.getX(), name + " が左のボタンに重なる");
			}

			check(s.urlWidth() == s.width - AROUND_URL, "いまのページの欄の幅: " + s.urlWidth() + " (画面の幅 " + s.width + ")");
			LOGGER.info("[bar] 画面の幅 {} でいまのページの欄は {}", s.width, s.urlWidth());
		});

		// ---- 戻る・進む: 2 つのページを開いてから戻り、進む
		load(context, home + "/");
		load(context, home + "/page2");
		click(context, "戻る");
		context.waitFor(mc -> browser(mc).pageUrl().equals(home + "/") && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		click(context, "進む");
		context.waitFor(mc -> browser(mc).pageUrl().equals(home + "/page2") && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);

		// ---- 更新: 同じページをもう一度取る
		long loads = served.stream().filter("/page2"::equals).count();
		click(context, "更新");
		context.waitFor(mc -> served.stream().filter("/page2"::equals).count() > loads && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> browser(mc).pageUrl()).equals(home + "/page2"), "更新でページが変わった");

		// ---- トップ: 設定の redtact (ここでは手元のサーバ) へ。新しく開いたので進む先は無く、押せない理由が名前の下に出る
		click(context, "トップ");
		context.waitFor(mc -> browser(mc).pageUrl().equals(home + "/") && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		context.waitFor(mc -> button(mc, "進む").disabledReason() != null && button(mc, "戻る").disabledReason() == null, LibraryClientGameTest.TIMEOUT);
		String forwardTip = context.computeOnClient(mc -> button(mc, "進む").tooltipText());
		check("進む\n進めるページがありません".equals(forwardTip), "押せない進むのツールチップ: " + forwardTip);

		// ---- 字を大きく・小さく
		int zoom = context.computeOnClient(mc -> RedtactBrowserScreen.zoomSteps());
		click(context, "字を大きく");
		check(context.computeOnClient(mc -> RedtactBrowserScreen.zoomSteps()) == zoom + 1, "字を大きくしても段が変わらない");
		click(context, "字を小さく");
		check(context.computeOnClient(mc -> RedtactBrowserScreen.zoomSteps()) == zoom, "字を小さくしても段が戻らない");

		// ---- 外のブラウザで開く: いまのページを外のブラウザへ (差し替えて記録だけ)
		int opened = external.size();
		click(context, "外のブラウザで開く");
		context.waitFor(mc -> external.size() > opened, LibraryClientGameTest.TIMEOUT);
		check(external.getLast().equals(home + "/"), "外のブラウザで開いたページが違う: " + external.getLast());

		// ---- 保管庫: 左半分に出し (銅の面)、もう一度押すと閉じる
		click(context, "保管庫");
		context.waitFor(mc -> browser(mc) != null && browser(mc).pane() != null, LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> button(mc, "保管庫").variant()) == RButton.Variant.PRIMARY, "保管庫を出しても銅の面にならない");
		context.waitTicks(10);
		neutralCursor(context);
		context.takeScreenshot("redtact-live-bar-2-pane");
		click(context, "保管庫");
		context.waitFor(mc -> browser(mc) != null && browser(mc).pane() == null, LibraryClientGameTest.TIMEOUT);

		// ---- マウスを重ねると名前 (と説明) のツールチップ
		hover(context, "保管庫");
		context.waitTicks(5);
		context.takeScreenshot("redtact-live-bar-3-tooltip");
		neutralCursor(context);

		// ---- 保存中の数: ダウンロードを半分で止め、帯 (↓ と数) と、帯を隠したときのヘッダの空きを撮る
		gate = new CountDownLatch(1);

		try {
			context.runOnClient(mc -> browser(mc).load(home + slowPath));
			context.waitFor(mc -> RedtactLive.get().downloads().inFlight() > 0, LibraryClientGameTest.TIMEOUT);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-bar-4-saving");
			LibraryClientGameTest.clickSubBarToggle(context);
			context.waitFor(mc -> browser(mc) != null && browser(mc).pageTop() == MenuScreen.BAR_H + 1, LibraryClientGameTest.TIMEOUT);
			neutralCursor(context);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-bar-5-saving-header");
			LibraryClientGameTest.clickSubBarToggle(context);
			context.waitFor(mc -> browser(mc) != null && browser(mc).pageTop() > MenuScreen.BAR_H + 1, LibraryClientGameTest.TIMEOUT);
		} finally {
			gate.countDown();
		}

		context.waitFor(mc -> RedtactLive.get().downloads().inFlight() == 0, LibraryClientGameTest.TIMEOUT);
		load(context, start);
		LOGGER.info("[bar] 帯のアイコン 8 つ・ツールチップ・押した動き・保存中の数を確かめた");
	}

	/**
	 * 画面の広さを変えて帯を撮る (GUI の広さ 480×270 = 1080p・GUI 4、640×360 = GUI 3、960×540 = GUI 2)。いまのページの欄の
	 * 幅と URL の字の幅を記録する。撮ったら窓と GUI の倍率を戻す。
	 */
	static void sizeShots(ClientGameTestContext context, String prefix) {
		int[] orig = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});

		try {
			for (int[] size : new int[][] {{960, 540, 2}, {1280, 720, 2}, {1920, 1080, 2}}) {
				context.getInput().resizeWindow(size[0], size[1]);
				context.runOnClient(mc -> {
					mc.options.guiScale().set(size[2]);
					mc.resizeGui();
				});
				context.waitTicks(40); // Chromium が新しい大きさで描き直すまで
				neutralCursor(context);
				int[] w = context.computeOnClient(mc -> {
					RedtactBrowserScreen s = browser(mc);
					return new int[] {s.width, s.height, s.urlWidth(), mc.font.width(RedtactLinks.display(s.pageUrl()))};
				});
				String tag = w[0] + "x" + w[1];
				LOGGER.info("[bar] {}: いまのページの欄 {}・URL の字 {} ({})", tag, w[2], w[3], w[3] <= w[2] ? "全部入る" : "…で切る");
				context.takeScreenshot(prefix + "-" + tag);
			}
		} finally {
			context.getInput().resizeWindow(orig[0], orig[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(orig[2]);
				mc.resizeGui();
			});
			context.waitTicks(10);
		}
	}

	/** 手元のサーバ: ダウンロードを半分送ったところで、テストが門を放すまで待つ (保存中の数を撮るため)。 */
	static void slowDownload(HttpExchange ex, byte[] bytes, String fileName) throws IOException {
		ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
		ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"test.litematic\"; filename*=UTF-8''"
				+ URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
		ex.sendResponseHeaders(200, bytes.length);

		try (OutputStream out = ex.getResponseBody()) {
			int half = bytes.length / 2;
			out.write(bytes, 0, half);
			out.flush();

			try {
				gate.await(60, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			out.write(bytes, half, bytes.length - half);
		}
	}

	// ---- 部品

	private static RedtactBrowserScreen browser(Minecraft mc) {
		return mc.gui.screen() instanceof RedtactBrowserScreen s ? s : null;
	}

	private static List<RButton> sorted(RedtactBrowserScreen s) {
		return s.barButtons().stream().sorted(Comparator.comparingInt(RButton::getX)).toList();
	}

	/** 帯のボタンを名前で引く (押すと画面を組み直すことがあるので、毎回いまの画面から)。 */
	private static RButton button(Minecraft mc, String label) {
		RedtactBrowserScreen s = browser(mc);
		check(s != null, "redtact タブでない: " + mc.gui.screen());
		return s.barButtons().stream().filter(b -> b.getMessage().getString().equals(label)).findFirst()
				.orElseThrow(() -> new AssertionError("帯に " + label + " が無い"));
	}

	private static void click(ClientGameTestContext context, String label) {
		int[] p = center(context, label);
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
		context.waitTicks(3);
	}

	private static void hover(ClientGameTestContext context, String label) {
		int[] p = center(context, label);
		double scale = context.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		context.getInput().setCursorPos(p[0] * scale, p[1] * scale);
	}

	private static int[] center(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> {
			RButton b = button(mc, label);
			return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
		});
	}

	/** マウスをページの外 (ヘッダの左端のロゴの上) へ。ツールチップを消す。 */
	private static void neutralCursor(ClientGameTestContext context) {
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(2);
	}

	private static void load(ClientGameTestContext context, String url) {
		context.runOnClient(mc -> browser(mc).load(url));
		context.waitFor(mc -> browser(mc).pageUrl().equals(url) && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(5);
	}
}
