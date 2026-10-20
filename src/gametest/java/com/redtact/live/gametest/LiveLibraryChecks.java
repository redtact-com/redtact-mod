package com.redtact.live.gametest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.LiveListScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.SessionScreen;
import com.redtact.live.gui.cards.LibraryCard;
import com.redtact.live.library.Library;
import com.redtact.live.library.LibraryActions;
import com.redtact.live.library.LibraryEntry;
import com.redtact.live.library.LiveLibrary;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.selection.Box;
import com.redtact.live.session.SessionManager;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/**
 * 開催中の自分の回路を保管庫へ・redtact に投稿 (#73)。ボタンは本物のマウスで押す:
 * <ol>
 * <li>複製を許可せずに開く (中継サーバの .litematic は 403。保管庫へ入れるのは MOD の手元の版なので関係ない)
 * <li>セッションタブの「保管庫へ」→ schematics/redtact/&lt;名前&gt;-v1.litematic に origin created の行。中身は同じ版のローカルの控えと同じバイト
 * <li>もう一度押しても行は増えない。回路を変えて同期すると、次の版は別の行
 * <li>開催中タブの自分のセッションの詳細にも 2 つ出る (他人のセッション REDTACT_LIVE_OTHER_TITLE には出ない)
 * <li>「redtact に投稿」→ MCEF Modern があれば redtact タブの左に保管庫・右に投稿の画面で、そのファイルを選んだ状態。
 *     左から右のページへドラッグするとページに届く。無ければ外のブラウザで投稿の画面と、保管庫のフォルダを開く
 * <li>保管庫タブの行は「redtact」の代わりに「redtact に投稿」(押せる)
 * <li>終了するとセッションタブから消える
 * </ol>
 */
