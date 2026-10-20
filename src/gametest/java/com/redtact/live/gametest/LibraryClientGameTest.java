package com.redtact.live.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.copy.LitematicaSupport;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.MenuSection;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.RedtactScreen;
import com.redtact.live.gui.cards.LibraryCard;
import com.redtact.live.library.Library;
import com.redtact.live.library.LibraryActions;
import com.redtact.live.library.LibraryEntry;
import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.litematic.LitematicWriter;
import com.redtact.live.litematic.RegionBlocks;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * redtact の保管庫 (#46) を本物のクライアント (26.3) で確かめる。中継サーバは要らない。
 * <ol>
 * <li>ダウンロードと同じ手順で保管庫に入れ、メニューの保管庫タブに出る (#51。前は redtact タブの下半分)
 * <li>「配置」(本物のクリック) で Litematica の配置が足元にでき、動いてもう一度押すと足元へ置き直す (Litematica があるとき)
 * <li>同じファイルを取り直しても 1 件のまま。「削除」を 2 回押すとファイルごと消える
 * <li>「上へ」・フォルダの行・パス欄で schematics の下を行き来し、ほかのフォルダのファイルに「配置」「redtact に投稿」が出る
 * ({@link LibraryFolderChecks}。#60)
 * <li>ファイルの行を押すと右に立体のプレビュー (形式ごと)・↑↓ で選ぶ・場所と検索の段を ▲ で隠す・検索 ({@link LibraryPreviewChecks}。#70)
 * <li>プレビューの右上の全画面のボタンで全画面のプレビュー: 回す・寄る・動かす・フライ・高さで切る・真上・撮影 ({@link FullscreenPreviewChecks}。#102。
 * REDTACT_LIVE_FULLSCREEN_ONLY=1 でこれだけ)
 * <li>MCEF Modern があれば ({@code -Pmcef})、redtact タブに本物の Chromium で手元のページを出し、リンクのクリックでダウンロード →
 * 保管庫 → 配置まで、外のサイト・別タブ・字の入力・ログインの扱いと、ほかのタブへ移って戻っても同じページかを確かめる ({@link McefChecks})。
 * 無ければ redtact タブは外のブラウザで開く入口になる
 * </ol>
 * <pre>
 * REDTACT_LIVE_LIBRARY_ONLY=1 JAVA_HOME=~/opt/jdk-25 ./gradlew runClientGameTest            # Litematica あり・MCEF なし
 * REDTACT_LIVE_LIBRARY_ONLY=1 JAVA_HOME=~/opt/jdk-25 ./gradlew runClientGameTest -PnoLitematica
 * REDTACT_LIVE_LIBRARY_ONLY=1 JAVA_HOME=~/opt/jdk-25 ./gradlew runClientGameTest -Pmcef     # 初回は Chromium を取ってくる
 * </pre>
 * REDTACT_LIVE_LIBRARY_ONLY を付けると、中継サーバの要る LiveClientGameTest を飛ばす。
 */
@SuppressWarnings("UnstableApiUsage")
public class LibraryClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	static final int TIMEOUT = 20 * 30;
	static final String CIRCUIT_ID = "abcdEFGH1234";

	@Override
	public void runTest(ClientGameTestContext context) {
		// 確かめる文は日本語 (#79。gametest のマイクラは英語なので、自動のままだと英語になる)
		context.runOnClient(mc -> com.redtact.live.config.Configs.Generic.LANGUAGE.setOptionListValue(com.redtact.live.i18n.UiLanguage.JA));

		if (System.getenv("REDTACT_LIVE_PERF_ONLY") != null) {
			return; // 重さの計測 (BrowserPerfClientGameTest) だけ
		}

		if (PointerLockClientGameTest.only()) {
			return; // Pointer Lock の肩代わり (#88。PointerLockClientGameTest) だけ
		}

		if (System.getenv("REDTACT_LIVE_SIGNIN_ONLY") != null || LiveClientGameTest.liveLibraryOnly()
				|| System.getenv("REDTACT_LIVE_COMMENTS_ONLY") != null || System.getenv("REDTACT_LIVE_ICONS_ONLY") != null
				|| System.getenv("REDTACT_LIVE_LANG_ONLY") != null || System.getenv("REDTACT_LIVE_BRANCH_ONLY") != null || LiveClientGameTest.searchOnly()) {
			return;
		}

		boolean litematica = context.computeOnClient(mc -> LitematicaSupport.available());
		boolean mcef = context.computeOnClient(mc -> BrowserSupport.available());
		LOGGER.info("[library] Litematica={} MCEF Modern={}", litematica, mcef);

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
			context.waitTicks(5);
			context.runOnClient(mc -> clearLibrary());

			if (FullscreenPreviewChecks.only()) {
				// 全画面のプレビュー (#102) だけ (直すあいだ用。通しは下の 4d)
				context.runOnClient(mc -> Menu.open(MenuTab.LIBRARY));
				context.waitForScreen(LibraryScreen.class);
				FullscreenPreviewChecks.library(context);
				context.runOnClient(mc -> mc.gui.setScreen(null));
				return;
			}

			// ---- 1. 保存 (ダウンロードと同じ手順) → タブに出る
			byte[] bytes = litematic("lib");
			LibraryEntry saved = context.computeOnClient(mc -> save("テスト回路.litematic", CIRCUIT_ID + "/0123abcd.litematic", bytes, "テスト回路"));
			check(saved.storedName.equals("テスト回路.litematic"), "保存名が違う: " + saved.storedName);
			Path file = context.computeOnClient(mc -> library().file(saved));
			check(file.getParent().getFileName().toString().equals(Library.FOLDER), "保管庫のフォルダが違う: " + file);
			check(file.getParent().getParent().equals(context.computeOnClient(mc -> com.redtact.live.copy.CircuitLoader.schematicsDir())),
					"Litematica の schematics の下にない: " + file);
			context.runOnClient(mc -> Menu.open(MenuTab.LIBRARY));
			context.waitForScreen(LibraryScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-library-1-tab");
			List<LibraryCard> cards = context.computeOnClient(mc -> screen(mc).libraryCards());
			check(cards.size() == 1 && cards.getFirst().entry().label.equals("テスト回路"), "タブの一覧が違う: " + cards.size());

			// ---- 2. 配置 (本物のクリック)。動いてもう一度押すと足元へ置き直す
			String key = LibraryActions.KEY_PREFIX + saved.storedName;

			if (litematica) {
				clickOnTab(context, s -> s.libraryCards().getFirst().placeButton());
				context.waitFor(mc -> LitematicaChecks.libraryPlacement(key) != null, TIMEOUT);
				LitematicaChecks.LibraryPlacement p = context.computeOnClient(mc -> LitematicaChecks.libraryPlacement(key));
				check(p.origin().equals(new BlockPos(0, -60, 0)), "配置の原点が足元でない: " + p.origin());
				check(p.file().equals(file.toAbsolutePath().normalize()), "配置のファイルが違う: " + p.file());
				// メインタブを本物のクリックで行き来する (設定 → 保管庫)
				clickSection(context, MenuSection.SETTINGS);
				context.waitForScreen(com.redtact.live.gui.SettingsScreen.class);
				sp.getServer().runCommand("tp @a 5.5 -60 7.5 0 0");
				context.waitTicks(5);
				clickSection(context, MenuSection.LIBRARY);
				context.waitForScreen(LibraryScreen.class);
				context.waitTicks(3);
				clickOnTab(context, s -> s.libraryCards().getFirst().placeButton());
				context.waitFor(mc -> {
					LitematicaChecks.LibraryPlacement q = LitematicaChecks.libraryPlacement(key);
					return q != null && q.origin().equals(new BlockPos(5, -60, 7));
				}, TIMEOUT);
				check(context.computeOnClient(mc -> LitematicaChecks.libraryPlacement(key)).sameFile() == 1, "同じファイルの配置が 2 つある");
				context.takeScreenshot("redtact-live-library-2-placed");
			} else {
				String reason = context.computeOnClient(mc -> screen(mc).libraryCards().getFirst().placeButton().disabledReason());
				check(reason != null && reason.contains("Litematica"), "Litematica が無いのに配置できる: " + reason);
			}

			// ---- 3. 同じファイルを取り直しても 1 件のまま (中身は新しく)
			byte[] bytes2 = litematic("lib v2");
			LibraryEntry again = context.computeOnClient(mc -> save("テスト回路.litematic", CIRCUIT_ID + "/0123abcd.litematic", bytes2, "テスト回路"));
			check(again.storedName.equals(saved.storedName), "取り直しで名前が変わった: " + again.storedName);
			check(context.computeOnClient(mc -> library().list().size()) == 1, "取り直しで 2 件になった");
			check(java.util.Arrays.equals(read(file), bytes2), "取り直しで中身が新しくなっていない");

			// ---- 4. 削除は 2 回押す
			context.waitTicks(3);
			clickOnTab(context, s -> s.libraryCards().getFirst().deleteButton());
			check(Files.exists(file), "1 回押しただけで消えた");
			clickOnTab(context, s -> s.libraryCards().getFirst().deleteButton());
			context.waitFor(mc -> screen(mc) != null && screen(mc).libraryCards().isEmpty(), TIMEOUT);
			check(!Files.exists(file), "削除してもファイルが残っている");
			check(context.computeOnClient(mc -> library().list().isEmpty()), "削除しても索引に残っている");
			context.takeScreenshot("redtact-live-library-3-deleted");

			// ---- 4b. schematics の下のフォルダの行き来・ほかのフォルダのファイルの配置と投稿の条件 (#60)
			LibraryFolderChecks.run(context, sp, litematica, mcef);

			// ---- 4c. ファイルの立体のプレビュー・↑↓・場所と検索の段を隠す・検索 (#70)
			LibraryPreviewChecks.run(context);

			// ---- 4d. 全画面のプレビュー: 回す・寄る・動かす・フライ・高さで切る・真上・撮影・5,014 ブロックの計測 (#102)
			FullscreenPreviewChecks.library(context);

			// ---- 5. MCEF Modern があれば本物の Chromium で。無ければ redtact タブは外のブラウザで開く入口
			if (mcef) {
				McefChecks.run(context, sp, litematica);
			} else {
				clickSection(context, MenuSection.REDTACT);
				context.waitForScreen(RedtactScreen.class);
				context.waitTicks(5);
				context.takeScreenshot("redtact-live-library-4-redtact-without-mcef");
				String label = context.computeOnClient(mc -> ((RedtactScreen) mc.gui.screen()).openButtonLabel());
				check(label.contains("外のブラウザ"), "MCEF Modern が無いのに外のブラウザと出ていない: " + label);
			}

			context.runOnClient(mc -> mc.gui.setScreen(null));
		}
	}

	// ---- 部品

	static Library library() {
		return RedtactLive.get().library();
	}

	static LibraryScreen screen(Minecraft mc) {
		return mc.gui.screen() instanceof LibraryScreen s ? s : null;
	}

	/** ダウンロードと同じ手順で保管庫に入れる (reserve → 一時ファイル → add)。 */
	static LibraryEntry save(String suggested, String sourceKey, byte[] bytes, String label) {
		try {
			Library lib = library();
			String name = lib.reserve(suggested, sourceKey);
			Path temp = lib.newTempFile();
			Files.write(temp, bytes);
			LibraryEntry e = new LibraryEntry();
			e.storedName = name;
			e.label = label;
			e.fileName = suggested;
			e.sourceKey = sourceKey;
			e.circuitId = sourceKey.substring(0, sourceKey.indexOf('/'));
			e.savedAt = Times.now();
			return lib.add(temp, e);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void clearLibrary() {
		Path dir = library().dir();

		if (!Files.isDirectory(dir)) {
			return;
		}

		try (Stream<Path> files = Files.walk(dir)) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			});
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** 3 ブロック (石・レッドストーンブロック・石) の .litematic。 */
	static byte[] litematic(String name) {
		Map<String, String> none = new TreeMap<>();
		List<BlockStateEntry> palette = List.of(BlockStateEntry.AIR, BlockStateEntry.of("minecraft:stone", none),
				BlockStateEntry.of("minecraft:redstone_block", none));
		RegionBlocks region = new RegionBlocks(3, 1, 1, palette, new int[] {1, 2, 1});
		return LitematicWriter.toBytes(region, List.of(), new LitematicWriter.Metadata(name, "gametest", "#46", SessionManager.dataVersion(), 0L));
	}

	private static byte[] read(Path f) {
		try {
			return Files.readAllBytes(f);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** 保管庫タブの部品を本物のマウスで押す。 */
	static void clickOnTab(ClientGameTestContext context, Function<LibraryScreen, AbstractWidget> widget) {
		clickWidget(context, mc -> widget.apply(screen(mc)));
	}

	/** メインタブ (ヘッダの階) を本物のマウスで押す (#51)。 */
	static void clickSection(ClientGameTestContext context, MenuSection section) {
		clickWidget(context, mc -> ((MenuScreen) mc.gui.screen()).sectionButton(section));
	}

	/** ヘッダの右端の、子タブの段 (サブヘッダ) を隠す・出すトグルを本物のマウスで押す (#51)。 */
	static void clickSubBarToggle(ClientGameTestContext context) {
		clickWidget(context, mc -> ((MenuScreen) mc.gui.screen()).subBarToggle());
	}

	/** ヘッダの右端の、その画面だけのトグル (段の ▲ の左) を本物のマウスで押す (#70)。 */
	static void clickHeaderToggle(ClientGameTestContext context, String name) {
		clickWidget(context, mc -> ((MenuScreen) mc.gui.screen()).headerToggle(name));
	}

	/** live の子タブを本物のマウスで押す (#51)。 */
	static void clickSubTab(ClientGameTestContext context, MenuTab tab) {
		clickWidget(context, mc -> ((MenuScreen) mc.gui.screen()).subTabButton(tab));
	}

	static void clickWidget(ClientGameTestContext context, Function<Minecraft, AbstractWidget> widget) {
		double[] p = context.computeOnClient(mc -> {
			AbstractWidget w = widget.apply(mc);
			check(w != null, "押す部品が無い (画面: " + mc.gui.screen() + ")");
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
		});
		click(context, p[0], p[1]);
	}

	/** GUI の座標 (gx, gy) を本物のマウスで押す。 */
	static void clickGui(ClientGameTestContext context, double gx, double gy) {
		double scale = context.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		click(context, gx * scale, gy * scale);
	}

	private static void click(ClientGameTestContext context, double x, double y) {
		context.getInput().setCursorPos(x, y);
		context.waitTicks(1);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}
}
