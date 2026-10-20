package com.redtact.live.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LibrarySearchTest {
	@TempDir
	Path root;

	private SchematicsBrowser browser() {
		return new SchematicsBrowser(() -> root);
	}

	private void touch(String rel) throws IOException {
		Path p = root.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.write(p, new byte[] {1});
	}

	private LibrarySearch.Result search(Path start, String q, Map<String, LibraryEntry> index) {
		return LibrarySearch.search(start, q, browser(), root.resolve("redtact"), index::get, () -> false);
	}

	@Test
	void termsAreNormalized() {
		assertEquals(List.of("abc", "回路"), LibrarySearch.terms("  ＡＢＣ　回路 "));
		assertEquals(List.of(), LibrarySearch.terms("   "));
		assertTrue(LibrarySearch.matches(LibrarySearch.terms("adder 4bit"), "4Bit_Adder.litematic"));
		assertFalse(LibrarySearch.matches(LibrarySearch.terms("adder 8bit"), "4Bit_Adder.litematic"));
		assertFalse(LibrarySearch.matches(List.of(), "anything"));
	}

	/** いまのフォルダとその下を探す。フォルダの名前も引け、回路でないファイル・隠しフォルダは出さない。場所は schematics からの形。 */
	@Test
	void findsBelowCurrentFolder() throws Exception {
		touch("builds/2026/回路2.litematic");
		touch("builds/2026/回路10.litematic");
		touch("builds/回路メモ.txt");
		touch("builds/.trash/回路3.litematic");
		touch("other/回路4.schem");
		Files.createDirectories(root.resolve("builds/回路置き場"));
		LibrarySearch.Result r = search(root.resolve("builds"), "回路", Map.of());
		assertNull(r.error());
		assertEquals(List.of("回路置き場"), r.folders().stream().map(h -> h.item().name()).toList());
		assertEquals(List.of("回路2.litematic", "回路10.litematic"), r.files().stream().map(h -> h.item().name()).toList());
		assertEquals("builds/2026/", r.files().getFirst().location());
		assertEquals("builds/", r.folders().getFirst().location());
		assertFalse(r.truncated());
		// 上のフォルダから探すと other の中も。いちばん上のファイルの場所は schematics の名前
		assertEquals(3, search(root, "回路", Map.of()).files().size());
		touch("回路0.nbt");
		assertEquals(root.getFileName() + "/", search(root, "回路0", Map.of()).files().getFirst().location());
	}

	/** 保管庫の中のファイルは、索引の題と redtact でのファイル名でも引ける。 */
	@Test
	void libraryEntriesMatchByLabel() throws Exception {
		touch("redtact/abc.litematic");
		touch("redtact/def.litematic");
		LibraryEntry e = new LibraryEntry();
		e.storedName = "abc.litematic";
		e.label = "高速な加算器";
		e.fileName = "adder-v3.litematic";
		LibrarySearch.Result byLabel = search(root, "加算器", Map.of("abc.litematic", e));
		assertEquals(1, byLabel.files().size());
		assertEquals(e, byLabel.files().getFirst().entry());
		assertEquals(1, search(root, "ADDER", Map.of("abc.litematic", e)).files().size());
		assertEquals(0, search(root, "加算器", Map.of()).files().size());
	}

	@Test
	void stopsAtLimitsAndCancels() throws Exception {
		for (int i = 0; i < LibrarySearch.MAX_RESULTS + 5; i++) {
			touch("many/c" + i + ".nbt");
		}

		LibrarySearch.Result r = search(root, "c", Map.of());
		assertTrue(r.truncated());
		assertEquals(LibrarySearch.MAX_RESULTS, r.count());
		LibrarySearch.Result cancelled = LibrarySearch.search(root, "c", browser(), root.resolve("redtact"), n -> null, () -> true);
		assertEquals(0, cancelled.count());
	}
}
