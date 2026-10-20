package com.redtact.live.gametest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.gui.CommentsScreen;
import com.redtact.live.gui.LiveListScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.rows.CommentRow;
import com.redtact.live.net.Api;
import com.redtact.live.net.Json;
import com.redtact.live.selection.Box;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.StoredComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * MOD からのコメント (#74)。自分のセッションにはコメントタブの入力欄から (Enter)、他人の一覧に出ていないセッションには
 * 開催中タブのリンク欄の「開く」で詳細を開いて右の欄から (「送信」) 書く。入力は本物のクリック・字の入力・キー。
 * gametest のアカウントは Mojang に join できないので表示名 (X-Live-User) で送る (中継サーバは LIVE_ALLOW_UNVERIFIED_VIEWERS=1)。
 */
@SuppressWarnings("UnstableApiUsage")
final class CommentChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final int TIMEOUT = 20 * 30;
	private static final String NAME = "gametest コメント";

	private CommentChecks() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext sp, String server, String hostName) {
		context.runOnClient(mc -> RedtactLive.get().selection().set(new Box(0, -60, 0, 5, -59, 3), "minecraft:overworld"));
		sp.getServer().runCommand("tp @a -3 -57.5 -3 -45 25");
		context.waitTicks(5);
		context.runOnClient(mc -> RedtactLive.get().sessions().open(NAME));
		context.waitFor(mc -> sessions().active() != null && sessions().active().lastVersion() >= 1 && !sessions().isBusy(), TIMEOUT);
		SessionManager.Active a = sessions().active();
		check(a.circuitName.equals(NAME), "コメントの確認のセッションが開かない: " + a.circuitName);
		CircuitRecord rec = context.computeOnClient(mc -> sessions().listCircuits().stream().filter(r -> r.circuitId.equals(a.circuitId)).findFirst().orElseThrow());
		waitMillis(context, 6_000); // 開始のトーストが消えるまで (撮る絵にかぶるため)

		ownSession(context, server, hostName, a, rec);
		otherSession(context, server, hostName);

		// セッションを終えると、コメントタブの欄は書けなくなる (過去のコメントは見られる)
		context.runOnClient(mc -> Menu.openComments(rec.circuitId));
		context.waitForScreen(CommentsScreen.class);
		context.runOnClient(mc -> sessions().close());
		context.waitFor(mc -> sessions().active() == null, TIMEOUT);
		context.waitFor(mc -> screen(CommentsScreen.class).composer().sessionId() == null, TIMEOUT);
		check(!context.computeOnClient(mc -> screen(CommentsScreen.class).composer().button().active), "終わったセッションに書ける");
		check(context.computeOnClient(mc -> screen(CommentsScreen.class).rows().size()) == 2, "終えたら過去のコメントが見えない");
		context.setScreen(() -> null);
		context.waitTicks(2);
		LOGGER.info("comments from the mod ok");
	}

	/** 自分のセッション: コメントタブの上の欄に書いて Enter → ホストの WebSocket で届いて「ホスト」の印付きで一覧に入る。知らせない。 */
	private static void ownSession(ClientGameTestContext context, String server, String hostName, SessionManager.Active a, CircuitRecord rec) {
		context.runOnClient(mc -> Menu.openComments(rec.circuitId));
		context.waitForScreen(CommentsScreen.class);
		context.waitFor(mc -> screen(CommentsScreen.class).composer() != null && a.sessionId.equals(screen(CommentsScreen.class).composer().sessionId()), TIMEOUT);
		check(context.computeOnClient(mc -> screen(CommentsScreen.class).rows().isEmpty()), "新しい回路なのにコメントがある");
		context.takeScreenshot("redtact-live-comments-0-own-empty");

		String reply = "返事: 2 tick で合っています";
		clickField(context, mc -> screen(CommentsScreen.class).composer().field());
		context.getInput().typeChars(reply);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> screen(CommentsScreen.class).composer().button().active), "書いたのに「送信」を押せない");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitFor(mc -> readComments(rec.circuitId).stream().anyMatch(c -> reply.equals(c.text) && c.byHost()), TIMEOUT);
		// 開いている間に届いたものも一覧に入る。欄は空になる。自分のコメントは未読にも数えない
		context.waitFor(mc -> screen(CommentsScreen.class).rows().stream().anyMatch(r -> reply.equals(r.comment().text) && r.comment().byHost() && !r.unread()), TIMEOUT);
		check(context.computeOnClient(mc -> screen(CommentsScreen.class).composer().field().getValue()).isEmpty(), "送ったのに欄が空にならない");
		check(context.computeOnClient(mc -> sessions().unread()) == 0, "自分のコメントが未読に数えられた");
		JsonObject onServer = lastComment(server, a.sessionId);
		check(onServer.get("authorIsHost").getAsBoolean() && hostName.equals(onServer.get("author").getAsString()) && reply.equals(onServer.get("text").getAsString()),
				"サーバのコメント: " + onServer);
		context.waitTicks(5);
		context.takeScreenshot("redtact-live-comments-1-own-reply");

		// 閲覧者のコメントは今までどおり届いて未読になり、開いている一覧にも入る
		postComment(server, a.sessionId, "viewer5", "ありがとうございます、試してみます");
		context.waitFor(mc -> screen(CommentsScreen.class).rows().stream().anyMatch(r -> "viewer5".equals(r.comment().author)), TIMEOUT);
		List<CommentRow> rows = context.computeOnClient(mc -> screen(CommentsScreen.class).rows());
		check(rows.size() == 2 && rows.get(0).comment().author.equals("viewer5") && !rows.get(0).comment().byHost() && rows.get(1).comment().byHost(),
				"コメントタブの行 (新しい順): " + rows);
		waitMillis(context, 7_000); // コメントのトーストが消えるまで
		context.takeScreenshot("redtact-live-comments-2-own-thread");

	}

	/**
	 * 他人の一覧に出ていないセッション: リンク欄に ID を入れて「開く」→ 詳細の右にコメント (ホストの返事に「ホスト」)
	 * → 書いて「送信」→ サーバに届き「自分」の印 → ほかの人の新着は 5 秒ごとに入る → 終わったら一覧に戻る。
	 */
	private static void otherSession(ClientGameTestContext context, String server, String hostName) {
		String[] created = createSession(server, "hana", "hana の 4bit 加算器");
		String id = created[0];
		String token = created[1];
		putState(server, id, token);
		postComment(server, id, "hana", "見てください。下のリピーターが怪しいです");
		postComment(server, id, "viewer3", "クロックの周期はいくつですか？");

		context.runOnClient(mc -> Menu.open(MenuTab.LIVE_LIST));
		context.waitForScreen(LiveListScreen.class);

		if (context.computeOnClient(mc -> screen(LiveListScreen.class).detail())) {
			context.runOnClient(mc -> screen(LiveListScreen.class).backToList());
			context.waitFor(mc -> !screen(LiveListScreen.class).detail(), TIMEOUT);
		}

		context.waitFor(mc -> screen(LiveListScreen.class).openButton() != null, TIMEOUT);
		check(context.computeOnClient(mc -> screen(LiveListScreen.class).rows().stream().noneMatch(r -> r.session().sessionId.equals(id))),
				"一覧に出していないのに一覧にある");
		context.runOnClient(mc -> screen(LiveListScreen.class).linkField().setValue(server + "/s/" + id));
		clickButton(context, mc -> screen(LiveListScreen.class).openButton());
		context.waitFor(mc -> screen(LiveListScreen.class).detail() && id.equals(screen(LiveListScreen.class).selectedId())
				&& screen(LiveListScreen.class).commentRows().size() == 2, TIMEOUT);
		List<CommentRow> rows = context.computeOnClient(mc -> screen(LiveListScreen.class).commentRows());
		check(rows.get(0).comment().author.equals("viewer3") && !rows.get(0).comment().byHost() && rows.get(1).comment().author.equals("hana")
				&& rows.get(1).comment().byHost() && rows.stream().noneMatch(CommentRow::mine), "詳細のコメント (新しい順): " + rows);
		check(id.equals(context.computeOnClient(mc -> screen(LiveListScreen.class).composer().sessionId())), "詳細の入力欄の書く先が違う");
		// MOD で詳細を開いている人も視聴中と閲覧数に数える (#90。詳細のコメントの取得で。前は WebSocket の閲覧者だけで 0 だった)
		JsonObject watched = getJson(server + "/api/sessions/" + id);
		check(watched.get("viewerCount").getAsInt() == 1 && watched.get("views").getAsInt() == 1, "MOD の詳細の視聴中・閲覧数: " + watched.get("viewerCount")
				+ "・" + watched.get("views"));
		LibraryPreviewWait.preview(context, id);
		context.takeScreenshot("redtact-live-comments-3-other-detail");

		String text = "下のリピーターを 2 tick にすると止まりませんでした";
		clickField(context, mc -> screen(LiveListScreen.class).composer().field());
		context.getInput().typeChars(text);
		clickButton(context, mc -> screen(LiveListScreen.class).composer().button());
		context.waitFor(mc -> screen(LiveListScreen.class).commentRows().stream().anyMatch(r -> text.equals(r.comment().text) && r.mine()), TIMEOUT);
		JsonObject mine = lastComment(server, id);
		check(text.equals(mine.get("text").getAsString()) && hostName.equals(mine.get("author").getAsString()) && !mine.get("authorIsHost").getAsBoolean(),
				"他人のセッションに書いたコメント: " + mine);
		check(context.computeOnClient(mc -> screen(LiveListScreen.class).composer().field().getValue()).isEmpty(), "送ったのに欄が空にならない");

		// ほかの人の新着は数秒ごとに入る (書いたあとは自分の分も重ねない)。hana の連投にならないよう 2 秒空ける
		waitMillis(context, 2_100);
		postComment(server, id, "hana", "本当だ、ありがとう！");
		context.waitFor(mc -> screen(LiveListScreen.class).commentRows().stream().anyMatch(r -> "本当だ、ありがとう！".equals(r.comment().text) && r.comment().byHost()), TIMEOUT);
		check(context.computeOnClient(mc -> screen(LiveListScreen.class).commentRows()).stream().filter(r -> text.equals(r.comment().text)).count() == 1,
				"自分のコメントが重なった");
		neutralCursor(context);
		context.takeScreenshot("redtact-live-comments-4-other-thread");
		detailShots(context, id);

		// 連投は 2 秒に 1 件まで: 送れなかった理由を欄の上の行とトーストに出し、書いた字は残す。間を空けて送り直すと通る
		sendWithEnter(context, "1 件目");
		context.waitFor(mc -> screen(LiveListScreen.class).commentRows().stream().anyMatch(r -> "1 件目".equals(r.comment().text)), TIMEOUT);
		sendWithEnter(context, "2 件目");
		context.waitFor(mc -> screen(LiveListScreen.class).composer().statusIsError(), TIMEOUT);
		String status = context.computeOnClient(mc -> screen(LiveListScreen.class).composer().status());
		check("続けて送れるのは 2 秒に 1 件までです".equals(status), "連投の理由: " + status);
		check("2 件目".equals(context.computeOnClient(mc -> screen(LiveListScreen.class).composer().field().getValue())), "送れなかったのに欄が消えた");
		context.waitTicks(10);
		context.takeScreenshot("redtact-live-comments-5-rate-limited");
		waitMillis(context, 2_100);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitFor(mc -> screen(LiveListScreen.class).commentRows().stream().anyMatch(r -> "2 件目".equals(r.comment().text))
				&& screen(LiveListScreen.class).composer().status() == null, TIMEOUT);

		// セッションが終わったら一覧に戻る
		httpDelete(server + "/api/sessions/" + id, token);
		context.waitFor(mc -> !screen(LiveListScreen.class).detail() && screen(LiveListScreen.class).selectedId() == null, TIMEOUT);
	}

	/**
	 * 狭い画面 (480×270 = 1080p・GUI 4)・640×360 と、いちばん狭い 320×180 (720p・GUI 4。左の板に操作のボタンが入らず 2 段になる)
	 * で詳細を撮る。撮ったら窓と GUI の倍率を戻す。
	 */
	private static void detailShots(ClientGameTestContext context, String id) {
		int[] orig = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});

		for (int[] size : new int[][] {{960, 540, 2}, {1280, 720, 2}, {1280, 720, 4}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				mc.resizeGui();
			});
			context.waitTicks(5);
			context.waitFor(mc -> screen(LiveListScreen.class).detail() && !screen(LiveListScreen.class).commentRows().isEmpty(), TIMEOUT);
			LibraryPreviewWait.preview(context, id);
			// 左の操作のボタンは左の板からはみ出さない (右のコメントの欄に重ねない)
			check(context.computeOnClient(mc -> {
				LiveListScreen g = screen(LiveListScreen.class);
				int right = g.composer().field().frameX() - 1;
				return g.rowLoadButton().getRight() < right && g.rowBranchButton().getRight() < right && g.rowOpenButton().getRight() < right;
			}), "操作のボタンがコメントの欄に重なる (" + (size[0] / size[2]) + "x" + (size[1] / size[2]) + ")");
			neutralCursor(context);
			context.takeScreenshot("redtact-live-comments-" + (size[0] / size[2]) + "x" + (size[1] / size[2]) + "-detail");
		}

		context.getInput().resizeWindow(orig[0], orig[1]);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(orig[2]);
			mc.resizeGui();
		});
		context.waitTicks(5);
	}

	// ---- 補助

	/** 詳細の入力欄を押して書き、Enter で送る。 */
	private static void sendWithEnter(ClientGameTestContext context, String text) {
		clickField(context, mc -> screen(LiveListScreen.class).composer().field());
		context.getInput().typeChars(text);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
	}

	private static void waitMillis(ClientGameTestContext context, long millis) {
		long end = System.currentTimeMillis() + millis;

		while (System.currentTimeMillis() < end) {
			context.waitTicks(1);
		}
	}

	/** マウスを画面の左上 (ロゴの上) に置く (撮る前にホバーとツールチップを消す)。 */
	private static void neutralCursor(ClientGameTestContext context) {
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(2);
	}

	private static SessionManager sessions() {
		return RedtactLive.get().sessions();
	}

	@SuppressWarnings("unchecked")
	private static <T> T screen(Class<T> type) {
		Object s = net.minecraft.client.Minecraft.getInstance().gui.screen();
		check(type.isInstance(s), "画面が " + type.getSimpleName() + " でない: " + s);
		return (T) s;
	}

	private static void clickField(ClientGameTestContext context, java.util.function.Function<net.minecraft.client.Minecraft, RTextField> field) {
		double[] p = context.computeOnClient(mc -> {
			RTextField f = field.apply(mc);
			return new double[] {f.frameX() + f.frameW() / 2.0, f.frameY() + f.frameH() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
		context.waitFor(mc -> field.apply(mc).isFocused(), TIMEOUT);
	}

	private static void clickButton(ClientGameTestContext context, java.util.function.Function<net.minecraft.client.Minecraft, RButton> button) {
		double[] p = context.computeOnClient(mc -> {
			RButton b = button.apply(mc);
			check(b != null && b.active, "押せないボタン: " + (b == null ? "なし" : b.getMessage().getString() + " (" + b.disabledReason() + ")"));
			return new double[] {b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
	}

	private static List<StoredComment> readComments(String circuitId) {
		try {
			return sessions().store().readComments(circuitId);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	/** サーバの最後のコメント。 */
	private static JsonObject lastComment(String server, String sessionId) {
		var cs = getJson(server + "/api/sessions/" + sessionId + "/comments").getAsJsonArray("comments");
		check(!cs.isEmpty(), "サーバにコメントが無い");
		return cs.get(cs.size() - 1).getAsJsonObject();
	}

	private static String[] createSession(String server, String user, String title) {
		HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(server + "/api/sessions"))
				.header("Content-Type", "application/json")
				.header("X-Live-User", URLEncoder.encode(user, StandardCharsets.UTF_8))
				.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(Map.of("title", title, "circuitId", "gametest-other", "listed", false)), StandardCharsets.UTF_8)));
		check(res.statusCode() == 201, "他人のセッションを開けない: " + res.statusCode() + " " + res.body());
		Api.CreateSessionResponse r = Json.GSON.fromJson(res.body(), Api.CreateSessionResponse.class);
		return new String[] {r.session.sessionId, r.hostToken};
	}

	/** 3×2×3 の小さな回路 (石の床とレッドストーンの粉) を全体で送る。 */
	private static void putState(String server, String id, String token) {
		short[] blocks = new short[18];

		for (int i = 0; i < 9; i++) {
			blocks[i] = 1; // y=0 は石
		}

		blocks[9 + 4] = 2; // y=1 の真ん中にレッドストーン
		byte[] raw = new byte[blocks.length * 2];

		for (int i = 0; i < blocks.length; i++) {
			raw[i * 2] = (byte) blocks[i];
			raw[i * 2 + 1] = (byte) (blocks[i] >> 8);
		}

		String body = Json.GSON.toJson(Map.of("size", List.of(3, 2, 3),
				"palette", List.of("minecraft:air", "minecraft:stone", "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]"),
				"blocks", java.util.Base64.getEncoder().encodeToString(raw), "blockEntities", List.of()));
		HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(server + "/api/sessions/" + id + "/state"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
		check(res.statusCode() == 200, "他人の回路を送れない: " + res.statusCode() + " " + res.body());
	}

	private static void postComment(String server, String sessionId, String user, String text) {
		HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(server + "/api/sessions/" + sessionId + "/comments"))
				.header("Content-Type", "application/json")
				.header("X-Live-User", URLEncoder.encode(user, StandardCharsets.UTF_8))
				.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(Map.of("text", text)), StandardCharsets.UTF_8)));
		check(res.statusCode() == 201, "コメント投稿: " + res.statusCode() + " " + res.body());
	}

	private static void httpDelete(String url, String token) {
		HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).DELETE());
		check(res.statusCode() == 204, "DELETE " + url + " -> " + res.statusCode());
	}

	private static JsonObject getJson(String url) {
		HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).GET());
		check(res.statusCode() == 200, "GET " + url + " -> " + res.statusCode() + " " + res.body());
		return Json.GSON.fromJson(res.body(), JsonObject.class);
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

	/** 詳細のプレビューがそのセッションの回路を描くまで待つ。 */
	private static final class LibraryPreviewWait {
		static void preview(ClientGameTestContext context, String id) {
			context.waitFor(mc -> screen(LiveListScreen.class).preview() != null && screen(LiveListScreen.class).preview().shownCircuit() != null
					&& screen(LiveListScreen.class).preview().shownCircuit().sessionId().equals(id), TIMEOUT);
			context.waitTicks(3);
		}
	}
}
