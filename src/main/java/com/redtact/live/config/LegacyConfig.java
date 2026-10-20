package com.redtact.live.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParseException;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Json;

/**
 * 前の版の {@code <game>/redtact-live/config.json}。今の設定 ({@code config/redtact-live.json}) へ
 * 移すときに読むだけ。MC に依存しない。
 */
public final class LegacyConfig {
	public String serverUrl;
	public String user;
	public Boolean autoSync;
	public Long autoSyncQuietMs;
	public Long autoSyncMaxDelayMs;
	public Long localSnapshotIntervalMs;

	public static LegacyConfig read(Path file) throws IOException {
		try {
			LegacyConfig c = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LegacyConfig.class);
			return c == null ? new LegacyConfig() : c;
		} catch (JsonParseException e) {
			throw new IOException(Tr.f("config.json を読めません: {0}", e.getMessage()), e);
		}
	}

	/** int に収まる範囲に丸める (設定の整数は int)。 */
	static int clampInt(long v, int min, int max) {
		return (int) Math.max(min, Math.min(max, v));
	}
}
