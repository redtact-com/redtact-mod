package com.redtact.live.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redtact.live.net.Json;
import org.junit.jupiter.api.Test;

class StateDifferTest {
	static final String AIR = "minecraft:air";
	static final String STONE = "minecraft:stone";
	static final String WIRE0 = "minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]";
	static final String WIRE15 = "minecraft:redstone_wire[east=side,north=none,power=15,south=none,west=side]";
	static final String GOLD = "minecraft:gold_block";

	static CircuitState state(int sx, int sy, int sz, String[] cells, Map<Integer, byte[]> bes) {
		List<String> palette = new ArrayList<>();
		palette.add(AIR);
		Map<String, Integer> idx = new HashMap<>();
		idx.put(AIR, 0);
		int[] blocks = new int[cells.length];

		for (int i = 0; i < cells.length; i++) {
			blocks[i] = idx.computeIfAbsent(cells[i], s -> {
				palette.add(s);
				return palette.size() - 1;
			});
		}

		return new CircuitState(sx, sy, sz, palette, blocks, bes);
	}

	@Test
	void noChangeIsEmpty() {
		CircuitState a = state(2, 1, 2, new String[] {STONE, WIRE0, AIR, STONE}, Map.of(1, new byte[] {1}));
		CircuitState b = state(2, 1, 2, new String[] {STONE, WIRE0, AIR, STONE}, Map.of(1, new byte[] {1}));
		CircuitDiff d = StateDiffer.diff(a, 7, b);
		assertTrue(d.isEmpty());
		assertEquals(7, d.baseVersion());
	}

	@Test
	void singleChangeUsesDiffLocalPalette() {
		CircuitState a = state(2, 1, 2, new String[] {STONE, WIRE0, AIR, STONE}, Map.of());
		// 別のパレット順でも、変わった 1 か所だけが出る
		CircuitState b = new CircuitState(2, 1, 2, List.of(WIRE15, STONE, AIR), new int[] {1, 0, 2, 1}, Map.of());
		CircuitDiff d = StateDiffer.diff(a, 3, b);
		assertEquals(1, d.changeCount());
		assertEquals(List.of(WIRE15), d.palette());
		assertArrayEquals(new int[] {1, 0}, d.changes());
		assertTrue(StateDiffer.apply(a, d).sameContent(b));
	}

	@Test
	void changeToExistingStateStillListsItInDiffPalette() {
		CircuitState a = state(3, 1, 1, new String[] {STONE, AIR, AIR}, Map.of());
		CircuitState b = state(3, 1, 1, new String[] {STONE, STONE, AIR}, Map.of());
		CircuitDiff d = StateDiffer.diff(a, 1, b);
		assertEquals(List.of(STONE), d.palette());
		assertArrayEquals(new int[] {1, 0}, d.changes());
		assertTrue(StateDiffer.apply(a, d).sameContent(b));
	}

	@Test
	void blockEntitiesAddedUpdatedRemoved() {
		CircuitState a = state(4, 1, 1, new String[] {STONE, STONE, STONE, STONE}, Map.of(0, new byte[] {1}, 1, new byte[] {2}, 2, new byte[] {3}));
		// 0 は同じ・1 は中身が変わる・2 は消える・3 は増える (ブロックは変えない)
		CircuitState b = state(4, 1, 1, new String[] {STONE, STONE, STONE, STONE}, Map.of(0, new byte[] {1}, 1, new byte[] {9}, 3, new byte[] {4}));
		CircuitDiff d = StateDiffer.diff(a, 1, b);
		assertEquals(0, d.changeCount());
		assertEquals(List.of(1, 3), new ArrayList<>(d.blockEntities().keySet()));
		assertArrayEquals(new int[] {2}, d.removedBlockEntities());
		assertFalse(d.isEmpty());
		assertTrue(StateDiffer.apply(a, d).sameContent(b));
	}

	/** ブロックが消えてもブロックエンティティは自動では消えないので、removed に入る。 */
	@Test
	void removedBlockAlsoRemovesBlockEntity() {
		CircuitState a = state(2, 1, 1, new String[] {"minecraft:chest[facing=north,type=single,waterlogged=false]", STONE}, Map.of(0, new byte[] {5}));
		CircuitState b = state(2, 1, 1, new String[] {AIR, STONE}, Map.of());
		CircuitDiff d = StateDiffer.diff(a, 1, b);
		assertArrayEquals(new int[] {0, 0}, d.changes());
		assertEquals(List.of(AIR), d.palette());
		assertArrayEquals(new int[] {0}, d.removedBlockEntities());
	}

