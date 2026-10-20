package com.redtact.live.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.redtact.live.i18n.UiLanguage;
import com.redtact.live.library.RedtactLinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 設定 ({@code config/redtact-live.json})。メニューの「設定」タブで変える。
 *
 * <p>ファイルの形は malilib を使っていた頃と同じ ({@code {"Generic": {...}, "Hotkeys": {"openMenu": {"keys": "R,L"}}}})
 * なので、前の版の設定をそのまま読む。前の版の {@code <game>/redtact-live/config.json} があれば最初の読み込みで引き継ぐ。
 * 既定を変えたときの読み替えのため、ファイルの版 ({@code "version": 2}) も書く (#69。無ければ 1)。
 */
public final class Configs {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final String FILE_NAME = "redtact-live.json";
	/**
	 * 中継サーバの既定 (本番)。設定の画面からは外したので (#51)、開発では設定ファイルの serverUrl を書き換える
	 * (gametest は REDTACT_LIVE_SERVER から入れる)。
	 */
	public static final String DEFAULT_SERVER_URL = "https://live.redtact.com";
	/** 前の既定 (開発用の手元の中継サーバ)。前の版が書いたこの値は、読むときに今の既定 (本番) に読み替える。 */
	public static final String OLD_DEFAULT_SERVER_URL = "http://localhost:8797";
	public static final String GENERIC = "Generic";
	public static final String HOTKEYS = "Hotkeys";
	/**
	 * 設定ファイルの版の項目 (#69)。既定を変えて、前の版が既定のまま書いた値を読み替えるときに版を上げ、
	 * {@link #upgradeOldDefaults} に足す。版の無いファイル (malilib の頃から #69 の前まで) は 1。
	 */
	public static final String VERSION = "version";
	public static final int CURRENT_VERSION = 2;

	public static final class Generic {
		// 画面の言語 (#79。自動はマイクラの言語に合わせる)
		public static final ConfigChoice<UiLanguage> LANGUAGE = new ConfigChoice<>("language", UiLanguage.AUTO);
		public static final ConfigString SERVER_URL = new ConfigString("serverUrl", DEFAULT_SERVER_URL);
		public static final ConfigString USER = new ConfigString("user", LiveConfig.defaultUser());
		public static final ConfigBoolean AUTO_SYNC = new ConfigBoolean("autoSync", true);
		public static final ConfigInteger AUTO_SYNC_QUIET_MS = new ConfigInteger("autoSyncQuietMs", 3_000, 200, 600_000);
		public static final ConfigInteger AUTO_SYNC_MAX_DELAY_MS = new ConfigInteger("autoSyncMaxDelayMs", 10_000, 200, 3_600_000);
		public static final ConfigInteger LOCAL_SNAPSHOT_INTERVAL_MS = new ConfigInteger("localSnapshotIntervalMs", 300_000, 0, 86_400_000);
		public static final ConfigBoolean HUD_ENABLED = new ConfigBoolean("hudEnabled", true);
		public static final ConfigChoice<HudCorner> HUD_ALIGNMENT = new ConfigChoice<>("hudAlignment", HudCorner.BOTTOM_RIGHT);
		// 新しいセッションで複製 (.litematic の取得・読み込み・分岐) を許すか。開催中はセッションのタブで切り替える
		public static final ConfigBoolean DEFAULT_ALLOW_COPY = new ConfigBoolean("defaultAllowCopy", true);
		// 範囲指定の道具 (Litematica の道具の既定は棒なので、ぶつからない羽根にする)
		public static final ConfigBoolean TOOL_ENABLED = new ConfigBoolean("toolEnabled", true);
		public static final ConfigString TOOL_ITEM = new ConfigString("toolItem", "minecraft:feather");
		public static final ConfigInteger TOOL_RANGE = new ConfigInteger("toolRange", 200, 5, 1024);
		// メニューの redtact タブで開く redtact (#46)。dev.redtact.com なども可
		public static final ConfigString REDTACT_URL = new ConfigString("redtactUrl", RedtactLinks.DEFAULT_HOME);
		// ゲーム内の redtact を描かせる解像度とコマ数 (#55。下げるほど軽い)
		public static final ConfigChoice<BrowserResolution> BROWSER_RESOLUTION = new ConfigChoice<>("browserResolution", BrowserResolution.AUTO);
		// コマ数の既定は 60 (#69。#55 では 30)。前の版が書いた 30 は upgradeOldDefaults で 1 回だけ読み替える
		public static final ConfigChoice<BrowserFrameRate> BROWSER_FRAME_RATE = new ConfigChoice<>("browserFrameRate", BrowserFrameRate.FPS_60);

		public static final List<ConfigOption> OPTIONS = List.of(
				LANGUAGE,
				SERVER_URL,
				USER,
				AUTO_SYNC,
				AUTO_SYNC_QUIET_MS,
				AUTO_SYNC_MAX_DELAY_MS,
				LOCAL_SNAPSHOT_INTERVAL_MS,
				HUD_ENABLED,
				HUD_ALIGNMENT,
				DEFAULT_ALLOW_COPY,
				TOOL_ENABLED,
				TOOL_ITEM,
				TOOL_RANGE,
				REDTACT_URL,
				BROWSER_RESOLUTION,
				BROWSER_FRAME_RATE);

		/** 設定の画面には出さず、設定ファイルでだけ変える項目 (中継サーバと redtact の URL。#51)。 */
		public static final Set<ConfigOption> FILE_ONLY = Set.of(SERVER_URL, REDTACT_URL);

		private Generic() {
		}
	}

	public static final class Hotkeys {
		// 同時押しは malilib の頃と同じ (R を押しながら L など)。Litematica (M)・MiniHUD (H)・Tweakeroo (X / Z) と被らない
		public static final ConfigHotkey OPEN_MENU = new ConfigHotkey("openMenu", "R,L");
		public static final ConfigHotkey SET_POS1 = new ConfigHotkey("setPos1", "LEFT_BRACKET");
		public static final ConfigHotkey SET_POS2 = new ConfigHotkey("setPos2", "RIGHT_BRACKET");
		public static final ConfigHotkey PUSH = new ConfigHotkey("push", "K");
		public static final ConfigHotkey TOGGLE_AUTO_SYNC = new ConfigHotkey("toggleAutoSync", "R,A");
		public static final ConfigHotkey TOGGLE_HUD = new ConfigHotkey("toggleHud", "R,H");
		// 道具を持っているときだけ反応し、そのクリックを打ち消す
		public static final ConfigHotkey TOOL_CORNER_1 = new ConfigHotkey("toolCorner1", "MOUSE_LEFT");
		public static final ConfigHotkey TOOL_CORNER_2 = new ConfigHotkey("toolCorner2", "MOUSE_RIGHT");
		/** 視線の先の保存済みの範囲を選ぶ (#39。Litematica の道具の中クリックと同じ)。 */
		public static final ConfigHotkey TOOL_PICK = new ConfigHotkey("toolPick", "MOUSE_MIDDLE");

		public static final List<ConfigHotkey> HOTKEY_LIST = List.of(
				OPEN_MENU,
				SET_POS1,
				SET_POS2,
				PUSH,
				TOGGLE_AUTO_SYNC,
				TOGGLE_HUD,
				TOOL_CORNER_1,
				TOOL_CORNER_2,
				TOOL_PICK);

		private Hotkeys() {
		}
	}

	private Configs() {
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** 前の版の設定ファイル。 */
	public static Path legacyFile() {
		return FabricLoader.getInstance().getGameDir().resolve("redtact-live").resolve("config.json");
	}

	public static void loadFromFile() {
		Path file = configFile();

		if (!Files.exists(file) && Files.exists(legacyFile())) {
			migrateFrom(legacyFile());
			return;
		}

		if (Files.isReadable(file)) {
			readFrom(file);
		}
	}

	public static void saveToFile() {
		writeTo(configFile());
	}

	/** 読めない値は既定のまま。ファイルごと読めなければ記録だけして既定のまま。 */
	public static void readFrom(Path file) {
		try {
			JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));

			if (!element.isJsonObject()) {
				LOGGER.error("[redtact-live] {} を設定として読めません (オブジェクトでない)", file);
				return;
			}

			JsonObject root = element.getAsJsonObject();
			read(root, GENERIC, Generic.OPTIONS);
			read(root, HOTKEYS, Hotkeys.HOTKEY_LIST);
			upgradeOldDefaults(version(root));
		} catch (IOException | JsonParseException | IllegalStateException e) {
			LOGGER.error("[redtact-live] {} を読めません: {}", file, e.toString());
		}
	}

	/** ファイルの版。無い・読めなければ 1。 */
	static int version(JsonObject root) {
		JsonElement v = root.get(VERSION);

		try {
			return v != null && v.isJsonPrimitive() ? v.getAsInt() : 1;
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static void read(JsonObject root, String group, List<? extends ConfigOption> options) {
		JsonElement g = root.get(group);

		if (g == null || !g.isJsonObject()) {
			return;
		}

		JsonObject o = g.getAsJsonObject();

		for (ConfigOption option : options) {
			JsonElement v = o.get(option.name());

			if (v != null && !option.fromJson(v)) {
				LOGGER.warn("[redtact-live] 設定 {}.{} の値 {} を読めないので既定のままにします", group, option.name(), v);
			}
		}
	}

	/** 途中で落ちても壊れたファイルが残らないよう、別名で書いてから置き換える。 */
	public static void writeTo(Path file) {
		JsonObject root = new JsonObject();
		root.addProperty(VERSION, CURRENT_VERSION);
		root.add(GENERIC, write(Generic.OPTIONS));
		root.add(HOTKEYS, write(Hotkeys.HOTKEY_LIST));

		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);

			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.error("[redtact-live] {} に設定を書けません: {}", file, e.toString());
		}
	}

	private static JsonObject write(List<? extends ConfigOption> options) {
		JsonObject o = new JsonObject();

		for (ConfigOption option : options) {
			o.add(option.name(), option.toJson());
		}

		return o;
	}

	/**
	 * 前の版の既定のまま保存された値を、今の既定に読み替える。設定は既定の値もファイルに書くので、既定を変えただけでは
	 * 前の版を使っていた人に届かない。中継サーバは設定の画面から外したので (#51)、手元の中継サーバ (前の既定) のままだと
	 * 本番につなげない。手元の中継サーバを使うときは http://127.0.0.1:8797 のように別の書き方にする。
	 *
	 * <p>version はファイルの版 ({@link #VERSION})。設定の画面で選べる項目は、選び直した値を守るため、その既定だった版が
	 * 書いた値だけを読み替える (書き直したファイルは今の版になるので 1 回だけ)。
	 */
	static void upgradeOldDefaults(int version) {
		if (OLD_DEFAULT_SERVER_URL.equals(Generic.SERVER_URL.getStringValue().trim())) {
			Generic.SERVER_URL.resetToDefault();
			LOGGER.info("[redtact-live] 中継サーバの URL を前の既定 ({}) から今の既定 ({}) に読み替えました", OLD_DEFAULT_SERVER_URL, DEFAULT_SERVER_URL);
		}

		// 版 1 のコマ数の既定は 30 (#55)。その版が書いた 30 は既定のまま保存されたものとみなし、今の既定 (60) にする (#69)
		if (version < 2 && Generic.BROWSER_FRAME_RATE.getOptionListValue() == BrowserFrameRate.FPS_30) {
			Generic.BROWSER_FRAME_RATE.resetToDefault();
			LOGGER.info("[redtact-live] ゲーム内の redtact のコマ数を前の既定 (30) から今の既定 ({}) に読み替えました",
					Generic.BROWSER_FRAME_RATE.getOptionListValue().label());
		}
	}

	/** すべて既定に戻す (テスト用)。 */
	public static void resetAll() {
		Generic.OPTIONS.forEach(ConfigOption::resetToDefault);
		Hotkeys.HOTKEY_LIST.forEach(ConfigOption::resetToDefault);
	}

	/**
	 * 前の版の config.json の値を引き継いで新しい形で保存し、古いファイルは {@code config.json.migrated} に名前を変える
	 * (消さない)。キー (ホットキー) は前の版の既定 ([ ] K) と同じなので移すものは無い。
	 */
	public static boolean migrateFrom(Path legacy) {
		try {
			LegacyConfig old = LegacyConfig.read(legacy);

			if (old.serverUrl != null && !old.serverUrl.isBlank()) {
				Generic.SERVER_URL.setValueFromString(old.serverUrl.trim());
			}

			if (old.user != null && !old.user.isBlank()) {
				Generic.USER.setValueFromString(old.user.trim());
			}

			if (old.autoSync != null) {
				Generic.AUTO_SYNC.setBooleanValue(old.autoSync);
			}

			if (old.autoSyncQuietMs != null) {
				Generic.AUTO_SYNC_QUIET_MS.setIntegerValue(LegacyConfig.clampInt(old.autoSyncQuietMs, 200, 600_000));
			}

			if (old.autoSyncMaxDelayMs != null) {
				Generic.AUTO_SYNC_MAX_DELAY_MS.setIntegerValue(LegacyConfig.clampInt(old.autoSyncMaxDelayMs, 200, 3_600_000));
			}

			if (old.localSnapshotIntervalMs != null) {
				Generic.LOCAL_SNAPSHOT_INTERVAL_MS.setIntegerValue(LegacyConfig.clampInt(old.localSnapshotIntervalMs, 0, 86_400_000));
			}

			upgradeOldDefaults(1); // 前の版のファイル (版なし。コマ数は持たない)
			saveToFile();
			Files.move(legacy, legacy.resolveSibling("config.json.migrated"), StandardCopyOption.REPLACE_EXISTING);
			LOGGER.info("[redtact-live] {} の設定を {} に引き継ぎました", legacy, configFile());
			return true;
		} catch (IOException | RuntimeException e) {
			LOGGER.error("[redtact-live] 前の版の設定を引き継げません: {}", e.toString());
			return false;
		}
	}
}
