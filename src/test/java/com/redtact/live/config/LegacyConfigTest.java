package com.redtact.live.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyConfigTest {
	@TempDir
	Path dir;

	@Test
	void readsAllKeysOfPreviousVersion() throws IOException {
		Path f = dir.resolve("config.json");
		Files.writeString(f, """
				{"serverUrl":"http://localhost:8797","user":"たく","autoSync":false,
				 "autoSyncQuietMs":4000,"autoSyncMaxDelayMs":20000,"localSnapshotIntervalMs":60000}
				""", StandardCharsets.UTF_8);
		LegacyConfig c = LegacyConfig.read(f);
		assertEquals("http://localhost:8797", c.serverUrl);
		assertEquals("たく", c.user);
		assertEquals(false, c.autoSync);
		assertEquals(4000L, c.autoSyncQuietMs);
		assertEquals(20000L, c.autoSyncMaxDelayMs);
		assertEquals(60000L, c.localSnapshotIntervalMs);
	}

	/** 最初の版 (serverUrl と user だけ) も読める。無い項目は null (引き継がない)。 */
	@Test
	void missingKeysAreNull() throws IOException {
		Path f = dir.resolve("config.json");
		Files.writeString(f, "{\"serverUrl\":\"http://localhost:8787\",\"user\":\"alice\"}", StandardCharsets.UTF_8);
		LegacyConfig c = LegacyConfig.read(f);
		assertEquals("alice", c.user);
		assertNull(c.autoSync);
		assertNull(c.localSnapshotIntervalMs);
	}

	@Test
	void brokenJsonIsAnError() throws IOException {
		Path f = dir.resolve("config.json");
		Files.writeString(f, "{not json", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> LegacyConfig.read(f));
	}

	@Test
	void clampToIntRange() {
		assertEquals(600_000, LegacyConfig.clampInt(10_000_000_000L, 200, 600_000));
		assertEquals(200, LegacyConfig.clampInt(-5, 200, 600_000));
		assertEquals(3000, LegacyConfig.clampInt(3000, 200, 600_000));
	}

	@Test
	void userValidation() {
		assertNull(LiveConfig.userError("テスト ホスト"));
		assertEquals(true, LiveConfig.userError("").contains("1〜32"));
		assertEquals(true, LiveConfig.userError("a".repeat(33)).contains("1〜32"));
		assertEquals(true, LiveConfig.userError("a\u0007b").contains("制御文字"));
	}
}
