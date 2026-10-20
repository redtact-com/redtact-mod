package com.redtact.live.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.redtact.live.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** live の開催中の自分の回路を保管庫に入れる (#73)。 */
class LiveLibraryTest {
	@TempDir
	Path tmp;

	private Library library() {
		Path dir = tmp.resolve("schematics").resolve(Library.FOLDER);
		return new Library(() -> dir, tmp.resolve("downloads"));
	}

	private static SessionManager.Export export(String circuitId, String name, int version, String body) {
		return new SessionManager.Export(circuitId, name, version, body.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void addsCreatedRowNamedByCircuitAndVersion() throws IOException {
		Library lib = library();
		LibraryEntry e = LiveLibrary.add(lib, export("abcd1234", "4bit 加算器", 7, "v7"));

		assertEquals("4bit 加算器-v7.litematic", e.storedName);
		assertEquals("4bit 加算器 v7", e.label);
		assertEquals(e.storedName, e.fileName);
		assertEquals("created", e.origin);
		assertTrue(e.created());
		assertEquals("live/abcd1234/v7", e.sourceKey);
		assertNull(e.circuitId); // redtact の回路ではない (「redtact」のページは無い)
		assertNull(e.fileId);
		assertEquals(2, e.bytes);
		assertTrue(e.placeable());
		assertArrayEquals("v7".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(lib.file(e)));

		// 索引から読み直しても created のまま
		LibraryEntry read = lib.find(e.storedName);
		assertTrue(read != null && read.created() && "live/abcd1234/v7".equals(read.sourceKey));
	}

	@Test
	void sameVersionOverwritesAndNextVersionAddsRow() throws IOException {
		Library lib = library();
		LibraryEntry first = LiveLibrary.add(lib, export("abcd1234", "回路", 3, "old"));
		LibraryEntry again = LiveLibrary.add(lib, export("abcd1234", "回路", 3, "new"));

		assertEquals(first.storedName, again.storedName);
		assertEquals(1, lib.list().size());
		assertEquals("new", Files.readString(lib.file(again)));

		LiveLibrary.add(lib, export("abcd1234", "回路", 4, "v4"));
		assertEquals(List.of("回路-v3.litematic", "回路-v4.litematic"), lib.list().stream().map(x -> x.storedName).sorted().toList());

		// 同じ名前でも別の回路 (別の circuitId) は別の行・連番の名前
		LibraryEntry other = LiveLibrary.add(lib, export("zzzz9999", "回路", 4, "other"));
		assertEquals("回路-v4 (2).litematic", other.storedName);
		assertEquals(3, lib.list().size());
	}

	@Test
	void doesNotOverwriteUserFileWithSameName() throws IOException {
		Library lib = library();
		Files.createDirectories(lib.dir());
		Files.writeString(lib.dir().resolve("回路-v1.litematic"), "mine");

		LibraryEntry e = LiveLibrary.add(lib, export("abcd1234", "回路", 1, "live"));
		assertEquals("回路-v1 (2).litematic", e.storedName);
		assertEquals("mine", Files.readString(lib.dir().resolve("回路-v1.litematic")));
	}

	@Test
	void fileNameIsSafeForWindows() {
		assertEquals("a_b_c-v2.litematic", LiveLibrary.fileName("a/b:c", 2));
		assertEquals("con-v1.litematic", LiveLibrary.fileName("con", 1)); // 版が付くので Windows の予約名 (con) にならない
		assertFalse(LiveLibrary.fileName("  名前  ", 5).startsWith(" "));
	}

	@Test
	void leavesNoTempFiles() throws IOException {
		Library lib = library();
		LiveLibrary.add(lib, export("abcd1234", "回路", 1, "x"));
		Path downloads = tmp.resolve("downloads");

		try (Stream<Path> s = Files.exists(downloads) ? Files.list(downloads) : Stream.empty()) {
			assertEquals(0, s.count());
		}
	}
}
