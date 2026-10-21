package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.browser.cef.McefBridge;
import com.redtact.live.browser.cef.RedtactBrowserScreen;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * メニューを閉じるときの確認 (#67)。本物の Chromium で、ページが止めなければ Esc ですぐ閉じ (ブラウザも)、止めれば
 * 「メニューを閉じますか？」の窓が出てキャンセルで開いたまま、ほかのタブ (保管庫) で閉じても redtact タブで窓が出て「閉じる」で閉じる。
 */
final class CloseChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	/** 答えが来ないときに待ち切れずに閉じるまで (McefBridge の CLOSE_WAIT_MS) より十分短い。 */
	private static final int QUICK_TICKS = 40;

	private CloseChecks() {
	}

	/** redtact タブで page を出している前提。最後はメニューを閉じている。 */
	static void run(ClientGameTestContext context, String page) {
		// ---- ページが止めない: Esc ですぐ閉じる (Chromium がブラウザを閉じた知らせで。待ち切れずに閉じるのではない)
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> mc.gui.screen() == null && !McefBridge.hasTabBrowser(), QUICK_TICKS);
		check(!GameTestText.TEXT_INPUT_TOGGLES || !context.computeOnClient(CloseChecks::textInputActive), "メニューを閉じても SDL の文字入力が有効のまま");

		// ---- 開き直し、ページを押してから (Chromium は押していないページの離脱の確認を出さない) 離脱を止める
		context.runOnClient(mc -> Menu.openRedtact(page));
		context.waitFor(mc -> browser(mc) != null && browser(mc).painted() && browser(mc).pageUrl().equals(page) && !browser(mc).loading(),
				LibraryClientGameTest.TIMEOUT);
		context.waitTicks(10);
		clickPage(context, 0.75, 0.55);
		context.waitTicks(5);
		context.runOnClient(mc -> browser(mc).executeScript(
				"addEventListener('beforeunload', function (e) { e.preventDefault(); e.returnValue = ''; }); location.hash = 'editing'"));
		context.waitFor(mc -> browser(mc).pageUrl().endsWith("#editing"), LibraryClientGameTest.TIMEOUT);
		int serial = context.computeOnClient(mc -> McefBridge.tabBrowserSerial());

		// ---- Esc → 「メニューを閉じますか？」→ キャンセルで開いたまま (同じブラウザの同じページ)
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		waitDialog(context);
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-library-16-close-confirm");
		clickWidget(context, mc -> browser(mc).dialogButton(false));
		context.waitTicks(20);
		check(context.computeOnClient(mc -> browser(mc) != null && browser(mc).dialogKind() == null), "キャンセルしたらメニューが閉じた (または窓が残った)");
		check(context.computeOnClient(mc -> McefBridge.hasTabBrowser() && McefBridge.tabBrowserSerial() == serial), "キャンセルしたのにブラウザを閉じた・作り直した");
		check(!context.computeOnClient(mc -> McefBridge.closePending()), "キャンセルしたのに閉じる頼みが残っている");
		check(context.computeOnClient(mc -> browser(mc).pageUrl()).endsWith("#editing"), "キャンセルしたのにページが変わった");

		// ---- 保管庫タブで Esc → redtact タブに切り替わって窓が出る → 閉じるでメニューもブラウザも閉じる
		LibraryClientGameTest.clickSection(context, MenuSection.LIBRARY);
		context.waitForScreen(LibraryScreen.class);
		context.waitTicks(5);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		waitDialog(context);
		clickWidget(context, mc -> browser(mc).dialogButton(true));
		context.waitFor(mc -> mc.gui.screen() == null && !McefBridge.hasTabBrowser(), LibraryClientGameTest.TIMEOUT);
		check(!context.computeOnClient(mc -> McefBridge.closePending()), "閉じたのに閉じる頼みが残っている");
		check(!GameTestText.TEXT_INPUT_TOGGLES || !context.computeOnClient(CloseChecks::textInputActive), "メニューを閉じても SDL の文字入力が有効のまま");
		LOGGER.info("[mcef] メニューを閉じるときの確認: 止めないページはすぐ閉じる・キャンセルで残る・ほかのタブからも窓で閉じるを確かめた");
	}

	private static void waitDialog(ClientGameTestContext context) {
		context.waitFor(mc -> browser(mc) != null && "BEFORE_UNLOAD".equals(browser(mc).dialogKind()), LibraryClientGameTest.TIMEOUT);
		String text = context.computeOnClient(mc -> browser(mc).dialogText());
		check(text.contains("メニューを閉じますか？"), "メニューを閉じるときの文言でない: " + text);
	}

	private static boolean textInputActive(Minecraft mc) {
		return GameTestText.textInputActive(mc);
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

	private static void clickPage(ClientGameTestContext context, double fx, double fy) {
		double[] g = context.computeOnClient(mc -> {
			RedtactBrowserScreen b = browser(mc);
			return new double[] {b.pageLeft() + b.pageWidth() * fx, b.pageTop() + (b.height - b.pageTop()) * fy};
		});
		LibraryClientGameTest.clickGui(context, g[0], g[1]);
	}
}
