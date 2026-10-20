package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.browser.cef.RedtactBrowserScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * ページの JS のダイアログ (#65)。本物の Chromium で confirm・alert・prompt・離脱の確認 (beforeunload) を出させ、
 * redtact タブの上の窓を本物のマウスとキーで答える。答えはページの URL の # で見る。
 * 受け口が無いと Chromium はダイアログを出さずにキャンセル扱いにする (redtact の編集中に移れなかった)。
 */
final class DialogChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");

	private DialogChecks() {
	}

	/** いまのページ (同じオリジンの手元のサーバ) で確かめ、最後に next へ移る。 */
	static void run(ClientGameTestContext context, String next) {
		// ---- confirm → OK (本物のクリック)
		script(context, "location.hash = 'c:' + confirm('保存していない変更があります。\\nこのページを離れますか？')");
		waitDialog(context, "CONFIRM");
		check(context.computeOnClient(mc -> browser(mc).dialogText()).contains("このページを離れますか？"), "confirm の本文が出ていない");
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-library-13-confirm");
		clickWidget(context, mc -> browser(mc).dialogButton(true));
		waitHash(context, "c:true");
		check(context.computeOnClient(mc -> browser(mc).dialogKind()) == null, "OK のあとも窓が残っている");

		// ---- confirm → Esc はキャンセル (メニューは閉じない)
		script(context, "location.hash = 'c2:' + confirm('もう一度？')");
		waitDialog(context, "CONFIRM");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		waitHash(context, "c2:false");
		check(context.computeOnClient(mc -> browser(mc) != null), "ダイアログの Esc でメニューを閉じた");

		// ---- alert → Enter
		script(context, "alert('お知らせ'); location.hash = 'a:done'");
		waitDialog(context, "ALERT");
		check(context.computeOnClient(mc -> browser(mc).dialogButton(false)) == null, "alert にキャンセルがある");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		waitHash(context, "a:done");

		// ---- prompt → 既定の字に打ち足して Enter
		script(context, "location.hash = 'p:' + prompt('名前は？', 'x')");
		waitDialog(context, "PROMPT");
		check("x".equals(context.computeOnClient(mc -> browser(mc).dialogField().getValue())), "prompt の既定の字が入っていない");
		context.getInput().typeChars("yz");
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-library-14-prompt");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		waitHash(context, "p:xyz");

		// ---- 離脱の確認 (beforeunload)。Chromium はページを押したあとでないと出さないので、本物のマウスで押しておく
		clickPage(context, 0.5, 0.9);
		context.waitTicks(5);
		script(context, "addEventListener('beforeunload', function (e) { e.preventDefault(); e.returnValue = ''; }); location.hash = 'guarded'");
		waitHash(context, "guarded");
		String before = context.computeOnClient(mc -> browser(mc).pageUrl());

		// キャンセル (とどまる)
		context.runOnClient(mc -> browser(mc).load(next));
		waitDialog(context, "BEFORE_UNLOAD");
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-library-15-beforeunload");
		clickWidget(context, mc -> browser(mc).dialogButton(false));
		context.waitTicks(30);
		check(context.computeOnClient(mc -> browser(mc).pageUrl()).equals(before), "離脱の確認でキャンセルしたのに移った: " + context.computeOnClient(mc -> browser(mc).pageUrl()));

		// 離れる
		context.runOnClient(mc -> browser(mc).load(next));
		waitDialog(context, "BEFORE_UNLOAD");
		clickWidget(context, mc -> browser(mc).dialogButton(true));
		context.waitFor(mc -> browser(mc).pageUrl().equals(next) && !browser(mc).loading(), LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> browser(mc).dialogKind()) == null, "離れたあとも窓が残っている");
		LOGGER.info("[mcef] ページのダイアログ: confirm (OK・Esc)・alert・prompt・離脱の確認 (キャンセル・離れる) を確かめた");
	}

	private static void script(ClientGameTestContext context, String js) {
		context.runOnClient(mc -> browser(mc).executeScript(js));
	}

	private static void waitDialog(ClientGameTestContext context, String kind) {
		context.waitFor(mc -> browser(mc) != null && kind.equals(browser(mc).dialogKind()), LibraryClientGameTest.TIMEOUT);
	}

	private static void waitHash(ClientGameTestContext context, String hash) {
		context.waitFor(mc -> hash.equals(hash(mc)), LibraryClientGameTest.TIMEOUT);
	}

	private static String hash(Minecraft mc) {
		String url = browser(mc) == null ? "" : browser(mc).pageUrl();
		int i = url.indexOf('#');
		return i < 0 ? "" : URLDecoder.decode(url.substring(i + 1), StandardCharsets.UTF_8);
	}

	private static RedtactBrowserScreen browser(Minecraft mc) {
		return mc.gui.screen() instanceof RedtactBrowserScreen s ? s : null;
	}

	private static void clickWidget(ClientGameTestContext context, Function<Minecraft, AbstractWidget> widget) {
		double[] p = context.computeOnClient(mc -> {
			AbstractWidget w = widget.apply(mc);
			return new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
	}

	/** ページの上の (横の割合, 縦の割合) を本物のマウスで押す。 */
	private static void clickPage(ClientGameTestContext context, double fx, double fy) {
		double[] g = context.computeOnClient(mc -> {
			RedtactBrowserScreen b = browser(mc);
			return new double[] {b.pageLeft() + b.pageWidth() * fx, b.pageTop() + (b.height - b.pageTop()) * fy};
		});
		LibraryClientGameTest.clickGui(context, g[0], g[1]);
	}
}
