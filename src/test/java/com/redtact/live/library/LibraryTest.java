package com.redtact.live.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryTest {
	@TempDir
	Path tmp;

	private Library library() {
		Path dir = tmp.resolve("schematics").resolve(Library.FOLDER);
		return new Library(() -> dir, tmp.resolve("downloads"));
	}

	private static LibraryEntry entry(String storedName, String label, String sourceKey, String savedAt) {
		LibraryEntry e = new LibraryEntry();
		e.storedName = storedName;
		e.label = label;
		e.fileName = storedName;
		e.sourceKey = sourceKey;
		e.savedAt = savedAt;
		return e;
	}

	/** reserve → 一時ファイルに書く → add (ダウンロードと同じ手順)。 */
	private static LibraryEntry save(Library lib, String suggested, String sourceKey, String body, String savedAt) throws IOException {
		String name = lib.reserve(suggested, sourceKey);
		Path temp = lib.newTempFile();
		Files.writeString(temp, body);
		return lib.add(temp, entry(name, suggested, sourceKey, savedAt));
	}

	@Test
	void savesListsNewestFirstAndDeletes() throws IOException {
		Library lib = library();
		LibraryEntry a = save(lib, "alu.schem", "c1/aaa.schem", "A", "2026-10-18T01:00:00.000Z");
		LibraryEntry b = save(lib, "adder.litematic", "c2/bbb.litematic", "BB", "2026-10-18T02:00:00.000Z");

		List<LibraryEntry> list = lib.list();
		assertEquals(List.of("adder.litematic", "alu.schem"), list.stream().map(e -> e.storedName).toList());
		assertEquals(2, list.getFirst().bytes);
		assertTrue(lib.exists(a));
		assertEquals("A", Files.readString(lib.file(a)));
		assertTrue(b.placeable());

		assertTrue(lib.remove("alu.schem"));
		assertFalse(Files.exists(lib.dir().resolve("alu.schem")));
		assertEquals(List.of("adder.litematic"), lib.list().stream().map(e -> e.storedName).toList());
		// 取り途中のファイルは残らない
		try (var s = Files.list(tmp.resolve("downloads"))) {
			assertEquals(0, s.count());
		}
	}

	@Test
	void sameSourceOverwritesSameFileAndRow() throws IOException {
		Library lib = library();
		save(lib, "alu.schem", "c1/aaa.schem", "old", "2026-10-18T01:00:00.000Z");
		LibraryEntry again = save(lib, "alu.schem", "c1/aaa.schem", "new", "2026-10-18T03:00:00.000Z");

		assertEquals("alu.schem", again.storedName);
		assertEquals(1, lib.list().size());
		assertEquals("new", Files.readString(lib.dir().resolve("alu.schem")));
	}

	@Test
	void sameNameFromOtherSourceGetsNumbered() throws IOException {
		Library lib = library();
		save(lib, "alu.schem", "c1/aaa.schem", "1", "2026-10-18T01:00:00.000Z");
		LibraryEntry second = save(lib, "alu.schem", "c9/zzz.schem", "2", "2026-10-18T02:00:00.000Z");
		LibraryEntry third = save(lib, "ALU.schem", null, "3", "2026-10-18T03:00:00.000Z");

		assertEquals("alu (2).schem", second.storedName);
		// 大文字小文字だけ違う名前も別の名前にする (Windows では同じファイル)
		assertEquals("ALU (3).schem", third.storedName);
		assertEquals(3, lib.list().size());
	}

	@Test
	void doesNotOverwriteFilesTheUserPutThere() throws IOException {
		Library lib = library();
		Files.createDirectories(lib.dir());
		Files.writeString(lib.dir().resolve("alu.schem"), "mine");

		LibraryEntry e = save(lib, "alu.schem", "c1/aaa.schem", "theirs", "2026-10-18T01:00:00.000Z");
		assertEquals("alu (2).schem", e.storedName);
		assertEquals("mine", Files.readString(lib.dir().resolve("alu.schem")));
	}

	@Test
	void reservedNamesAreNotGivenTwice() {
		Library lib = library();
		String a = lib.reserve("alu.schem", "c1/a.schem");
		String b = lib.reserve("alu.schem", "c2/b.schem");
		assertNotEquals(a, b);
		lib.release(a);
		assertEquals("alu.schem", lib.reserve("alu.schem", "c3/c.schem"));
	}

	@Test
	void brokenIndexKeepsFilesAndIsSetAside() throws IOException {
		Library lib = library();
		save(lib, "alu.schem", "c1/aaa.schem", "A", "2026-10-18T01:00:00.000Z");
		Files.writeString(lib.dir().resolve(Library.INDEX), "{ not json", StandardCharsets.UTF_8);

		assertEquals(List.of(), lib.list());
		assertTrue(Files.exists(lib.dir().resolve("alu.schem")), "索引が壊れてもファイルは消さない");
		// 次の保存で、壊れた索引は .broken に控えてから書き直す (同じ名前のファイルがあるので連番)
		LibraryEntry e = save(lib, "alu.schem", "c1/aaa.schem", "B", "2026-10-18T02:00:00.000Z");
		assertEquals("alu (2).schem", e.storedName);
		assertEquals("{ not json", Files.readString(lib.dir().resolve(Library.INDEX + ".broken")));
		assertEquals(1, lib.list().size());
	}

	@Test
	void indexRowsPointingOutsideAreIgnored() throws IOException {
		Library lib = library();
		Files.createDirectories(lib.dir());
		Files.writeString(lib.dir().resolve(Library.INDEX), """
				[{"storedName": "../evil.schem", "label": "x"}, {"storedName": "ok.schem", "label": "ok", "savedAt": "2026-10-18T01:00:00.000Z"}]
				""");
		assertEquals(List.of("ok.schem"), lib.list().stream().map(e -> e.storedName).toList());
		LibraryEntry evil = new LibraryEntry();
		evil.storedName = "../evil.schem";
		assertThrows(IllegalArgumentException.class, () -> lib.file(evil));
		assertFalse(lib.exists(evil));
	}

	@Test
	void indexIsTheSameShapeAsTheIosLibrary() throws IOException {
		Library lib = library();
		save(lib, "alu.schem", "c1/aaa.schem", "A", "2026-10-18T01:00:00.000Z");
		String json = Files.readString(lib.dir().resolve(Library.INDEX));

		// iOS の LibraryEntry の項目 (fileId・circuitId は分かったときだけ入る)
		for (String key : List.of("storedName", "label", "fileName", "bytes", "savedAt", "origin")) {
			assertTrue(json.contains("\"" + key + "\""), key + " が無い: " + json);
		}

		assertTrue(json.startsWith("["), "配列で書く");
		assertTrue(json.contains("\"origin\": \"downloaded\""));
	}

	@Test
	void safeNames() {
		assertEquals("alu.schem", Library.safeName("alu.schem"));
		assertEquals("a_b_c.litematic", Library.safeName("a/b\\c.litematic"));
		String traversal = Library.safeName("../../etc/passwd");
		assertFalse(traversal.contains("/") || traversal.startsWith("."), traversal);
		assertEquals("circuit.schem", Library.safeName(".schem"));
		assertEquals("circuit", Library.safeName("  "));
		assertEquals("circuit", Library.safeName(null));
		assertEquals("CON_.nbt", Library.safeName("CON.nbt"));
		assertEquals("回路 ①.litematic", Library.safeName("回路 ①.litematic"));
		assertEquals("x", Library.safeName("x. . "));
		String longName = "あ".repeat(300) + ".litematic";
		String safe = Library.safeName(longName);
		assertTrue(safe.endsWith(".litematic"));
		assertTrue(safe.codePointCount(0, safe.length()) <= 100, safe);
		assertFalse(Library.safeName("a\u0000b:c?.nbt").matches(".*[\\x00:?].*"));
	}

	@Test
	void uniqueNames() {
		Set<String> taken = Set.of("a.schem", "a (2).schem", "noext");
		assertEquals("a (3).schem", Library.uniqueName("a.schem", taken::contains));
		assertEquals("noext (2)", Library.uniqueName("noext", taken::contains));
		assertEquals("b.schem", Library.uniqueName("b.schem", taken::contains));
	}

	@Test
	void extensions() {
		assertEquals("litematic", Library.extension("x.LITEMATIC"));
		assertEquals("", Library.extension("noext"));
		assertEquals("", Library.extension(".hidden"));
		assertEquals("gz", Library.extension("a.tar.gz"));
		LibraryEntry e = new LibraryEntry();
		e.storedName = "c.mcstructure";
		assertFalse(e.placeable());
		e.storedName = "c.nbt";
		assertTrue(e.placeable());
		assertNull(new LibraryEntry().fileId);
	}
}
