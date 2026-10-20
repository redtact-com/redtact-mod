package com.redtact.live.gametest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.capture.RegionCapture;
import com.redtact.live.config.Configs;
import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.gui.CommentsScreen;
import com.redtact.live.gui.LiveListScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.MenuSection;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.RangeScreen;
import com.redtact.live.gui.SessionScreen;
import com.redtact.live.gui.SettingsScreen;
import com.redtact.live.gui.cards.LiveCard;
import com.redtact.live.gui.rows.CommentRow;
import com.redtact.live.gui.rows.LiveRow;
import com.redtact.live.hud.HudRenderer;
import com.redtact.live.i18n.UiLanguage;
import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.litematic.LitematicaBitArray;
import com.redtact.live.live.LiveText;
import com.redtact.live.net.Api;
import com.redtact.live.net.Json;
import com.redtact.live.net.LiveApi;
import com.redtact.live.gui.kit.PreviewView;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.preview.PreviewCircuit;
import com.redtact.live.selection.Box;
import com.redtact.live.session.SessionManager;
import com.redtact.live.state.CircuitState;
import com.redtact.live.state.StateJson;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.StoredComment;
import com.redtact.live.tool.SelectionTool;
import net.fabricmc.fabric.mixin.client.gametest.input.MouseHandlerAccessor;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.level.block.LeverBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 実ワールドでの通し確認。中継サーバ (server/) を立ててから
 * {@code REDTACT_LIVE_SERVER=http://localhost:8797 ./gradlew runClientGameTest}。
 * {@code REDTACT_LIVE_LIVE_LIBRARY_ONLY=1} を足すと、開催中の回路を保管庫へ・redtact に投稿 (#73、{@link LiveLibraryChecks}) だけを確かめる
 * (-Pmcef なら本物の Chromium で redtact タブの左右分割まで)。{@code REDTACT_LIVE_SEARCH_ONLY=1} なら開催中の一覧の検索・並べ替え・
 * フォロー中 (#90、{@link SearchChecks}) だけ。
 *
 * <p>キー ([ ] K) とチャットへの入力 (/rlive ...) は実際の入力として送る。
 */
@SuppressWarnings("UnstableApiUsage")
public class LiveClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final String NAME = "gametest 回路";
	private static final int TIMEOUT = 20 * 30;
	private static final String HOST_NAME = "テスト ホスト";

	@Override
	public void runTest(ClientGameTestContext context) {
		String server = System.getenv("REDTACT_LIVE_SERVER");

		if (System.getenv("REDTACT_LIVE_PERF_ONLY") != null) {
			return; // 重さの計測 (BrowserPerfClientGameTest) だけ
		}

		if (System.getenv("REDTACT_LIVE_SIGNIN_ONLY") != null) {
			LOGGER.info("REDTACT_LIVE_SIGNIN_ONLY なので閲覧者のサインイン (SignInClientGameTest) だけを確かめる");
			return;
		}

		if (System.getenv("REDTACT_LIVE_LIBRARY_ONLY") != null) {
			LOGGER.info("REDTACT_LIVE_LIBRARY_ONLY なので redtact の保管庫 (LibraryClientGameTest) だけを確かめる");
			return;
		}

		if (server == null || server.isBlank()) {
			throw new AssertionError("REDTACT_LIVE_SERVER (中継サーバの URL) を指定してください");
		}

		// 前の版の config.json からの引き継ぎ (別の場所に置いて試す)
		context.runOnClient(mc -> checkMigration(mc));

		context.runOnClient(mc -> {
			Configs.Generic.SERVER_URL.setValueFromString(server);
			Configs.Generic.USER.setValueFromString(HOST_NAME); // 日本語・空白入りの表示名 (X-Live-User は percent-encode)
			Configs.Generic.AUTO_SYNC.setBooleanValue(true);
			Configs.Generic.AUTO_SYNC_QUIET_MS.setIntegerValue(3000);
			Configs.Generic.AUTO_SYNC_MAX_DELAY_MS.setIntegerValue(10000);
			Configs.Generic.LOCAL_SNAPSHOT_INTERVAL_MS.setIntegerValue(300000);
			// 確かめる文は日本語 (gametest のマイクラは英語なので、自動のままだと英語になる。#79)
			Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.JA);
			Configs.saveToFile();
		});

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("gamemode creative @a");
			buildCircuit(sp);
			context.waitTicks(10);

			if (liveLibraryOnly()) {
				LOGGER.info("REDTACT_LIVE_LIVE_LIBRARY_ONLY なので開催中の回路を保管庫へ・redtact に投稿 (#73) だけを確かめる");
				LiveLibraryChecks.run(context, sp, server);
				return;
			}

			if (System.getenv("REDTACT_LIVE_COMMENTS_ONLY") != null) {
				// MOD からのコメント (#74) だけを確かめる (通しの確認は長いので、直すあいだ用)
				CommentChecks.run(context, sp, server, HOST_NAME);
				return;
			}

			if (System.getenv("REDTACT_LIVE_LANG_ONLY") != null) {
				// 英語の画面 (#79) だけを確かめる
				LanguageChecks.run(context);
				return;
			}

			if (System.getenv("REDTACT_LIVE_ICONS_ONLY") != null) {
				// ボタンのアイコン化・範囲の削除・設定の一括リセット・詳細の拡大 (#77) だけを確かめる
				IconChecks.run(context, sp);
				return;
			}

			if (System.getenv("REDTACT_LIVE_BRANCH_ONLY") != null) {
				// 分岐 (#13) と、親が開き直したときの付け直し (#87) だけを確かめる
				checkBranchChild(context, sp, server);
				checkBranchParent(context, sp, server);
				return;
			}

			if (searchOnly()) {
				// 開催中の一覧の検索・並べ替え・フォロー中 (#90) だけを確かめる
				SearchChecks.run(context, sp, server);
				return;
			}

			checkSelectionTool(context, sp);

			// ---- 範囲指定: 真下を向いて [ と ] を押す
			sp.getServer().runCommand("tp @a 0.5 -50 0.5 0 90");
			context.waitTicks(5);
			keys(context, InputConstants.KEY_LBRACKET); // ホットキー setPos1
			context.waitTicks(2);
			sp.getServer().runCommand("tp @a 5.5 -50 3.5 0 90");
			context.waitTicks(5);
			keys(context, InputConstants.KEY_RBRACKET);
			context.waitTicks(2);
			Box sel = context.computeOnClient(mc -> RedtactLive.get().selection().box());
			check(sel != null, "選択できていない");
			LOGGER.info("selection = {} {}", sel, sel.sizeText());
			check(sel.equals(new Box(0, -60, 0, 5, -59, 3)), "選択範囲が想定と違う: " + sel);

			sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
			context.waitTicks(10);
			context.takeScreenshot("redtact-live-1-selection");

			// ---- 上限超えは open を拒否する
			context.runOnClient(mc -> {
				RedtactLive.get().setPos(1, new BlockPos(0, -60, 0), "minecraft:overworld");
				RedtactLive.get().setPos(2, new BlockPos(300, -60, 0), "minecraft:overworld");
			});
			chat(context, "/rlive open too big");
			context.waitTicks(20);
			check(sessions(context).active() == null, "上限超えなのにセッションが開いた");
			check(findCircuit(context, "too big").isEmpty(), "上限超えなのに回路が保存された");
			context.runOnClient(mc -> RedtactLive.get().selection().set(sel, "minecraft:overworld"));

			// ---- open (チャットに入力) -> 最初の push (全体 = PUT) が自動で走る。自動同期は既定で on
			chat(context, "/rlive open " + NAME);
			context.waitFor(mc -> {
				SessionManager.Active a = sessions(mc).active();
				return a != null && a.lastVersion() >= 1 && !sessions(mc).isBusy();
			}, TIMEOUT);
			SessionManager.Active active = sessions(context).active();
			LOGGER.info("session {} {}", active.sessionId, active.shareUrl);
			// 開発環境のアカウントは Mojang に join できないので、表示名 (確認なし) で開く
			check(!active.hostVerified() && active.hostName().equals(HOST_NAME), "開発環境なのに確認済み / ホスト名: " + active.hostName());
			check(context.computeOnClient(mc -> SessionScreen.hostLine(sessions(mc).active())).contains("確認なし"), "セッションタブに確認なしが出ない");
			check(httpGetString(server + "/api/sessions/" + active.sessionId).contains("\"hostVerified\":false"), "サーバの hostVerified が false でない");
			check(context.computeOnClient(mc -> Configs.Generic.AUTO_SYNC.getBooleanValue()), "自動同期が on でない");
			checkPush(context, SessionManager.PushKind.FULL, 1, -1, -1);
			verifyServerState(context, server, active.sessionId, sel, 1, 13);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-2-open");
			CircuitRecord rec = findCircuit(context, NAME).orElseThrow();
			Path v1 = latestSnapshot(context, rec.circuitId);
			check(v1.getFileName().toString().endsWith("-v1.litematic"), "スナップショットの名前: " + v1);
			verifySnapshot(context, v1, sel, true);

			// ==== 自動同期 ====
			// 何もしなければ、変化が無いので送られない (回路が落ち着いていること)
			context.waitTicks(20 * 4);
			check(version(context) == 1, "変化が無いのに自動で送られた: v" + version(context));

			// サーバ側コマンドで範囲内を変えて、何もせず待つ -> 静止 3 秒で差分が送られる
			sp.getServer().runCommand("setblock 3 -59 1 minecraft:glass");
			long t0 = System.currentTimeMillis();
			context.waitFor(mc -> sessions(mc).active().lastVersion() >= 2 && !sessions(mc).isBusy(), TIMEOUT);
			long quietElapsed = System.currentTimeMillis() - t0;
			LOGGER.info("auto push after {} ms (quiet)", quietElapsed);
			checkPush(context, SessionManager.PushKind.DIFF, 2, 1, 0);
			check(sessions(context).lastPush().trigger() == SessionManager.Trigger.AUTO, "自動の push でない");
			check(quietElapsed >= 2900 && quietElapsed <= 4500, "静止からの自動 push までの時間: " + quietElapsed + " ms");
			verifyServerState(context, server, active.sessionId, sel, 2, 13);
			context.takeScreenshot("redtact-live-2b-auto");

			// 変化が止まらない (0.5 秒ごとに切り替え続ける) -> 最初の変化から 10 秒で送られる
			long t1 = System.currentTimeMillis();
			int toggles = 0;

			while (version(context) < 3) {
				// 直前に送った状態 (glass) とは必ず違う 2 種で切り替える (同じだと「変化なし」で版が上がらない)
				sp.getServer().runCommand("setblock 3 -59 1 minecraft:" + (toggles++ % 2 == 0 ? "stone" : "dirt"));
				context.waitTicks(10);
				check(System.currentTimeMillis() - t1 < 14_000, "変化が続くと自動で送られない");
			}

			long maxElapsed = System.currentTimeMillis() - t1;
			LOGGER.info("auto push after {} ms while changing ({} toggles)", maxElapsed, toggles);
			check(maxElapsed >= 9_500 && maxElapsed <= 11_000, "変化が続くときの自動 push までの時間: " + maxElapsed + " ms");
			check(sessions(context).lastPush().trigger() == SessionManager.Trigger.AUTO, "自動の push でない");
			// 止めたあとの最後の変化も、静止 3 秒で送られて揃う
			context.waitFor(mc -> !sessions(mc).isBusy() && !RedtactLive.get().autoSync().debouncer().isDirty() && sessions(mc).lastPush() != null
					&& sessions(mc).lastPush().version() == version(mc), TIMEOUT);
			context.waitTicks(20 * 4);
			verifyServerState(context, server, active.sessionId, sel, version(context), 13);

			// /rlive auto off では送られない。on に戻すと溜まっていた変化が送られる
			chat(context, "/rlive auto off");
			check(!context.computeOnClient(mc -> Configs.Generic.AUTO_SYNC.getBooleanValue()), "auto off にならない");
			int beforeOff = version(context);
			sp.getServer().runCommand("setblock 3 -59 1 minecraft:redstone_block");
			context.waitTicks(20 * 5);
			check(version(context) == beforeOff, "auto off なのに送られた");
			// 自動同期の切り替えはホットキー (R を押しながら A) でも
			keys(context, InputConstants.KEY_R, InputConstants.KEY_A);
			check(context.computeOnClient(mc -> Configs.Generic.AUTO_SYNC.getBooleanValue()), "R+A で自動同期が on にならない");
			context.waitFor(mc -> version(mc) > beforeOff && !sessions(mc).isBusy(), TIMEOUT);
			verifyServerState(context, server, active.sessionId, sel, version(context), 13);

			// ブロックはそのままでチェストの中身だけ変える (統合サーバの setChanged を拾う) -> ブロックエンティティ 1 件の差分
			int beforeChest = version(context);
			sp.getServer().runCommand("item replace block 5 -60 1 container.0 with minecraft:redstone 20");
			context.waitFor(mc -> version(mc) > beforeChest && !sessions(mc).isBusy(), TIMEOUT);
			checkPush(context, SessionManager.PushKind.DIFF, beforeChest + 1, 0, 0);
			check(sessions(context).lastPush().blockEntities() == 1, "チェストの中身の差分: " + sessions(context).lastPush());
			verifyServerState(context, server, active.sessionId, sel, beforeChest + 1, 20);
			sp.getServer().runCommand("item replace block 5 -60 1 container.0 with minecraft:redstone 13");
			context.waitFor(mc -> version(mc) > beforeChest + 1 && !sessions(mc).isBusy(), TIMEOUT);
			verifyServerState(context, server, active.sessionId, sel, beforeChest + 2, 13);

			// 自動の push を短い間隔で何度か起こしても、ローカルの litematic は open 直後の 1 つだけ (既定は 5 分おき)
			LOGGER.info("auto pushes so far: v2..v{}, local snapshots: {}", version(context), countSnapshots(context, rec.circuitId));
			check(version(context) >= 5, "自動の push が想定より少ない: v" + version(context));
			check(countSnapshots(context, rec.circuitId) == 1, "自動の push でスナップショットが増えた: " + countSnapshots(context, rec.circuitId));

			// ここからは手動の push を確かめるので、自動同期は止めておく
			chat(context, "/rlive auto off");
			int base = version(context);

			// ---- 1 ブロックだけ変えて K -> 差分 (PATCH) で changes が 1 件
			sp.getServer().runCommand("setblock 2 -59 3 minecraft:gold_block");
			context.waitTicks(5);
			pushWithKey(context, base + 1);
			checkPush(context, SessionManager.PushKind.DIFF, base + 1, 1, 0);
			check(sessions(context).lastPush().trigger() == SessionManager.Trigger.MANUAL, "手動の push でない");
			verifyServerState(context, server, active.sessionId, sel, base + 1, 13);
			Path v2 = latestSnapshot(context, rec.circuitId);
			check(v2.getFileName().toString().endsWith("-v" + (base + 1) + ".litematic"), "スナップショット: " + v2);
			verifySnapshot(context, v2, sel, true); // ローカルの litematic は差分でも全体

			// ---- 変化なしなら送らない (版も上がらず、ローカルにも残さない)
			int snapshotsBefore = countSnapshots(context, rec.circuitId);
			context.runOnClient(mc -> RedtactLive.get().sessions().push());
			context.waitFor(mc -> !sessions(mc).isBusy() && sessions(mc).lastPush() != null
					&& sessions(mc).lastPush().kind() == SessionManager.PushKind.UNCHANGED, TIMEOUT);
			check(version(context) == base + 1, "変化なしで版が上がった");
			check(countSnapshots(context, rec.circuitId) == snapshotsBefore, "変化なしでスナップショットが増えた");

			// ---- チェストを壊す -> ブロック 1 件 + removedBlockEntities 1 件
			sp.getServer().runCommand("setblock 5 -60 1 minecraft:air");
			context.waitTicks(5);
			pushWithKey(context, base + 2);
			checkPush(context, SessionManager.PushKind.DIFF, base + 2, 1, 1);
			verifyServerState(context, server, active.sessionId, sel, base + 2, 0);

			// ---- サーバの版が MOD の知らないうちに進んでいたら (別の PUT)、差分は 409 -> MOD が全体を送り直す
			String hostToken = findCircuit(context, NAME).orElseThrow().session(active.sessionId).hostToken;
			CircuitState foreign = new CircuitState(sel.sizeX(), sel.sizeY(), sel.sizeZ(), List.of("minecraft:air"), new int[(int) sel.volume()], Map.of());

			try {
				check(new LiveApi(LiveApi.newHttpClient(), () -> server, () -> "gametest").putState(active.sessionId, hostToken, foreign).version == base + 3, "外からの PUT");
			} catch (com.redtact.live.net.ApiException e) {
				throw new AssertionError(e.describe(), e);
			}

			sp.getServer().runCommand("setblock 1 -59 1 minecraft:glass");
			context.waitTicks(5);
			pushWithKey(context, base + 4);
			checkPush(context, SessionManager.PushKind.FULL, base + 4, -1, -1);
			check(sessions(context).lastPush().reason().contains("v" + (base + 3)), "409 から全体に切り替えた理由: " + sessions(context).lastPush().reason());
			verifyServerState(context, server, active.sessionId, sel, base + 4, 0);

			// ---- 範囲を変えて push -> 全体 (PUT)
			Box sel2 = new Box(0, -60, 0, 6, -59, 3);
			context.runOnClient(mc -> RedtactLive.get().setPos(2, new BlockPos(6, -59, 3), "minecraft:overworld"));
			pushWithKey(context, base + 5);
			checkPush(context, SessionManager.PushKind.FULL, base + 5, -1, -1);
			check("範囲の変更".equals(sessions(context).lastPush().reason()), "全体にした理由: " + sessions(context).lastPush().reason());
			verifyServerState(context, server, active.sessionId, sel2, base + 5, 0);
			verifySnapshot(context, latestSnapshot(context, rec.circuitId), sel2, false);

			// ---- 閲覧者のコメント -> チャット + トースト + comments.jsonl + ack
			postComment(server, active.sessionId, "viewer1", "リピーターを 2 tick にすると安定します");
			context.waitFor(mc -> readComments(rec.circuitId).stream().anyMatch(c -> "viewer1".equals(c.author)), TIMEOUT);
			context.waitTicks(30); // トーストが滑り込み終わるまで
			context.takeScreenshot("redtact-live-3-comment-toast");
			check(context.computeOnClient(mc -> sessions(mc).unread()) == 1, "HUD の未読が 1 でない");
			List<String> hud = context.computeOnClient(mc -> HudRenderer.lines(RedtactLive.get()));
			LOGGER.info("HUD: {}", hud);
			check(hud.size() == 2 && hud.get(0).contains("開催中") && hud.get(0).contains("v" + version(context)) && hud.get(1).contains("未読 1"),
					"HUD の行: " + hud);
			String sessionJson = httpGetString(server + "/api/sessions/" + active.sessionId);
			check(sessionJson.contains("\"hostOnline\":true"), "hostOnline になっていない: " + sessionJson);
			check(sessionJson.contains("\"host\":\"" + HOST_NAME + "\""), "host が表示名になっていない: " + sessionJson);

			context.waitTicks(20 * 7); // トーストが消えるのを待つ
			context.takeScreenshot("redtact-live-4-hud");

			// ---- メニュー: R を押しながら L で開く (最初は redtact タブ)。メインタブは redtact・live・保管庫・設定で、live に未読の数 (#51)
			keys(context, InputConstants.KEY_R, InputConstants.KEY_L);
			context.waitFor(mc -> mc.gui.screen() instanceof MenuScreen m && m.tab() == MenuTab.REDTACT, TIMEOUT);
			context.waitTicks(5);
			List<String> sections = context.computeOnClient(mc -> ((MenuScreen) mc.gui.screen()).sectionLabels());
			check(sections.equals(List.of("redtact", "live 1", "保管庫", "設定")), "メインタブ: " + sections);
			context.takeScreenshot("redtact-live-menu-redtact");
			// メインタブの live (本物のクリック) で live の子タブ。最初はセッション
			LibraryClientGameTest.clickSection(context, MenuSection.LIVE);
			context.waitForScreen(SessionScreen.class);
			List<String> subTabs = context.computeOnClient(mc -> ((MenuScreen) mc.gui.screen()).subTabLabels());
			check(subTabs.equals(List.of("セッション", "開催中", "範囲", "コメント 1")), "live の子タブ: " + subTabs);
			// ヘッダの右端のトグル (本物のクリック) で子タブの段を隠すと中身が上がり、もう一度押すと戻る (#51)
			int shownTop = context.computeOnClient(mc -> ((MenuScreen) mc.gui.screen()).top());
			LibraryClientGameTest.clickSubBarToggle(context);
			context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen m && m.subTabButton(MenuTab.RANGE) == null, TIMEOUT);
			int hiddenTop = context.computeOnClient(mc -> ((MenuScreen) mc.gui.screen()).top());
			check(hiddenTop == shownTop - (MenuScreen.SUB_H + 1), "子タブの段を隠しても中身が上がらない: " + shownTop + " → " + hiddenTop);
			check(context.computeOnClient(mc -> ((MenuScreen) mc.gui.screen()).subBarToggle().getMessage().getString()).equals("▼"), "トグルが ▼ にならない");
			context.waitTicks(3);
			context.takeScreenshot("redtact-live-menu-session-subbar-hidden");
			LibraryClientGameTest.clickSubBarToggle(context);
			context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen m && m.subTabButton(MenuTab.RANGE) != null && m.top() == shownTop, TIMEOUT);
			List<String> status = context.computeOnClient(mc -> SessionScreen.statusLines(sessions(mc).active(), sessions(mc)));
			check(status.get(0).contains("開催中") && status.get(0).contains(NAME), "セッションタブの状態: " + status);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-menu-session");
			// ふだんは要点 1 行だけ。「詳細 ▼」で範囲・ホスト・同期の設定・共有リンク・サーバ・最近の出来事が出る (#34)
			check(!context.computeOnClient(mc -> SessionScreen.detailsOpen()), "詳細が最初から開いている");
			clickWidget(context, s -> s.detailsButton().getX() + s.detailsButton().getWidth() / 2, s -> s.detailsButton().getY() + 8);
			context.waitFor(mc -> SessionScreen.detailsOpen() && mc.gui.screen() instanceof SessionScreen, TIMEOUT);
			context.waitTicks(3);
			context.takeScreenshot("redtact-live-menu-session-details");
			clickWidget(context, s -> s.detailsButton().getX() + s.detailsButton().getWidth() / 2, s -> s.detailsButton().getY() + 8);
			context.waitFor(mc -> !SessionScreen.detailsOpen(), TIMEOUT);
			// 子タブの範囲へ (本物のクリック)。メインタブの設定へ移ってから live に戻ると、最後に見ていた子タブ (範囲) が開く
			LibraryClientGameTest.clickSubTab(context, MenuTab.RANGE);
			context.waitForScreen(RangeScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-menu-range");
			LibraryClientGameTest.clickSection(context, MenuSection.SETTINGS);
			context.waitForScreen(SettingsScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-menu-settings");
			// 中継サーバと redtact の URL は設定の画面に出さない (設定ファイルでだけ変える。#51)
			check(context.computeOnClient(mc -> {
				SettingsScreen st = (SettingsScreen) mc.gui.screen();
				return st.cardFor(Configs.Generic.SERVER_URL) == null && st.cardFor(Configs.Generic.REDTACT_URL) == null
						&& st.cardFor(Configs.Generic.USER) != null;
			}), "設定の画面にサーバ URL か redtact の URL が出ている");
			checkClipTooltips(context);
			checkHotkeyEditor(context);
			LibraryClientGameTest.clickSection(context, MenuSection.LIVE);
			context.waitForScreen(RangeScreen.class);
			context.waitTicks(3);
			LibraryClientGameTest.clickSubTab(context, MenuTab.COMMENTS);
			context.waitForScreen(CommentsScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("redtact-live-menu-comments");
			List<CommentRow> rows = context.computeOnClient(mc -> ((CommentsScreen) mc.gui.screen()).rows());
			check(rows.size() == 1 && rows.get(0).comment().author.equals("viewer1") && rows.get(0).unread(), "コメントタブの行: " + rows);
			// 開いたら既読になり、live とコメントの数が消える
			context.waitFor(mc -> sessions(mc).unread() == 0, TIMEOUT);
			context.waitFor(mc -> mc.gui.screen() instanceof MenuScreen m && m.sectionLabels().get(1).equals("live")
					&& m.subTabLabels().get(3).equals("コメント"), TIMEOUT);
			check(findCircuit(context, NAME).orElseThrow().lastReadCommentId != null, "既読が記録されていない");
			context.setScreen(() -> null);
			context.waitTicks(2);

			// /rlive status はチャットに出さずにメニューを開く
			chat(context, "/rlive status");
			context.waitForScreen(SessionScreen.class);
			context.setScreen(() -> null);
			context.waitTicks(2);

			// ---- close -> サーバ側は 404、circuit.json に終了を記録
			// 最後に自動の push をしてから close -> 保存していなかった最新の版が残る
			int beforeLast = version(context);
			int snapshotsBeforeClose = countSnapshots(context, rec.circuitId);
			chat(context, "/rlive auto on");
			sp.getServer().runCommand("setblock 6 -59 0 minecraft:stone");
			context.waitFor(mc -> version(mc) > beforeLast && !sessions(mc).isBusy(), TIMEOUT);
			int lastAuto = version(context);
			check(sessions(context).lastPush().trigger() == SessionManager.Trigger.AUTO, "最後の push が自動でない");
			check(countSnapshots(context, rec.circuitId) == snapshotsBeforeClose, "自動の push で保存された");
			chat(context, "/rlive auto off");
			chat(context, "/rlive close");
			context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
			check(httpStatus(server + "/api/sessions/" + active.sessionId) == 404, "close 後もサーバにセッションが残っている");
			check(countSnapshots(context, rec.circuitId) == snapshotsBeforeClose + 1, "close で最新の版が保存されていない");
			Path flushed = latestSnapshot(context, rec.circuitId);
			check(flushed.getFileName().toString().endsWith("-v" + lastAuto + ".litematic"), "close で残した版: " + flushed);
			Box sel3 = new Box(0, -60, 0, 6, -59, 3);
			verifySnapshot(context, flushed, sel3, false);
			int snapshotsSession1 = countSnapshots(context, rec.circuitId);
			check(httpStatus(server + "/api/sessions/" + active.sessionId + "/state") == 404, "close 後もサーバに回路が残っている");
			CircuitRecord closed = findCircuit(context, NAME).orElseThrow();
			CircuitRecord.SessionRecord s1 = closed.session(active.sessionId);
			check(s1.endedAt != null && "host_closed".equals(s1.endReason) && s1.hostToken == null, "終了の記録: " + Json.GSON.toJson(s1));

			// ---- 再開 (選択なし -> 保存済みの範囲を使う)。コメント履歴はローカルに残る
			chat(context, "/rlive sel clear");
			chat(context, "/rlive open " + NAME);
			context.waitFor(mc -> {
				SessionManager.Active a = sessions(mc).active();
				return a != null && a.lastVersion() >= 1 && !sessions(mc).isBusy();
			}, TIMEOUT);
			SessionManager.Active reopened = sessions(context).active();
			check(!reopened.sessionId.equals(active.sessionId), "新しいセッションになっていない");
			check(reopened.circuitId.equals(rec.circuitId), "同じ回路 ID を使っていない");
			check(reopened.region().equals(sel2), "保存済みの範囲を使っていない: " + reopened.region());
			check(readComments(rec.circuitId).size() == 1, "コメント履歴が残っていない");
			checkPush(context, SessionManager.PushKind.FULL, 1, -1, -1);
			verifyServerState(context, server, reopened.sessionId, sel2, 1, 0);
			// ローカルの litematic: 1 回目のセッションの分 + 再開後の v1
			check(countSnapshots(context, rec.circuitId) == snapshotsSession1 + 1, "スナップショットの数: " + countSnapshots(context, rec.circuitId));

			// ---- ゲーム終了相当 (接続だけ切る) -> 留守中のコメント -> 同じ名前で open すると同じセッションに再接続
			context.runOnClient(mc -> RedtactLive.get().sessions().suspend());
			context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
			context.waitTicks(10);
			postComment(server, reopened.sessionId, "viewer2", "留守中のコメント");
			check(readComments(rec.circuitId).size() == 1, "切断中なのにコメントが届いた");
			chat(context, "/rlive open " + NAME);
			context.waitFor(mc -> readComments(rec.circuitId).stream().anyMatch(c -> "viewer2".equals(c.author)), TIMEOUT);
			SessionManager.Active resumed = sessions(context).active();
			check(resumed != null && resumed.sessionId.equals(reopened.sessionId), "同じセッションに再接続していない");
			check(findCircuit(context, NAME).orElseThrow().sessions.size() == 2, "再接続なのにセッションが増えた");
			// 再接続しても MOD は前に送った状態を知らないので、開いた直後の push は全体
			context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 2 && !sessions(mc).isBusy(), TIMEOUT);
			checkPush(context, SessionManager.PushKind.FULL, 2, -1, -1);
			check("再接続".equals(sessions(context).lastPush().reason()), "再接続後の理由: " + sessions(context).lastPush().reason());
			verifyServerState(context, server, reopened.sessionId, sel2, 2, 0);
			context.waitTicks(10);
			context.takeScreenshot("redtact-live-5-resumed");
			chat(context, "/rlive close");
			context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
			check(findCircuit(context, NAME).orElseThrow().sessions.size() == 2, "セッション履歴が 2 件でない");
			checkSavedRanges(context, sp, server);
			checkLiveList(context, sp, server);
			SearchChecks.run(context, sp, server); // 検索・並べ替え・フォロー中 (#90。最後に既定に戻す)
			LiveLibraryChecks.run(context, sp, server);
			CommentChecks.run(context, sp, server, HOST_NAME); // MOD からのコメント (#74)
			IconChecks.run(context, sp); // アイコン化・範囲の削除・一括リセット・詳細の拡大 (#77)
			LanguageChecks.run(context); // 英語の画面 (#79。最後に日本語に戻す)
			checkCopy(context, sp, server);
			checkBranchChild(context, sp, server);
			checkBranchParent(context, sp, server);
			checkStrictServer(context, server);
			checkNoChatLines(context);
			LOGGER.info("redtact-live gametest: all checks passed");
		}
	}

	/** 開催中の回路を保管庫へ・redtact に投稿 (#73) だけを確かめるか (ほかの gametest も飛ばす)。 */
	static boolean liveLibraryOnly() {
		return System.getenv("REDTACT_LIVE_LIVE_LIBRARY_ONLY") != null;
	}

	/** 開催中の一覧の検索・並べ替え・フォロー中 (#90) だけを確かめるか (ほかの gametest も飛ばす)。 */
	static boolean searchOnly() {
		return System.getenv("REDTACT_LIVE_SEARCH_ONLY") != null;
	}

	private static void buildCircuit(TestSingleplayerContext sp) {
		String[] cmds = {
			"setblock 0 -60 0 minecraft:lever[face=floor,facing=east,powered=true]",
			"setblock 1 -60 0 minecraft:redstone_wire",
			"setblock 2 -60 0 minecraft:redstone_wire",
			"setblock 3 -60 0 minecraft:repeater[facing=west,delay=3]",
			"setblock 4 -60 0 minecraft:redstone_wire",
			"setblock 4 -60 1 minecraft:comparator[facing=north,mode=subtract]",
			"setblock 5 -60 1 minecraft:chest[facing=west]{Items:[{Slot:0b,id:\"minecraft:redstone\",count:13}]}",
			"setblock 1 -60 3 minecraft:hopper[facing=down]",
			"setblock 2 -60 2 minecraft:redstone_torch",
			"setblock 3 -60 3 minecraft:redstone_lamp",
			"setblock 5 -59 3 minecraft:stone",
			"setblock 0 -59 3 minecraft:sticky_piston[facing=up]",
		};

		for (String c : cmds) {
			sp.getServer().runCommand(c);
		}
	}

	/**
	 * 範囲指定の道具 (羽根)。左クリックで角 1・右クリックで角 2。遠くのブロックにも届き、
	 * 羽根を持っているあいだはブロックを壊さず・置かず・レバーを動かさない。羽根以外・道具 off ではふつうに動く。
	 */
	private static void checkSelectionTool(ClientGameTestContext context, TestSingleplayerContext sp) {
		BlockPos stone = new BlockPos(40, -60, 40);
		BlockPos lever = new BlockPos(44, -59, 40);
		sp.getServer().runCommand("setblock 40 -60 40 minecraft:stone");
		sp.getServer().runCommand("setblock 44 -60 40 minecraft:stone");
		sp.getServer().runCommand("setblock 44 -59 40 minecraft:lever[face=floor,facing=north,powered=false]");
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:feather");
		context.runOnClient(mc -> RedtactLive.get().selection().clear());
		// 遠くの区画へ移るので、目標のチャンクが届いて描かれるまで待つ
		sp.getServer().runCommand("tp @a 40.5 -60 20.5");
		context.waitFor(mc -> mc.level.getChunkSource().getChunkNow(40 >> 4, 40 >> 4) != null && mc.level.getChunkSource().getChunkNow(44 >> 4, 40 >> 4) != null,
				TIMEOUT);
		sp.getConnection().waitForChunksRender();
		context.waitFor(mc -> !mc.level.getBlockState(stone).isAir() && !mc.level.getBlockState(lever).isAir(), TIMEOUT);

		// 遠く (約 20 ブロック、手の届く距離の外) のブロックを羽根で左クリック -> 角 1。石は壊れない
		lookFrom(context, sp, "40.5 -60 20.5", "40.5 -59.5 40.5");
		click(context, InputConstants.MOUSE_BUTTON_LEFT);
		check(stone.equals(context.computeOnClient(mc -> RedtactLive.get().selection().pos1())), "羽根の左クリックで角 1 にならない: "
				+ context.computeOnClient(mc -> RedtactLive.get().selection().pos1() + " active=" + SelectionTool.active(mc) + " target=" + SelectionTool.target(mc, 200)));
		check(!serverBlock(sp, stone).isAir(), "羽根の左クリックで石が壊れた");

		// 遠くのレバーを羽根で右クリック -> 角 2。レバーは切り替わらない
		lookFrom(context, sp, "44.5 -60 20.5", "44.5 -58.85 40.5");
		click(context, InputConstants.MOUSE_BUTTON_RIGHT);
		check(lever.equals(context.computeOnClient(mc -> RedtactLive.get().selection().pos2())), "羽根の右クリックで角 2 にならない: "
				+ context.computeOnClient(mc -> RedtactLive.get().selection().pos2()));
		check(!serverBlock(sp, lever).getValue(LeverBlock.POWERED), "羽根の右クリックでレバーが切り替わった");
		lookFrom(context, sp, "42.5 -57 31", "42 -59.5 40");
		context.waitTicks(10);
		context.takeScreenshot("redtact-live-tool-feather");

		// 手の届く距離でも同じ (石は壊れず、レバーも動かない)
		lookFrom(context, sp, "40.5 -60 37.5", "40.5 -59.5 40.5");
		click(context, InputConstants.MOUSE_BUTTON_LEFT);
		check(!serverBlock(sp, stone).isAir(), "近くでも羽根の左クリックで石が壊れた");
		lookFrom(context, sp, "44.5 -60 37.5", "44.5 -58.85 40.5");
		click(context, InputConstants.MOUSE_BUTTON_RIGHT);
		check(!serverBlock(sp, lever).getValue(LeverBlock.POWERED), "近くでも羽根の右クリックでレバーが切り替わった");

		// チャット入力中は道具として扱わない (角は変えず、クリックは画面に渡る)
		BlockPos beforeChat = context.computeOnClient(mc -> RedtactLive.get().selection().pos1());
		context.getInput().pressKey(options -> options.keyChat);
		context.waitForScreen(ChatScreen.class);
		check(!context.computeOnClient(mc -> SelectionTool.onClick(1)), "チャット入力中なのに道具のクリックを打ち消した");
		check(beforeChat.equals(context.computeOnClient(mc -> RedtactLive.get().selection().pos1())), "チャット入力中なのに角 1 が変わった");
		context.setScreen(() -> null);

		// 空を向いて何も当たらなければ角は変えない
		BlockPos before = context.computeOnClient(mc -> RedtactLive.get().selection().pos1());
		sp.getServer().runCommand("tp @a 40.5 -60 37.5 0 -90");
		context.waitTicks(5);
		click(context, InputConstants.MOUSE_BUTTON_LEFT);
		check(before.equals(context.computeOnClient(mc -> RedtactLive.get().selection().pos1())), "空を向いたのに角 1 が変わった");

		// 羽根以外 (素手) ならふつうに壊れ、レバーも切り替わる。石を持てば置ける
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		context.waitTicks(3);
		lookFrom(context, sp, "40.5 -60 37.5", "40.5 -59.5 40.5");
		click(context, InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		check(serverBlock(sp, stone).isAir(), "素手の左クリックで石が壊れない");
		lookFrom(context, sp, "44.5 -60 37.5", "44.5 -58.85 40.5");
		click(context, InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		check(serverBlock(sp, lever).getValue(LeverBlock.POWERED), "素手の右クリックでレバーが切り替わらない");
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:stone");
		context.waitTicks(3);
		lookFrom(context, sp, "40.5 -60 37.5", "40.5 -59.99 40.5"); // (40,-61,40) の草の上面
		click(context, InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		check(serverBlock(sp, stone).is(net.minecraft.world.level.block.Blocks.STONE), "石を持った右クリックで置けない");

		// 道具 off なら羽根でも反応せず、ふつうに壊れる
		context.runOnClient(mc -> Configs.Generic.TOOL_ENABLED.setBooleanValue(false));
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:feather");
		BlockPos pos1Before = context.computeOnClient(mc -> RedtactLive.get().selection().pos1());
		lookFrom(context, sp, "44.5 -60 37.5", "44.5 -59.5 40.5");
		click(context, InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		check(pos1Before.equals(context.computeOnClient(mc -> RedtactLive.get().selection().pos1())), "道具 off なのに角 1 が変わった");
		check(serverBlock(sp, new BlockPos(44, -60, 40)).isAir() || serverBlock(sp, lever).isAir(), "道具 off なのに羽根の左クリックで壊れない");
		context.runOnClient(mc -> Configs.Generic.TOOL_ENABLED.setBooleanValue(true));
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		context.runOnClient(mc -> RedtactLive.get().selection().clear());
		LOGGER.info("selection tool ok");
	}

	/**
	 * 保存済みの範囲 (#39): 範囲タブとコマンドで名前を付けて保存 (セッションなし) → 羽根の中クリックで選ぶ → セッションタブの検索とカードで選ぶ
	 * → 角を動かして「開始」すると、その回路を再開して保存済みの範囲を更新する → 終了後も選んだまま → 「新規作成」で外れる。
	 * ほかのワールドの回路は一覧の後ろに出て、選べず再開もできない。
	 */
	private static void checkSavedRanges(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		String nameA = "gametest 範囲A";
		String nameB = "gametest 範囲B";
		Box boxA = new Box(60, -60, 0, 63, -59, 3);
		Box boxB = new Box(70, -60, 0, 72, -59, 2);
		String[] cmds = {
			"setblock 60 -60 0 minecraft:lever[face=floor,facing=east]",
			"setblock 61 -60 0 minecraft:redstone_wire",
			"setblock 63 -59 3 minecraft:stone",
			"setblock 70 -60 0 minecraft:redstone_torch",
			"setblock 72 -60 2 minecraft:redstone_lamp",
		};

		for (String c : cmds) {
			sp.getServer().runCommand(c);
		}

		sp.getServer().runCommand("tp @a 66.5 -60 -6.5");
		context.waitFor(mc -> mc.level.getChunkSource().getChunkNow(60 >> 4, 0) != null && mc.level.getChunkSource().getChunkNow(72 >> 4, 0) != null, TIMEOUT);
		sp.getConnection().waitForChunksRender();
		check(sessions(context).active() == null, "セッションが開いたまま");
		String world = context.computeOnClient(mc -> SessionManager.worldKey(mc));

		// ---- 範囲タブの「範囲を保存」: セッションを開かずに circuit.json を作り、選んだ状態になる
		context.runOnClient(mc -> RedtactLive.get().selection().set(boxA, "minecraft:overworld"));
		context.runOnClient(mc -> Menu.open(MenuTab.RANGE));
		context.waitForScreen(RangeScreen.class);
		context.runOnClient(mc -> {
			RangeScreen r = (RangeScreen) mc.gui.screen();
			r.saveNameField().setValue(nameA);
			r.save();
		});
		CircuitRecord recA = findCircuit(context, nameA).orElseThrow(() -> new AssertionError("範囲タブで保存した範囲が無い"));
		check(recA.sessions.isEmpty() && boxA.equals(recA.box()) && world.equals(recA.world) && "minecraft:overworld".equals(recA.dimension),
				"保存した範囲: " + Json.GSON.toJson(recA));
		check(recA.circuitId.equals(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId())), "保存した範囲を選んでいない");
		context.setScreen(() -> null);
		context.waitTicks(2);

		// ---- コマンドでも保存できる (/rlive sel save)
		context.runOnClient(mc -> RedtactLive.get().selection().set(boxB, "minecraft:overworld"));
		chat(context, "/rlive sel save " + nameB);
		CircuitRecord recB = findCircuit(context, nameB).orElseThrow(() -> new AssertionError("/rlive sel save で保存されない"));
		check(recB.sessions.isEmpty() && boxB.equals(recB.box()), "コマンドで保存した範囲: " + Json.GSON.toJson(recB));

		// ほかのワールドの回路 (一覧の後ろに出て、選べない)
		String otherName = "gametest 別ワールド";
		context.runOnClient(mc -> {
			try {
				var store = sessions(mc).store();
				CircuitRecord o = store.create(otherName);
				o.world = "server:example.invalid";
				o.dimension = "minecraft:overworld";
				o.region = CircuitRecord.Region.of(boxA);
				store.save(o);
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});

		// ---- 選択を解除すると選んだ範囲も外れる
		chat(context, "/rlive sel clear");
		check(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId() == null && RedtactLive.get().selection().isEmpty()),
				"選択を解除しても選んだ範囲が残っている");

		// ---- 羽根を持つと枠が出て、中クリックで視線の先の範囲 (A) を選ぶ。ピックブロック (持ち物の入れ替え) は起きない
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:feather");
		lookFrom(context, sp, "66.5 -60 -6.5", "61.5 -59.5 1.5");
		check(nameA.equals(context.computeOnClient(mc -> {
			CircuitRecord r = SelectionTool.lookedAtSaved(mc);
			return r == null ? null : r.name;
		})), "視線の先の保存済みの範囲が A でない");
		click(context, InputConstants.MOUSE_BUTTON_MIDDLE);
		check(recA.circuitId.equals(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId())), "中クリックで A を選べない");
		check(boxA.equals(context.computeOnClient(mc -> RedtactLive.get().selection().box())), "中クリックで選択が A にならない");
		check(context.computeOnClient(mc -> mc.player.getMainHandItem().is(net.minecraft.world.item.Items.FEATHER)), "中クリックで持ち物が変わった (ピックブロック)");
		// B を見ている (金色の枠と名前) ところを撮る。A は選択の赤い枠と「(選択中)」
		lookFrom(context, sp, "66.5 -60 -6.5", "71.5 -59.5 1.5");
		check(nameB.equals(context.computeOnClient(mc -> {
			CircuitRecord r = SelectionTool.lookedAtSaved(mc);
			return r == null ? null : r.name;
		})), "視線の先の保存済みの範囲が B でない");
		// 地面に立って撮る (宙に tp すると落ちて視線がずれる)
		lookFrom(context, sp, "66.5 -60 -10.5", "71.5 -59.5 1.5");
		context.waitTicks(10);
		check(nameB.equals(context.computeOnClient(mc -> {
			CircuitRecord r = SelectionTool.lookedAtSaved(mc);
			return r == null ? null : r.name;
		})), "撮る位置から B を見ていない");
		context.runOnClient(mc -> mc.gui.toastManager().clear()); // 前のトーストを消して撮る
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-saved-0-frames");
		// 空を向いて中クリック: 選んだ範囲は変わらない
		sp.getServer().runCommand("tp @a 66.5 -60 -6.5 0 -90");
		context.waitTicks(5);
		click(context, InputConstants.MOUSE_BUTTON_MIDDLE);
		check(recA.circuitId.equals(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId())), "空を向いた中クリックで選んだ範囲が変わった");
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:air");

		// ---- セッションタブ: 選んでいれば「開始」「新規作成」(名前の欄は無い)。要点に選んだ範囲
		sp.getServer().runCommand("tp @a 66.5 -57 -9.5 0 25");
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> {
			SessionScreen s = (SessionScreen) mc.gui.screen();
			return s.nameField() == null && s.startButton() != null && s.newButton() != null;
		}), "選んでいるのに開始の行が「開始」「新規作成」でない");
		List<String> status = context.computeOnClient(mc -> SessionScreen.statusLines(null, sessions(mc)));
		check(status.get(1).contains("「" + nameA + "」") && status.get(1).contains("(保存済み)"), "要点に選んだ範囲が無い: " + status);
		check(context.computeOnClient(mc -> {
			SessionScreen s = (SessionScreen) mc.gui.screen();
			return s.circuitCard(recA.circuitId).row().picked() && s.circuitCard(recB.circuitId).pickable();
		}), "A のカードが選択中でない");
		check(context.computeOnClient(mc -> {
			SessionScreen s = (SessionScreen) mc.gui.screen();
			var other = s.cards().stream().filter(c -> c instanceof com.redtact.live.gui.cards.CircuitCard cc && cc.row().record().name.equals(otherName))
					.map(c -> (com.redtact.live.gui.cards.CircuitCard) c).findFirst().orElse(null);
			return other != null && other.row().otherWorld() != null && !other.pickable() && !other.resumeButton().active;
		}), "ほかのワールドの回路が選べてしまう / 一覧に無い");
		context.runOnClient(mc -> mc.gui.toastManager().clear()); // 前のトーストを消して撮る
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-saved-1-session-picked");

		// ---- 検索欄に「範囲B」→ B だけになる。カードを押すと B を選ぶ
		clickWidget(context, s -> s.searchField().frameX() + 10, s -> s.searchField().frameY() + 8);
		context.getInput().typeChars("範囲B");
		context.waitTicks(2);
		List<String> shown = context.computeOnClient(mc -> ((SessionScreen) mc.gui.screen()).cards().stream()
				.filter(c -> c instanceof com.redtact.live.gui.cards.CircuitCard)
				.map(c -> ((com.redtact.live.gui.cards.CircuitCard) c).row().record().name).toList());
		check(shown.equals(List.of(nameB)), "検索で絞れない: " + shown);
		clickWidget(context, s -> s.circuitCard(recB.circuitId).getX() + 30, s -> s.circuitCard(recB.circuitId).getY() + 8);
		context.waitFor(mc -> recB.circuitId.equals(RedtactLive.get().savedRanges().pickedId()) && mc.gui.screen() instanceof SessionScreen s
				&& s.circuitCard(recB.circuitId) != null && s.circuitCard(recB.circuitId).row().picked(), TIMEOUT);
		check(boxB.equals(context.computeOnClient(mc -> RedtactLive.get().selection().box())), "カードを押しても選択が B にならない");
		context.runOnClient(mc -> mc.gui.toastManager().clear()); // 前のトーストを消して撮る
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-saved-2-search-picked");

		// ---- 角を動かす → 要点に「角を動かした」→「開始」で B を再開し、保存済みの範囲を更新する
		Box boxB2 = new Box(70, -60, 0, 73, -59, 2);
		context.runOnClient(mc -> RedtactLive.get().setPos(2, new BlockPos(73, -59, 2), "minecraft:overworld"));
		check(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedChanged()), "角を動かしても変更ありにならない");
		check(context.computeOnClient(mc -> SessionScreen.statusLines(null, sessions(mc))).get(1).contains("角を動かした"), "要点に変更ありが出ない");
		clickWidget(context, s -> s.startButton().getX() + s.startButton().getWidth() / 2, s -> s.startButton().getY() + 10);
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 1 && !sessions(mc).isBusy(), TIMEOUT);
		SessionManager.Active a = sessions(context).active();
		check(a.circuitId.equals(recB.circuitId) && a.region().equals(boxB2), "選んだ範囲で再開していない: " + a.circuitName + " " + a.region());
		CircuitRecord recB2 = findCircuit(context, nameB).orElseThrow();
		check(boxB2.equals(recB2.box()) && recB2.sessions.size() == 1, "保存済みの範囲が更新されていない: " + Json.GSON.toJson(recB2));
		check(recB.circuitId.equals(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId())), "開始したら選んだ範囲が外れた");

		// 開催中は中クリックでもカードでも選べない
		context.setScreen(() -> null);
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:feather");
		lookFrom(context, sp, "66.5 -60 -6.5", "61.5 -59.5 1.5");
		click(context, InputConstants.MOUSE_BUTTON_MIDDLE);
		check(recB.circuitId.equals(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId()))
				&& boxB2.equals(context.computeOnClient(mc -> RedtactLive.get().selection().box())), "開催中なのに中クリックで選べた");
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		chat(context, "/rlive close");
		context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
		checkResumeCard(context, sp, server, recA, boxA, recB, boxB2);

		// ---- 終了後も (再開した) A を選んだまま。「新規作成」で選んだ範囲と選択が外れ、名前の欄に戻る
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> ((SessionScreen) mc.gui.screen()).newButton() != null), "終了後に「新規作成」が無い");
		clickWidget(context, s -> s.newButton().getX() + s.newButton().getWidth() / 2, s -> s.newButton().getY() + 10);
		context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen s && s.nameField() != null, TIMEOUT);
		check(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId() == null && RedtactLive.get().selection().isEmpty()),
				"「新規作成」で選んだ範囲・選択が外れない");
		context.runOnClient(mc -> ((SessionScreen) mc.gui.screen()).searchField().setValue(""));
		context.waitTicks(2);
		context.runOnClient(mc -> mc.gui.toastManager().clear()); // 前のトーストを消して撮る
		context.waitTicks(2);
		context.takeScreenshot("redtact-live-saved-3-new");
		context.setScreen(() -> null);
		context.waitTicks(2);
		LOGGER.info("saved ranges ok");
	}

	/**
	 * カードの「再開」(#44): 別の範囲を選んでいても、その回路の保存済みの範囲で開き、保存済みの範囲を上書きしない。
	 * 開催中に角を動かすと、ブロックが変わらなくても自動同期で新しい範囲を全体で送り、保存済みの範囲も更新する。
	 */
	private static void checkResumeCard(ClientGameTestContext context, TestSingleplayerContext sp, String server, CircuitRecord recA, Box boxA, CircuitRecord recB,
			Box boxB) {
		// いまの選択は B を選んだあとに角を動かした範囲 (A とも B とも違う)
		Box elsewhere = new Box(70, -60, 0, 74, -59, 2);
		context.runOnClient(mc -> RedtactLive.get().setPos(2, new BlockPos(74, -59, 2), "minecraft:overworld"));
		check(elsewhere.equals(context.computeOnClient(mc -> RedtactLive.get().selection().box())), "選択を動かせない");
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.runOnClient(mc -> ((SessionScreen) mc.gui.screen()).searchField().setValue(""));
		context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen s && s.circuitCard(recA.circuitId) != null
				&& s.circuitCard(recA.circuitId).resumeButton().active, TIMEOUT);
		context.waitTicks(2); // カードのボタンの位置は描くときに決まる
		clickWidget(context, s -> s.circuitCard(recA.circuitId).resumeButton().getX() + s.circuitCard(recA.circuitId).resumeButton().getWidth() / 2,
				s -> s.circuitCard(recA.circuitId).resumeButton().getY() + 8);
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 1 && !sessions(mc).isBusy(), TIMEOUT);
		SessionManager.Active a = sessions(context).active();
		check(a.circuitId.equals(recA.circuitId) && a.region().equals(boxA), "「再開」が保存済みの範囲で開いていない: " + a.circuitName + " " + a.region());
		check(boxA.equals(context.computeOnClient(mc -> RedtactLive.get().selection().box())), "「再開」で選択が保存済みの範囲にならない");
		check(recA.circuitId.equals(context.computeOnClient(mc -> RedtactLive.get().savedRanges().pickedId())), "「再開」で A を選んでいない");
		checkPush(context, SessionManager.PushKind.FULL, 1, -1, -1);
		check(boxA.equals(findCircuit(context, recA.name).orElseThrow().box()), "「再開」で保存済みの範囲が上書きされた");
		check(boxB.equals(findCircuit(context, recB.name).orElseThrow().box()), "「再開」でほかの回路の範囲が変わった");
		verifyStateSize(server, a.sessionId, boxA, 1);
		// A と B が両方見える所から、羽根を持って (保存済みの範囲の枠と名前が出る) 撮る。選択 (赤い枠) が A にある
		context.setScreen(() -> null);
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:feather");
		lookFrom(context, sp, "66.5 -60 -12.5", "66.5 -59.5 1.5");
		context.waitTicks(10);
		context.takeScreenshot("redtact-live-saved-4-resume");

		// ---- 開催中に角を動かす → ブロックは変えずに待つだけで、自動同期が新しい範囲を全体で送る
		chat(context, "/rlive auto on");
		Box boxA2 = new Box(60, -60, 0, 64, -59, 3);
		context.runOnClient(mc -> RedtactLive.get().setPos(2, new BlockPos(64, -59, 3), "minecraft:overworld"));
		context.waitFor(mc -> sessions(mc).active().lastVersion() >= 2 && !sessions(mc).isBusy(), TIMEOUT);
		checkPush(context, SessionManager.PushKind.FULL, 2, -1, -1);
		check(sessions(context).lastPush().trigger() == SessionManager.Trigger.AUTO, "範囲の変更が自動で送られていない");
		check("範囲の変更".equals(sessions(context).lastPush().reason()), "全体にした理由: " + sessions(context).lastPush().reason());
		check(sessions(context).active().region().equals(boxA2), "セッションの範囲が変わらない: " + sessions(context).active().region());
		check(boxA2.equals(findCircuit(context, recA.name).orElseThrow().box()), "保存済みの範囲が更新されない");
		verifyStateSize(server, a.sessionId, boxA2, 2);
		// 送ったあとは何もしなければ送らない
		context.waitTicks(20 * 4);
		check(version(context) == 2, "範囲を送ったあとも送り続ける: v" + version(context));
		context.takeScreenshot("redtact-live-saved-5-resized");
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		chat(context, "/rlive auto off");
		chat(context, "/rlive close");
		context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
		LOGGER.info("resume card ok");
	}

	/** サーバの回路の版と大きさだけを確かめる (チェストの無い範囲用)。 */
	private static void verifyStateSize(String server, String sessionId, Box box, int version) {
		StateJson.State json = Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + sessionId + "/state"), StateJson.State.class);
		check(json.version != null && json.version == version, "サーバの版: " + json.version + " (想定 " + version + ")");
		CircuitState st = CircuitState.fromJson(json);
		check(st.sizeX() == box.sizeX() && st.sizeY() == box.sizeY() && st.sizeZ() == box.sizeZ(), "サーバの大きさ: " + java.util.Arrays.toString(st.size()));
	}

	/** from (足元) に立ち、目の高さから facing の点を向く (tp の facing は足元から向きを計算するので使わない)。 */
	private static void lookFrom(ClientGameTestContext context, TestSingleplayerContext sp, String from, String facing) {
		double[] f = parse3(from);
		double[] t = parse3(facing);
		double dx = t[0] - f[0];
		double dy = t[1] - (f[1] + 1.62);
		double dz = t[2] - f[2];
		double yaw = Math.toDegrees(-Math.atan2(dx, dz));
		double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %s %.4f %.4f", from, yaw, pitch));
		context.waitTicks(5);
	}

	private static double[] parse3(String s) {
		String[] p = s.trim().split("\\s+");
		return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
	}

	private static net.minecraft.world.level.block.state.BlockState serverBlock(TestSingleplayerContext sp, BlockPos pos) {
		return sp.getServer().computeOnServer(server -> server.overworld().getBlockState(pos));
	}

	/**
	 * マウスのクリック (ゲーム中)。バニラの MouseHandler.onButton を呼ぶ。onButton の頭で HotkeyManager (mixin) が
	 * ホットキーを調べ、道具なら打ち消す。打ち消さなければバニラの攻撃・使用に進む。
	 */
	private static void click(ClientGameTestContext context, int button) {
		context.runOnClient(mc -> ((MouseHandlerAccessor) mc.mouseHandler).invokeOnButton(mc.getWindow().handle(), new MouseButtonInfo(button, 0), 1));
		context.waitTicks(2);
		context.runOnClient(mc -> ((MouseHandlerAccessor) mc.mouseHandler).invokeOnButton(mc.getWindow().handle(), new MouseButtonInfo(button, 0), 0));
		context.waitTicks(2);
	}

	/**
	 * 設定タブのホットキーの入力欄: 「メニューを開く」を R を押しながら M に変える → ゲーム中に R+M でメニューが開く。
	 * Esc で入力をやめると変わらない。最後に既定 (R+L) に戻す。
	 */
	/**
	 * 「…」で切った字は、マウスを重ねると全文をツールチップに出す。名前を切ったボタンはツールチップの先頭に全文を出し、
	 * マウスを重ねても、キーボード (Tab) で選んでも出る (#41)。設定タブで確かめる。
	 */
	private static void checkClipTooltips(ClientGameTestContext context) {
		// 字: 「表示名」の説明 (日本語でも英語でも) は 427 px の幅では切れる。説明は MOD の言語で言語ファイルから (#79)
		String help = context.computeOnClient(mc -> com.redtact.live.i18n.Tr.key("redtact-live.config.generic.comment." + Configs.Generic.USER.name()));
		int[] p = context.computeOnClient(mc -> {
			com.redtact.live.gui.cards.MenuCard c = ((SettingsScreen) mc.gui.screen()).cardFor(Configs.Generic.USER);
			return c == null ? null : new int[] {c.getX() + 40, c.getY() + 16};
		});
		check(p != null, "設定タブに表示名のカードが無い");
		moveCursor(context, p[0], p[1]);
		context.waitTicks(3);
		check(help.equals(com.redtact.live.gui.kit.Draw.lastFullText()), "切れた説明に重ねても全文が出ない: " + com.redtact.live.gui.kit.Draw.lastFullText());
		context.takeScreenshot("redtact-live-clip-1-text");

		// ボタン: メニューを開くホットキーに長い組み合わせを入れて、幅 110 px のボタンの名前を切らせる
		context.runOnClient(mc -> {
			Configs.Hotkeys.OPEN_MENU.getKeybind().setKeys(List.of(InputConstants.KEY_LCONTROL, InputConstants.KEY_LSHIFT, InputConstants.KEY_LALT, InputConstants.KEY_R));
			Menu.open(MenuTab.SETTINGS);
			((SettingsScreen) mc.gui.screen()).scrollToHotkeys();
		});
		context.waitTicks(3);
		String full = context.computeOnClient(mc -> com.redtact.live.input.KeyDisplay.combo(Configs.Hotkeys.OPEN_MENU.getKeybind().getKeys()));
		String tip = context.computeOnClient(mc -> ((SettingsScreen) mc.gui.screen()).hotkeyButton(Configs.Hotkeys.OPEN_MENU).tooltipText());
		LOGGER.info("clipped hotkey button: label={} tooltip={}", full, tip);
		check(tip != null && tip.startsWith(full + "\n"), "名前を切ったボタンのツールチップの先頭に全文が無い: " + tip);
		int[] b = context.computeOnClient(mc -> {
			com.redtact.live.gui.kit.RButton button = ((SettingsScreen) mc.gui.screen()).hotkeyButton(Configs.Hotkeys.OPEN_MENU);
			return new int[] {button.getX() + button.getWidth() / 2, button.getY() + 8};
		});
		moveCursor(context, b[0], b[1]);
		context.waitTicks(3);
		context.takeScreenshot("redtact-live-clip-2-button-hover");

		// キーボード: マウスを離して Tab で選ぶ
		moveCursor(context, 1, 1);
		boolean focused = false;

		for (int i = 0; i < 120 && !focused; i++) {
			context.getInput().pressKey(InputConstants.KEY_TAB);
			focused = context.computeOnClient(mc -> ((SettingsScreen) mc.gui.screen()).hotkeyButton(Configs.Hotkeys.OPEN_MENU).isFocused());
		}

		check(focused, "Tab でホットキーのボタンを選べない");
		context.waitTicks(3);
		context.takeScreenshot("redtact-live-clip-3-button-keyboard");

		// 元に戻す (このあとのホットキーの入力の確認は既定の R+L から)
		context.runOnClient(mc -> {
			Configs.Hotkeys.OPEN_MENU.resetToDefault();
			Configs.saveToFile();
			Menu.open(MenuTab.SETTINGS);
		});
		context.waitTicks(2);
	}

	/** マウスを GUI の座標へ動かす。 */
	private static void moveCursor(ClientGameTestContext context, int guiX, int guiY) {
		double scale = context.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		context.getInput().setCursorPos(guiX * scale, guiY * scale);
	}

	private static void checkHotkeyEditor(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			SettingsScreen s = (SettingsScreen) mc.gui.screen();
			s.scrollToHotkeys();
			s.startCaptureFor(Configs.Hotkeys.OPEN_MENU);
		});
		check(context.computeOnClient(mc -> ((SettingsScreen) mc.gui.screen()).capturing()) == Configs.Hotkeys.OPEN_MENU, "入力が始まらない");
		context.waitTicks(3);
		context.takeScreenshot("redtact-live-menu-settings-hotkey");
		context.getInput().holdKey(InputConstants.KEY_R);
		context.getInput().pressKey(InputConstants.KEY_M);
		context.getInput().releaseKey(InputConstants.KEY_R);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> ((SettingsScreen) mc.gui.screen()).capturing()) == null, "離したのに入力が終わらない");
		check(context.computeOnClient(mc -> Configs.Hotkeys.OPEN_MENU.getKeybind().getStringValue()).equals("R,M"), "R+M にならない: "
				+ context.computeOnClient(mc -> Configs.Hotkeys.OPEN_MENU.getKeybind().getStringValue()));
		// Esc でやめると変わらない (K を押したまま Esc。K を離した時点で決まってしまうので押したままにする)
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).startCaptureFor(Configs.Hotkeys.OPEN_MENU));
		context.getInput().holdKey(InputConstants.KEY_K);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.getInput().releaseKey(InputConstants.KEY_K);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof SettingsScreen s && s.capturing() == null), "Esc で入力が終わらない (画面ごと閉じた)");
		check(context.computeOnClient(mc -> Configs.Hotkeys.OPEN_MENU.getKeybind().getStringValue()).equals("R,M"), "Esc でやめたのに変わった");
		// ゲーム中に R+M で開く (R+L ではもう開かない)
		context.setScreen(() -> null);
		context.waitTicks(2);
		keys(context, InputConstants.KEY_R, InputConstants.KEY_L);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> !(mc.gui.screen() instanceof com.redtact.live.gui.MenuScreen)), "変えたのに R+L で開いた");
		context.setScreen(() -> null); // R+L の L で開いた進捗の画面を閉じる
		context.waitTicks(2);
		keys(context, InputConstants.KEY_R, InputConstants.KEY_M);
		context.waitForScreen(SettingsScreen.class);
		context.runOnClient(mc -> {
			Configs.Hotkeys.OPEN_MENU.resetToDefault();
			Configs.saveToFile();
		});
		check(context.computeOnClient(mc -> Configs.Hotkeys.OPEN_MENU.getKeybind().getStringValue()).equals("R,L"), "既定に戻らない");
	}

	/**
	 * 開催中の一覧: セッションタブで「一覧に出す」を ON にして開始 → 「開催中」タブに自分のセッションが印つきで出る →
	 * OFF に切り替えると消える。ボタンはカーソルを動かした本物のクリックで押す。
	 */
	private static void checkLiveList(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		String name = "gametest 一覧";
		context.runOnClient(mc -> RedtactLive.get().selection().set(new Box(0, -60, 0, 5, -59, 3), "minecraft:overworld"));
		sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
		context.waitTicks(5);
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> SessionScreen.listDraft()), "「一覧に出す」の既定が OFF でない");
		String predicted = context.computeOnClient(mc -> SessionScreen.hostLine(null));
		check(predicted.contains("確認なし・開発用") && predicted.contains(HOST_NAME), "開く前のホスト名の見込み: " + predicted);
		context.takeScreenshot("redtact-live-list-0-session-before-start");

		// 名前の欄を押して入力 -> 「一覧に出す」を押して ON -> 「開始」
		clickWidget(context, s -> s.nameField().getX() + 10, s -> s.nameField().getY() + 8);
		context.getInput().typeChars(name);
		clickWidget(context, s -> s.listButton().getX() + s.listButton().getWidth() / 2, s -> s.listButton().getY() + 10);
		check(context.computeOnClient(mc -> SessionScreen.listDraft()), "「一覧に出す」を押しても ON にならない");
		clickWidget(context, s -> s.startButton().getX() + s.startButton().getWidth() / 2, s -> s.startButton().getY() + 10);
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 1 && !sessions(mc).isBusy(), TIMEOUT);
		SessionManager.Active a = sessions(context).active();
		check(a.circuitName.equals(name) && a.listed(), "一覧に出して開始できていない: listed=" + a.listed());
		check(!context.computeOnClient(mc -> SessionScreen.listDraft()), "開始したのに「一覧に出す」が OFF に戻らない");
		check(httpGetString(server + "/api/sessions").contains("\"sessionId\":\"" + a.sessionId + "\""), "サーバの一覧に出ていない");
		context.waitTicks(5);
		context.takeScreenshot("redtact-live-list-1-session-tab");

		// 「開催中」タブ: 自分のセッションが印つきで出る。ブラウザで開く URL は自分の共有リンク
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.rows().stream().anyMatch(r -> r.session().sessionId.equals(a.sessionId)), TIMEOUT);
		List<LiveRow> rows = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rows());
		LiveRow mine = rows.stream().filter(r -> r.session().sessionId.equals(a.sessionId)).findFirst().orElseThrow();
		check(mine.mine() && a.shareUrl.equals(mine.url()) && mine.session().title.equals(name) && mine.session().host.equals(HOST_NAME)
				&& !mine.session().hostVerified,
				"開催中タブの自分の行: mine=" + mine.mine() + " url=" + mine.url() + " title=" + mine.session().title + " host=" + mine.session().host);

		// 他人のセッション (fake-host -listed を裏で動かしているとき): 自分の印なしで出て、URL はサーバの shareUrl
		String otherTitle = System.getenv("REDTACT_LIVE_OTHER_TITLE");

		if (otherTitle != null && !otherTitle.isBlank()) {
			context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.rows().stream().anyMatch(r -> r.session().title.equals(otherTitle)), TIMEOUT);
			LiveRow other = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rows()).stream()
					.filter(r -> r.session().title.equals(otherTitle)).findFirst().orElseThrow();
			Api.Session onServer = LiveText.parseList(httpGetString(server + "/api/sessions")).stream()
					.filter(x -> x.sessionId.equals(other.session().sessionId)).findFirst().orElseThrow();
			check(!other.mine() && onServer.shareUrl != null && onServer.shareUrl.equals(other.url()),
					"他人の行: mine=" + other.mine() + " url=" + other.url() + " server=" + onServer.shareUrl);
			check(context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rows()).size() >= 2, "自分と他人の 2 行が無い");
			LOGGER.info("other session row ok: {} by {} -> {}", other.session().title, other.session().host, other.url());
		}

		// グリッド (#56): 見えているカードのサムネが出るまで待つ。自分のカードのサムネはサーバの回路と同じ数のブロック
		waitMillis(context, 7_000); // 開始とコメントのトーストが消えるまで (カードにかぶるため)
		waitThumbs(context);
		int cols = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).gridColumns());
		check(cols >= 2 && cols <= 4, "グリッドの列: " + cols);
		Api.Session mineOnServer = LiveText.parseList(httpGetString(server + "/api/sessions")).stream()
				.filter(x -> x.sessionId.equals(a.sessionId)).findFirst().orElseThrow();
		PreviewCircuit mineThumb = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).cardFor(a.sessionId).thumbCircuit());
		check(mineThumb != null && mineOnServer.snapshot != null && mineThumb.model().nonAir() == mineOnServer.snapshot.blockCount,
				"自分のカードのサムネ: " + (mineThumb == null ? "なし" : mineThumb.model().nonAir() + " ブロック"));
		neutralCursor(context);
		context.takeScreenshot("redtact-live-list-2-live-grid");
		String detailId = otherTitle != null && !otherTitle.isBlank()
				? context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rows()).stream().filter(r -> r.session().title.equals(otherTitle))
						.findFirst().orElseThrow().session().sessionId
				: a.sessionId;
		liveCardShots(context, detailId);

		// カードを押すと詳細 (立体のプレビューと操作)。中身はサーバの回路と同じ数のブロック (fake-host は版が進み続けるので、開く前の版以上)
		Api.Session detailOnServer = LiveText.parseList(httpGetString(server + "/api/sessions")).stream()
				.filter(x -> x.sessionId.equals(detailId)).findFirst().orElseThrow();
		selectLive(context, detailId);
		int wantVersion = detailOnServer.snapshot == null ? 0 : detailOnServer.snapshot.version;
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.preview() != null && g.preview().shownCircuit() != null
				&& g.preview().shownCircuit().sessionId().equals(detailId) && g.preview().shownCircuit().version() >= wantVersion, TIMEOUT);
		PreviewCircuit pc = waitPreview(context, detailId);
		check(detailOnServer.snapshot != null && pc.model().nonAir() == detailOnServer.snapshot.blockCount && pc.version() >= detailOnServer.snapshot.version
				&& pc.unknown() == 0 && pc.model().drawCount() > 0,
				"プレビューの中身: nonAir=" + pc.model().nonAir() + " v" + pc.version() + " unknown=" + pc.unknown() + " drawn=" + pc.model().drawCount());
		check(context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).cardFor(detailId)) == null, "詳細なのに一覧のカードがある");
		neutralCursor(context);
		context.takeScreenshot("redtact-live-list-2b-detail");

		// 止まった絵: ドラッグでは回らず、右上の「右に回す」で 90° 回る (#93)
		float yaw0 = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).preview().yaw());
		hoverLive(context, g -> new int[] {g.preview().getX() + g.preview().getWidth() / 2, g.preview().getY() + g.preview().getHeight() / 2});
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		context.getInput().moveCursor(60, 24);
		context.waitTicks(2);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		float yawDragged = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).preview().yaw());
		check(yawDragged == yaw0, "ドラッグで回った: " + yaw0 + " -> " + yawDragged);
		clickLive(context, g -> {
			RButton b = g.preview().controls().get(1);
			return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
		});
		context.waitTicks(3);
		float yaw1 = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).preview().yaw());
		check(yaw1 == ((yaw0 - PreviewView.STEP) % 360.0F + 360.0F) % 360.0F, "「右に回す」で " + PreviewView.STEP + "° 回らない: " + yaw0 + " -> " + yaw1);
		context.takeScreenshot("redtact-live-list-2c-preview-rotated");

		// 全画面 (#102): 右上の全画面のボタンで開くと、他人の回路も形を組み立てて描く。Esc で詳細に戻る
		FullscreenPreviewChecks.openFromLive(context, mc -> ((LiveListScreen) mc.gui.screen()).preview());
		int otherQuads = FullscreenPreviewChecks.waitLiveMesh(context);
		check(otherQuads > 0, "開催中の全画面に形が無い");
		FullscreenPreviewChecks.shotLive(context, "redtact-live-list-2d-fullscreen");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.detail() && detailId.equals(g.selectedId()), TIMEOUT);

		// 自分の回路を書き換えて送ると、全画面を開いたまま版が進み、形を組み立て直す (版はコメントと一緒に 5 秒ごと・取り直しは 15 秒あける)
		selectLive(context, a.sessionId);
		waitPreview(context, a.sessionId);
		FullscreenPreviewChecks.openFromLive(context, mc -> ((LiveListScreen) mc.gui.screen()).preview());
		FullscreenPreviewChecks.waitLiveMesh(context);
		int shown0 = FullscreenPreviewChecks.shownVersion(context);
		sp.getServer().runCommand("setblock 2 -59 1 minecraft:gold_block");
		context.waitTicks(5);
		context.runOnClient(mc -> sessions(mc).push()); // この時点では自動同期を off にしてある (前の確認で)。「今すぐ同期」と同じ
		context.waitFor(mc -> FullscreenPreviewChecks.rebuiltPast(mc, shown0), TIMEOUT * 2);
		LOGGER.info("[fullscreen] 開催中: 全画面のまま v{} -> v{} で組み立て直した", shown0, FullscreenPreviewChecks.shownVersion(context));
		FullscreenPreviewChecks.shotLive(context, "redtact-live-list-2e-fullscreen-updated");
		sp.getServer().runCommand("setblock 2 -59 1 minecraft:air");
		context.waitTicks(5);
		context.runOnClient(mc -> sessions(mc).push());
		context.waitFor(mc -> !sessions(mc).isBusy(), TIMEOUT);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.detail() && a.sessionId.equals(g.selectedId()), TIMEOUT);
		selectLive(context, detailId);

		// タブを作り直しても詳細のまま → 「← 一覧」で一覧に戻る
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.detail() && detailId.equals(g.selectedId()), TIMEOUT);
		backToLiveList(context);
		check(context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).cardFor(detailId)) != null, "一覧に戻ってもカードが無い");

		// HUD に「公開中」
		context.setScreen(() -> null);
		context.waitTicks(5);
		List<String> hud = context.computeOnClient(mc -> HudRenderer.lines(RedtactLive.get()));
		check(hud.get(0).contains("公開中"), "HUD に公開中が無い: " + hud);
		context.takeScreenshot("redtact-live-list-3-hud");

		// セッションタブで OFF に切り替える -> 一覧から消える
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);
		clickWidget(context, s -> s.listButton().getX() + s.listButton().getWidth() / 2, s -> s.listButton().getY() + 10);
		context.waitFor(mc -> sessions(mc).active() != null && !sessions(mc).active().listed() && !sessions(mc).active().listedPending(), TIMEOUT);
		check(!httpGetString(server + "/api/sessions").contains("\"sessionId\":\"" + a.sessionId + "\""), "OFF にしたのにサーバの一覧に残っている");
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && RedtactLive.get().liveDirectory().state().fetchedAt() != null
				&& !RedtactLive.get().liveDirectory().state().loading()
				&& g.rows().stream().noneMatch(r -> r.session().sessionId.equals(a.sessionId)), TIMEOUT);
		context.waitTicks(5);
		context.takeScreenshot("redtact-live-list-4-live-tab-empty");
		check(!context.computeOnClient(mc -> HudRenderer.lines(RedtactLive.get())).get(0).contains("公開中"), "OFF なのに HUD に公開中がある");
		context.setScreen(() -> null);
		context.waitTicks(2);
		context.runOnClient(mc -> sessions(mc).close());
		context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
		LOGGER.info("live list ok");
	}

	/**
	 * 複製 (#12): セッションタブの「複製を許可」(既定 ON・開催中は PATCH) → 開催中タブの行の「Litematica に読み込む」
	 * (Litematica があれば足元に配置、無ければ schematics に保存だけ) → 回路を変えてリンク欄から読み直す (前の配置の原点のまま)
	 * → /rlive load → 複製を止めると行のボタンは押せず、リンク欄からは 403 のトースト。ボタンは本物のクリックで押す。
	 */
	private static void checkCopy(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		boolean litematica = context.computeOnClient(mc -> CircuitLoader.litematicaLoaded());
		LOGGER.info("copy: litematica={}", litematica);
		String name = "gametest 複製";
		Box region = new Box(0, -60, 0, 5, -59, 3);
		context.runOnClient(mc -> RedtactLive.get().selection().set(region, "minecraft:overworld"));
		sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
		context.waitTicks(5);
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);

		// 開始前: 既定は ON (全般の「複製を許可 (新しいセッションの既定)」に従う)。押すと OFF、もう一度で ON
		check(context.computeOnClient(mc -> SessionScreen.copyDraft()), "「複製を許可」の既定が ON でない");
		context.runOnClient(mc -> Configs.Generic.DEFAULT_ALLOW_COPY.setBooleanValue(false));
		check(!context.computeOnClient(mc -> SessionScreen.copyDraft()), "全般の既定を OFF にしても開始前の表示が ON");
		context.runOnClient(mc -> {
			Configs.Generic.DEFAULT_ALLOW_COPY.setBooleanValue(true);
			Configs.saveToFile();
		});
		clickWidget(context, s -> s.copyButton().getX() + s.copyButton().getWidth() / 2, s -> s.copyButton().getY() + 10);
		check(!context.computeOnClient(mc -> SessionScreen.copyDraft()), "「複製を許可」を押しても OFF にならない");
		check(!context.computeOnClient(mc -> ((SessionScreen) mc.gui.screen()).copyButton().on()), "ボタンの表示が OFF でない");
		clickWidget(context, s -> s.copyButton().getX() + s.copyButton().getWidth() / 2, s -> s.copyButton().getY() + 10);
		check(context.computeOnClient(mc -> SessionScreen.copyDraft()), "もう一度押しても ON に戻らない");

		// 名前を入れて「一覧に出す」を ON にして開始 -> サーバの allowCopy は true
		clickWidget(context, s -> s.nameField().getX() + 10, s -> s.nameField().getY() + 8);
		context.getInput().typeChars(name);
		clickWidget(context, s -> s.listButton().getX() + s.listButton().getWidth() / 2, s -> s.listButton().getY() + 10);
		clickWidget(context, s -> s.startButton().getX() + s.startButton().getWidth() / 2, s -> s.startButton().getY() + 10);
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 1 && !sessions(mc).isBusy(), TIMEOUT);
		SessionManager.Active a = sessions(context).active();
		check(a.circuitName.equals(name) && a.listed() && a.allowCopy(),
				"複製を許可して開始できていない: name=" + a.circuitName + " listed=" + a.listed() + " allowCopy=" + a.allowCopy());
		// 開始すると次の tick で画面を作り直す (送るのが速いと、作り直す前にここへ来る)
		context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen s && s.nameField() == null, TIMEOUT);
		check(httpGetString(server + "/api/sessions/" + a.sessionId).contains("\"allowCopy\":true"), "サーバの allowCopy が true でない");
		context.runOnClient(mc -> Configs.Generic.DEFAULT_ALLOW_COPY.setBooleanValue(false));
		check(!context.computeOnClient(mc -> SessionScreen.copyDraft()), "開始したのに開始前の選択が残っている (全般の既定に戻らない)");
		context.runOnClient(mc -> {
			Configs.Generic.DEFAULT_ALLOW_COPY.setBooleanValue(true);
			Configs.saveToFile();
		});
		context.waitTicks(5);
		context.takeScreenshot("redtact-live-copy-0-session-tab");

		// 開催中タブ: 何も無い場所に移ってから、自分の行の「Litematica に読み込む」を押す
		BlockPos feet = new BlockPos(20, -60, 20);
		sp.getServer().runCommand("tp @a 20.5 -60 20.5 0 0");
		context.waitTicks(5);
		openLiveTabWithRow(context, a.sessionId, true);
		String label = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rowLoadButton().getMessage().getString());
		check(label.equals(litematica ? "読み込む" : "保存"), "右のボタン: " + label);
		context.takeScreenshot("redtact-live-copy-1-live-tab");
		CircuitLoader.Result r1 = loadAndWait(context, null, () -> clickLiveRow(context, a.sessionId));
		int v1 = version(context);
		check(r1.sessionId().equals(a.sessionId) && r1.version() == v1 && r1.title().equals(name), "読み込んだ版: " + r1);
		Path dir = context.computeOnClient(mc -> CircuitLoader.schematicsDir()).resolve(CircuitLoader.FOLDER).resolve(a.sessionId);
		check(r1.file().equals(dir.resolve(name + "-v" + v1 + ".litematic")) && Files.isRegularFile(r1.file()), "保存先: " + r1.file());
		CircuitState onServer = CircuitState.fromJson(Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + a.sessionId + "/state"), StateJson.State.class));
		verifyLitematicFile(r1.file(), name, onServer);

		if (litematica) {
			check(r1.placed() && !r1.replaced() && feet.equals(r1.origin()), "足元に配置していない: " + r1);
			verifyPlacement(context, a.sessionId, feet, onServer, 1);
			check(context.computeOnClient(mc -> LitematicaChecks.selectedName()).startsWith(name + " v" + v1), "読み込んだ配置が選ばれていない");
		} else {
			check(!r1.placed(), "Litematica が無いのに配置した: " + r1);
		}

		context.waitTicks(30); // トーストが滑り込み終わるまで
		context.takeScreenshot("redtact-live-copy-2-loaded-toast");

		if (litematica) {
			// 配置のホログラム (ワールドは空なので回路がまるごと半透明で出る)
			context.setScreen(() -> null);
			lookFrom(context, sp, "15.5 -60 14.5", "23 -59.5 22");
			context.waitTicks(40);
			context.takeScreenshot("redtact-live-copy-3-placement");
		}

		// 回路を変えて push -> 遠くに移って、リンク欄に共有リンクを貼って読み込む -> 前の配置と同じ原点に新しい版
		sp.getServer().runCommand("setblock 2 -59 1 minecraft:gold_block");
		context.waitTicks(5);
		context.runOnClient(mc -> sessions(mc).push());
		context.waitFor(mc -> version(mc) > v1 && !sessions(mc).isBusy(), TIMEOUT);
		int v2 = version(context);
		sp.getServer().runCommand("tp @a 40.5 -60 40.5 0 0");
		context.waitTicks(5);
		openLiveList(context, a.sessionId);
		context.runOnClient(mc -> ((LiveListScreen) mc.gui.screen()).linkField().setValue(""));
		clickLive(context, g -> new int[] {g.linkField().getX() + 10, g.linkField().getY() + 8});
		context.getInput().typeChars(a.shareUrl);
		check(context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).linkField().getValue()).equals(a.shareUrl), "リンク欄に入らない");
		CircuitLoader.Result r2 = loadAndWait(context, r1,
				() -> clickLive(context, g -> new int[] {g.loadButton().getX() + g.loadButton().getWidth() / 2, g.loadButton().getY() + 10}));
		check(r2.version() == v2 && r2.file().getFileName().toString().equals(name + "-v" + v2 + ".litematic"), "読み直した版: " + r2);
		CircuitState onServer2 = CircuitState.fromJson(Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + a.sessionId + "/state"), StateJson.State.class));
		check(onServer2.blockAt(onServer2.index(2, 1, 1)).equals("minecraft:gold_block"), "変えたブロックがサーバに無い");
		verifyLitematicFile(r2.file(), name, onServer2);
		check(Files.isRegularFile(r1.file()), "前の版のファイルが消えた");

		if (litematica) {
			check(r2.placed() && r2.replaced() && feet.equals(r2.origin()), "前の配置の原点に置き直していない: " + r2);
			verifyPlacement(context, a.sessionId, feet, onServer2, 1);
		}

		// /rlive load <ID> でも同じ
		context.setScreen(() -> null);
		context.waitTicks(2);
		CircuitLoader.Result r3 = loadAndWait(context, r2, () -> chat(context, "/rlive load " + a.sessionId));
		check(r3.version() == v2 && r3.file().equals(r2.file()), "/rlive load: " + r3);

		if (litematica) {
			check(r3.replaced() && feet.equals(r3.origin()), "/rlive load で原点が変わった: " + r3);
			verifyPlacement(context, a.sessionId, feet, onServer2, 1);
			// 起動し直したあと (MOD は覚えていない) でも、同じセッションのフォルダの配置を置き換える
			context.runOnClient(mc -> LitematicaChecks.forgetTracked());
			CircuitLoader.Result r4 = loadAndWait(context, r3, () -> context.runOnClient(mc -> RedtactLive.get().circuitLoader().load(a.shareUrl)));
			check(r4.replaced() && feet.equals(r4.origin()), "起動し直したあとの読み込みで原点が変わった: " + r4);
			verifyPlacement(context, a.sessionId, feet, onServer2, 1);
			r3 = r4;
		}

		// 複製を止める (セッションタブ) -> サーバの allowCopy=false・.litematic は 403
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);
		clickWidget(context, s -> s.copyButton().getX() + s.copyButton().getWidth() / 2, s -> s.copyButton().getY() + 10);
		context.waitFor(mc -> sessions(mc).active() != null && !sessions(mc).active().allowCopy() && !sessions(mc).active().allowCopyPending(), TIMEOUT);
		check(httpStatus(server + "/api/sessions/" + a.sessionId + "/litematic") == 403, "複製を止めたのに .litematic が取れる");
		check(httpGetString(server + "/api/sessions/" + a.sessionId).contains("\"allowCopy\":false"), "サーバの allowCopy が false でない");
		context.waitTicks(5);
		check(context.computeOnClient(mc -> SessionScreen.statusLines(sessions(mc).active(), sessions(mc))).get(0).contains("複製: 不可"), "状態の行に複製不可が出ない");
		context.waitTicks(20 * 6); // 読み込みのトーストが消えるまで
		context.takeScreenshot("redtact-live-copy-4-session-copy-off");

		// 開催中タブ: 自分の行のボタンは押せない (灰色。タイトルの後ろに「(複製不可)」)
		openLiveTabWithRow(context, a.sessionId, false);
		check(!context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rowLoadButton().active), "複製不可なのに押せる");
		// 押しても何も起きない
		clickLiveRow(context, a.sessionId);
		context.waitTicks(5);
		check(!RedtactLive.get().circuitLoader().isBusy() && RedtactLive.get().circuitLoader().last() == r3, "押せないボタンで読み込みが始まった");
		context.takeScreenshot("redtact-live-copy-5-live-tab-disabled");

		// リンク欄からは 403 -> トースト「ホストが複製を許可していません」(リンク欄は一覧にある)
		backToLiveList(context);
		context.runOnClient(mc -> ((LiveListScreen) mc.gui.screen()).linkField().setValue(a.sessionId));
		clickLive(context, g -> new int[] {g.loadButton().getX() + g.loadButton().getWidth() / 2, g.loadButton().getY() + 10});
		context.waitFor(mc -> !RedtactLive.get().circuitLoader().isBusy()
				&& com.redtact.live.ui.EventLog.latest(1).stream().anyMatch(e -> e.text().contains("ホストが複製を許可していません")), TIMEOUT);
		check(RedtactLive.get().circuitLoader().last() == r3, "403 なのに結果が変わった");
		context.waitTicks(20 * 6); // 前のトーストが消えて、エラーのトーストが滑り込むまで
		context.takeScreenshot("redtact-live-copy-6-denied-toast");

		// まだ同期されていない・無いセッションは 404 のトースト
		context.runOnClient(mc -> RedtactLive.get().circuitLoader().load("Zz9Yy8Xx7W"));
		context.waitFor(mc -> !RedtactLive.get().circuitLoader().isBusy()
				&& com.redtact.live.ui.EventLog.latest(1).stream().anyMatch(e -> e.text().contains("セッションが無いか、まだ回路が同期されていません")), TIMEOUT);

		// 他人のセッション (fake-host -listed を裏で動かしているとき): 行から読み込める
		String otherTitle = System.getenv("REDTACT_LIVE_OTHER_TITLE");

		if (otherTitle != null && !otherTitle.isBlank()) {
			sp.getServer().runCommand("tp @a 60.5 -60 20.5 0 0");
			context.waitTicks(5);
			String otherId = LiveText.parseList(httpGetString(server + "/api/sessions")).stream()
					.filter(x -> x.title.equals(otherTitle)).findFirst().orElseThrow().sessionId;
			openLiveTabWithRow(context, otherId, true);
			CircuitLoader.Result first = loadAndWait(context, r3, () -> clickLiveRow(context, otherId));
			CircuitLoader.Result ro = first;
			CircuitState otherState = null;

			// fake-host は数秒ごとに回路を変えるので、取ってきた版とサーバのいまの版がそろうまで読み直す (同じ原点で置き換わる)
			for (int attempt = 0; ; attempt++) {
				StateJson.State st = Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + otherId + "/state"), StateJson.State.class);

				if (st.version == ro.version()) {
					otherState = CircuitState.fromJson(st);
					break;
				}

				check(attempt < 5, "他人の回路の版がそろわない (fake-host の -every を長くする): " + ro.version() + " != " + st.version);
				CircuitLoader.Result prev = ro;
				ro = loadAndWait(context, prev, () -> clickLiveRow(context, otherId));
			}

			check(ro.sessionId().equals(otherId) && ro.title().equals(otherTitle), "他人の回路: " + ro);
			verifyLitematicFile(ro.file(), otherTitle, otherState);

			if (litematica) {
				check(first.placed() && !first.replaced() && ro.placed() && new BlockPos(60, -60, 20).equals(ro.origin()), "他人の回路の配置: " + first + " / " + ro);
				verifyPlacement(context, otherId, new BlockPos(60, -60, 20), otherState, 1);
			}

			LOGGER.info("other session loaded: {} v{} -> {}", otherTitle, ro.version(), ro.file());
		}

		// 複製を許可していない他人のセッション (fake-host -listed -no-copy): 行のボタンは押せない
		String noCopyTitle = System.getenv("REDTACT_LIVE_NOCOPY_TITLE");

		if (noCopyTitle != null && !noCopyTitle.isBlank()) {
			String noCopyId = LiveText.parseList(httpGetString(server + "/api/sessions")).stream()
					.filter(x -> x.title.equals(noCopyTitle)).findFirst().orElseThrow().sessionId;
			openLiveTabWithRow(context, noCopyId, false);
			check(!context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rowLoadButton().active), "複製不可の他人の行が押せる");
			check(httpStatus(server + "/api/sessions/" + noCopyId + "/litematic") == 403, "複製不可の他人の .litematic が取れる");
			check(httpStatus(server + "/api/sessions/" + noCopyId + "/state") == 403, "複製不可の他人の全体が取れる");
			// プレビューは見るだけなので複製不可でも出る (#34)
			PreviewCircuit noCopyPreview = waitPreview(context, noCopyId);
			check(noCopyPreview.model().nonAir() > 0 && noCopyPreview.model().drawCount() > 0, "複製不可の回路のプレビューが空");
			waitMillis(context, 7_000); // 読み込みのトーストが消えるまで (プレビューにかぶるため)
			context.takeScreenshot("redtact-live-copy-7-live-tab-others");
		}

		// 解釈できない入力・つながらないサーバはトースト
		context.setScreen(() -> null);
		context.waitTicks(2);
		chat(context, "/rlive load これはリンクではない");
		context.waitFor(mc -> com.redtact.live.ui.EventLog.latest(1).stream().anyMatch(e -> e.text().contains("共有リンク (…/s/<ID>)")), TIMEOUT);
		context.runOnClient(mc -> {
			Configs.Generic.SERVER_URL.setValueFromString("http://127.0.0.1:1");
			RedtactLive.get().circuitLoader().load(a.shareUrl);
		});
		context.waitFor(mc -> !RedtactLive.get().circuitLoader().isBusy()
				&& com.redtact.live.ui.EventLog.latest(1).stream().anyMatch(e -> e.text().contains("サーバに接続できません")), TIMEOUT);
		context.runOnClient(mc -> {
			Configs.Generic.SERVER_URL.setValueFromString(server);
			Configs.saveToFile();
		});
		context.waitTicks(30);
		context.takeScreenshot("redtact-live-copy-8-network-error-toast");

		// 後片付け: 複製を戻して閉じる
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);
		clickWidget(context, s -> s.copyButton().getX() + s.copyButton().getWidth() / 2, s -> s.copyButton().getY() + 10);
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().allowCopy() && !sessions(mc).active().allowCopyPending(), TIMEOUT);
		check(httpStatus(server + "/api/sessions/" + a.sessionId + "/litematic") == 200, "複製を戻したのに .litematic が取れない");
		context.setScreen(() -> null);
		context.waitTicks(2);
		context.runOnClient(mc -> sessions(mc).close());
		context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
		LOGGER.info("copy ok (litematica={})", litematica);
	}

	/**
	 * 分岐 (#13) 子の側。fake-host (REDTACT_LIVE_BRANCH_PARENT_TITLE) を親に、開催中タブの「分岐する」→ 足元に分岐元の配置 →
	 * 貼り付ける前の開始は断る → 貼り付け (setblock で再現) → 「この配置で分岐を開始」→ 子が開く (サーバの parent・親の branches)。
	 * テストで開いた親からはコマンドで分岐し、親が終わると子のセッションタブの分岐元が「終了」になる。
	 */
	private static void checkBranchChild(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		boolean litematica = context.computeOnClient(mc -> CircuitLoader.litematicaLoaded());
		check(sessions(context).active() == null, "分岐の確認の前にセッションが開いている");
		String parentTitle = System.getenv("REDTACT_LIVE_BRANCH_PARENT_TITLE");
		waitMillis(context, 9_000); // 前の確認のトーストが消えるまで

		if (parentTitle != null && !parentTitle.isBlank()) {
			Api.Session parentInfo = LiveText.parseList(httpGetString(server + "/api/sessions")).stream()
					.filter(x -> x.title.equals(parentTitle)).findFirst().orElseThrow();
			String pid = parentInfo.sessionId;
			BlockPos feet = new BlockPos(30, -60, 60);
			sp.getServer().runCommand("tp @a 30.5 -60 60.5 0 0");
			context.waitTicks(5);
			context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
			context.waitForScreen(LiveListScreen.class);
			selectLive(context, pid);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).rowBranchButton().active) == litematica,
					"「分岐する」の押せる・押せないが Litematica の有無と合わない");

			if (!litematica) {
				// Litematica が無ければ分岐できない (行もリンク欄もコマンドも)
				check(!context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).branchButton() != null
						&& ((LiveListScreen) mc.gui.screen()).rowBranchButton().active), "Litematica が無いのに分岐を押せる");
				context.takeScreenshot("redtact-live-branch-0-live-tab-no-litematica");
				context.setScreen(() -> null);
				context.waitTicks(2);
				chat(context, "/rlive branch " + pid);
				context.waitFor(mc -> com.redtact.live.ui.EventLog.latest(1).stream().anyMatch(e -> e.text().contains("分岐には Litematica が要ります")), TIMEOUT);
				check(RedtactLive.get().branches().prep() == null, "Litematica が無いのに分岐の準備ができた");
				LOGGER.info("branch (child) ok without litematica");
				return;
			}

			context.takeScreenshot("redtact-live-branch-0-live-tab");
			CircuitLoader.Result before = context.computeOnClient(mc -> RedtactLive.get().circuitLoader().last());
			clickLive(context, g -> {
				var b = g.rowBranchButton();
				return new int[] {b.getX() + b.getWidth() / 2, b.getY() + 10};
			});
			context.waitFor(mc -> RedtactLive.get().branches().prep() != null && !RedtactLive.get().circuitLoader().isBusy(), TIMEOUT);
			waitMillis(context, 800);
			context.takeScreenshot("redtact-live-branch-0b-prep-toast");
			com.redtact.live.branch.BranchManager.Prep prep = context.computeOnClient(mc -> RedtactLive.get().branches().prep());
			StateJson.State parentJson = Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + pid + "/state"), StateJson.State.class);
			CircuitState parentState = CircuitState.fromJson(parentJson);
			check(prep.sessionId().equals(pid) && prep.title().equals(parentTitle) && prep.version() == parentJson.version,
					"分岐の準備: " + prep + " (サーバの版 " + parentJson.version + ")");
			check(RedtactLive.get().circuitLoader().last() != before && RedtactLive.get().circuitLoader().last().purpose() == CircuitLoader.Purpose.BRANCH,
					"分岐の準備の読み込みの結果");
			verifyPlacement(context, pid, feet, parentState, 1);

			// Litematica で回したら、範囲は回した配置を囲む箱になる (戻しておく)
			context.runOnClient(mc -> LitematicaChecks.rotate(pid, net.minecraft.world.level.block.Rotation.CLOCKWISE_90));
			Box rotated = context.computeOnClient(mc -> com.redtact.live.copy.LitematicaSupport.placementInfo(pid, null).box());
			Box expectedRotated = new Box(feet.getX() - parentState.sizeZ() + 1, feet.getY(), feet.getZ(),
					feet.getX(), feet.getY() + parentState.sizeY() - 1, feet.getZ() + parentState.sizeX() - 1);
			check(rotated.equals(expectedRotated), "回した配置を囲む箱: " + rotated + " (想定 " + expectedRotated + ")");
			context.runOnClient(mc -> LitematicaChecks.rotate(pid, net.minecraft.world.level.block.Rotation.NONE));
			Box box = new Box(feet.getX(), feet.getY(), feet.getZ(), feet.getX() + parentState.sizeX() - 1, feet.getY() + parentState.sizeY() - 1,
					feet.getZ() + parentState.sizeZ() - 1);
			check(context.computeOnClient(mc -> com.redtact.live.copy.LitematicaSupport.placementInfo(pid, null).box()).equals(box), "戻した配置の範囲");

			// セッションタブ: 分岐の準備の行 (名前の既定は「<分岐元> の分岐」)
			context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
			context.waitForScreen(SessionScreen.class);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> ((SessionScreen) mc.gui.screen()).branchNameField().getValue()).equals(parentTitle + " の分岐"),
					"分岐の名前の既定");
			List<String> status = context.computeOnClient(mc -> SessionScreen.statusLines(null, sessions(mc)));
			check(status.stream().anyMatch(l -> l.contains("分岐の準備") && l.contains("v" + prep.version())
					&& l.contains(feet.toShortString()) && l.contains(box.sizeText())), "分岐の準備の行: " + status);
			waitMillis(context, 6_000); // 準備のトーストが消えるまで
			context.takeScreenshot("redtact-live-branch-1-prep");

			// 「一覧に出す」を ON にしておく (分岐でも上のボタンの選択に従う。断られたときは選択を消費しない)
			clickWidget(context, s -> s.listButton().getX() + s.listButton().getWidth() / 2, s -> s.listButton().getY() + 10);
			check(context.computeOnClient(mc -> SessionScreen.listDraft()), "「一覧に出す」が ON にならない");

			// 貼り付ける前に開始すると断る
			clickWidget(context, s -> s.branchStartButton().getX() + s.branchStartButton().getWidth() / 2, s -> s.branchStartButton().getY() + 10);
			context.waitFor(mc -> com.redtact.live.ui.EventLog.latest(1).stream().anyMatch(e -> e.text().contains("まだ貼り付けていない")), TIMEOUT);
			context.waitTicks(10);
			check(sessions(context).active() == null && RedtactLive.get().branches().prep() != null, "貼り付ける前なのに開いた / 準備が消えた");
			check(context.computeOnClient(mc -> SessionScreen.listDraft()), "断られたのに「一覧に出す」の選択が消えた");
			waitMillis(context, 800);
			context.takeScreenshot("redtact-live-branch-1b-not-pasted-toast");

			// 貼り付け (Litematica の貼り付けの代わりに setblock で同じ位置に置く)
			paste(sp, parentState, feet);
			context.waitTicks(5);
			int[] counts = context.computeOnClient(mc -> {
				var info = com.redtact.live.copy.LitematicaSupport.placementInfo(pid, mc.level);
				return new int[] {info.matched(), info.nonAir()};
			});
			check(counts[0] == counts[1] && counts[1] > 0, "貼り付けた後の一致: " + counts[0] + " / " + counts[1]);

			// 名前を変えて「この配置で分岐を開始」
			String childName = "gametest 分岐";
			context.runOnClient(mc -> ((SessionScreen) mc.gui.screen()).branchNameField().setValue(""));
			clickWidget(context, s -> s.branchNameField().getX() + 10, s -> s.branchNameField().getY() + 8);
			context.getInput().typeChars(childName);
			clickWidget(context, s -> s.branchStartButton().getX() + s.branchStartButton().getWidth() / 2, s -> s.branchStartButton().getY() + 10);
			context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 1 && !sessions(mc).isBusy(), TIMEOUT);
			SessionManager.Active child = sessions(context).active();
			check(child.circuitName.equals(childName), "分岐の名前: " + child.circuitName);
			check(child.listed() && !context.computeOnClient(mc -> SessionScreen.listDraft()), "分岐した子が一覧に出ていない / 選択が戻らない");
			check(child.parent() != null && child.parent().sessionId.equals(pid) && child.parent().version == prep.version() && !child.parent().ended,
					"子の分岐元: " + (child.parent() == null ? null : Json.GSON.toJson(child.parent())));
			check(child.region().equals(box) && box.equals(RedtactLive.get().selection().box()), "子の範囲: " + child.region() + " 選択: " + RedtactLive.get().selection().box());
			context.waitFor(mc -> RedtactLive.get().branches().prep() == null, TIMEOUT);
			Api.Session childOnServer = Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + child.sessionId), Api.Session.class);
			check(childOnServer.parent != null && childOnServer.parent.sessionId.equals(pid) && childOnServer.parent.version == prep.version(),
					"サーバの子の parent");
			Api.Session parentOnServer = Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + pid), Api.Session.class);
			check(parentOnServer.branchList().stream().anyMatch(b -> b.sessionId.equals(child.sessionId) && b.fromVersion == prep.version()
					&& b.title.equals(childName)), "サーバの親の branches に子が無い");
			// 子が送った回路は貼り付けた回路 (ブロックの種類が親と同じ)
			CircuitState childState = CircuitState.fromJson(Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + child.sessionId + "/state"),
					StateJson.State.class));
			for (int i = 0; i < parentState.volume(); i++) {
				String want = parentState.blockAt(i).split("\\[")[0];
				String got = childState.blockAt(i).split("\\[")[0];
				check(want.equals(got), "子の回路の添字 " + i + ": " + got + " != " + want);
			}

			// 子のセッションタブ: 分岐元の行と「分岐元を開く」
			context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen g && g.parentOpenButton() != null, TIMEOUT);
			List<String> childStatus = context.computeOnClient(mc -> SessionScreen.statusLines(sessions(mc).active(), sessions(mc)));
			check(childStatus.stream().anyMatch(l -> l.contains("分岐元: 「" + parentTitle + "」") && l.contains("v" + prep.version()) && l.contains("開催中")),
					"子の分岐元の行: " + childStatus);
			waitMillis(context, 9_000); // エラーと開始のトーストが消えるまで
			context.takeScreenshot("redtact-live-branch-2-child-session-tab");
			context.setScreen(() -> null);
			context.waitTicks(2);
			context.runOnClient(mc -> sessions(mc).close());
			context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);

			// /rlive branch <ID> で準備 → /rlive branch cancel でやめる
			chat(context, "/rlive branch " + pid);
			context.waitFor(mc -> RedtactLive.get().branches().prep() != null && !RedtactLive.get().circuitLoader().isBusy(), TIMEOUT);
			chat(context, "/rlive branch cancel");
			context.waitFor(mc -> RedtactLive.get().branches().prep() == null, TIMEOUT);
		}

		if (!litematica) {
			return;
		}

		// テストで開いた親から、コマンドで分岐する。親が終わると子の分岐元が「終了」に変わる
		LiveApi parentApi = new LiveApi(LiveApi.newHttpClient(), () -> server, () -> "終わる親ホスト");
		CircuitState small = new CircuitState(3, 1, 2, List.of("minecraft:air", "minecraft:stone", "minecraft:gold_block", "minecraft:glass"),
				new int[] {1, 2, 0, 3, 1, 2}, Map.of());
		Api.CreateSessionResponse parent;

		try {
			parent = parentApi.createSession(new Api.CreateSessionRequest("gametest 終わる親", "gtparent00000001", "26.3", SessionManager.dataVersion(), false));
			parentApi.putState(parent.session.sessionId, parent.hostToken, small);
		} catch (com.redtact.live.net.ApiException e) {
			throw new AssertionError(e.describe(), e);
		}

		String pid2 = parent.session.sessionId;
		BlockPos feet2 = new BlockPos(30, -60, 75);
		sp.getServer().runCommand("tp @a 30.5 -60 75.5 0 0");
		context.waitTicks(5);
		chat(context, "/rlive branch " + parent.shareUrl);
		context.waitFor(mc -> RedtactLive.get().branches().prep() != null && RedtactLive.get().branches().prep().sessionId().equals(pid2)
				&& !RedtactLive.get().circuitLoader().isBusy(), TIMEOUT);
		paste(sp, small, feet2);
		context.waitTicks(5);
		chat(context, "/rlive branch start gametest 分岐 2");
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 1 && !sessions(mc).isBusy(), TIMEOUT);
		SessionManager.Active child2 = sessions(context).active();
		check(child2.circuitName.equals("gametest 分岐 2") && child2.parent() != null && child2.parent().sessionId.equals(pid2) && !child2.parent().ended,
				"コマンドで分岐した子");
		check(child2.region().equals(new Box(30, -60, 75, 32, -60, 76)), "コマンドで分岐した子の範囲: " + child2.region());

		try {
			parentApi.deleteSession(pid2, parent.hostToken);
		} catch (com.redtact.live.net.ApiException e) {
			throw new AssertionError(e.describe(), e);
		}

		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().parent() != null && sessions(mc).active().parent().ended, TIMEOUT);
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(5);
		List<String> endedStatus = context.computeOnClient(mc -> SessionScreen.statusLines(sessions(mc).active(), sessions(mc)));
		check(endedStatus.stream().anyMatch(l -> l.contains("分岐元: 「gametest 終わる親」") && l.contains("終了")), "分岐元が終了にならない: " + endedStatus);
		waitMillis(context, 6_000);
		setDetailsOpen(context, true); // 分岐元の行 (終了・開催中) と最近の出来事を写す
		context.takeScreenshot("redtact-live-branch-3-parent-ended");
		setDetailsOpen(context, false);
		context.setScreen(() -> null);
		context.waitTicks(2);
		check(httpStatus(server + "/api/sessions/" + child2.sessionId) == 200, "親が終わったら子も終わった");

		// 親のホストが同じ回路 (circuitId) を開き直すと、子の分岐元が新しいセッションに付け直される (#87)
		Api.CreateSessionResponse reopened;

		try {
			reopened = parentApi.createSession(new Api.CreateSessionRequest("gametest 終わる親", "gtparent00000001", "26.3", SessionManager.dataVersion(), false));
		} catch (com.redtact.live.net.ApiException e) {
			throw new AssertionError(e.describe(), e);
		}

		String pid3 = reopened.session.sessionId;
		check(!pid3.equals(pid2) && reopened.session.branchList().stream().anyMatch(b -> b.sessionId.equals(child2.sessionId)),
				"開き直した親に子が付いていない: " + reopened.session.branchList());
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().parent() != null
				&& sessions(mc).active().parent().sessionId.equals(pid3) && !sessions(mc).active().parent().ended, TIMEOUT);
		context.waitFor(mc -> com.redtact.live.ui.EventLog.latest(3).stream()
				.anyMatch(e -> e.text().contains("分岐元「gametest 終わる親」のセッションが開き直されました")), TIMEOUT);
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(5);
		List<String> reopenedStatus = context.computeOnClient(mc -> SessionScreen.statusLines(sessions(mc).active(), sessions(mc)));
		check(reopenedStatus.stream().anyMatch(l -> l.contains("分岐元: 「gametest 終わる親」") && l.contains("開催中")), "分岐元が開催中に戻らない: " + reopenedStatus);
		waitMillis(context, 4_000);
		setDetailsOpen(context, true);
		context.takeScreenshot("redtact-live-branch-3b-parent-reopened");
		setDetailsOpen(context, false);
		context.setScreen(() -> null);
		context.waitTicks(2);

		try {
			parentApi.deleteSession(pid3, reopened.hostToken);
		} catch (com.redtact.live.net.ApiException e) {
			throw new AssertionError(e.describe(), e);
		}

		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().parent() != null && sessions(mc).active().parent().ended, TIMEOUT);
		context.runOnClient(mc -> sessions(mc).close());
		context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
		LOGGER.info("branch (child) ok");
	}

	/**
	 * 分岐 (#13) 親の側: 自分のセッションに、テストのコードから別のユーザ名で子を開く → トーストとセッションタブの子の一覧 →
	 * 「重ねて読み込む」で自分の範囲の最小の角に子の配置 (中身は子の回路) → 読み直しても同じ原点 → 子が複製を止めると押せない →
	 * 子が終わると一覧から消える。
	 */
	private static void checkBranchParent(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		boolean litematica = context.computeOnClient(mc -> CircuitLoader.litematicaLoaded());
		Box region = new Box(0, -60, 0, 5, -59, 3);
		BlockPos corner = new BlockPos(0, -60, 0);
		context.runOnClient(mc -> RedtactLive.get().selection().set(region, "minecraft:overworld"));
		sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
		context.waitTicks(5);
		context.runOnClient(mc -> sessions(mc).open("gametest 分岐される親", false, true));
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= 1 && !sessions(mc).isBusy(), TIMEOUT);
		SessionManager.Active a = sessions(context).active();
		CircuitState mine = CircuitState.fromJson(Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + a.sessionId + "/state"), StateJson.State.class));

		// 別のユーザ名で子を開き、少し変えた回路を送る
		List<String> palette = new ArrayList<>(mine.palette());
		palette.add("minecraft:diamond_block");
		int[] blocks = mine.blocks().clone();
		blocks[mine.index(2, 1, 1)] = palette.size() - 1;
		CircuitState childState = new CircuitState(mine.sizeX(), mine.sizeY(), mine.sizeZ(), palette, blocks, mine.blockEntities());
		LiveApi childApi = new LiveApi(LiveApi.newHttpClient(), () -> server, () -> "子ホスト");
		Api.CreateSessionResponse c;

		try {
			c = childApi.createSession(new Api.CreateSessionRequest("子の案", "gtchild000000001", "26.3", SessionManager.dataVersion(), false, null, true,
					new Api.ParentRef(a.sessionId, a.lastVersion())));
			childApi.putState(c.session.sessionId, c.hostToken, childState);
		} catch (com.redtact.live.net.ApiException e) {
			throw new AssertionError(e.describe(), e);
		}

		String cid = c.session.sessionId;
		context.waitFor(mc -> sessions(mc).active().branches().stream().anyMatch(b -> b.sessionId.equals(cid)), TIMEOUT);
		context.waitFor(mc -> com.redtact.live.ui.EventLog.latest(3).stream().anyMatch(e -> e.text().contains("分岐が開かれました") && e.text().contains("子の案")),
				TIMEOUT);
		check(context.computeOnClient(mc -> SessionScreen.statusLines(sessions(mc).active(), sessions(mc))).get(0).contains("分岐 1"), "状態の行に分岐の数が無い");

		// セッションタブの子の一覧
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen g && g.branchCard(cid) != null, TIMEOUT);
		waitMillis(context, 800);
		String label = context.computeOnClient(mc -> ((SessionScreen) mc.gui.screen()).branchCard(cid).overlayLabel());
		check(label.equals(litematica ? "重ねて読み込む" : ".litematic を保存") && context.computeOnClient(mc -> ((SessionScreen) mc.gui.screen()).branchCard(cid).overlayEnabled()),
				"子の行のボタン: " + label);
		context.takeScreenshot("redtact-live-branch-4-parent-branches");

		// 重ねて読み込む -> 自分の範囲の最小の角に子の配置
		CircuitLoader.Result before = context.computeOnClient(mc -> RedtactLive.get().circuitLoader().last());
		CircuitLoader.Result r = loadAndWait(context, before, () -> clickWidget(context,
				s -> s.branchCard(cid).overlayButton().getX() + s.branchCard(cid).overlayButton().getWidth() / 2,
				s -> s.branchCard(cid).overlayButton().getY() + 10));
		check(r.sessionId().equals(cid) && r.purpose() == CircuitLoader.Purpose.OVERLAY && r.title().equals("子の案"), "重ねた結果: " + r);
		verifyLitematicFile(r.file(), "子の案", childState);

		if (litematica) {
			check(r.placed() && !r.replaced() && corner.equals(r.origin()), "自分の範囲の角に置いていない: " + r);
			verifyPlacement(context, cid, corner, childState, 1);
			waitMillis(context, 800);
			context.takeScreenshot("redtact-live-branch-5-overlay-toast");
			context.setScreen(() -> null);
			lookFrom(context, sp, "-2.5 -58 -2.5", "3 -59.5 2");
			context.waitTicks(40);
			context.takeScreenshot("redtact-live-branch-6-overlay");
			// 読み直しても同じ原点で置き換える
			CircuitLoader.Result r2 = loadAndWait(context, r, () -> context.runOnClient(mc ->
					RedtactLive.get().branches().overlay(sessions(mc).active().branches().getFirst())));
			check(r2.replaced() && corner.equals(r2.origin()), "読み直した重ね: " + r2);
			verifyPlacement(context, cid, corner, childState, 1);
		} else {
			check(!r.placed(), "Litematica が無いのに配置した: " + r);
			waitMillis(context, 800);
			context.takeScreenshot("redtact-live-branch-5-overlay-saved-toast");
			context.setScreen(() -> null);
		}

		// 子が複製を止めると「重ねて読み込む」は押せない
		try {
			childApi.setAllowCopy(cid, c.hostToken, false);
		} catch (com.redtact.live.net.ApiException e) {
			throw new AssertionError(e.describe(), e);
		}

		context.waitFor(mc -> sessions(mc).active().branches().stream().anyMatch(b -> b.sessionId.equals(cid) && !b.copyAllowed()), TIMEOUT);
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen g && g.branchCard(cid) != null && !g.branchCard(cid).overlayEnabled(), TIMEOUT);

		// 自分のセッションを終えて同じ回路を開き直すと、子が新しいセッションに付き直る (#87)
		context.setScreen(() -> null);
		context.waitTicks(2);
		String firstSession = a.sessionId;
		context.runOnClient(mc -> sessions(mc).close());
		context.waitFor(mc -> sessions(mc).active() == null && !sessions(mc).isBusy(), TIMEOUT);
		context.waitFor(mc -> httpGetString(server + "/api/sessions/" + cid).contains("\"ended\":true"), TIMEOUT);
		context.runOnClient(mc -> sessions(mc).open("gametest 分岐される親", false, true));
		context.waitFor(mc -> sessions(mc).active() != null && !sessions(mc).isBusy()
				&& sessions(mc).active().branches().stream().anyMatch(b -> b.sessionId.equals(cid)), TIMEOUT);
		check(!sessions(context).active().sessionId.equals(firstSession), "開き直したのに同じセッション");
		context.waitFor(mc -> com.redtact.live.ui.EventLog.latest(5).stream().anyMatch(e -> e.text().contains("前のセッションの分岐 1 件を付け直しました")),
				TIMEOUT);
		String reopenedId = sessions(context).active().sessionId;
		check(httpGetString(server + "/api/sessions/" + cid).contains("\"sessionId\":\"" + reopenedId + "\""), "子の分岐元が新しいセッションになっていない");
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof SessionScreen g && g.branchCard(cid) != null && !g.branchCard(cid).overlayEnabled(), TIMEOUT);
		waitMillis(context, 800);
		context.takeScreenshot("redtact-live-branch-4b-parent-reopened");

		// 子が終わると一覧から消える (開いたままの画面でも)
		try {
			childApi.deleteSession(cid, c.hostToken);
		} catch (com.redtact.live.net.ApiException e) {
			throw new AssertionError(e.describe(), e);
		}

		context.waitFor(mc -> sessions(mc).active().branches().isEmpty() && mc.gui.screen() instanceof SessionScreen g && g.branchCard(cid) == null, TIMEOUT);
		context.setScreen(() -> null);
		context.waitTicks(2);
		context.runOnClient(mc -> sessions(mc).close());
		context.waitFor(mc -> sessions(mc).active() == null, TIMEOUT);
		LOGGER.info("branch (parent) ok (litematica={})", litematica);
	}

	/** セッションタブの状態の板の「詳細」を開く・閉じる。 */
	private static void setDetailsOpen(ClientGameTestContext context, boolean open) {
		context.runOnClient(mc -> {
			if (SessionScreen.detailsOpen() != open && mc.gui.screen() instanceof SessionScreen g) {
				g.toggleDetails();
			}
		});
		context.waitFor(mc -> SessionScreen.detailsOpen() == open, TIMEOUT);
		context.waitTicks(3);
	}

	/** 実時間で待つ (トーストは tick ではなく時計で消えるため)。 */
	private static void waitMillis(ClientGameTestContext context, long millis) {
		long end = System.currentTimeMillis() + millis;

		while (System.currentTimeMillis() < end) {
			context.waitTicks(1);
		}
	}

	/** 回路を setblock で置く (Litematica の貼り付けの代わり)。空気のマスは空気にする。 */
	private static void paste(TestSingleplayerContext sp, CircuitState state, BlockPos at) {
		for (int y = 0; y < state.sizeY(); y++) {
			for (int z = 0; z < state.sizeZ(); z++) {
				for (int x = 0; x < state.sizeX(); x++) {
					String block = state.blockAt(state.index(x, y, z));
					sp.getServer().runCommand("setblock " + (at.getX() + x) + " " + (at.getY() + y) + " " + (at.getZ() + z) + " " + block);
				}
			}
		}
	}

	/** 開催中タブを開き、そのセッションの詳細を (enabled なら「読み込む」を押せる状態で) 開く。 */
	private static void openLiveTabWithRow(ClientGameTestContext context, String sessionId, boolean enabled) {
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && !RedtactLive.get().liveDirectory().state().loading()
				&& g.rows().stream().anyMatch(r -> r.session().sessionId.equals(sessionId)), TIMEOUT);
		selectLive(context, sessionId);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.rowLoadButton() != null && g.rowLoadButton().active == enabled, TIMEOUT);
		context.waitTicks(5);
	}

	/** 開催中タブを一覧の表示で開き、そのセッションのカードが出るまで待つ (詳細を開いていれば「← 一覧」を押す)。 */
	private static void openLiveList(ClientGameTestContext context, String sessionId) {
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		backToLiveList(context);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && !RedtactLive.get().liveDirectory().state().loading()
				&& g.cardFor(sessionId) != null, TIMEOUT);
		context.waitTicks(2);
	}

	/** 詳細を開いていれば「← 一覧」を押して一覧に戻る (#56)。 */
	private static void backToLiveList(ClientGameTestContext context) {
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof LiveListScreen g && g.detail())) {
			return;
		}

		clickLive(context, g -> {
			var b = g.backButton();
			return new int[] {b.getX() + b.getWidth() / 2, b.getY() + 8};
		});
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && !g.detail() && g.selectedId() == null, TIMEOUT);
		context.waitTicks(2);
	}

	/** 開催中タブのグリッドでそのセッションのカードを押して詳細を開く (#56。詳細を開いていれば先に一覧へ戻る)。 */
	private static void selectLive(ClientGameTestContext context, String sessionId) {
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.rows().stream().anyMatch(r -> r.session().sessionId.equals(sessionId)), TIMEOUT);

		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof LiveListScreen g && g.detail() && sessionId.equals(g.selectedId()))) {
			backToLiveList(context);
			context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.cardFor(sessionId) != null, TIMEOUT);
			context.runOnClient(mc -> ((LiveListScreen) mc.gui.screen()).scrollToCard(sessionId));
			context.waitTicks(2);
			clickLive(context, g -> {
				LiveCard c = g.cardFor(sessionId);
				return new int[] {c.getX() + c.getWidth() / 2, c.getY() + c.getHeight() / 3};
			});
		}

		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.detail() && sessionId.equals(g.selectedId()), TIMEOUT);
		context.waitTicks(2);
	}

	/** 一覧の見えているカードのサムネが出る (か、出さない理由が決まる) まで待つ (#56)。 */
	private static void waitThumbs(ClientGameTestContext context) {
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && !g.visibleCards().isEmpty()
				&& g.visibleCards().stream().allMatch(c -> !"読み込み中…".equals(c.thumbMessage())), TIMEOUT);
		context.waitTicks(5);
	}

	/** マウスを画面の左上 (ロゴの上) に置く (撮る前にカードのホバーとツールチップを消す)。 */
	private static void neutralCursor(ClientGameTestContext context) {
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(2);
	}

	/**
	 * 開催中のカードを 480×270 (1080p・GUI 4 と同じ広さ) と 640×360 で撮る (一覧と詳細。#56)。撮ったら窓と GUI の倍率を戻し、一覧に戻る。
	 */
	private static void liveCardShots(ClientGameTestContext context, String detailId) {
		int[] orig = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});

		for (int[] size : new int[][] {{960, 540, 2}, {1280, 720, 2}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				mc.resizeGui();
			});
			context.waitTicks(5);
			String tag = (size[0] / size[2]) + "x" + (size[1] / size[2]);
			backToLiveList(context);
			waitThumbs(context);
			int cols = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).gridColumns());
			int visible = context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).visibleCards().size());
			LOGGER.info("live cards at {}: {} columns, {} cards visible", tag, cols, visible);
			neutralCursor(context);
			context.takeScreenshot("redtact-live-cards-" + tag + "-grid");
			selectLive(context, detailId);
			waitPreview(context, detailId);
			neutralCursor(context);
			context.takeScreenshot("redtact-live-cards-" + tag + "-detail");
		}

		context.getInput().resizeWindow(orig[0], orig[1]);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(orig[2]);
			mc.resizeGui();
		});
		context.waitTicks(5);
		backToLiveList(context);
	}

	/** 詳細のプレビューがそのセッションの回路を描くまで待つ (#34)。 */
	private static PreviewCircuit waitPreview(ClientGameTestContext context, String sessionId) {
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen g && g.preview() != null && g.preview().shownCircuit() != null
				&& g.preview().shownCircuit().sessionId().equals(sessionId), TIMEOUT);
		return context.computeOnClient(mc -> ((LiveListScreen) mc.gui.screen()).preview().shownCircuit());
	}

	/** 読み込みを始めて、新しい結果 (previous と別のもの) が出るまで待つ。 */
	private static CircuitLoader.Result loadAndWait(ClientGameTestContext context, CircuitLoader.Result previous, Runnable start) {
		start.run();
		context.waitFor(mc -> !RedtactLive.get().circuitLoader().isBusy() && RedtactLive.get().circuitLoader().last() != null
				&& RedtactLive.get().circuitLoader().last() != previous, TIMEOUT);
		CircuitLoader.Result r = context.computeOnClient(mc -> RedtactLive.get().circuitLoader().last());
		LOGGER.info("loaded: {}", r);
		return r;
	}

	/** グリッドでそのセッションの詳細を開き、「読み込む」を押す。 */
	private static void clickLiveRow(ClientGameTestContext context, String sessionId) {
		selectLive(context, sessionId);
		clickLive(context, g -> {
			var b = g.rowLoadButton();
			return new int[] {b.getX() + b.getWidth() / 2, b.getY() + 10};
		});
	}

	/** 開催中タブの部品を、カーソルを動かして左クリックする。 */
	private static void clickLive(ClientGameTestContext context, java.util.function.Function<LiveListScreen, int[]> at) {
		hoverLive(context, at);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	private static void hoverLive(ClientGameTestContext context, java.util.function.Function<LiveListScreen, int[]> at) {
		double[] p = context.computeOnClient(mc -> {
			int[] xy = at.apply((LiveListScreen) mc.gui.screen());
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {xy[0] * scale, xy[1] * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.waitTicks(2);
	}

	/** Litematica の配置: 原点・大きさ・中身 (サーバの回路と 1 マスずつ)・同じセッションの配置の数。 */
	private static void verifyPlacement(ClientGameTestContext context, String sessionId, BlockPos origin, CircuitState expected, int placements) {
		LitematicaChecks.Placed p = context.computeOnClient(mc -> LitematicaChecks.placed(sessionId));
		check(p != null, "Litematica の配置が無い");
		check(p.origin().equals(origin), "配置の原点: " + p.origin() + " (想定 " + origin + ")");
		check(p.sizeX() == expected.sizeX() && p.sizeY() == expected.sizeY() && p.sizeZ() == expected.sizeZ(),
				"配置の大きさ: " + p.sizeX() + "x" + p.sizeY() + "x" + p.sizeZ());
		check(p.sameSessionPlacements() == placements, "同じセッションの配置の数: " + p.sameSessionPlacements());

		for (int i = 0; i < p.blocks().size(); i++) {
			var state = p.blocks().get(i);
			String got = state.isAir() ? "minecraft:air" : RegionCapture.toEntry(state).toString();
			check(got.equals(expected.blockAt(i)), "配置の中身の添字 " + i + ": " + got + " != " + expected.blockAt(i));
		}

		// ブロックエンティティ (チェストの中身など) も Litematica に読み込まれている: 位置と id がサーバの回路と同じ
		Map<Integer, String> want = new java.util.TreeMap<>();

		expected.blockEntities().forEach((i, nbt) -> {
			try {
				want.put(i, NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(nbt))).getStringOr("id", ""));
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		check(want.equals(p.blockEntityIds()), "配置のブロックエンティティ: " + p.blockEntityIds() + " != " + want);

		LOGGER.info("placement ok: {} at {} ({}, {} block entities)", p.name(), p.origin(), p.file().getFileName(), p.blockEntityIds().size());
	}

	/** 保存した .litematic (サーバが作ったもの) を MOD の LitematicaBitArray で読み、サーバの回路と比べる。 */
	private static void verifyLitematicFile(Path file, String regionName, CircuitState expected) {
		CompoundTag root;

		try {
			root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}

		CompoundTag region = root.getCompoundOrEmpty("Regions").getCompoundOrEmpty(regionName);
		ListTag palette = region.getListOrEmpty("BlockStatePalette");
		check(palette.size() > 0 && "minecraft:air".equals(palette.getCompoundOrEmpty(0).getStringOr("Name", "")), "palette[0] が air でない: " + file);
		long[] states = region.getLongArray("BlockStates").orElseThrow();
		int bits = LitematicaBitArray.bitsForPaletteSize(palette.size());

		for (int i = 0; i < expected.volume(); i++) {
			CompoundTag e = palette.getCompoundOrEmpty(LitematicaBitArray.get(states, bits, i));
			Map<String, String> props = new java.util.TreeMap<>();
			e.getCompoundOrEmpty("Properties").forEach((k, v) -> props.put(k, v.asString().orElse("")));
			String got = BlockStateEntry.of(e.getStringOr("Name", ""), props).toString();
			check(got.equals(expected.blockAt(i)), file.getFileName() + " の添字 " + i + ": " + got + " != " + expected.blockAt(i));
		}

		check(region.getListOrEmpty("TileEntities").size() == expected.blockEntities().size(), "TileEntities の数");
		LOGGER.info("litematic file ok: {} ({} palette)", file.getFileName(), palette.size());
	}

	/**
	 * 未確認のホストを許さない中継サーバ (REDTACT_LIVE_STRICT_SERVER) では、開発環境のアカウントでは開けず、トーストで知らせる。
	 */
	private static void checkStrictServer(ClientGameTestContext context, String server) {
		String strict = System.getenv("REDTACT_LIVE_STRICT_SERVER");

		if (strict == null || strict.isBlank()) {
			return;
		}

		context.runOnClient(mc -> {
			Configs.Generic.SERVER_URL.setValueFromString(strict);
			Configs.saveToFile();
			RedtactLive.get().selection().set(new Box(0, -60, 0, 5, -59, 3), "minecraft:overworld");
		});
		chat(context, "/rlive open gametest 厳格");
		context.waitFor(mc -> !sessions(mc).isBusy() && com.redtact.live.ui.EventLog.latest(1).stream()
				.anyMatch(e -> e.text().startsWith("マイクラアカウントで確認できませんでした")), TIMEOUT);
		String msg = com.redtact.live.ui.EventLog.latest(1).getFirst().text();
		check(sessions(context).active() == null, "未確認を許さないサーバなのに開けた");
		check(msg.contains("オフラインのアカウントか開発環境のため") || msg.contains("Mojang の認証サーバに接続できないため"), "理由が出ない: " + msg);
		LOGGER.info("strict server: {}", msg);
		context.waitTicks(20 * 7); // 前のトーストが消えて、エラーのトーストが出るまで
		context.takeScreenshot("redtact-live-auth-strict-toast");
		context.runOnClient(mc -> {
			Configs.Generic.SERVER_URL.setValueFromString(server);
			Configs.saveToFile();
		});
	}

	/** セッションタブの部品の真ん中あたりを、カーソルを動かして左クリックする。 */
	private static void clickWidget(ClientGameTestContext context, java.util.function.ToIntFunction<SessionScreen> x, java.util.function.ToIntFunction<SessionScreen> y) {
		double[] p = context.computeOnClient(mc -> {
			SessionScreen s = (SessionScreen) mc.gui.screen();
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {x.applyAsInt(s) * scale, y.applyAsInt(s) * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** 前の版の config.json を、今の設定に引き継げること。 */
	private static void checkMigration(net.minecraft.client.Minecraft mc) {
		try {
			Path dir = mc.gameDirectory.toPath().resolve("redtact-live-migration-test");
			Files.createDirectories(dir);
			Path legacy = dir.resolve("config.json");
			Files.writeString(legacy, "{\"serverUrl\":\"http://example.invalid:1234/\",\"user\":\"旧ユーザ\",\"autoSync\":false,"
					+ "\"autoSyncQuietMs\":4000,\"autoSyncMaxDelayMs\":20000,\"localSnapshotIntervalMs\":60000}");
			check(Configs.migrateFrom(legacy), "引き継ぎに失敗");
			check(Configs.Generic.SERVER_URL.getStringValue().equals("http://example.invalid:1234/"), "serverUrl を引き継げていない");
			check(Configs.Generic.USER.getStringValue().equals("旧ユーザ"), "user を引き継げていない");
			check(!Configs.Generic.AUTO_SYNC.getBooleanValue(), "autoSync を引き継げていない");
			check(Configs.Generic.AUTO_SYNC_QUIET_MS.getIntegerValue() == 4000 && Configs.Generic.AUTO_SYNC_MAX_DELAY_MS.getIntegerValue() == 20000
					&& Configs.Generic.LOCAL_SNAPSHOT_INTERVAL_MS.getIntegerValue() == 60000, "時間の設定を引き継げていない");
			check(!Files.exists(legacy) && Files.exists(dir.resolve("config.json.migrated")), "古いファイルが config.json.migrated になっていない");
			check(Files.exists(Configs.configFile()), "新しい設定ファイルが無い");
			LOGGER.info("migration ok: {}", Configs.configFile());
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * ホットキーを押す: 並べた順に押したままにし、逆順に離す (R を押しながら L など)。gametest の疑似キーは
	 * KeyboardHandler.keyPress を通るので、本物のキーと同じく HotkeyManager (mixin) に届く。
	 */
	private static void keys(ClientGameTestContext context, int... scancodes) {
		for (int k : scancodes) {
			context.getInput().holdKey(k);
		}

		for (int i = scancodes.length - 1; i >= 0; i--) {
			context.getInput().releaseKey(scancodes[i]);
		}

		context.waitTicks(2);
	}

	/** チャットに redtact-live の行が 1 行も無いこと。 */
	@SuppressWarnings("unchecked")
	private static void checkNoChatLines(ClientGameTestContext context) {
		List<String> lines = context.computeOnClient(mc -> {
			try {
				java.lang.reflect.Field f = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
				f.setAccessible(true);
				List<net.minecraft.client.multiplayer.chat.GuiMessage> all = (List<net.minecraft.client.multiplayer.chat.GuiMessage>) f.get(mc.gui.hud.getChat());
				return all.stream().map(m -> m.content().getString()).toList();
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		LOGGER.info("chat lines: {}", lines);
		check(lines.stream().noneMatch(l -> l.contains("redtact-live") || l.contains("同期しました") || l.contains("セッション")),
				"チャットに redtact-live の行がある: " + lines);
	}

	private static int version(ClientGameTestContext context) {
		return context.computeOnClient(LiveClientGameTest::version);
	}

	private static int version(net.minecraft.client.Minecraft mc) {
		SessionManager.Active a = RedtactLive.get().sessions().active();
		return a == null ? -1 : a.lastVersion();
	}

	/** push キー (K) を押して、版が expectedVersion になるまで待つ。 */
	private static void pushWithKey(ClientGameTestContext context, int expectedVersion) {
		keys(context, InputConstants.KEY_K);
		context.waitFor(mc -> sessions(mc).active() != null && sessions(mc).active().lastVersion() >= expectedVersion && !sessions(mc).isBusy(), TIMEOUT);
	}

	/** 直前の push の種類と件数 (-1 は見ない)。 */
	private static void checkPush(ClientGameTestContext context, SessionManager.PushKind kind, int version, int changes, int removed) {
		SessionManager.PushResult r = sessions(context).lastPush();
		LOGGER.info("push result: {}", r);
		check(r != null && r.kind() == kind && r.version() == version, "push の結果が想定と違う: " + r + " (想定 " + kind + " v" + version + ")");
		check(changes < 0 || r.changes() == changes, "changes の件数: " + r);
		check(removed < 0 || r.removedBlockEntities() == removed, "removedBlockEntities の件数: " + r);
	}

	/** サーバの GET /state が、MOD が送ったつもりの状態とワールドの両方に一致すること。 */
	private static void verifyServerState(ClientGameTestContext context, String server, String sessionId, Box box, int version, int chestCount) {
		StateJson.State json = Json.GSON.fromJson(httpGetString(server + "/api/sessions/" + sessionId + "/state"), StateJson.State.class);
		check(json.version != null && json.version == version, "サーバの版: " + json.version + " (想定 " + version + ")");
		CircuitState st = CircuitState.fromJson(json);
		check(st.sizeX() == box.sizeX() && st.sizeY() == box.sizeY() && st.sizeZ() == box.sizeZ(), "サーバの大きさ: " + java.util.Arrays.toString(st.size()));
		SessionManager.Sent sent = sessions(context).active().sent();
		check(sent != null && sent.version() == version && sent.state().sameContent(st), "サーバの状態が MOD の送った状態と違う");
		List<BlockStateEntry> world = worldEntries(context, box);

		for (int i = 0; i < world.size(); i++) {
			check(world.get(i).toString().equals(st.blockAt(i)), "サーバの状態の添字 " + i + ": " + st.blockAt(i) + " != " + world.get(i));
		}

		int chestIndex = st.index(5, 0, 1);
		byte[] chest = st.blockEntities().get(chestIndex);

		if (chestCount > 0) {
			check(chest != null, "サーバの状態にチェストのブロックエンティティが無い");

			try {
				CompoundTag tag = NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(chest)));
				check("minecraft:chest".equals(tag.getStringOr("id", "")) && !tag.contains("x")
						&& tag.getListOrEmpty("Items").getCompoundOrEmpty(0).getIntOr("count", 0) == chestCount, "チェストの NBT: " + tag);
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		} else {
			check(chest == null, "壊したチェストのブロックエンティティが残っている");
		}

		LOGGER.info("server state v{} ok: {} palette, {} block entities", version, st.palette().size(), st.blockEntities().size());
	}

	private static List<BlockStateEntry> worldEntries(ClientGameTestContext context, Box box) {
		return context.computeOnClient(mc -> {
			List<BlockStateEntry> list = new ArrayList<>();

			for (int y = box.minY(); y <= box.maxY(); y++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					for (int x = box.minX(); x <= box.maxX(); x++) {
						var state = mc.level.getBlockState(new BlockPos(x, y, z));
						list.add(state.isAir() ? BlockStateEntry.AIR : RegionCapture.toEntry(state));
					}
				}
			}

			return list;
		});
	}

	/** ローカルの litematic の中身がクライアントのワールドと一致すること。 */
	private static void verifySnapshot(ClientGameTestContext context, Path file, Box box, boolean expectChest) {
		CompoundTag root;

		try {
			root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}

		check(root.getIntOr("Version", -1) == 7 && root.getIntOr("SubVersion", -1) == 1, "Version/SubVersion");
		int dataVersion = SessionManager.dataVersion();
		check(root.getIntOr("MinecraftDataVersion", -1) == dataVersion, "MinecraftDataVersion");
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		check(regions.size() == 1, "リージョンが 1 つでない");
		CompoundTag region = regions.getCompoundOrEmpty(NAME);
		CompoundTag size = region.getCompoundOrEmpty("Size");
		check(size.getIntOr("x", 0) == box.sizeX() && size.getIntOr("y", 0) == box.sizeY() && size.getIntOr("z", 0) == box.sizeZ(), "Size");
		ListTag palette = region.getListOrEmpty("BlockStatePalette");
		check("minecraft:air".equals(palette.getCompoundOrEmpty(0).getStringOr("Name", "")), "palette[0] が air でない");
		long[] states = region.getLongArray("BlockStates").orElseThrow();
		int bits = LitematicaBitArray.bitsForPaletteSize(palette.size());

		List<BlockStateEntry> world = worldEntries(context, box);

		int nonAir = 0;

		for (int i = 0; i < world.size(); i++) {
			CompoundTag e = palette.getCompoundOrEmpty(LitematicaBitArray.get(states, bits, i));
			Map<String, String> props = new java.util.TreeMap<>();
			e.getCompoundOrEmpty("Properties").forEach((k, v) -> props.put(k, v.asString().orElse("")));
			BlockStateEntry got = BlockStateEntry.of(e.getStringOr("Name", ""), props);
			check(got.equals(world.get(i)), "添字 " + i + ": " + got + " != " + world.get(i));

			if (!got.isAir()) {
				nonAir++;
			}
		}

		check(root.getCompoundOrEmpty("Metadata").getIntOr("TotalBlocks", -1) == nonAir, "TotalBlocks");
		ListTag tes = region.getListOrEmpty("TileEntities");
		boolean chest = false;

		for (int i = 0; i < tes.size(); i++) {
			CompoundTag te = tes.getCompoundOrEmpty(i);

			if ("minecraft:chest".equals(te.getStringOr("id", ""))) {
				chest = te.getIntOr("x", -1) == 5 && te.getIntOr("y", -1) == 0 && te.getIntOr("z", -1) == 1
						&& te.getListOrEmpty("Items").getCompoundOrEmpty(0).getIntOr("count", 0) == 13;
			}
		}

		check(chest == expectChest, "チェストのブロックエンティティ (相対座標・中身): " + tes);
		LOGGER.info("snapshot {} ok: {} palette, {} non-air, {} tile entities", file.getFileName(), palette.size(), nonAir, tes.size());
	}

	private static void chat(ClientGameTestContext context, String text) {
		context.getInput().pressKey(options -> options.keyChat);
		context.waitForScreen(ChatScreen.class);
		context.getInput().typeChars(text);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(3);
	}

	private static SessionManager sessions(ClientGameTestContext context) {
		return context.computeOnClient(mc -> RedtactLive.get().sessions());
	}

	private static SessionManager sessions(net.minecraft.client.Minecraft mc) {
		return RedtactLive.get().sessions();
	}

	private static Optional<CircuitRecord> findCircuit(ClientGameTestContext context, String name) {
		try {
			return RedtactLive.get().sessions().store().findByName(name);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static List<StoredComment> readComments(String circuitId) {
		try {
			return RedtactLive.get().sessions().store().readComments(circuitId);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static Path latestSnapshot(ClientGameTestContext context, String circuitId) {
		Path dir = RedtactLive.get().sessions().store().snapshotsDir(circuitId);
		Path latest = null;

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.litematic")) {
			for (Path p : ds) {
				if (latest == null || version(p) > version(latest)) {
					latest = p;
				}
			}
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}

		check(latest != null, "スナップショットが無い: " + dir);
		return latest;
	}

	private static int countSnapshots(ClientGameTestContext context, String circuitId) {
		try {
			return RedtactLive.get().sessions().store().countSnapshots(circuitId);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static int version(Path p) {
		String n = p.getFileName().toString();
		return Integer.parseInt(n.substring(n.lastIndexOf("-v") + 2, n.length() - ".litematic".length()));
	}

	private static void postComment(String server, String sessionId, String user, String text) {
		try {
			HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server + "/api/sessions/" + sessionId + "/comments"))
					.header("Content-Type", "application/json")
					.header("X-Live-User", user)
					.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(Map.of("text", text)), StandardCharsets.UTF_8))
					.build(), HttpResponse.BodyHandlers.ofString());
			check(res.statusCode() == 201, "コメント投稿: " + res.statusCode() + " " + res.body());
		} catch (java.io.IOException | InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] httpGetBytes(String url) {
		try {
			HttpResponse<byte[]> res = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
					.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
			check(res.statusCode() == 200, "GET " + url + " -> " + res.statusCode());
			return res.body();
		} catch (java.io.IOException | InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static String httpGetString(String url) {
		return new String(httpGetBytes(url), StandardCharsets.UTF_8);
	}

	private static int httpStatus(String url) {
		try {
			return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (java.io.IOException | InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}
}
