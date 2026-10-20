package com.redtact.live.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.LiveListScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.MenuSection;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.SessionScreen;
import com.redtact.live.gui.SettingsScreen;
import com.redtact.live.gui.cards.CircuitCard;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.PreviewView;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.selection.Box;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.CircuitRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * ボタンのアイコン化と、範囲の削除・設定の一括リセット・詳細の拡大 (#77)。押すのは本物のマウス。
 * <ul>
 * <li>セッションタブ: 保存済みの範囲のゴミ箱を 1 回押すと赤くなるだけ、2 回目で trash/ へ。開催中の回路のゴミ箱は押せない</li>
 * <li>設定: 「全般」「ホットキー」の見出しの ↺ を 2 回押すと段の全部が既定に戻る (全部が既定なら押せない)</li>
 * <li>開催中の詳細: ヘッダの拡大で、帯と題の段が隠れてプレビューが全幅で上いっぱいになり、その下に操作の行とコメントが来る
 * (プレビューには何も重ねない)。回路は大きく描かれる。縮小のボタンか Esc で左右に戻る</li>
 * <li>保管庫のサブヘッダー: ボタンがアイコンになり、パス欄と検索欄が広い</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
final class IconChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final int TIMEOUT = 20 * 30;

	private IconChecks() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
		context.waitTicks(5);
		trashSavedRange(context);
		sectionReset(context);
		expandDetail(context);
		libraryBar(context);
		// 範囲タブ: 視線の先へ (目)・選択を解除 (×)・セッションの範囲に戻す (↺)
		context.runOnClient(mc -> {
			RedtactLive.get().selection().set(new Box(0, -60, 0, 5, -59, 3), "minecraft:overworld");
			Menu.open(MenuTab.RANGE);
		});
		context.waitTicks(5);
		LiveClientGameTestShots.neutralCursor(context);
		context.takeScreenshot("redtact-live-icons-9-range");
		// 後の確認は範囲を選んでいない状態から (開催した範囲は終えても選んだままなので外す)
		context.runOnClient(mc -> RedtactLive.get().savedRanges().clearPick());
		context.setScreen(() -> null);
		context.waitTicks(2);
		LOGGER.info("icons, trash, section reset and expanded detail ok");
	}

	/** 保存済みの範囲のゴミ箱 (2 回押す → trash/)。開催中の回路は消せない。 */
	private static void trashSavedRange(ClientGameTestContext context) {
		CircuitRecord gone = saveRange(context, "gametest 消す範囲", new Box(20, -60, 20, 22, -59, 22));
		CircuitRecord other = saveRange(context, "gametest 消す範囲 2", new Box(24, -60, 24, 25, -59, 25));
		Path dir = RedtactLive.get().sessions().store().circuitDir(gone.circuitId);
		Path otherDir = RedtactLive.get().sessions().store().circuitDir(other.circuitId);
		check(Files.isDirectory(dir) && Files.isDirectory(otherDir), "保存した範囲のフォルダが無い");
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitFor(mc -> session(mc).circuitCard(gone.circuitId) != null && session(mc).circuitCard(other.circuitId) != null, TIMEOUT);
		context.runOnClient(mc -> session(mc).searchField().setValue("消す範囲")); // 一覧の上に出す
		context.waitTicks(3);

		// 開始の行: 新規作成は ＋ のアイコン (選んでいるとき)
		context.runOnClient(mc -> RedtactLive.get().savedRanges().pick(gone));
		context.waitFor(mc -> session(mc).newButton() != null, TIMEOUT);
		check(context.computeOnClient(mc -> session(mc).newButton().icon()) == Icon.PLUS, "新規作成が ＋ のアイコンでない");
		CircuitCard card = context.computeOnClient(mc -> session(mc).circuitCard(gone.circuitId));
		check(card.commentsButton().icon() == Icon.COMMENT && card.deleteButton().icon() == Icon.TRASH && card.resumeButton().icon() == null,
				"カードのボタン: コメントは吹き出し・削除はゴミ箱・再開は字");

		// 選んでいない範囲を消す: 一覧から消え、選んでいる範囲はそのまま
		click(context, mc -> session(mc).circuitCard(other.circuitId).deleteButton());
		click(context, mc -> session(mc).circuitCard(other.circuitId).deleteButton());
		context.waitFor(mc -> !Files.exists(otherDir) && session(mc).circuitCard(other.circuitId) == null, TIMEOUT);
		check(gone.circuitId.equals(RedtactLive.get().savedRanges().pickedId()), "ほかの範囲を消したら選んでいた範囲が外れた");
		check(Files.isDirectory(dir), "消していない範囲まで消えた");

		// 1 回目: 赤くなるだけ (まだ消えない)
		click(context, mc -> session(mc).circuitCard(gone.circuitId).deleteButton());
		check(context.computeOnClient(mc -> session(mc).circuitCard(gone.circuitId).deleteButton().armed()), "1 回目で 2 回目を待たない");
		check(context.computeOnClient(mc -> session(mc).circuitCard(gone.circuitId).deleteButton().variant()) == RButton.Variant.DANGER, "1 回目で赤くならない");
		check(Files.isDirectory(dir), "1 回目で消えた");
		context.takeScreenshot("redtact-live-icons-1-trash-armed");

		// 2 回目: trash/ へ移り、一覧から消え、選んでいた範囲も外れる
		click(context, mc -> session(mc).circuitCard(gone.circuitId).deleteButton());
		context.waitFor(mc -> !Files.exists(dir) && session(mc).circuitCard(gone.circuitId) == null, TIMEOUT);
		check(RedtactLive.get().savedRanges().pickedId() == null, "消した範囲を選んだまま");
		Path trash = RedtactLive.get().sessions().store().trashDir();

		try (Stream<Path> s = Files.list(trash)) {
			check(s.anyMatch(p -> p.getFileName().toString().startsWith(gone.circuitId + "-") && Files.isRegularFile(p.resolve("circuit.json"))),
					"trash/ に回路のフォルダが無い");
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}

		context.runOnClient(mc -> session(mc).searchField().setValue(""));
		context.waitTicks(3);
		context.takeScreenshot("redtact-live-icons-2-trashed");

		// 開催中の回路のゴミ箱は押せない
		// 拡大の確認 (下) でプレビューを描くので、通しの確認の回路 (buildCircuit) と同じ所
		CircuitRecord live = saveRange(context, "gametest 開催中は消せない", new Box(0, -60, 0, 5, -59, 3));
		context.runOnClient(mc -> RedtactLive.get().resumeSavedRange(live, () -> false, () -> true));
		context.waitFor(mc -> RedtactLive.get().sessions().active() != null && RedtactLive.get().sessions().active().lastVersion() >= 1
				&& !RedtactLive.get().sessions().isBusy(), TIMEOUT);
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitFor(mc -> session(mc).circuitCard(live.circuitId) != null, TIMEOUT);
		String reason = context.computeOnClient(mc -> session(mc).circuitCard(live.circuitId).deleteButton().disabledReason());
		check(reason != null && reason.contains("開催中"), "開催中の回路のゴミ箱が押せる: " + reason);
		LiveClientGameTestShots.wait(context, 6_000); // 開始のトーストが消えるまで
		context.takeScreenshot("redtact-live-icons-3-session-live");
	}

	/** 設定の段の ↺: 2 回押すと段の全部が既定に。全部が既定なら押せない。 */
	private static void sectionReset(ClientGameTestContext context) {
		// 通しの確認の値 (表示名など) は最後に戻す
		String user = Configs.Generic.USER.getStringValue();
		boolean auto = Configs.Generic.AUTO_SYNC.getBooleanValue();
		int quiet = Configs.Generic.AUTO_SYNC_QUIET_MS.getIntegerValue();
		int maxDelay = Configs.Generic.AUTO_SYNC_MAX_DELAY_MS.getIntegerValue();
		int interval = Configs.Generic.LOCAL_SNAPSHOT_INTERVAL_MS.getIntegerValue();

		context.runOnClient(mc -> {
			Configs.Generic.HUD_ENABLED.setBooleanValue(false);
			Configs.Hotkeys.OPEN_MENU.getKeybind().setKeys(List.of(com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL,
					com.mojang.blaze3d.platform.InputConstants.KEY_M));
			Menu.open(MenuTab.SETTINGS);
		});
		context.waitForScreen(SettingsScreen.class);
		context.waitFor(mc -> settings(mc).sectionResetButton("全般") != null && settings(mc).sectionResetButton("全般").active, TIMEOUT);
		check(context.computeOnClient(mc -> settings(mc).sectionResetButton("全般").icon()) == Icon.RESET, "見出しのリセットが ↺ でない");
		context.takeScreenshot("redtact-live-icons-4-settings");

		click(context, mc -> settings(mc).sectionResetButton("全般"));
		check(!Configs.Generic.HUD_ENABLED.isDefault(), "1 回目で戻った");
		click(context, mc -> settings(mc).sectionResetButton("全般"));
		context.waitFor(mc -> Configs.Generic.HUD_ENABLED.isDefault() && Configs.Generic.USER.isDefault(), TIMEOUT);
		check(!Configs.Hotkeys.OPEN_MENU.isDefault(), "全般を戻したらホットキーまで戻った");
		context.waitFor(mc -> settings(mc).sectionResetButton("全般") != null && !settings(mc).sectionResetButton("全般").active, TIMEOUT);

		context.runOnClient(mc -> settings(mc).scrollToHotkeys());
		context.waitTicks(3);
		double scrolled = context.computeOnClient(mc -> settings(mc).scrollAmount());
		check(scrolled > 0, "ホットキーの見出しまで送れない");
		click(context, mc -> settings(mc).sectionResetButton("ホットキー"));
		check(context.computeOnClient(mc -> settings(mc).sectionResetButton("ホットキー").armed()), "ホットキーの ↺ が 1 回目で 2 回目を待たない");
		context.takeScreenshot("redtact-live-icons-5-settings-hotkeys-reset"); // 1 回目のあと (赤い ↺ と「もう一度押すと…」)
		click(context, mc -> settings(mc).sectionResetButton("ホットキー"));
		context.waitFor(mc -> Configs.Hotkeys.OPEN_MENU.isDefault(), TIMEOUT);
		context.waitTicks(2);
		double after = context.computeOnClient(mc -> settings(mc).scrollAmount());
		check(Math.abs(after - scrolled) < 1, "戻したら一覧が上へ飛んだ: " + scrolled + " → " + after);

		context.runOnClient(mc -> {
			Configs.Generic.USER.setValueFromString(user);
			Configs.Generic.AUTO_SYNC.setBooleanValue(auto);
			Configs.Generic.AUTO_SYNC_QUIET_MS.setIntegerValue(quiet);
			Configs.Generic.AUTO_SYNC_MAX_DELAY_MS.setIntegerValue(maxDelay);
			Configs.Generic.LOCAL_SNAPSHOT_INTERVAL_MS.setIntegerValue(interval);
			Configs.saveToFile();
		});
	}

	/** 開催中の詳細の拡大: プレビューが全幅になり、コメントがその下に来る。 */
	private static void expandDetail(ClientGameTestContext context) {
		SessionManager.Active a = RedtactLive.get().sessions().active();
		check(a != null, "開催中のセッションが無い");
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		context.runOnClient(mc -> {
			LiveListScreen g = list(mc);

			if (g.detail()) {
				g.backToList();
			}
		});
		context.waitFor(mc -> list(mc).openButton() != null, TIMEOUT);
		check(context.computeOnClient(mc -> list(mc).openButton().icon()) == Icon.FORWARD
				&& context.computeOnClient(mc -> list(mc).branchButton().icon()) == Icon.BRANCH, "リンク欄のボタンがアイコンでない");
		context.takeScreenshot("redtact-live-icons-6-live-list");
		context.runOnClient(mc -> list(mc).linkField().setValue(a.sessionId));
		click(context, mc -> list(mc).openButton());
		context.waitFor(mc -> list(mc).detail() && a.sessionId.equals(list(mc).selectedId()) && list(mc).preview().shownCircuit() != null, TIMEOUT);
		check(!LiveListScreen.expanded(), "最初から拡大している");
		int splitW = context.computeOnClient(mc -> list(mc).preview().getWidth());

		// 拡大: 帯 (ヘッダと子タブの段) と題の段が隠れ、プレビューが全幅で上いっぱい、コメントがその下
		expand(context);
		check(context.computeOnClient(mc -> list(mc).sectionButton(MenuSection.LIVE) == null && list(mc).subTabButton(MenuTab.LIVE_LIST) == null
				&& list(mc).headerToggle(LiveListScreen.EXPAND_TOGGLE) == null), "拡大しても帯が出ている");
		int[] geo = context.computeOnClient(mc -> {
			LiveListScreen g = list(mc);
			return new int[] {g.preview().getWidth(), g.width - 2 * MenuScreen.MARGIN, g.preview().getY() + g.preview().getHeight(), g.commentList().getY(),
					g.composer().field().frameX(), MenuScreen.MARGIN, g.preview().getY(), g.top()};
		});
		check(geo[0] > splitW && geo[0] >= geo[1] - 12, "拡大してもプレビューが全幅にならない: " + geo[0] + " (左右のとき " + splitW + "・中身 " + geo[1] + ")");
		check(geo[3] > geo[2], "コメントがプレビューの下に無い");
		check(geo[4] == geo[5], "入力欄が左端から始まらない: " + geo[4]);
		check(geo[6] <= geo[7] + 5, "プレビューが画面の上端から始まらない (題の段が残っている): " + geo[6]);
		// 「←」・操作・縮小はプレビューの外 (すぐ下の 1 行。プレビューには何も重ねない)、入力欄はその下
		check(context.computeOnClient(mc -> {
			LiveListScreen g = list(mc);
			PreviewView p = g.preview();
			return below(g.backButton(), p) && below(g.rowLoadButton(), p) && below(g.rowOpenButton(), p) && below(g.shrinkButton(), p)
					&& g.shrinkButton().getY() == g.backButton().getY() && g.rowLoadButton().getY() == g.backButton().getY()
					&& g.composer().field().frameY() > g.backButton().getY() + RButton.H;
		}), "「←」・操作・縮小がプレビューの下の 1 行に無い");

		// 拡大したまま書ける (入力欄はプレビューの下に動いた)
		String text = "拡大したまま書いたコメント";
		double[] f = context.computeOnClient(mc -> {
			var field = list(mc).composer().field();
			return new double[] {field.frameX() + field.frameW() / 2.0, field.frameY() + field.frameH() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, f[0], f[1]);
		context.waitFor(mc -> list(mc).composer().field().isFocused(), TIMEOUT);
		context.getInput().typeChars(text);
		click(context, mc -> list(mc).composer().button());
		context.waitFor(mc -> list(mc).commentRows().stream().anyMatch(r -> text.equals(r.comment().text)), TIMEOUT);
		check(context.computeOnClient(mc -> list(mc).composer().field().getValue()).isEmpty(), "送ったのに欄が空にならない");
		LiveClientGameTestShots.neutralCursor(context);
		context.waitFor(mc -> list(mc).preview().shownCircuit() != null, TIMEOUT);
		context.takeScreenshot("redtact-live-icons-7-expanded");

		// 縮小のボタン (プレビューの右上) で戻ると帯も戻る。重ねたボタンは下のプレビューより先に押せる
		click(context, mc -> list(mc).shrinkButton());
		context.waitFor(mc -> !LiveListScreen.expanded() && list(mc).detail() && list(mc).sectionButton(MenuSection.LIVE) != null, TIMEOUT);
		// Esc でも戻る (拡大している間はメニューを閉じない)
		expand(context);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> !LiveListScreen.expanded() && mc.gui.screen() instanceof LiveListScreen l && l.detail() && l.sectionButton(MenuSection.LIVE) != null,
				TIMEOUT);

		// 同じ大きさで、左右に分けた詳細と拡大した詳細の回路を描く倍率 (既定の向き) を比べる。拡大したら大きくなる
		int[][] sizes = {{960, 540, 2}, {1280, 720, 2}, {1920, 1080, 2}};
		float[] splitFit = new float[sizes.length];

		for (int i = 0; i < sizes.length; i++) {
			LiveClientGameTestShots.resize(context, sizes[i]);
			context.waitFor(mc -> list(mc).preview() != null && list(mc).preview().shownCircuit() != null, TIMEOUT);
			splitFit[i] = context.computeOnClient(IconChecks::fit);
			context.takeScreenshot("redtact-live-icons-7-split-" + sizeLabel(sizes[i]));
		}

		LiveClientGameTestShots.restore(context);
		expand(context);

		for (int i = 0; i < sizes.length; i++) {
			LiveClientGameTestShots.resize(context, sizes[i]);
			context.waitFor(mc -> list(mc).preview() != null && list(mc).preview().shownCircuit() != null, TIMEOUT);
			float big = context.computeOnClient(IconChecks::fit);
			context.takeScreenshot("redtact-live-icons-7-expanded-" + sizeLabel(sizes[i]));
			LOGGER.info("preview fit at {}: split {} / expanded {} (x{})", sizeLabel(sizes[i]), splitFit[i], big, String.format("%.2f", big / splitFit[i]));
			check(big > splitFit[i] * 1.05F, "拡大しても回路が大きくならない (" + sizeLabel(sizes[i]) + "): " + splitFit[i] + " → " + big);
		}

		LiveClientGameTestShots.restore(context);
		click(context, mc -> list(mc).shrinkButton());
		context.waitFor(mc -> !LiveListScreen.expanded() && list(mc).detail(), TIMEOUT);
		context.runOnClient(mc -> list(mc).backToList());
		context.runOnClient(mc -> RedtactLive.get().sessions().close());
		context.waitFor(mc -> RedtactLive.get().sessions().active() == null, TIMEOUT);
	}

	/** ヘッダの ▲ の左の拡大を押す (左右に分けた詳細から)。 */
	private static void expand(ClientGameTestContext context) {
		click(context, mc -> ((MenuScreen) mc.gui.screen()).headerToggle(LiveListScreen.EXPAND_TOGGLE));
		context.waitFor(mc -> LiveListScreen.expanded() && mc.gui.screen() instanceof LiveListScreen l && l.detail() && l.preview() != null
				&& l.commentList() != null && l.shrinkButton() != null, TIMEOUT);
	}

	/** ボタンがプレビューの外の下にあるか (横はプレビューの板の幅の中)。 */
	private static boolean below(@Nullable RButton b, PreviewView p) {
		return b != null && b.getY() >= p.getY() + p.getHeight() && b.getX() >= MenuScreen.MARGIN
				&& b.getX() + b.getWidth() <= p.getX() + p.getWidth() + 5;
	}

	/** 保管庫のサブヘッダー: 上へ・開く・フォルダを開く がアイコンで、パス欄と検索欄が広い。 */
	private static void libraryBar(ClientGameTestContext context) {
		LiveClientGameTestShots.resize(context, new int[] {960, 540, 2}); // 480×270 (1080p・GUI 4)
		context.runOnClient(mc -> Menu.open(MenuTab.LIBRARY));
		context.waitForScreen(LibraryScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof LibraryScreen l && l.pathField() != null && l.searchField() != null, TIMEOUT);
		int[] w = context.computeOnClient(mc -> {
			LibraryScreen l = (LibraryScreen) mc.gui.screen();
			check(l.upButton().icon() == Icon.UP && l.goButton().icon() == Icon.FORWARD && l.openFolderButton().icon() == Icon.FOLDER, "サブヘッダーのボタンがアイコンでない");
			return new int[] {l.pathField().frameW(), l.searchField().frameW()};
		});
		LOGGER.info("library bar at 480x270: path {} / search {}", w[0], w[1]);
		// 前は 480 幅で 123・123 (上へ・開く・フォルダを開く が字)
		check(w[0] >= 150 && w[1] >= 150, "パス欄と検索欄が広くならない: " + w[0] + " / " + w[1]);
		LiveClientGameTestShots.neutralCursor(context);
		context.takeScreenshot("redtact-live-icons-8-library-480x270");
		LiveClientGameTestShots.restore(context);
	}

	// ---- 補助

	private static CircuitRecord saveRange(ClientGameTestContext context, String name, Box box) {
		return context.computeOnClient(mc -> {
			RedtactLive.get().selection().set(box, "minecraft:overworld");
			CircuitRecord r = RedtactLive.get().saveRange(name);
			check(r != null, "範囲を保存できない: " + name);
			RedtactLive.get().savedRanges().clearPick();
			return r;
		});
	}

	private static SessionScreen session(Minecraft mc) {
		return (SessionScreen) mc.gui.screen();
	}

	private static SettingsScreen settings(Minecraft mc) {
		return (SettingsScreen) mc.gui.screen();
	}

	private static LiveListScreen list(Minecraft mc) {
		return (LiveListScreen) mc.gui.screen();
	}

	/** 詳細のプレビューで回路を描く倍率 (1 ブロックの大きさ。いまの向きで)。 */
	private static float fit(Minecraft mc) {
		var p = list(mc).preview();
		var c = p.shownCircuit();
		return c == null ? 0 : c.model().fitScale(p.yaw(), p.pitch(), p.getWidth() - 10, p.getHeight() - 10);
	}

	/** GUI の大きさ (例 480x270)。 */
	private static String sizeLabel(int[] size) {
		return (size[0] / size[2]) + "x" + (size[1] / size[2]);
	}

	/** 部品の真ん中を本物のマウスで押す。 */
	private static void click(ClientGameTestContext context, Function<Minecraft, AbstractWidget> widget) {
		double[] p = context.computeOnClient(mc -> {
			AbstractWidget w = widget.apply(mc);
			check(w != null, "押す部品が無い (画面: " + mc.gui.screen() + ")");
			check(w.active, "押せない部品: " + w.getMessage().getString());
			return new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
		context.waitTicks(2);
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}
}
