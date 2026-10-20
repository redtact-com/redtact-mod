package com.redtact.live.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.redtact.live.net.Api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保存済みの範囲 (回路) をゴミ箱へ移す (#77)。 */
class LocalStoreTrashTest {
	@TempDir
	Path dir;

	private static Api.Comment comment(String id) {
		Api.Comment c = new Api.Comment();
		c.id = id;
		c.author = "viewer";
		c.text = "コメント";
		c.createdAt = "2026-10-18T12:00:00.000Z";
		return c;
	}

	@Test
	void movesTheWholeFolderToTrash() throws Exception {
		LocalStore store = new LocalStore(dir);
		CircuitRecord keep = store.create("残す回路");
		CircuitRecord gone = store.create("消す回路");
		store.saveSnapshot(gone.circuitId, 1, new byte[] {1, 2, 3}, Instant.parse("2026-10-18T12:00:00Z"));
		store.appendComments(gone.circuitId, "s1", List.of(comment("c_1")));
		int before = store.generation();

		Path dest = store.trashCircuit(gone.circuitId, Instant.parse("2026-10-18T14:30:00Z"));

		// 一覧から消え、フォルダごと trash/<ID>-<日時>/ へ (履歴・コメントも一緒)
		assertEquals(List.of("残す回路"), store.listCircuits().stream().map(r -> r.name).toList());
		assertFalse(Files.exists(store.circuitDir(gone.circuitId)));
		assertEquals(store.trashDir(), dest.getParent());
		assertTrue(dest.getFileName().toString().startsWith(gone.circuitId + "-2026101"), dest.toString());
		assertTrue(Files.isRegularFile(dest.resolve("circuit.json")));
		assertTrue(Files.isRegularFile(dest.resolve("comments.jsonl")));
		assertEquals(1, Files.list(dest.resolve("snapshots")).count());
		assertNotEquals(before, store.generation(), "一覧を読み直す目印が変わらない");
		assertTrue(store.findById(keep.circuitId).isPresent());

		// もう無い回路は消せない
		assertThrows(NoSuchFileException.class, () -> store.trashCircuit(gone.circuitId, Instant.now()));
	}

	@Test
	void sameNameInTrashGetsANumber() throws Exception {
		LocalStore store = new LocalStore(dir);
		CircuitRecord a = store.create("回路");
		Instant t = Instant.parse("2026-10-18T14:30:00Z");
		Path first = store.trashCircuit(a.circuitId, t);

		// 写しを戻してからもう一度、同じ時刻に消しても、ゴミ箱の前のものを上書きしない
		Files.createDirectories(store.circuitDir(a.circuitId));
		Files.copy(first.resolve("circuit.json"), store.circuitDir(a.circuitId).resolve("circuit.json"));
		Path second = store.trashCircuit(a.circuitId, t);
		assertEquals(first.getFileName() + "-2", second.getFileName().toString());
		assertTrue(Files.isRegularFile(first.resolve("circuit.json")));
	}

	@Test
	void restoredFolderComesBackWithItsComments() throws Exception {
		LocalStore store = new LocalStore(dir);
		CircuitRecord a = store.create("回路");
		store.appendComments(a.circuitId, "s1", List.of(comment("c_1")));
		Path dest = store.trashCircuit(a.circuitId, Instant.now());
		assertTrue(store.readComments(a.circuitId).isEmpty());

		// 戻す (フォルダを circuits/ に戻す) と、範囲もコメントも元どおり。消す前に覚えた ID で重複も除ける
		Files.move(dest, store.circuitDir(a.circuitId));
		assertEquals(List.of("回路"), store.listCircuits().stream().map(r -> r.name).toList());
		assertEquals(1, store.readComments(a.circuitId).size());
		LocalStore.AppendResult again = store.appendComments(a.circuitId, "s1", List.of(comment("c_1")));
		assertTrue(again.added().isEmpty(), "戻したコメントがもう一度足された");
	}
}
