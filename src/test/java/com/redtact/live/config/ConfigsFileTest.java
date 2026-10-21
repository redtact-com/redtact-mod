package com.redtact.live.config;

import com.mojang.blaze3d.platform.InputConstants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 設定ファイルは malilib を使っていた頃の形をそのまま読み書きする (#28)。 */
class ConfigsFileTest {
	@TempDir
	Path dir;

	@AfterEach
	void reset() {
		Configs.resetAll();
	}

	@Test
	void readsTheFileWrittenByMalilib() throws IOException {
		// malilib (0.30.2) が書いた形そのまま (Hotkeys に settings が付くことがある)
		Path f = dir.resolve("redtact-live.json");
		Files.writeString(f, """
				{
				  "Generic": {
				    "serverUrl": "https://live.redtact.com",
				    "user": "テスト ホスト",
				    "autoSync": false,
				    "autoSyncQuietMs": 4000,
				    "autoSyncMaxDelayMs": 20000,
				    "localSnapshotIntervalMs": 60000,
				    "hudEnabled": false,
				    "hudAlignment": "top_left",
				    "defaultAllowCopy": false,
				    "toolEnabled": true,
				    "toolItem": "minecraft:stick",
				    "toolRange": 99999
				  },
				  "Hotkeys": {
				    "openMenu": {"keys": "R,M", "settings": {"activate_on": "PRESS", "context": "INGAME"}},
				    "setPos1": {"keys": "LEFT_BRACKET"},
				    "push": {"keys": ""},
				    "toolCorner1": {"keys": "MOUSE_LEFT"},
				    "toggleHud": {"keys": "R,NO_SUCH_KEY"}
				  }
				}
				""", StandardCharsets.UTF_8);
		Configs.readFrom(f);
		assertEquals("https://live.redtact.com", Configs.Generic.SERVER_URL.getStringValue());
		assertEquals("テスト ホスト", Configs.Generic.USER.getStringValue());
		assertFalse(Configs.Generic.AUTO_SYNC.getBooleanValue());
		assertEquals(4000, Configs.Generic.AUTO_SYNC_QUIET_MS.getIntegerValue());
		assertEquals(20000, Configs.Generic.AUTO_SYNC_MAX_DELAY_MS.getIntegerValue());
		assertEquals(60000, Configs.Generic.LOCAL_SNAPSHOT_INTERVAL_MS.getIntegerValue());
		assertFalse(Configs.Generic.HUD_ENABLED.getBooleanValue());
		assertEquals(HudCorner.TOP_LEFT, Configs.Generic.HUD_ALIGNMENT.getOptionListValue());
		assertFalse(Configs.Generic.DEFAULT_ALLOW_COPY.getBooleanValue());
		assertEquals("minecraft:stick", Configs.Generic.TOOL_ITEM.getStringValue());
		assertEquals(1024, Configs.Generic.TOOL_RANGE.getIntegerValue(), "範囲の外は端に丸める");
		assertEquals("R,M", Configs.Hotkeys.OPEN_MENU.getKeybind().getStringValue());
		assertTrue(Configs.Hotkeys.PUSH.getKeybind().isUnbound(), "空は割り当てなし");
		assertEquals("R,H", Configs.Hotkeys.TOGGLE_HUD.getKeybind().getStringValue(), "読めない値は既定のまま");
		assertEquals("RIGHT_BRACKET", Configs.Hotkeys.SET_POS2.getKeybind().getStringValue(), "無い項目は既定のまま");
	}

	@Test
	void writesTheSameShapeAndReadsItBack() throws IOException {
		Configs.Generic.SERVER_URL.setValueFromString("http://example.invalid:1234");
		Configs.Generic.HUD_ALIGNMENT.setOptionListValue(HudCorner.BOTTOM_LEFT);
		Configs.Hotkeys.OPEN_MENU.getKeybind().setKeys(List.of(InputConstants.KEY_R, InputConstants.KEY_M));
		Path f = dir.resolve("sub/redtact-live.json");
		Configs.writeTo(f);
		JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("http://example.invalid:1234", root.getAsJsonObject("Generic").get("serverUrl").getAsString());
		assertEquals("bottom_left", root.getAsJsonObject("Generic").get("hudAlignment").getAsString());
		assertEquals("R,M", root.getAsJsonObject("Hotkeys").getAsJsonObject("openMenu").get("keys").getAsString());
		assertEquals(Configs.Generic.OPTIONS.size(), root.getAsJsonObject("Generic").size());
		assertEquals(Configs.Hotkeys.HOTKEY_LIST.size(), root.getAsJsonObject("Hotkeys").size());
		assertFalse(Files.exists(dir.resolve("sub/redtact-live.json.tmp")), "書きかけのファイルが残っている");

		Configs.resetAll();
		Configs.readFrom(f);
		assertEquals("http://example.invalid:1234", Configs.Generic.SERVER_URL.getStringValue());
		assertEquals(HudCorner.BOTTOM_LEFT, Configs.Generic.HUD_ALIGNMENT.getOptionListValue());
		assertEquals(List.of(InputConstants.KEY_R, InputConstants.KEY_M), Configs.Hotkeys.OPEN_MENU.getKeybind().getKeys());
	}

	/** 前の版の既定 (手元の中継サーバ) のまま保存された serverUrl は、今の既定 (本番) に読み替える (#51)。 */
	@Test
	void oldDefaultServerUrlBecomesTheCurrentDefault() throws IOException {
		Path f = dir.resolve("redtact-live.json");
		Files.writeString(f, "{\"Generic\": {\"serverUrl\": \"http://localhost:8797\", \"user\": \"たく\"}}", StandardCharsets.UTF_8);
		Configs.readFrom(f);
		assertEquals("https://live.redtact.com", Configs.DEFAULT_SERVER_URL);
		assertEquals(Configs.DEFAULT_SERVER_URL, Configs.Generic.SERVER_URL.getStringValue());
		assertEquals("たく", Configs.Generic.USER.getStringValue(), "ほかの項目はそのまま");
	}

	/** 手元の中継サーバを別の書き方 (127.0.0.1・別のポート) で入れていれば、そのまま使う。 */
	@Test
	void otherServerUrlsAreKept() throws IOException {
		for (String url : List.of("http://127.0.0.1:8797", "http://localhost:28797", "https://live.redtact.com")) {
			Path f = dir.resolve("redtact-live.json");
			Files.writeString(f, "{\"Generic\": {\"serverUrl\": \"" + url + "\"}}", StandardCharsets.UTF_8);
			Configs.readFrom(f);
			assertEquals(url, Configs.Generic.SERVER_URL.getStringValue());
		}
	}

	/** 中継サーバと redtact の URL は設定の画面に出さず、設定ファイルにだけ書く (#51)。 */
	@Test
	void serverAndRedtactUrlsAreFileOnly() throws IOException {
		assertTrue(Configs.Generic.FILE_ONLY.contains(Configs.Generic.SERVER_URL));
		assertTrue(Configs.Generic.FILE_ONLY.contains(Configs.Generic.REDTACT_URL));
		assertEquals(2, Configs.Generic.FILE_ONLY.size());
		Path f = dir.resolve("redtact-live.json");
		Configs.writeTo(f);
		JsonObject g = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("Generic");
		assertTrue(g.has("serverUrl") && g.has("redtactUrl"), "ファイルには今までどおり書く");
	}

	@Test
	void brokenFileKeepsDefaults() throws IOException {
		Path f = dir.resolve("redtact-live.json");
		Files.writeString(f, "{ これは JSON ではない", StandardCharsets.UTF_8);
		Configs.readFrom(f);
		assertEquals(Configs.DEFAULT_SERVER_URL, Configs.Generic.SERVER_URL.getStringValue());
		assertTrue(Configs.Hotkeys.OPEN_MENU.isDefault());
	}

	/** ファイルには版を書く (#69)。 */
	@Test
	void writesTheVersion() throws IOException {
		Path f = dir.resolve("redtact-live.json");
		Configs.writeTo(f);
		JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(Configs.CURRENT_VERSION, root.get(Configs.VERSION).getAsInt());
		assertEquals(2, Configs.CURRENT_VERSION);
	}

	/** 版の無いファイル (#55〜#69 の前) が書いたコマ数 30 は前の既定なので、今の既定 (60) に読み替える (#69)。 */
	@Test
	void frameRate30WithoutVersionBecomesTheCurrentDefault() throws IOException {
		Path f = dir.resolve("redtact-live.json");
		Files.writeString(f, """
				{"Generic": {"user": "たく", "browserResolution": "50", "browserFrameRate": "30"}}
				""", StandardCharsets.UTF_8);
		Configs.readFrom(f);
		assertEquals(BrowserFrameRate.FPS_60, Configs.Generic.BROWSER_FRAME_RATE.getOptionListValue());
		assertEquals(BrowserResolution.HALF, Configs.Generic.BROWSER_RESOLUTION.getOptionListValue(), "ほかの項目はそのまま");
		assertEquals("たく", Configs.Generic.USER.getStringValue());
	}

	/** 読み替えたあと (版 2 のファイル) に選んだ 30 は守る。60 はどの版でもそのまま。 */
	@Test
	void frameRateChosenAfterTheUpgradeIsKept() throws IOException {
		Path f = dir.resolve("redtact-live.json");
		Files.writeString(f, "{\"version\": 2, \"Generic\": {\"browserFrameRate\": \"30\"}}", StandardCharsets.UTF_8);
		Configs.readFrom(f);
		assertEquals(BrowserFrameRate.FPS_30, Configs.Generic.BROWSER_FRAME_RATE.getOptionListValue());

		Configs.resetAll();
		Files.writeString(f, "{\"Generic\": {\"browserFrameRate\": \"60\"}}", StandardCharsets.UTF_8);
		Configs.readFrom(f);
		assertEquals(BrowserFrameRate.FPS_60, Configs.Generic.BROWSER_FRAME_RATE.getOptionListValue());
	}

	/** 30 を選んで書いたファイルは、読み直しても 30 (版 2 で書くので読み替えない)。 */
	@Test
	void frameRate30SurvivesWriteAndRead() throws IOException {
		Configs.Generic.BROWSER_FRAME_RATE.setOptionListValue(BrowserFrameRate.FPS_30);
		Path f = dir.resolve("redtact-live.json");
		Configs.writeTo(f);
		Configs.resetAll();
		Configs.readFrom(f);
		assertEquals(BrowserFrameRate.FPS_30, Configs.Generic.BROWSER_FRAME_RATE.getOptionListValue());
	}

	/** 版が読めなければ 1 (前の版) とみなす。 */
	@Test
	void unreadableVersionIsOne() {
		assertEquals(1, Configs.version(JsonParser.parseString("{}").getAsJsonObject()));
		assertEquals(1, Configs.version(JsonParser.parseString("{\"version\": \"abc\"}").getAsJsonObject()));
		assertEquals(1, Configs.version(JsonParser.parseString("{\"version\": {\"x\": 1}}").getAsJsonObject()));
		assertEquals(1, Configs.version(JsonParser.parseString("{\"version\": true}").getAsJsonObject()));
		assertEquals(2, Configs.version(JsonParser.parseString("{\"version\": 2}").getAsJsonObject()));
	}
}
