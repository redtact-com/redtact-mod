package com.redtact.live.gametest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.gui.LiveListScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.kit.LiveGrid;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.rows.LiveRow;
import com.redtact.live.live.LiveDirectory;
import com.redtact.live.live.LiveQuery;
import com.redtact.live.live.LiveText;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import com.redtact.live.session.SessionManager;
import com.redtact.live.state.CircuitState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 開催中の一覧の検索・並べ替え・フォロー中 (#90)。押すのは本物のマウス、打つのは本物の字の入力とキー。
 * <ul>
 * <li>2 人のホスト (表示名の taro と hana) が一覧に出したセッションを開く (taro が先)。閲覧数は、ほかの回線 (CF-Connecting-IP) から
 * 詳細の取得 (GET /api/sessions/{id}/comments。MOD で詳細を開いているのと同じ) を送って数えさせる (taro 3・hana 1)</li>
 * <li>検索欄に打つと、打ち終わって少ししてから探し、合うものだけが出る (大文字小文字は区別しない・題でも)。Enter ならすぐ。
 * タブを開き直しても探す字は残る</li>
 * <li>並べ替えのボタン (押すと次・右クリックで前) で、新しい順・視聴中・閲覧数が中継サーバの並びと同じになる</li>
 * <li>「フォロー中」: 開発環境のアカウントは確かめられないので問い合わせず、空の板に「マイクラアカウントでの確認が要ります」</li>
 * <li>狭い画面 (480×270・いちばん狭い 320×240) では検索欄から縮み、並べ替えとトグルは画面に入る。撮る</li>
 * </ul>
 * 最後に探す字・並べ替え・フォロー中を既定に戻し、開いたセッションを閉じる (後の確かめに残さない)。
 */
@SuppressWarnings("UnstableApiUsage")
final class SearchChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final int TIMEOUT = 20 * 30;
	private static final String TARO_TITLE = "gametest 検索 クロック回路";
	private static final String HANA_TITLE = "gametest 検索 4bit 加算器";

	private SearchChecks() {
	}

	/** 開いたセッション (ID とホストのトークン)。 */
	private record Opened(String id, String token, LiveApi api) {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext sp, String server) {
		sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
		context.waitTicks(5);
		Opened taro = open(server, "taro", TARO_TITLE);
		LiveClientGameTestShots.wait(context, 50); // 開始の時刻をずらす (新しい順を hana・taro にする)
		Opened hana = open(server, "hana", HANA_TITLE);

		try {
			for (int i = 1; i <= 3; i++) {
				watch(server, taro.id(), "203.0.113." + i);
			}

			watch(server, hana.id(), "203.0.113.9");
			checkViews(server, taro.id(), 3);
			checkViews(server, hana.id(), 1);

			openList(context);
			context.waitFor(mc -> has(mc, taro.id()) && has(mc, hana.id()), TIMEOUT);
			checkRowFits(context, "既定の窓");
			waitThumbs(context);
			LiveClientGameTestShots.neutralCursor(context);
			context.takeScreenshot("redtact-live-search-0-list");

			search(context, server, taro, hana);
			sort(context, server, taro, hana);
			following(context, server);
			narrowShots(context, taro, hana);
			reset(context);
		} finally {
			close(taro);
			close(hana);
		}

		context.setScreen(() -> null);
		context.waitTicks(2);
		LOGGER.info("live list search, sort and following ok");
	}

	/** 検索欄: 打ち終わってから探す・大文字小文字・題でも・Enter ですぐ・タブを開き直しても残る。 */
	private static void search(ClientGameTestContext context, String server, Opened taro, Opened hana) {
		clickField(context);
		long t0 = System.currentTimeMillis();
		context.getInput().typeChars("HANA");
		long typed = System.currentTimeMillis() - t0;

		if (typed < LiveListScreen.SEARCH_DELAY_MS - 100) {
			check(context.computeOnClient(mc -> LiveListScreen.query().q()).isEmpty(), "打ち終わる前に探した (" + typed + " ms)");
		} else {
			LOGGER.info("typing took {} ms, skip the debounce check", typed);
		}

		waitQuery(context, LiveQuery.DEFAULT.withQ("HANA"));
		checkOnly(context, hana.id(), taro.id(), "hana");
		LiveClientGameTestShots.neutralCursor(context);
		context.takeScreenshot("redtact-live-search-1-hana");

		// タブを開き直しても探す字と結果は残る (ゲームを閉じるまで)
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);
		check("HANA".equals(context.computeOnClient(mc -> screen(mc).searchField().getValue())), "開き直したら検索欄が空になった");
		checkOnly(context, hana.id(), taro.id(), "hana");

		// 題で探す (打ち直し)。Enter なら打ち終わりを待たない
		clickField(context);

		for (int i = 0; i < 4; i++) {
			context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		}

		context.getInput().typeChars("クロック");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		check("クロック".equals(context.computeOnClient(mc -> LiveListScreen.query().q())), "Enter ですぐに探さない: " + context.computeOnClient(mc -> LiveListScreen.query()));
		waitQuery(context, LiveQuery.DEFAULT.withQ("クロック"));
		checkOnly(context, taro.id(), hana.id(), "クロック");

		// 合うものが無い
		setSearch(context, "合うものの無い字 zzz");
		waitQuery(context, LiveQuery.DEFAULT.withQ("合うものの無い字 zzz"));
		check(context.computeOnClient(mc -> screen(mc).rows().isEmpty()), "合わないのに一覧に出た");
		check("条件に合うセッションはありません".equals(context.computeOnClient(mc -> screen(mc).emptyText())), "空の板: " + context.computeOnClient(mc -> screen(mc).emptyText()));
		LiveClientGameTestShots.neutralCursor(context);
		context.takeScreenshot("redtact-live-search-1b-no-match");

		setSearch(context, "");
		waitQuery(context, LiveQuery.DEFAULT);
		context.waitFor(mc -> has(mc, taro.id()) && has(mc, hana.id()), TIMEOUT);
	}

	/** 並べ替え: 押すと 新しい順 → 視聴中 → 閲覧数、右クリックで前。並びは中継サーバと同じ。 */
	private static void sort(ClientGameTestContext context, String server, Opened taro, Opened hana) {
		checkOrder(context, server, LiveQuery.DEFAULT, List.of(hana.id(), taro.id()));
		check("新しい順".equals(sortLabel(context)), "並べ替えの名前: " + sortLabel(context));

		// 視聴中 (直近 15 秒に詳細を取りに来た回線): 押す直前に取り直させて、taro 3・hana 1 にしておく
		for (int i = 1; i <= 3; i++) {
			watch(server, taro.id(), "203.0.113." + i);
		}

		watch(server, hana.id(), "203.0.113.9");
		click(context, mc -> screen(mc).sortButton(), InputConstants.MOUSE_BUTTON_LEFT);
		check("視聴中".equals(sortLabel(context)), "押しても視聴中にならない: " + sortLabel(context));
		LiveQuery viewers = LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWERS);
		waitQuery(context, viewers);
		checkOrder(context, server, viewers, List.of(taro.id(), hana.id()));

		click(context, mc -> screen(mc).sortButton(), InputConstants.MOUSE_BUTTON_LEFT);
		LiveQuery views = LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWS);
		waitQuery(context, views);
		check("閲覧数".equals(sortLabel(context)), "閲覧数にならない: " + sortLabel(context));
		checkOrder(context, server, views, List.of(taro.id(), hana.id()));
		LiveRow taroRow = row(context, taro.id());
		check(taroRow.session().views != null && taroRow.session().views == 3, "taro の閲覧数: " + taroRow.session().views);
		check(taroRow.audienceText().contains("閲覧数 3"), "視聴中と閲覧数の字: " + taroRow.audienceText());
		waitThumbs(context);
		LiveClientGameTestShots.neutralCursor(context);
		context.takeScreenshot("redtact-live-search-2-views");
		// カードの右上の数に重ねると、何の数かが出る
		hoverViews(context, taro.id());
		String tip = com.redtact.live.gui.kit.Draw.lastFullText();
		check(tip != null && tip.contains("閲覧数 3"), "閲覧数のツールチップ: " + tip);
		context.takeScreenshot("redtact-live-search-2b-views-tooltip");

		// 右クリックで前へ (閲覧数 → 視聴中 → 新しい順)
		click(context, mc -> screen(mc).sortButton(), InputConstants.MOUSE_BUTTON_RIGHT);
		check("視聴中".equals(sortLabel(context)), "右クリックで前に戻らない: " + sortLabel(context));
		click(context, mc -> screen(mc).sortButton(), InputConstants.MOUSE_BUTTON_RIGHT);
		check("新しい順".equals(sortLabel(context)), "右クリックで新しい順に戻らない: " + sortLabel(context));
		waitQuery(context, LiveQuery.DEFAULT);
		checkOrder(context, server, LiveQuery.DEFAULT, List.of(hana.id(), taro.id()));
	}

	/**
	 * 「フォロー中」: 開発環境のアカウントは確かめられないので、中継サーバに問い合わせずに理由を空の板に出す
	 * (中継サーバはトークンの無い following=1 を 401 signin_required で断る)。もう一度押すと戻る。
	 */
	private static void following(ClientGameTestContext context, String server) {
		click(context, mc -> screen(mc).followingToggle(), InputConstants.MOUSE_BUTTON_LEFT);
		check(context.computeOnClient(mc -> screen(mc).followingToggle().on()), "押してもトグルが点かない");
		LiveQuery follow = LiveQuery.DEFAULT.withFollowing(true);
		waitQuery(context, follow);
		LiveDirectory.State s = context.computeOnClient(mc -> RedtactLive.get().liveDirectory().state());
		check(s.following() != null && s.following().status() == LiveDirectory.FollowStatus.UNVERIFIED, "フォロー中の様子: " + s.following());
		check(s.sessions().isEmpty() && context.computeOnClient(mc -> screen(mc).rows().isEmpty()), "確かめられないのに一覧が出た");
		String empty = context.computeOnClient(mc -> screen(mc).emptyText());
		check(empty.startsWith("フォロー中で絞るには、マイクラアカウントでの確認が要ります"), "空の板: " + empty);
		check(!context.computeOnClient(mc -> screen(mc).settingsButton().visible), "連携の案内のボタンが出た (確かめられないだけ)");
		check(empty.equals(LiveText.emptyText(follow, s.following())), "空の板の字が違う");
		LOGGER.info("following with the dev account: {}", empty);
		LiveClientGameTestShots.neutralCursor(context);
		context.takeScreenshot("redtact-live-search-3-following-unverified");

		// 中継サーバの側: トークンの無い following=1 は 401 signin_required
		HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(server + "/api/sessions?following=1")).GET());
		check(res.statusCode() == 401 && res.body().contains("signin_required"), "トークンなしの following=1: " + res.statusCode() + " " + res.body());

		click(context, mc -> screen(mc).followingToggle(), InputConstants.MOUSE_BUTTON_LEFT);
		check(!context.computeOnClient(mc -> screen(mc).followingToggle().on()), "もう一度押してもトグルが消えない");
		waitQuery(context, LiveQuery.DEFAULT);
	}

	/**
	 * 狭い画面: 検索欄から縮み、並べ替えとトグルが画面に入る (探す字を入れたまま撮る)。480×270 (1080p・GUI 4 と同じ広さ) と、
	 * いちばん狭い 320 幅 (1280×960・GUI 4。マイクラは高さが 240 を切る倍率にしないので、1280×720 では GUI 3 の 427×240 になる)。
	 */
	private static void narrowShots(ClientGameTestContext context, Opened taro, Opened hana) {
		setSearch(context, "gametest 検索");
		waitQuery(context, LiveQuery.DEFAULT.withQ("gametest 検索"));

		try {
			for (int[] size : new int[][] {{960, 540, 2}, {1280, 960, 4}}) {
				LiveClientGameTestShots.resize(context, size);
				int[] gui = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
				String tag = gui[0] + "x" + gui[1];
				context.waitFor(mc -> screen(mc).searchField() != null && has(mc, taro.id()) && has(mc, hana.id()), TIMEOUT);
				checkRowFits(context, tag);
				int searchW = context.computeOnClient(mc -> screen(mc).searchField().frameW());
				LOGGER.info("search row at {}: search field {} px, info from x={}", tag, searchW, context.computeOnClient(mc -> screen(mc).infoX()));

				if (gui[0] <= 330) {
					check(searchW == LiveGrid.SEARCH_MIN_W, "いちばん狭い画面で検索欄がいちばん狭い幅にならない: " + searchW + " (" + tag + ")");
				} else {
					check(searchW > LiveGrid.SEARCH_MIN_W, "広さがあるのに検索欄がいちばん狭い: " + tag);
				}

				waitThumbs(context);
				LiveClientGameTestShots.neutralCursor(context);
				context.takeScreenshot("redtact-live-search-" + tag);
			}
		} finally {
			LiveClientGameTestShots.restore(context);
		}
	}

	/** 探す字・並べ替え・フォロー中を既定に戻す (後の確かめは既定の一覧で見る)。 */
	private static void reset(ClientGameTestContext context) {
		setSearch(context, "");

		while (!"新しい順".equals(sortLabel(context))) {
			click(context, mc -> screen(mc).sortButton(), InputConstants.MOUSE_BUTTON_LEFT);
		}

		if (context.computeOnClient(mc -> screen(mc).followingToggle().on())) {
			click(context, mc -> screen(mc).followingToggle(), InputConstants.MOUSE_BUTTON_LEFT);
		}

		waitQuery(context, LiveQuery.DEFAULT);
	}

	// ---- 確かめ

	/** 一覧の上の段: 検索欄・並べ替え・トグルが重ならずに画面に入る。 */
	private static void checkRowFits(ClientGameTestContext context, String tag) {
		String problem = context.computeOnClient(mc -> {
			LiveListScreen g = screen(mc);
			RTextField f = g.searchField();
			int right = g.width - MenuScreen.MARGIN;

			if (f == null || g.sortButton() == null || g.followingToggle() == null) {
				return "部品が無い";
			}

			if (f.frameX() + f.frameW() > g.sortButton().getX() || g.sortButton().getRight() > g.followingToggle().getX()) {
				return "重なる: 検索欄 " + f.frameX() + "+" + f.frameW() + " 並べ替え " + g.sortButton().getX() + "-" + g.sortButton().getRight()
						+ " トグル " + g.followingToggle().getX();
			}

			if (g.followingToggle().getRight() > right) {
				return "トグルが画面の外: " + g.followingToggle().getRight() + " > " + right;
			}

			return null;
		});
		check(problem == null, "一覧の上の段 (" + tag + "): " + problem);
	}

	/** 一覧に first だけが出て (other は出ない)、出ているものはどれも word を名前か題に含む。 */
	private static void checkOnly(ClientGameTestContext context, String first, String other, String word) {
		context.waitFor(mc -> has(mc, first) && !has(mc, other), TIMEOUT);
		List<LiveRow> rows = context.computeOnClient(mc -> screen(mc).rows());
		String w = word.toLowerCase(java.util.Locale.ROOT);

		for (LiveRow r : rows) {
			String host = r.session().host == null ? "" : r.session().host.toLowerCase(java.util.Locale.ROOT);
			String title = r.session().title == null ? "" : r.session().title.toLowerCase(java.util.Locale.ROOT);
			check(host.contains(w) || title.contains(w) || r.redtactName() != null, "「" + word + "」に合わない行: " + r.session().title + " by " + r.session().host);
		}
	}

	/** 一覧の並び (このテストの 2 つだけ) が want で、中継サーバに同じ問い合わせをしたときと同じ。 */
	private static void checkOrder(ClientGameTestContext context, String server, LiveQuery q, List<String> want) {
		List<String> shown = context.computeOnClient(mc -> screen(mc).rows()).stream().map(r -> r.session().sessionId).filter(want::contains).toList();
		List<String> onServer = LiveText.parseList(send(HttpRequest.newBuilder(URI.create(server + "/api/sessions" + q.queryString())).GET()).body()).stream()
				.map(s -> s.sessionId).filter(want::contains).toList();
		check(shown.equals(want), q.sort() + " の並び: " + shown + " (欲しいのは " + want + ")");
		check(onServer.equals(want), q.sort() + " の中継サーバの並び: " + onServer);
	}

	private static void checkViews(String server, String id, int views) {
		Api.Session s = LiveText.parseList(send(HttpRequest.newBuilder(URI.create(server + "/api/sessions")).GET()).body()).stream()
				.filter(x -> x.sessionId.equals(id)).findFirst().orElseThrow(() -> new AssertionError("一覧に無い: " + id));
		check(s.views != null && s.views == views, id + " の閲覧数: " + s.views + " (欲しいのは " + views + ")");
	}

	// ---- 操作

	private static void openList(ClientGameTestContext context) {
		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);

		if (context.computeOnClient(mc -> screen(mc).detail())) {
			context.runOnClient(mc -> screen(mc).backToList());
			context.waitFor(mc -> !screen(mc).detail(), TIMEOUT);
		}

		context.waitFor(mc -> screen(mc).searchField() != null, TIMEOUT);
	}

	/** 問い合わせが q になり、その一覧が取れるまで待つ。 */
	private static void waitQuery(ClientGameTestContext context, LiveQuery q) {
		context.waitFor(mc -> {
			LiveDirectory.State s = RedtactLive.get().liveDirectory().state();
			return LiveListScreen.query().equals(q) && s.query().equals(q) && !s.loading() && s.fetchedAt() != null;
		}, TIMEOUT);
		context.waitTicks(2);
	}

	/** 検索欄の字を入れ替えて Enter (字の入れ替えは中身を直に。打つ確かめは {@link #search})。 */
	private static void setSearch(ClientGameTestContext context, String text) {
		clickField(context);
		context.runOnClient(mc -> screen(mc).searchField().setValue(text));
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
	}

	private static void clickField(ClientGameTestContext context) {
		double[] p = context.computeOnClient(mc -> {
			RTextField f = screen(mc).searchField();
			return new double[] {f.frameX() + f.frameW() / 2.0, f.frameY() + f.frameH() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
		context.waitFor(mc -> screen(mc).searchField().isFocused(), TIMEOUT);
	}

	private static void click(ClientGameTestContext context, Function<Minecraft, AbstractWidget> widget, int button) {
		double[] p = context.computeOnClient(mc -> {
			AbstractWidget w = widget.apply(mc);
			check(w != null && w.active && w.visible, "押せない部品: " + w);
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.waitTicks(1);
		context.getInput().pressMouse(button);
		context.waitTicks(2);
	}

	/** そのセッションのカードの右上の数 (視聴中と閲覧数) にマウスを重ねる。 */
	private static void hoverViews(ClientGameTestContext context, String id) {
		context.runOnClient(mc -> screen(mc).scrollToCard(id));
		context.waitTicks(2);
		double[] p = context.computeOnClient(mc -> {
			var c = screen(mc).cardFor(id);
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			// サムネの右上の印 (右端から少し内側・上から 8)
			return new double[] {(c.getX() + c.getWidth() - LiveGrid.THUMB_INSET - 8) * scale, (c.getY() + LiveGrid.THUMB_INSET + 8) * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.waitTicks(3);
	}

	private static void waitThumbs(ClientGameTestContext context) {
		context.waitFor(mc -> screen(mc).visibleCards().stream().allMatch(c -> !"読み込み中…".equals(c.thumbMessage())), TIMEOUT);
		context.waitTicks(3);
	}

	private static String sortLabel(ClientGameTestContext context) {
		return context.computeOnClient(mc -> screen(mc).sortButton().getMessage().getString());
	}

	private static boolean has(Minecraft mc, String id) {
		return mc.gui.screen() instanceof LiveListScreen g && g.rows().stream().anyMatch(r -> r.session().sessionId.equals(id));
	}

	private static LiveRow row(ClientGameTestContext context, String id) {
		return context.computeOnClient(mc -> screen(mc).rows()).stream().filter(r -> r.session().sessionId.equals(id)).findFirst()
				.orElseThrow(() -> new AssertionError("一覧に無い: " + id));
	}

	private static LiveListScreen screen(Minecraft mc) {
		check(mc.gui.screen() instanceof LiveListScreen, "画面が開催中でない: " + mc.gui.screen());
		return (LiveListScreen) mc.gui.screen();
	}

	// ---- 中継サーバ

	/** 表示名のホストで、一覧に出すセッションを開いて小さな回路を送る。 */
	private static Opened open(String server, String host, String title) {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> server, () -> host);

		try {
			Api.CreateSessionResponse r = api.createSession(new Api.CreateSessionRequest(title, "gtsearch" + host, "26.3", SessionManager.dataVersion(), true));
			int[] blocks = new int[3 * 2 * 3];

			for (int i = 0; i < 9; i++) {
				blocks[i] = 1; // 下の段は石
			}

			blocks[9 + 4] = host.equals("hana") ? 2 : 1;
			api.putState(r.session.sessionId, r.hostToken,
					new CircuitState(3, 2, 3, List.of("minecraft:air", "minecraft:stone", "minecraft:redstone_block"), blocks, Map.of()));
			return new Opened(r.session.sessionId, r.hostToken, api);
		} catch (ApiException e) {
			throw new AssertionError("セッションを開けない (" + host + "): " + e.describe(), e);
		}
	}

	private static void close(Opened o) {
		try {
			o.api().deleteSession(o.id(), o.token());
		} catch (ApiException e) {
			LOGGER.warn("gametest のセッションを閉じられない: {}", e.describe());
		}
	}

	/** ほかの回線から、MOD で詳細を開いたのと同じ取得をする (視聴中と閲覧数に数えさせる)。 */
	private static void watch(String server, String id, String ip) {
		HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(server + "/api/sessions/" + id + "/comments")).header("CF-Connecting-IP", ip).GET());
		check(res.statusCode() == 200, "詳細の取得: " + res.statusCode() + " " + res.body());
	}

	private static HttpResponse<String> send(HttpRequest.Builder b) {
		try {
			return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