	@Test
	void sizeChangeIsNotDiffable() {
		CircuitState a = state(2, 1, 1, new String[] {STONE, AIR}, Map.of());
		CircuitState b = state(1, 1, 2, new String[] {STONE, AIR}, Map.of());
		assertThrows(IllegalArgumentException.class, () -> StateDiffer.diff(a, 1, b));
	}

	@Test
	void tooLargeThreshold() {
		String[] before = new String[16];
		String[] after = new String[16];
		java.util.Arrays.fill(before, AIR);
		java.util.Arrays.fill(after, AIR);

		for (int i = 0; i < 4; i++) {
			after[i] = STONE;
		}

		CircuitState a = state(4, 1, 4, before, Map.of());
		assertFalse(StateDiffer.tooLarge(StateDiffer.diff(a, 1, state(4, 1, 4, after, Map.of())), 16)); // 4 = 1/4 ちょうど
		after[4] = STONE;
		assertTrue(StateDiffer.tooLarge(StateDiffer.diff(a, 1, state(4, 1, 4, after, Map.of())), 16));
	}

	@Test
	void diffJsonShape() {
		CircuitState a = state(2, 1, 1, new String[] {STONE, AIR}, Map.of(0, new byte[] {1}));
		CircuitState b = state(2, 1, 1, new String[] {STONE, GOLD}, Map.of(1, new byte[] {2}));
		JsonObject o = JsonParser.parseString(Json.GSON.toJson(StateDiffer.diff(a, 4, b).toJson())).getAsJsonObject();
		assertEquals(4, o.get("baseVersion").getAsInt());
		assertFalse(o.has("version"));
		assertEquals("[\"" + GOLD + "\"]", o.get("palette").toString());
		assertEquals("[1,0]", o.get("changes").toString());
		assertEquals(1, o.getAsJsonArray("blockEntities").get(0).getAsJsonObject().get("i").getAsInt());
		assertEquals("[0]", o.get("removedBlockEntities").toString());
		CircuitDiff back = CircuitDiff.fromJson(Json.GSON.fromJson(o, StateJson.Diff.class));
		assertTrue(StateDiffer.apply(a, back).sameContent(b));
	}

	/** 乱数で変えても、差分を当てれば必ず元に戻る。 */
	@Test
	void randomRoundTrip() {
		Random r = new Random(7);
		String[] kinds = {AIR, STONE, WIRE0, WIRE15, GOLD, "minecraft:repeater[delay=2,facing=east,locked=false,powered=true]"};

		for (int round = 0; round < 50; round++) {
			int sx = 1 + r.nextInt(6);
			int sy = 1 + r.nextInt(4);
			int sz = 1 + r.nextInt(6);
			int v = sx * sy * sz;
			String[] c1 = new String[v];
			String[] c2 = new String[v];
			Map<Integer, byte[]> b1 = new HashMap<>();
			Map<Integer, byte[]> b2 = new HashMap<>();

			for (int i = 0; i < v; i++) {
				c1[i] = kinds[r.nextInt(kinds.length)];
				c2[i] = r.nextInt(4) == 0 ? kinds[r.nextInt(kinds.length)] : c1[i];

				if (r.nextInt(5) == 0) {
					b1.put(i, new byte[] {(byte) r.nextInt(3)});
				}

				if (r.nextInt(5) == 0) {
					b2.put(i, new byte[] {(byte) r.nextInt(3)});
				}
			}

			CircuitState s1 = state(sx, sy, sz, c1, b1);
			CircuitState s2 = state(sx, sy, sz, c2, b2);
			CircuitDiff d = StateDiffer.diff(s1, round, s2);
			assertTrue(StateDiffer.apply(s1, d).sameContent(s2), "round " + round);

			for (int k = 0; k < d.changes().length; k += 2) {
				assertTrue(!s1.blockAt(d.changes()[k]).equals(s2.blockAt(d.changes()[k])), "変わっていない位置が入っている");
			}
		}
	}
}
