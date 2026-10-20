package com.redtact.live.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.LocalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.BlockPos;

class SavedRangesTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	@TempDir
	Path dir;

	private static CircuitRecord save(LocalStore store, String name, String world, String dim, Box box) throws Exception {
		CircuitRecord r = store.create(name);
		r.world = world;
		r.dimension = dim;
		r.region = CircuitRecord.Region.of(box);
		store.save(r);
		return r;
	}

	@Test
	void savedWithoutSessionsAndReloadedWhenWritten() throws Exception {
		LocalStore store = new LocalStore(dir);
		SavedRanges ranges = new SavedRanges(store, new Selection());
		assertTrue(ranges.all().isEmpty());

		// セッションの無い circuit.json (「範囲を保存」) も一覧に出る
		CircuitRecord a = save(store, "仕分け機", "singleplayer:w1", OVERWORLD, new Box(0, 0, 0, 4, 2, 4));
		assertEquals(1, ranges.all().size());
		assertTrue(ranges.all().getFirst().sessions.isEmpty());

		// 書かなければ読み直さない (同じ一覧を返す)
		List<CircuitRecord> first = ranges.all();
		assertSame(first, ranges.all());

		save(store, "クロック", "singleplayer:w1", OVERWORLD, new Box(10, 0, 0, 12, 1, 1));
		assertEquals(2, ranges.all().size());
		assertEquals(a.circuitId, ranges.all().stream().filter(r -> r.name.equals("仕分け機")).findFirst().orElseThrow().circuitId);
	}

	@Test
	void visibleOnlyInTheSameWorldAndDimension() throws Exception {
		LocalStore store = new LocalStore(dir);
		SavedRanges ranges = new SavedRanges(store, new Selection());
		save(store, "here", "singleplayer:w1", OVERWORLD, new Box(0, 0, 0, 1, 1, 1));
		save(store, "nether", "singleplayer:w1", NETHER, new Box(0, 0, 0, 1, 1, 1));
		save(store, "other", "server:example.com", OVERWORLD, new Box(0, 0, 0, 1, 1, 1));
		CircuitRecord legacy = store.create("legacy"); // ワールドの無い古い記録はどこでも
		legacy.dimension = OVERWORLD;
		legacy.region = CircuitRecord.Region.of(new Box(5, 5, 5, 6, 6, 6));
		store.save(legacy);
		store.create("no range"); // 範囲の無い記録は出さない

		List<String> names = ranges.visible("singleplayer:w1", OVERWORLD).stream().map(r -> r.name).sorted().toList();
		assertEquals(List.of("here", "legacy"), names);
		assertEquals(List.of("nether"), ranges.visible("singleplayer:w1", NETHER).stream().map(r -> r.name).toList());
	}

	@Test
	void pickSetsSelectionAndTracksChanges() throws Exception {
		LocalStore store = new LocalStore(dir);
		Selection sel = new Selection();
		SavedRanges ranges = new SavedRanges(store, sel);
		Box box = new Box(0, -60, 0, 5, -59, 3);
		CircuitRecord r = save(store, "仕分け機", "singleplayer:w1", OVERWORLD, box);

		ranges.pick(r);
		assertEquals(r.circuitId, ranges.pickedId());
		assertEquals(box, sel.box());
		assertEquals(OVERWORLD, sel.dimension());
		assertFalse(ranges.pickedChanged());

		// 角を動かしても選んだまま (開始で保存済みの範囲を更新する)
		sel.setPos2(new BlockPos(7, -59, 3), OVERWORLD);
		assertEquals(r.circuitId, ranges.pickedId());
		assertTrue(ranges.pickedChanged());

		// 保存済みの範囲が選択に合えば、変更なしに戻る
		r.region = CircuitRecord.Region.of(sel.box());
		store.save(r);
		assertFalse(ranges.pickedChanged());

		// 選んだ回路でセッションを開いても外れない。別の回路なら外れる
		ranges.tick(r.circuitId);
		assertEquals(r.circuitId, ranges.pickedId());
		ranges.tick(null);
		assertEquals(r.circuitId, ranges.pickedId());
		ranges.tick("ffffffffffffffff");
		assertNull(ranges.pickedId());
		assertNull(ranges.picked());
		assertFalse(ranges.pickedChanged());
	}

	@Test
	void markKeepsSelection() throws Exception {
		LocalStore store = new LocalStore(dir);
		Selection sel = new Selection();
		SavedRanges ranges = new SavedRanges(store, sel);
		sel.set(new Box(1, 1, 1, 2, 2, 2), OVERWORLD);
		CircuitRecord r = save(store, "x", "singleplayer:w1", OVERWORLD, new Box(1, 1, 1, 2, 2, 2));
		ranges.mark(r.circuitId);
		assertEquals(r.circuitId, ranges.picked().circuitId);
		assertEquals(new Box(1, 1, 1, 2, 2, 2), sel.box());
		ranges.clearPick();
		assertNull(ranges.picked());
	}
}