final class LiveLibraryChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	static final String NAME = "gametest 保管庫へ";
	private static final int TIMEOUT = 20 * 30;
	/** MCEF Modern の Chromium の用意 (初回は取ってくる)。 */
	private static final int BROWSER_TIMEOUT = 20 * 600;

	private LiveLibraryChecks() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		Box box = new Box(0, -60, 0, 5, -59, 3);
		boolean autoBefore = context.computeOnClient(mc -> Configs.Generic.AUTO_SYNC.getBooleanValue());
		context.runOnClient(mc -> {
			Configs.Generic.AUTO_SYNC.setBooleanValue(false); // 版を手で進める
			RedtactLive.get().selection().set(box, "minecraft:overworld");
		});
		sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
		context.waitTicks(5);

		// ---- 一覧に出し、複製は許可せずに開く
		context.runOnClient(mc -> sessions().open(NAME, true, false));
		context.waitFor(mc -> sessions().active() != null && sessions().active().lastVersion() >= 1 && !sessions().isBusy(), TIMEOUT);
		SessionManager.Active a = context.computeOnClient(mc -> sessions().active());
		check(!a.allowCopy(), "複製を許可せずに開けていない");
		check(httpStatus(server + "/api/sessions/" + a.sessionId + "/litematic") == 403, "複製を許可していないのに中継サーバから .litematic を取れる");
		String prefix = "live/" + a.circuitId + "/";
		// 前の実行で入れた行を消す (実行のフォルダは残るので、同じ名前の回路は同じ回路 ID になる)
		context.runOnClient(mc -> {
			Library lib = RedtactLive.get().library();

			for (LibraryEntry e : lib.list()) {
				if (e.sourceKey != null && e.sourceKey.startsWith(prefix)) {
					try {
						lib.remove(e.storedName);
					} catch (IOException ex) {
						throw new AssertionError(ex);
					}
				}
			}
		});

		try {
			// ---- セッションタブの 2 段目の右に「保管庫へ」「redtact に投稿」(最初の同期が済んでいるので押せる)
			context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
			context.waitForScreen(SessionScreen.class);
			context.waitFor(mc -> session(mc).libraryButton() != null && session(mc).libraryButton().disabledReason() == null
					&& session(mc).postButton() != null && session(mc).postButton().disabledReason() == null, TIMEOUT);
			int[] rowY = context.computeOnClient(mc -> new int[] {session(mc).libraryButton().getY(), session(mc).copyButton().getY(),
					session(mc).postButton().getX() + session(mc).postButton().getWidth(), mc.gui.screen().width});
			check(rowY[0] == rowY[1], "「保管庫へ」が「複製を許可」と同じ段に無い: " + Arrays.toString(rowY));
			check(rowY[2] <= rowY[3], "「redtact に投稿」が画面の外にはみ出す: " + Arrays.toString(rowY));
			LibraryFolderChecks.cleanShot(context, "redtact-live-live-library-1-session");

			// ---- 「保管庫へ」(本物のクリック) → v1 の行。中身は v1 のローカルの控えと同じ
			LibraryEntry v1 = saveWith(context, mc -> session(mc).libraryButton(), prefix + "v1");
			check(v1.storedName.equals(NAME + "-v1.litematic") && v1.created() && v1.label.equals(NAME + " v1"), "v1 の行: " + v1.storedName + " / " + v1.label);
			sameBytes(context, v1, a.circuitId, 1);
			check(rows(context, prefix).size() == 1, "v1 の行の数: " + rows(context, prefix).size());
			context.waitTicks(20); // トーストが滑り込むまで
			context.takeScreenshot("redtact-live-live-library-2-saved-toast");

			// ---- もう一度押しても増えない (同じ版は上書き)
			saveWith(context, mc -> session(mc).libraryButton(), prefix + "v1");
			check(rows(context, prefix).size() == 1, "同じ版なのに行が増えた: " + rows(context, prefix));

			// ---- 回路を変えて同期 (手動) → 次の版は別の行 (ほかの確認で置かないブロックにする。同じブロックだと変化なしで版が進まない)
			sp.getServer().runCommand("setblock 2 -59 3 minecraft:lapis_block");
			context.waitTicks(5);
			context.runOnClient(mc -> sessions().push());
			context.waitFor(mc -> sessions().active().lastVersion() >= 2 && !sessions().isBusy(), TIMEOUT);
			LibraryEntry v2 = saveWith(context, mc -> session(mc).libraryButton(), prefix + "v2");
			sameBytes(context, v2, a.circuitId, 2);
			check(rows(context, prefix).equals(List.of(NAME + "-v1.litematic", NAME + "-v2.litematic")), "v1・v2 の行: " + rows(context, prefix));

			// ---- 開催中タブ: 自分のセッションの詳細に 2 つ。「保管庫へ」は同じ版なので増えない
			context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
			context.waitForScreen(LiveListScreen.class);
			context.waitFor(mc -> list(mc) != null && list(mc).rows().stream().anyMatch(r -> r.session().sessionId.equals(a.sessionId)), TIMEOUT);
			context.runOnClient(mc -> list(mc).select(a.sessionId));
			context.waitFor(mc -> list(mc).detail() && list(mc).rowLibraryButton() != null && list(mc).rowLibraryButton().disabledReason() == null
					&& list(mc).rowPostButton() != null, TIMEOUT);
			// 詳細は左にプレビューと操作・右にコメント (#74)。5 つのボタンは左の板 (コメントの欄の左) に収め、入らなければ下の段へ
			int[] detail = context.computeOnClient(mc -> new int[] {list(mc).rowPostButton().getX() + list(mc).rowPostButton().getWidth(),
					list(mc).composer().field().frameX(), list(mc).rowLibraryButton().getY(), list(mc).rowLoadButton().getY()});
			check(detail[0] < detail[1] && detail[2] >= detail[3], "詳細のボタンの位置: " + Arrays.toString(detail));
			LibraryFolderChecks.cleanShot(context, "redtact-live-live-library-3-live-detail");
			saveWith(context, mc -> list(mc).rowLibraryButton(), prefix + "v2");
			check(rows(context, prefix).size() == 2, "開催中タブからの「保管庫へ」で行が増えた: " + rows(context, prefix));
			checkOtherSession(context);

			// ---- 「redtact に投稿」
			if (BrowserSupport.available()) {
				postInGame(context, v2);
			} else {
				postExternal(context, v2);
			}

			// ---- 保管庫タブ: live から入れた行は「redtact に投稿」(押せる)・ふつうの行は「redtact」のまま
			context.runOnClient(mc -> {
				Menu.open(MenuTab.LIBRARY);
				LibraryNav.go(LibraryNav.libraryDir());
			});
			context.waitFor(mc -> mc.gui.screen() instanceof LibraryScreen s && s.libraryCards().stream().anyMatch(c -> c.entry().storedName.equals(v2.storedName)),
					TIMEOUT);
			LibraryCard card = context.computeOnClient(mc -> ((LibraryScreen) mc.gui.screen()).libraryCards().stream()
					.filter(c -> c.entry().storedName.equals(v2.storedName)).findFirst().orElseThrow());
			// 字は「投稿」だけ (ファイル名の幅を空ける)、名前「redtact に投稿」はツールチップの先頭 (#77)
			check(card.pageButton().getMessage().getString().equals(LibraryCard.POST_LABEL) && card.pageButton().disabledReason() == null
					&& card.pageButton().tooltipText() != null && card.pageButton().tooltipText().startsWith(LibraryCard.POST_NAME + "\n"),
					"保管庫の行の投稿ボタン: " + card.pageButton().getMessage().getString() + " / " + card.pageButton().disabledReason() + " / "
							+ card.pageButton().tooltipText());
			LibraryFolderChecks.cleanShot(context, "redtact-live-live-library-5-library-row");
		} finally {
			context.runOnClient(mc -> {
				if (sessions().active() != null) {
					sessions().close();
				}
			});
			context.waitFor(mc -> sessions().active() == null, TIMEOUT);
			context.runOnClient(mc -> Configs.Generic.AUTO_SYNC.setBooleanValue(autoBefore));
		}

		// ---- 終了するとセッションタブから消える
		context.runOnClient(mc -> Menu.open(MenuTab.SESSION));
		context.waitForScreen(SessionScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> session(mc).libraryButton() == null && session(mc).postButton() == null), "終了したのにセッションタブに「保管庫へ」がある");
		context.setScreen(() -> null);
		context.waitTicks(2);
		LOGGER.info("[live-library] 開催中の回路を保管庫へ (版ごとの行・同じ版の上書き・ローカルの控えと同じ中身)・redtact に投稿・開催中タブ・保管庫の行を確かめた");
	}

	/** 他人のセッション (fake-host を裏で動かしているとき) の詳細には出ない。 */
	private static void checkOtherSession(ClientGameTestContext context) {
		String otherTitle = System.getenv("REDTACT_LIVE_OTHER_TITLE");

		if (otherTitle == null || otherTitle.isBlank()) {
			LOGGER.info("[live-library] REDTACT_LIVE_OTHER_TITLE が無いので、他人のセッションの詳細は確かめない");
			return;
		}

		context.waitFor(mc -> list(mc).rows().stream().anyMatch(r -> r.session().title.equals(otherTitle)), TIMEOUT);
		String otherId = context.computeOnClient(mc -> list(mc).rows().stream().filter(r -> r.session().title.equals(otherTitle)).findFirst().orElseThrow()
				.session().sessionId);
		context.runOnClient(mc -> list(mc).select(otherId));
		context.waitFor(mc -> list(mc).detail() && otherId.equals(list(mc).selectedId()), TIMEOUT);
		check(context.computeOnClient(mc -> list(mc).rowLibraryButton() == null && list(mc).rowPostButton() == null), "他人のセッションに「保管庫へ」「redtact に投稿」がある");
		LibraryFolderChecks.cleanShot(context, "redtact-live-live-library-3b-other-detail");
		context.runOnClient(mc -> list(mc).backToList());
	}

	/** MCEF Modern が無い: 外のブラウザで投稿の画面と、保管庫のフォルダを開く。 */
	private static void postExternal(ClientGameTestContext context, LibraryEntry v2) {
		List<String> external = new CopyOnWriteArrayList<>();
		List<Path> folders = new CopyOnWriteArrayList<>();
		context.runOnClient(mc -> {
			BrowserSupport.setExternalOpener(external::add);
			LibraryActions.setFolderOpener(folders::add);
			Menu.open(MenuTab.SESSION);
		});

		try {
			context.waitForScreen(SessionScreen.class);
			context.waitFor(mc -> session(mc).postButton() != null && session(mc).postButton().disabledReason() == null, TIMEOUT);
			LibraryClientGameTest.clickWidget(context, mc -> session(mc).postButton());
			context.waitFor(mc -> !external.isEmpty() && !folders.isEmpty() && !LiveLibrary.busy(), TIMEOUT);
			String want = context.computeOnClient(mc -> RedtactLinks.newCircuitPage(BrowserSupport.home()));
			Path dir = context.computeOnClient(mc -> LibraryNav.libraryDir().toAbsolutePath().normalize());
			check(external.equals(List.of(want)), "外のブラウザで投稿の画面を開いていない: " + external);
			check(folders.equals(List.of(dir)), "保管庫のフォルダを開いていない: " + folders + " (想定 " + dir + ")");
			check(context.computeOnClient(mc -> LiveLibrary.lastSaved()).storedName.equals(v2.storedName), "投稿の前に入れた行が v2 でない");
			check(rows(context, v2.sourceKey.substring(0, v2.sourceKey.lastIndexOf('/') + 1)).size() == 2, "「redtact に投稿」で行が増えた");
			context.takeScreenshot("redtact-live-live-library-4-post-external");
			LOGGER.info("[live-library] MCEF Modern 無し: 外のブラウザ {} とフォルダ {}", external, folders);
		} finally {
			context.runOnClient(mc -> {
				BrowserSupport.setExternalOpener(null);
				LibraryActions.setFolderOpener(null);
			});
		}
	}

	/** MCEF Modern がある: redtact タブの左に保管庫・右に投稿の画面 (手元のページ)。そのファイルを選んだ状態で、右へドラッグすると届く。 */
	private static void postInGame(ClientGameTestContext context, LibraryEntry v2) {
		HttpServer page;

		try {
			page = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		String home = "http://localhost:" + page.getAddress().getPort();
		page.createContext("/", ex -> {
			byte[] body = ex.getRequestURI().getPath().equals("/circuits/new") ? DropChecks.NEW_PAGE.getBytes(StandardCharsets.UTF_8)
					: "<!doctype html><title>home</title>".getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			ex.sendResponseHeaders(200, body.length);

			try (OutputStream out = ex.getResponseBody()) {
				out.write(body);
			}
		});
		page.start();
		List<String> external = new CopyOnWriteArrayList<>();
		String savedUrl = context.computeOnClient(mc -> Configs.Generic.REDTACT_URL.getStringValue());

		try {
			context.runOnClient(mc -> {
				Configs.Generic.REDTACT_URL.setValueFromString(home);
				BrowserSupport.setExternalOpener(external::add);
				RedtactLive.get().fileSelection().clear();
				Menu.open(MenuTab.SESSION);
			});
			context.waitForScreen(SessionScreen.class);
			context.waitFor(mc -> session(mc).postButton() != null && session(mc).postButton().disabledReason() == null, TIMEOUT);
			LibraryClientGameTest.clickWidget(context, mc -> session(mc).postButton());
			String newPage = home + "/circuits/new";
			// 用意がまだなら redtact タブの入口で待ち、済んだらそのタブにブラウザが出る
			context.waitFor(mc -> DropChecks.browser(mc) != null && DropChecks.browser(mc).pane() != null && DropChecks.browser(mc).pageUrl().startsWith(newPage)
					&& !DropChecks.browser(mc).loading(), BROWSER_TIMEOUT);
			Path file = context.computeOnClient(mc -> RedtactLive.get().library().file(v2).toAbsolutePath().normalize());
			context.waitFor(mc -> DropChecks.selection().equals(List.of(file)), TIMEOUT);
			check(context.computeOnClient(mc -> LibraryNav.libraryDir().toAbsolutePath().normalize().equals(LibraryNav.current().toAbsolutePath().normalize())),
					"左の保管庫が schematics/redtact/ でない: " + context.computeOnClient(mc -> LibraryNav.current()));
			int[] widths = context.computeOnClient(mc -> new int[] {DropChecks.browser(mc).pageLeft(), DropChecks.browser(mc).width});
			check(widths[0] == widths[1] / 2, "ページが右半分でない: " + widths[0] + " / " + widths[1]);
			check(external.isEmpty(), "外のブラウザを開いた: " + external);
			context.waitTicks(20);
			context.takeScreenshot("redtact-live-live-library-4-post-split");

			// 左の v2 を右のドロップ欄へ (本物のドラッグ) → ページに中身ごと届く
			long bytes = Files.size(file);
			DropChecks.dragToPage(context, mc -> DropChecks.pick(mc, v2.storedName), 0.1, 0.1, "redtact-live-live-library-4b-dragging");
			DropChecks.waitHash(context, "drop:" + v2.storedName + ":" + bytes);
			LOGGER.info("[live-library] redtact タブの左に保管庫・右に投稿の画面。{} をドロップで渡せた", v2.storedName);
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			context.runOnClient(mc -> {
				Configs.Generic.REDTACT_URL.setValueFromString(savedUrl);
				BrowserSupport.setExternalOpener(null);
				RedtactLive.get().fileSelection().clear();
				LibraryNav.setPaneShown(false);
			});
			page.stop(0);
		}
	}

	/** ボタンを本物のマウスで押し、その版の行が入るまで待つ。 */
	private static LibraryEntry saveWith(ClientGameTestContext context, java.util.function.Function<Minecraft, net.minecraft.client.gui.components.AbstractWidget> button,
			String sourceKey) {
		int before = context.computeOnClient(mc -> RedtactLive.get().library().revision());
		LibraryClientGameTest.clickWidget(context, button);
		context.waitFor(mc -> !LiveLibrary.busy() && RedtactLive.get().library().revision() > before && LiveLibrary.lastSaved() != null
				&& sourceKey.equals(LiveLibrary.lastSaved().sourceKey), TIMEOUT);
		LibraryEntry e = context.computeOnClient(mc -> LiveLibrary.lastSaved());
		check(context.computeOnClient(mc -> RedtactLive.get().library().exists(e)), "保管庫にファイルが無い: " + e.storedName);
		return e;
	}

	/** 保管庫のファイルが、同じ版のローカルの控え (MOD のローカル保存) と同じバイトか。 */
	private static void sameBytes(ClientGameTestContext context, LibraryEntry e, String circuitId, int version) {
		Path snapshot = context.computeOnClient(mc -> snapshot(circuitId, version));
		Path file = context.computeOnClient(mc -> RedtactLive.get().library().file(e));

		try {
			check(Arrays.equals(Files.readAllBytes(snapshot), Files.readAllBytes(file)), "保管庫の v" + version + " がローカルの控えと違う: " + file + " / " + snapshot);
		} catch (IOException ex) {
			throw new AssertionError(ex);
		}
	}

	/** その版のローカルの控えのうち新しいもの (名前の頭が時刻。前のセッションの同じ版も残っている)。 */
	private static Path snapshot(String circuitId, int version) {
		Path dir = sessions().store().snapshotsDir(circuitId);
		Path latest = null;

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*-v" + version + ".litematic")) {
			for (Path p : ds) {
				if (latest == null || p.getFileName().toString().compareTo(latest.getFileName().toString()) > 0) {
					latest = p;
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		if (latest == null) {
			throw new AssertionError("v" + version + " のローカルの控えが無い: " + dir);
		}

		return latest;
	}

	/** その回路の保管庫の行 (ファイル名の順)。 */
	private static List<String> rows(ClientGameTestContext context, String sourcePrefix) {
		return context.computeOnClient(mc -> {
			Library lib = RedtactLive.get().library();
			return lib.list().stream().filter(x -> x.sourceKey != null && x.sourceKey.startsWith(sourcePrefix)).map(x -> x.storedName).sorted().toList();
		});
	}

	private static int httpStatus(String url) {
		try {
			return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException e) {
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
		return mc.gui.screen() instanceof SessionScreen s ? s : null;
	}

	private static LiveListScreen list(Minecraft mc) {
		return mc.gui.screen() instanceof LiveListScreen s ? s : null;
	}

	private static void check(boolean ok, String message) {
		LibraryClientGameTest.check(ok, message);
	}
}
