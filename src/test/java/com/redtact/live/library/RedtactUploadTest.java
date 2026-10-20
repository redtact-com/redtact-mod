package com.redtact.live.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedtactUploadTest {
	@TempDir
	Path tmp;

	@Test
	void reasonsForFilesRedtactCannotTake() {
		assertNull(RedtactUpload.unavailableReason("a.litematic", 10));
		assertNull(RedtactUpload.unavailableReason("A.LITEMATICA", 10));
		assertNull(RedtactUpload.unavailableReason("w.mcworld", RedtactUpload.MAX_BYTES));
		String big = RedtactUpload.unavailableReason("a.schem", RedtactUpload.MAX_BYTES + 1);
		assertNotNull(big);
		assertTrue(big.contains("50.0 MB"), big);
		String ext = RedtactUpload.unavailableReason("notes.txt", 1);
		assertNotNull(ext);
		assertTrue(ext.startsWith(".txt "), ext);
		assertTrue(RedtactUpload.unavailableReason("noext", 1).startsWith("拡張子の無い"));
	}

	@Test
	void acceptMatchesWhatChromiumPasses() {
		// redtact のファイルの欄 (試作で見た Chromium の値)
		List<String> filters = List.of(".litematic", ".litematica", ".nbt", ".schem", ".schematic", ".mcstructure", ".mcworld");
		assertTrue(RedtactUpload.accepts(filters, filters, "回路.litematic"));
		assertTrue(RedtactUpload.accepts(filters, filters, "B.NBT"));
		assertFalse(RedtactUpload.accepts(filters, filters, "a.png"));
		// 画像の欄: filters は MIME、exts は ; でつないだ拡張子
		List<String> image = List.of("image/*");
		List<String> imageExts = List.of(".tif;.png;.gif;.jpg;.webp");
		assertFalse(RedtactUpload.accepts(image, imageExts, "回路.litematic"));
		assertTrue(RedtactUpload.accepts(image, imageExts, "thumb.PNG"));
		// MIME だけで拡張子の並びが無い → 合わないとみる
		assertFalse(RedtactUpload.accepts(image, List.of(), "回路.litematic"));
		// accept の無い欄 → 何でも
		assertTrue(RedtactUpload.accepts(List.of(), List.of(), "回路.litematic"));
		assertTrue(RedtactUpload.accepts(List.of("*/*"), List.of(), "x.bin"));
	}

	@Test
	void selectionKeepsOrderAndLimit() throws IOException {
		FileSelection s = new FileSelection();
		Path a = Files.writeString(tmp.resolve("a.nbt"), "a");
		Path b = Files.writeString(tmp.resolve("b.nbt"), "b");
		int r0 = s.revision();

		assertTrue(s.toggle(b));
		assertTrue(s.toggle(a));
		assertEquals(List.of(b.toAbsolutePath().normalize(), a.toAbsolutePath().normalize()), s.existing());
		assertTrue(s.contains(tmp.resolve("x/../a.nbt")));
		assertTrue(s.revision() > r0);
		assertTrue(s.toggle(b));
		assertEquals(1, s.size());

		Files.delete(a);
		assertEquals(List.of(), s.existing());
		s.clear();
		assertTrue(s.isEmpty());

		for (int i = 0; i < RedtactUpload.MAX_FILES; i++) {
			assertTrue(s.toggle(tmp.resolve("f" + i + ".nbt")));
		}

		assertFalse(s.toggle(tmp.resolve("over.nbt")));
		assertEquals(RedtactUpload.MAX_FILES, s.size());
		s.set(List.of(a, b));
		assertEquals(2, s.size());
	}
}
