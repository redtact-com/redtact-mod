package com.redtact.live.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.CommentsScreen;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.LiveListScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.PreviewScreen;
import com.redtact.live.gui.SessionScreen;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.cards.SelectableCard;
import com.redtact.live.i18n.UiLanguage;
import com.redtact.live.library.LibraryEntry;
import com.redtact.live.library.LiveLibrary;
import com.redtact.live.net.Json;
import com.redtact.live.selection.Box;
import com.redtact.live.session.SessionManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * README と Modrinth に載せる画面を撮る (#105)。{@code REDTACT_LIVE_README_SHOTS=1} のときだけ LiveClientGameTest から呼ぶ。
 *
 * <p>確かめはせず、撮るだけ。英語の画面・1280×720 (GUI の倍率 2)。手元の中継サーバ (閲覧者の表示名を受ける
 * {@code LIVE_ALLOW_UNVERIFIED_VIEWERS=1}) が要る。開催中の一覧にほかのカードを出すなら、先に {@code fake-host -listed} を動かしておく。
 * 撮ったものは build/run/clientGameTest/screenshots/ の {@code readme-*.png}。
 */
@SuppressWarnings("UnstableApiUsage")
final class ReadmeShots {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final int TIMEOUT = 20 * 30;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	static final String TITLE = "4-bit lamp display";
	/** 回路の北西の角。床は Y - 1 (草を滑らかな石に替える)、回路は Y。ほかの確認と重ならない場所。 */
	private static final int X = 100;
	private static final int Y = -60;
	private static final int Z = 100;
	/** 左から 1011 */
	private static final boolean[] BITS = {true, false, true, true};

	private ReadmeShots() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		context.runOnClient(mc -> {
			Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.EN);
			Configs.Generic.USER.setValueFromString("Builder");
			Configs.saveToFile();
		});
		LiveClientGameTestShots.resize(context, new int[] {1280, 720, 2});
		build(sp);
		// 回路のそばから見下ろす (遠いとチャンクが読み込まれず、回路を写せない)
		sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:feather");
		// 飛んでいないと落ちるので、見えない足場 (バリア) の上に立たせる
		sp.getServer().runCommand("setblock " + (X + 4) + " " + (Y + 5) + " " + (Z - 4) + " minecraft:barrier");
		sp.getServer().runCommand("tp @a " + (X + 4.5) + " " + (Y + 6) + " " + (Z - 3.5) + " 0 47");
		context.waitTicks(20);
		sp.getConnection().waitForChunksRender();
		context.waitTicks(20);

		// ---- 範囲 (床も入れる) を選び、一覧に公開・複製を許可でセッションを開く
		Box box = new Box(X, Y - 1, Z, X + 8, Y, Z + 6);
		context.runOnClient(mc -> RedtactLive.get().selection().set(box, "minecraft:overworld"));
		context.runOnClient(mc -> RedtactLive.get().sessions().open(TITLE, true, true));
		context.waitFor(mc -> sessions().active() != null && sessions().active().lastVersion() >= 1 && !sessions().isBusy(), TIMEOUT);
		SessionManager.Active a = context.computeOnClient(mc -> sessions().active());
		LOGGER.info("[readme] session {} ({})", a.sessionId, TITLE);

		postComment(server, a.sessionId, "Alex", "Nice! Which lamp is the lowest bit?");
		postComment(server, a.sessionId, "Mika", "Could you add a carry output next?");
		context.waitFor(mc -> sessions().unread() >= 2, TIMEOUT);

		// ---- 1. ゲームの中: 羽根を持って回路を見下ろす (範囲の枠と HUD)
		context.setScreen(() -> null);
		context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false)); // ゲームモードの知らせを写さない
		context.waitTicks(30);
		LibraryFolderChecks.cleanShot(context, "readme-1-world");

		// ---- 2. live のセッションタブ
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(10);
		LibraryFolderChecks.cleanShot(context, "readme-2-session");

		// ---- 3. コメントタブ: 閲覧者の 2 つに返事を書く
		context.runOnClient(mc -> Menu.openComments(a.circuitId));
		context.waitForScreen(CommentsScreen.class);
		context.waitFor(mc -> comments(mc).composer() != null && a.sessionId.equals(comments(mc).composer().sessionId()), TIMEOUT);
		String reply = "Thanks! The right lamp is bit 0.";
		LibraryClientGameTest.clickWidget(context, mc -> comments(mc).composer().field());
		context.getInput().typeChars(reply);
		context.waitTicks(2);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitFor(mc -> comments(mc).rows().stream().anyMatch(r -> reply.equals(r.comment().text)), TIMEOUT);
		context.waitTicks(10);
		LibraryFolderChecks.cleanShot(context, "readme-3-comments");

		// ---- 4. 開催中の一覧 (fake-host のカードも)
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof LiveListScreen s && !s.rows().isEmpty(), TIMEOUT);
		LiveClientGameTestShots.wait(context, 4_000); // カードの立体のプレビューを描き終えるまで
		LibraryFolderChecks.cleanShot(context, "readme-4-live-list");

		// ---- 5. 保管庫: セッションタブの「保管庫へ」→ 保管庫で選んで立体のプレビュー
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitFor(mc -> session(mc).libraryButton() != null && session(mc).libraryButton().disabledReason() == null, TIMEOUT);
		context.runOnClient(mc -> LiveLibrary.save(false)); // 「保管庫へ」と同じ
		context.waitFor(mc -> !LiveLibrary.busy() && LiveLibrary.lastSaved() != null, TIMEOUT);
		LibraryEntry saved = context.computeOnClient(mc -> LiveLibrary.lastSaved());
		Path file = context.computeOnClient(mc -> RedtactLive.get().library().file(saved));
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		context.runOnClient(mc -> Menu.open(MenuTab.LIBRARY));
		context.waitForScreen(LibraryScreen.class);
		// 保管庫の行を本物のマウスで押して選ぶ (LibraryPreviewChecks と同じ。見えていなければ送ってから)
		context.waitFor(mc -> card(library(mc), file) != null, TIMEOUT);
		context.runOnClient(mc -> library(mc).list().scrollToTop(card(library(mc), file)));
		context.waitTicks(6);
		double[] at = context.computeOnClient(mc -> {
			SelectableCard c = card(library(mc), file);
			return new double[] {c.getX() + 20, c.getY() + c.height() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, at[0], at[1]);
		context.waitFor(mc -> file.equals(LibraryNav.selected()) && library(mc).preview() != null && library(mc).preview().shownCircuit() != null, TIMEOUT);
		context.waitTicks(10);
		LibraryFolderChecks.cleanShot(context, "readme-5-vault");

		// ---- 6. 全画面のプレビュー
		LibraryClientGameTest.clickWidget(context, mc -> library(mc).preview().fullscreenButton());
		context.waitFor(mc -> mc.gui.screen() instanceof PreviewScreen p && p.scene().mesh() != null && !p.building(), TIMEOUT);
		context.waitTicks(10);
		LibraryFolderChecks.cleanShot(context, "readme-6-fullscreen");

		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.setScreen(() -> null);
		LOGGER.info("[readme] shots done (session {})", a.sessionId);
	}

	/** 4 本のレバー → 赤石 → リピーター → 赤石 → ランプ。床は滑らかな石。 */
	private static void build(TestSingleplayerContext sp) {
		run(sp, "fill " + X + " " + (Y - 1) + " " + Z + " " + (X + 8) + " " + (Y - 1) + " " + (Z + 6) + " minecraft:smooth_stone");

		for (int i = 0; i < BITS.length; i++) {
			int x = X + 1 + 2 * i;
			run(sp, "setblock " + x + " " + Y + " " + (Z + 5) + " minecraft:redstone_lamp");
			run(sp, "setblock " + x + " " + Y + " " + (Z + 4) + " minecraft:redstone_wire");
			run(sp, "setblock " + x + " " + Y + " " + (Z + 3) + " minecraft:repeater[facing=north,delay=" + (i + 1) + "]");
			run(sp, "setblock " + x + " " + Y + " " + (Z + 2) + " minecraft:redstone_wire");
		}

		// レバーは最後に (置いたときの更新で信号が流れる)
		for (int i = 0; i < BITS.length; i++) {
			int x = X + 1 + 2 * i;
			run(sp, "setblock " + x + " " + Y + " " + (Z + 1) + " minecraft:lever[face=floor,facing=north,powered=" + BITS[i] + "]");
		}
	}

	private static void run(TestSingleplayerContext sp, String command) {
		sp.getServer().runCommand(command);
	}

	private static void postComment(String server, String sessionId, String user, String text) {
		try {
			HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(server + "/api/sessions/" + sessionId + "/comments"))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.header("X-Live-User", URLEncoder.encode(user, StandardCharsets.UTF_8))
					.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(Map.of("text", text)), StandardCharsets.UTF_8))
					.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (res.statusCode() != 201) {
				throw new AssertionError("コメントを入れられない (中継サーバに LIVE_ALLOW_UNVERIFIED_VIEWERS=1 が要る): " + res.statusCode() + " " + res.body());
			}
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static SessionManager sessions() {
		return RedtactLive.get().sessions();
	}

	private static SessionScreen session(Minecraft mc) {
		return (SessionScreen) mc.gui.screen();
	}

	private static CommentsScreen comments(Minecraft mc) {
		return (CommentsScreen) mc.gui.screen();
	}

	private static LibraryScreen library(Minecraft mc) {
		return (LibraryScreen) mc.gui.screen();
	}

	private static SelectableCard card(LibraryScreen s, Path file) {
		for (MenuCard c : s.list().cards()) {
			if (c instanceof SelectableCard sc && sc.file().equals(file)) {
				return sc;
			}
		}

		return null;
	}
}
