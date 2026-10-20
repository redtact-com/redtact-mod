package com.redtact.live.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchematicsBrowserTest {
	@TempDir
	Path tmp;

	private Path root() {
		return tmp.resolve("schematics");
	}

	private SchematicsBrowser browser() throws IOException {
		Files.createDirectories(root());
		return new SchematicsBrowser(this::root);
	}

	private static Path file(Path p, int bytes) throws IOException {
		Files.createDirectories(p.getParent());
		Files.write(p, new byte[bytes]);
		return p;
	}

	@Test
	void resolvesRelativePathsFromSchematics() throws IOException {
		SchematicsBrowser b = browser();
		Path r = root().toAbsolutePath().normalize();

		assertEquals(r, b.resolve(""));
		assertEquals(r, b.resolve("   "));
		assertEquals(r, b.resolve(null));
		assertEquals(r.resolve("redtact"), b.resolve("redtact"));
		assertEquals(r.resolve("builds").resolve("2026"), b.resolve("builds/2026/"));
		assertEquals(r.resolve("builds").resolve("2026"), b.resolve("builds\\2026"));
		assertEquals(r.resolve("builds"), b.resolve("/builds"));
		assertEquals(r.resolve("builds"), b.resolve("./builds/./"));
		assertEquals(r.resolve("a"), b.resolve("a/b/.."));
		// 根の名前で始めてもよい (画面の「schematics/」をそのまま打った)
		assertEquals(r.resolve("redtact"), b.resolve("schematics/redtact"));
		assertEquals(r, b.resolve("schematics"));
		assertEquals(r, b.resolve("Schematics/"));
	}

	@Test
	void keepsARealFolderNamedLikeTheRoot() throws IOException {
		SchematicsBrowser b = browser();
		Files.createDirectories(root().resolve("schematics"));

		assertEquals(root().toAbsolutePath().normalize().resolve("schematics").resolve("x"), b.resolve("schematics/x"));
	}

	@Test
	void acceptsAbsolutePathsInsideAndRejectsOutside() throws IOException {
		SchematicsBrowser b = browser();
		Path inside = root().toAbsolutePath().resolve("redtact");

		assertEquals(inside.normalize(), b.resolve(inside.toString()));
		assertEquals(root().toAbsolutePath().normalize(), b.resolve(root().toAbsolutePath().toString()));
		IllegalArgumentException up = assertThrows(IllegalArgumentException.class, () -> b.resolve(".."));
		assertTrue(up.getMessage().contains("schematics の外"), up.getMessage());
		assertThrows(IllegalArgumentException.class, () -> b.resolve("redtact/../../x"));
		assertThrows(IllegalArgumentException.class, () -> b.resolve(tmp.toAbsolutePath().toString()));
		Files.createDirectories(tmp.resolve("other"));
		assertThrows(IllegalArgumentException.class, () -> b.resolve(tmp.resolve("other").toAbsolutePath().toString()));
	}

	@Test
	void readsAMissingSlashPathAsRelative() throws IOException {
		SchematicsBrowser b = browser();
		// Linux では /builds は絶対パスだが、無い場所なら schematics/builds と読む (Windows ではもともと相対)
		assertEquals(root().toAbsolutePath().normalize().resolve("redtact-live-test-nowhere"), b.resolve("/redtact-live-test-nowhere"));
	}

	@Test
	void relativeDisplayAndParent() throws IOException {
		SchematicsBrowser b = browser();
		Path r = root().toAbsolutePath().normalize();

		assertEquals("", b.relative(r));
		assertEquals("redtact", b.relative(r.resolve("redtact")));
		assertEquals("builds/2026", b.relative(r.resolve("builds").resolve("2026")));
		assertEquals("schematics/", b.display(r));
		assertEquals("schematics/builds/2026/", b.display(r.resolve("builds").resolve("2026")));
		assertNull(b.parent(r));
		assertEquals(r, b.parent(r.resolve("redtact")));
		assertEquals(r.resolve("builds"), b.parent(r.resolve("builds").resolve("2026")));
		assertNull(b.parent(tmp));
		assertTrue(b.contains(r.resolve("a")));
		assertFalse(b.contains(tmp.resolve("x")));
	}

	@Test
	void openReasons() throws IOException {
		SchematicsBrowser b = browser();
		Path r = root().toAbsolutePath().normalize();
		file(r.resolve("a.litematic"), 1);

		assertNull(b.openReason(r));
		assertEquals("フォルダがありません: schematics/nope/", b.openReason(r.resolve("nope")));
		assertEquals("フォルダではありません: a.litematic", b.openReason(r.resolve("a.litematic")));
		assertNotNull(b.openReason(tmp));
	}

	@Test
	void listsFoldersThenCircuitFilesInNameOrder() throws IOException {
		SchematicsBrowser b = browser();
		Path r = root();
		Files.createDirectories(r.resolve("redtact"));
		Files.createDirectories(r.resolve("Builds"));
		Files.createDirectories(r.resolve(".hidden"));
		file(r.resolve("回路10.litematic"), 10);
		file(r.resolve("回路2.schem"), 2);
		file(r.resolve("alu.NBT"), 3);
		file(r.resolve("bedrock.mcstructure"), 4);
		file(r.resolve("world.mcworld"), 5);
		file(r.resolve("readme.txt"), 6);
		file(r.resolve("library.json"), 7);
		file(r.resolve(".dot.litematic"), 8);

		SchematicsBrowser.Listing l = b.list(r);
		assertNull(l.error());
		assertEquals(List.of("Builds", "redtact"), l.folders().stream().map(SchematicsBrowser.Item::name).toList());
		assertEquals(List.of("alu.NBT", "bedrock.mcstructure", "world.mcworld", "回路2.schem", "回路10.litematic"),
				l.files().stream().map(SchematicsBrowser.Item::name).toList());
		SchematicsBrowser.Item first = l.files().getFirst();
		assertEquals(3, first.bytes());
		assertEquals("nbt", first.extension());
		assertFalse(first.folder());
		assertEquals(0, l.omitted());
	}

	@Test
	void listsMissingFolderAsError() throws IOException {
		SchematicsBrowser b = browser();
		SchematicsBrowser.Listing l = b.list(root().resolve("nope"));

		assertEquals("フォルダがありません", l.error());
		assertTrue(l.isEmpty());
	}

	@Test
	void capsLongFoldersKeepingFoldersFirst() throws IOException {
		SchematicsBrowser b = browser();
		Path r = root();

		for (int i = 0; i < 3; i++) {
			Files.createDirectories(r.resolve("dir" + i));
		}

		for (int i = 0; i < SchematicsBrowser.MAX_ITEMS + 5; i++) {
			file(r.resolve("f" + i + ".nbt"), 1);
		}

		SchematicsBrowser.Listing l = b.list(r);
		assertEquals(3, l.folders().size());
		assertEquals(SchematicsBrowser.MAX_ITEMS - 3, l.files().size());
		assertEquals(8, l.omitted());
		assertEquals("f0.nbt", l.files().getFirst().name());
		assertEquals("f1.nbt", l.files().get(1).name());
	}

	@Test
	void naturalNameOrder() {
		List<String> names = new ArrayList<>(List.of("v10", "V2", "v1", "a", "B", "v02", "回路", "2x", "10x"));
		names.sort(SchematicsBrowser.NAME_ORDER);
		assertEquals(List.of("2x", "10x", "a", "B", "v1", "V2", "v02", "v10", "回路"), names);
	}
}
