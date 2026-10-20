package com.redtact.live.gametest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * 閲覧者のサインイン (#33) を本物のクライアント (26.3) で確かめる。中継サーバのマイクラのログインの受付に
 * マルチプレイで接続し、出たコードを Web と同じ API でトークンに替える。MOD とは関係なく、
 * バニラのクライアントの動き (サーバ一覧の表示・暗号化・ダイアログ・キックの文) を見るためのもの。
 *
 * <p>1.21.6 以降はキックの前にコードのダイアログが出る (#85)。本文のコードと「コードをコピー」を本物のマウスで押して
 * クリップボードに入ること、押してもダイアログが閉じないこと、「閉じる」でキックの画面に同じコードが出ることを見る。
 *
 * <p>開発用のアカウントは Mojang に join できないので、中継サーバを偽の hasJoined と組み合わせて動かす:
 * <pre>
 * cd server && go run ./cmd/fake-mojang -addr 127.0.0.1:8799 &amp;
 * LIVE_MC_LOGIN_ADDR=:25577 LIVE_MC_LOGIN_SKIP_CLIENT_AUTH=1 \
 *   LIVE_MOJANG_HAS_JOINED_URL=http://127.0.0.1:8799/session/minecraft/hasJoined go run ./cmd/redtact-live-server
 * REDTACT_LIVE_SIGNIN=localhost:25577 REDTACT_LIVE_SERVER=http://localhost:8797 REDTACT_LIVE_SIGNIN_ONLY=1 \
 *   JAVA_HOME=~/opt/jdk-25 ./gradlew runClientGameTest
 * </pre>
 * REDTACT_LIVE_SIGNIN が無ければ何もしない。REDTACT_LIVE_SIGNIN_ONLY を付けると LiveClientGameTest を飛ばす。
 */
@SuppressWarnings("UnstableApiUsage")
public class SignInClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final int TIMEOUT = 20 * 30;
	private static final Pattern CODE = Pattern.compile("\\b([A-Z0-9]{4}-[A-Z0-9]{4})\\b");

	@Override
	public void runTest(ClientGameTestContext context) {
		// 確かめる文は日本語 (#79。gametest のマイクラは英語なので、自動のままだと英語になる)
		context.runOnClient(mc -> com.redtact.live.config.Configs.Generic.LANGUAGE.setOptionListValue(com.redtact.live.i18n.UiLanguage.JA));

		String address = System.getenv("REDTACT_LIVE_SIGNIN");
		String api = System.getenv("REDTACT_LIVE_SERVER");

		if (address == null || address.isBlank()) {
			LOGGER.info("[signin] REDTACT_LIVE_SIGNIN が無いので飛ばす");
			return;
		}

		if (api == null || api.isBlank()) {
			throw new AssertionError("REDTACT_LIVE_SERVER (中継サーバの URL) を指定してください");
		}

		String player = context.computeOnClient(mc -> mc.getUser().getName());

		// サーバ一覧: 説明 (status) が出て、版が違うと言われない
		context.runOnClient(mc -> {
			ServerList list = new ServerList(mc);
			list.load();
			list.add(new ServerData("redtact-live", address, ServerData.Type.OTHER), false);
			list.save();
			mc.gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
		});
		context.waitTicks(60);
		context.takeScreenshot("redtact-live-signin-0-server-list");

		// 接続すると、ワールドに入らずにコードのダイアログが出る (#85)
		context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
				new ServerData("redtact-live", address, ServerData.Type.OTHER), false, null));
		context.waitFor(mc -> mc.gui.screen() instanceof DialogScreen<?>, TIMEOUT);
		context.waitTicks(10);
		List<String> texts = context.computeOnClient(mc -> widgetTexts(mc.gui.screen()));
		LOGGER.info("[signin] dialog = {}", texts);
		context.takeScreenshot("redtact-live-signin-1-dialog");
		String code = texts.stream().map(CODE::matcher).filter(Matcher::find).map(dm -> dm.group(1)).findFirst()
				.orElseThrow(() -> new AssertionError("ダイアログにコードが無い: " + texts));
		check(texts.stream().anyMatch(t -> t.contains(player + " のサインインのコード")), "ダイアログにプレイヤー名が無い: " + texts);

		// 本文のコードを押すとコピーされる
		context.runOnClient(mc -> mc.keyboardHandler.setClipboard(""));
		LibraryClientGameTest.clickWidget(context, mc -> widget(mc.gui.screen(), code, false));
		context.waitTicks(5);
		String clicked = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
		check(code.equals(clicked), "本文のコードを押してもコピーされない: " + clicked);

		// 「コードをコピー」でもコピーされ、押してもダイアログは閉じない
		context.runOnClient(mc -> mc.keyboardHandler.setClipboard(""));
		LibraryClientGameTest.clickWidget(context, mc -> widget(mc.gui.screen(), "コードをコピー", true));
		context.waitTicks(5);
		String copied = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
		check(code.equals(copied), "「コードをコピー」の値が違う: " + copied);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof DialogScreen<?>), "コピーでダイアログが閉じた");
		LOGGER.info("[signin] {} をコピーした", copied);

		// 「閉じる」で、キックの画面に同じコードが出る
		LibraryClientGameTest.clickWidget(context, mc -> widget(mc.gui.screen(), "閉じる", true));
		context.waitFor(mc -> mc.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
		context.waitTicks(5);
		String kick = context.computeOnClient(mc -> mc.gui.screen().getNarrationMessage().getString());
		LOGGER.info("[signin] kick = {}", kick.replace('\n', ' '));
		context.takeScreenshot("redtact-live-signin-2-kick");
		Matcher m = CODE.matcher(kick);
		check(m.find() && code.equals(m.group(1)), "キックの画面のコードがダイアログと違う: " + kick);
		check(kick.contains(player + " のサインインのコード"), "キックの画面にプレイヤー名が無い: " + kick);

		// Web と同じ API でトークンに替え、そのトークンで自分を問い合わせる
		JsonObject signIn = post(api + "/api/auth/minecraft/code", "{\"code\":\"" + m.group(1) + "\"}");
		String token = signIn.get("token").getAsString();
		check(player.equals(signIn.getAsJsonObject("viewer").get("name").getAsString()), "サインインした名前が違う: " + signIn);
		JsonObject me = get(api + "/api/me", token);
		check(player.equals(me.getAsJsonObject("viewer").get("name").getAsString()), "/api/me の名前が違う: " + me);
		check(me.getAsJsonObject("viewer").get("verified").getAsBoolean(), "/api/me が確認済みでない: " + me);
		// 同じコードは 2 回使えない
		check(status(api + "/api/auth/minecraft/code", "{\"code\":\"" + m.group(1) + "\"}") == 401, "使ったコードがまた通った");
		LOGGER.info("[signin] {} としてサインインできた", player);

		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	/** 画面の部品 (入れ物の中も) の字。 */
	private static List<String> widgetTexts(Screen s) {
		List<String> out = new ArrayList<>();
		walk(s.children(), w -> out.add(w.getMessage().getString()));
		return out;
	}

	/** 字が text の部品。button なら字がぴったり同じボタン、そうでなければ字に text を含む部品 (本文の文字)。 */
	private static AbstractWidget widget(Screen s, String text, boolean button) {
		List<AbstractWidget> found = new ArrayList<>();
		walk(s.children(), w -> {
			String t = w.getMessage().getString();
			if (button ? w instanceof AbstractButton && t.equals(text) : !(w instanceof AbstractButton) && t.contains(text)) {
				found.add(w);
			}
		});
		return found.isEmpty() ? null : found.getFirst();
	}

	private static void walk(List<? extends GuiEventListener> listeners, Consumer<AbstractWidget> f) {
		for (GuiEventListener l : listeners) {
			if (l instanceof AbstractWidget w) {
				f.accept(w);
			}
			if (l instanceof ContainerEventHandler c) {
				walk(c.children(), f);
			}
		}
	}

	private static JsonObject post(String url, String body) {
		try {
			HttpResponse<String> res = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
							.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			check(res.statusCode() == 200, "POST " + url + " = " + res.statusCode() + " " + res.body());
			return JsonParser.parseString(res.body()).getAsJsonObject();
		} catch (Exception e) {
			throw new AssertionError("POST " + url, e);
		}
	}

	private static int status(String url, String body) {
		try {
			return HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
							.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
					HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (Exception e) {
			throw new AssertionError("POST " + url, e);
		}
	}

	private static JsonObject get(String url, String token) {
		try {
			HttpResponse<String> res = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			check(res.statusCode() == 200, "GET " + url + " = " + res.statusCode() + " " + res.body());
			return JsonParser.parseString(res.body()).getAsJsonObject();
		} catch (Exception e) {
			throw new AssertionError("GET " + url, e);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}
}
