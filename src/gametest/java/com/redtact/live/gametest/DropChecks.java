package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.browser.cef.RedtactBrowserScreen;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.gui.LibraryPane;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.MenuSection;
import com.redtact.live.gui.cards.PickCard;
import com.redtact.live.gui.kit.RButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * 保管庫からゲーム内の redtact のページへファイルを入れる (#60)。本物の Chromium (MCEF Modern) で、redtact の投稿の画面の
 * 代わりの手元のページ ({@link #NEW_PAGE}。ドロップ欄 + 「ファイルを追加」で開く隠しの input type=file) を出し、本物のマウスで:
 * <ol>
 * <li>保管庫タブの「redtact に投稿」→ redtact タブの左半分に保管庫・右に投稿の画面、そのファイルを選んだ状態
 * <li>左のファイルを右のドロップ欄へドラッグ → ページの drop に中身ごと届く。選択は外れる
 * <li>2 つ選んでドラッグ → 2 つとも届く
 * <li>選んでからページの「ファイルを追加」→ OS のファイル選択を出さずに入る (input の change)。選択は外れる
 * <li>ドロップ欄の外に落とす → ページはファイルを開かず (外のブラウザにも出さず)、選択が戻る
 * <li>左のパス欄 (本物のキー) で移る・帯の「保管庫」で閉じるとページが全幅に戻る
 * </ol>
 */
final class DropChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	/** redtact の投稿の画面 (CircuitFilesForm) の代わり: 上半分がドロップ欄 (中に「ファイルを追加」)、下半分は受け手なし。 */
	static final String NEW_PAGE = """
			<!doctype html><html><head><meta charset="utf-8"><title>new circuit (gametest)</title></head>
			<body style="margin:0;font:20px sans-serif">
			<div id="zone" style="position:fixed;left:0;top:0;width:100vw;height:50vh;background:#eef">
			<button id="add" style="position:absolute;left:30vw;top:15vh;width:40vw;height:20vh;font-size:30px"
			 onclick="document.getElementById('f').click()">add files</button></div>
			<div style="position:fixed;left:0;top:50vh;width:100vw;height:50vh;background:#fee;font-size:22px;padding:8px;box-sizing:border-box">
			<b>received (not a drop zone)</b><ol id="log"></ol></div>
			<input id="f" type="file" multiple accept=".litematic,.litematica,.nbt,.schem,.schematic,.mcstructure,.mcworld" style="display:none">
			<script>
			var got = [];
			function report(kind, files) {
			  for (var i = 0; i < files.length; i++) {
			    got.push(kind + ':' + encodeURIComponent(files[i].name) + ':' + files[i].size);
			    var li = document.createElement('li');
			    li.textContent = kind + ': ' + files[i].name + ' (' + files[i].size + ' B)';
			    document.getElementById('log').appendChild(li);
			  }
			  location.hash = got.join(',');
			}
			function files(e) { return e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types, 'Files') >= 0; }
			var zone = document.getElementById('zone');
			zone.addEventListener('dragenter', function (e) { if (files(e)) e.preventDefault(); });
			zone.addEventListener('dragover', function (e) { if (files(e)) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; } });
			zone.addEventListener('drop', function (e) { if (files(e)) { e.preventDefault(); report('drop', e.dataTransfer.files); } });
			document.getElementById('f').addEventListener('change', function (e) { report('pick', e.target.files); e.target.value = ''; });
			</script></body></html>
			""";

	private DropChecks() {
	}

	static void run(ClientGameTestContext context, String home, List<String> external) {
		Path dir = LibraryFolderChecks.prepareFiles(context);
		Path small = dir.resolve(LibraryFolderChecks.SMALL);
		Path large = dir.resolve(LibraryFolderChecks.LARGE);
		long smallBytes = size(small);
		long largeBytes = size(large);
		String newPage = home + "/circuits/new";

		// ---- 保管庫タブで builds/2026 を開き、回路2 の「redtact に投稿」(本物のクリック)
		LibraryClientGameTest.clickSection(context, MenuSection.LIBRARY);
		context.waitForScreen(LibraryScreen.class);
		context.runOnClient(mc -> LibraryNav.go(dir));
		context.waitFor(mc -> LibraryClientGameTest.screen(mc) != null && LibraryClientGameTest.screen(mc).fileCards().size() == 2, LibraryClientGameTest.TIMEOUT);
		LibraryClientGameTest.clickOnTab(context, s -> s.fileCards().getFirst().postButton());
		context.waitFor(mc -> browser(mc) != null && browser(mc).pane() != null && browser(mc).pageUrl().startsWith(newPage) && !browser(mc).loading(),
				LibraryClientGameTest.TIMEOUT);
		context.waitFor(mc -> browser(mc).pane().pickCards().size() == 2, LibraryClientGameTest.TIMEOUT);
		context.waitTicks(20);
		check(context.computeOnClient(mc -> selection()).equals(List.of(small)), "投稿するファイルが選ばれていない: " + context.computeOnClient(mc -> selection()));
		check(context.computeOnClient(mc -> browser(mc).paneToggle().variant()) == RButton.Variant.PRIMARY, "帯の「保管庫」が点いていない");
		int[] widths = context.computeOnClient(mc -> new int[] {browser(mc).pageLeft(), browser(mc).width, browser(mc).browserWidth()});
		check(widths[0] == widths[1] / 2, "ページが右半分でない: " + widths[0] + " / " + widths[1]);
		shot(context, "redtact-live-library-8-post-with-pane");

		// ---- 1 つ: 選んだ回路2 を右のドロップ欄へ (本物のドラッグ)
		dragToPage(context, mc -> pick(mc, LibraryFolderChecks.SMALL), 0.1, 0.1, "redtact-live-library-8b-dragging");
		waitHash(context, "drop:" + LibraryFolderChecks.SMALL + ":" + smallBytes);
		check(context.computeOnClient(mc -> selection()).isEmpty(), "落としたのに選んだまま");
		shot(context, "redtact-live-library-9-dropped");

		// ---- 2 つ選んで (本物のクリック) 回路10 の行から運ぶ → 2 つとも (選んだ順に)
		clickCard(context, mc -> pick(mc, LibraryFolderChecks.SMALL));
		clickCard(context, mc -> pick(mc, LibraryFolderChecks.LARGE));
		check(context.computeOnClient(mc -> selection()).equals(List.of(small, large)), "2 つ選べていない: " + context.computeOnClient(mc -> selection()));
		shot(context, "redtact-live-library-10-two-selected");
		dragToPage(context, mc -> pick(mc, LibraryFolderChecks.LARGE), 0.1, 0.4, "redtact-live-library-10b-dragging-two");
		waitHash(context, "drop:" + LibraryFolderChecks.SMALL + ":" + smallBytes + ",drop:" + LibraryFolderChecks.LARGE + ":" + largeBytes);
		check(context.computeOnClient(mc -> selection()).isEmpty(), "落としたのに選んだまま (2 つ)");

		// ---- 回路10 を選んでからページの「ファイルを追加」(本物のクリック) → OS の画面を出さずに入る
		clickCard(context, mc -> pick(mc, LibraryFolderChecks.LARGE));
		clickPage(context, 0.5, 0.25);
		waitHash(context, "pick:" + LibraryFolderChecks.LARGE + ":" + largeBytes);
		context.waitFor(mc -> selection().isEmpty(), LibraryClientGameTest.TIMEOUT);
		shot(context, "redtact-live-library-11-file-dialog");

		// ---- ドロップ欄の外 (下半分) に落とす → ファイルを開かず、外のブラウザにも出さず、選択が戻る
		clickCard(context, mc -> pick(mc, LibraryFolderChecks.SMALL));
		String before = context.computeOnClient(mc -> browser(mc).pageUrl());
		int externalBefore = external.size();
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		dragToPage(context, mc -> pick(mc, LibraryFolderChecks.SMALL), 0.5, 0.8, "redtact-live-library-11b-dragging-outside");
		context.waitFor(mc -> selection().equals(List.of(small)), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(20);
		context.takeScreenshot("redtact-live-library-11c-dropped-outside"); // 知らせのトーストも写す
		check(context.computeOnClient(mc -> browser(mc).pageUrl()).equals(before), "欄の外に落としたらページが変わった: " + context.computeOnClient(mc -> browser(mc).pageUrl()));
		check(external.size() == externalBefore, "欄の外に落としたファイルを外のブラウザで開いた: " + external);

		// ---- 左のパス欄 (本物のキー) で builds へ
		context.waitTicks(6);
		clickWidget(context, mc -> browser(mc).pane().pathField());
		LibraryFolderChecks.clearAndType(context, mc -> browser(mc).pane().pathField(), LibraryFolderChecks.BUILDS);
		context.waitFor(mc -> dir.getParent().equals(LibraryNav.current()) && browser(mc).pane().folderCards().stream()
				.anyMatch(c -> c.item().name().equals(LibraryFolderChecks.YEAR)), LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> browser(mc).pageUrl()).equals(before), "パス欄の字がページに届いた");

		// ---- パス欄のあとでページ (下の受け取りの欄。押しても何も起きない) を押すと、ページに字が届く状態に戻る
		// (パス欄が外れるとバニラが SDL の文字入力を止める。#61)
		clickPage(context, 0.5, 0.8);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> GameTestText.textInputActive(mc)), "パス欄のあとでページを押しても SDL の文字入力が止まったまま");

		// ---- 帯の「保管庫」(本物のクリック) で閉じる → ページが全幅に戻る (同じページのまま)
		clickWidget(context, mc -> browser(mc).paneToggle());
		context.waitFor(mc -> browser(mc) != null && browser(mc).pane() == null && browser(mc).pageLeft() == 0, LibraryClientGameTest.TIMEOUT);
		int fullWidth = context.computeOnClient(mc -> browser(mc).browserWidth());
		check(fullWidth > widths[2], "保管庫を閉じてもページが広がらない: " + widths[2] + " → " + fullWidth);
		check(context.computeOnClient(mc -> browser(mc).pageUrl()).equals(before), "保管庫を閉じたらページが変わった");
		shot(context, "redtact-live-library-12-pane-closed");

		context.runOnClient(mc -> {
			RedtactLive.get().fileSelection().clear();
			LibraryNav.go(LibraryNav.libraryDir());
		});
		LOGGER.info("[mcef] 保管庫から投稿の画面へ: ドロップ (1 つ・2 つ)・ファイルを追加・欄の外・パス欄・閉じるを確かめた");
	}

	static RedtactBrowserScreen browser(Minecraft mc) {
		return mc.gui.screen() instanceof RedtactBrowserScreen s ? s : null;
	}

	static List<Path> selection() {
		return RedtactLive.get().fileSelection().existing();
	}

	static PickCard pick(Minecraft mc, String name) {
		LibraryPane pane = browser(mc).pane();
		check(pane != null, "左の保管庫が出ていない");
		return pane.pickCards().stream().filter(c -> c.item().name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError("左の保管庫に " + name + " が無い"));
	}

	/** 行の左寄り (選んだ印と名前のあたり) の GUI の座標。 */
	private static double[] cardPoint(PickCard c) {
		return new double[] {c.getX() + 24, c.getY() + PickCard.HEIGHT / 2.0};
	}

	private static double[] center(AbstractWidget w) {
		return new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
	}

	private static void clickCard(ClientGameTestContext context, Function<Minecraft, PickCard> card) {
		double[] p = context.computeOnClient(mc -> cardPoint(card.apply(mc)));
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
		context.waitTicks(2);
	}

	private static void clickWidget(ClientGameTestContext context, Function<Minecraft, AbstractWidget> widget) {
		double[] p = context.computeOnClient(mc -> center(widget.apply(mc)));
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
	}

	/** ページの上の (横の割合, 縦の割合) を本物のマウスで押す。 */
	private static void clickPage(ClientGameTestContext context, double fx, double fy) {
		double[] g = context.computeOnClient(mc -> pagePoint(browser(mc), fx, fy));
		LibraryClientGameTest.clickGui(context, g[0], g[1]);
	}

	private static double[] pagePoint(RedtactBrowserScreen b, double fx, double fy) {
		return new double[] {b.pageLeft() + b.pageWidth() * fx, b.pageTop() + (b.height - b.pageTop()) * fy};
	}

	/** トーストを消し、マウスを左下へ外して (ツールチップを出さない) から撮る (案内のトーストがページの右上を隠すので。確かめには関わらない)。 */
	private static void shot(ClientGameTestContext context, String name) {
		LibraryFolderChecks.cleanShot(context, name);
	}

	/** 左の行を押したまま、ページの (横の割合, 縦の割合) まで少しずつ動かして離す (本物のマウス)。離す前に shot の名前で撮る。 */
	static void dragToPage(ClientGameTestContext context, Function<Minecraft, PickCard> card, double fx, double fy, String shot) {
		double scale = context.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		double[] from = context.computeOnClient(mc -> cardPoint(card.apply(mc)));
		double[] to = context.computeOnClient(mc -> pagePoint(browser(mc), fx, fy));
		context.getInput().setCursorPos(from[0] * scale, from[1] * scale);
		context.waitTicks(1);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		int steps = 10;

		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			context.getInput().setCursorPos((from[0] + (to[0] - from[0]) * t) * scale, (from[1] + (to[1] - from[1]) * t) * scale);
			context.waitTicks(1);
		}

		check(context.computeOnClient(mc -> browser(mc).dragging()), "ドラッグになっていない");
		context.runOnClient(mc -> mc.gui.toastManager().clear()); // マウスは動かさない (運んでいる途中)
		context.waitTicks(2);
		context.takeScreenshot(shot);
		context.waitTicks(3);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
	}

	/** ページの URL のフラグメント (ページが受け取ったファイルの記録) が want になるまで待つ。 */
	static void waitHash(ClientGameTestContext context, String want) {
		context.waitFor(mc -> {
			String u = browser(mc).pageUrl();
			int h = u.indexOf('#');
			return h >= 0 && URLDecoder.decode(u.substring(h + 1), StandardCharsets.UTF_8).endsWith(want);
		}, LibraryClientGameTest.TIMEOUT);
	}

	private static long size(Path f) {
		try {
			return Files.size(f);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
