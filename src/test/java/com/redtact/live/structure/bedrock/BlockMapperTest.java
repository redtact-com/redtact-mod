package com.redtact.live.structure.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/**
 * 統合版 → Java 版の対応表 (#70)。期待値は Go 版 (mc-nbt-converter 7f17b26) の mapping.MapBlock と fmt に同じ入力を
 * 渡して出したもの (go1.24.0)。
 */
class BlockMapperTest {
	static String map(String name, Map<String, ?> states) {
		return BlockMapper.get().map(name, states).toString();
	}

	@Test
	void mapsLikeGo() {
		assertEquals("minecraft:repeater[delay=3,facing=east,locked=false,powered=false]",
				map("minecraft:unpowered_repeater", Map.of("minecraft:cardinal_direction", "east", "repeater_delay", 2)));
		assertEquals("minecraft:redstone_wall_torch[facing=east,lit=true]", map("minecraft:redstone_torch", Map.of("torch_facing_direction", "west")));
		assertEquals("minecraft:redstone_torch[lit=false]", map("minecraft:unlit_redstone_torch", Map.of("torch_facing_direction", "top")));
		assertEquals("minecraft:stone", map("minecraft:stone", Map.of()));
		assertEquals("minecraft:oak_button[face=wall,facing=south,powered=false]",
				map("minecraft:wooden_button", Map.of("button_pressed_bit", (byte) 0, "facing_direction", 3)));
		assertEquals("minecraft:stone_button[face=floor,facing=north,powered=true]",
				map("minecraft:stone_button", Map.of("button_pressed_bit", (byte) 1, "facing_direction", 1)));
		assertEquals("minecraft:quartz_block[axis=x,chisel_type=lines]", map("minecraft:quartz_block", Map.of("chisel_type", "lines", "pillar_axis", "x")));
		assertEquals("minecraft:water_cauldron[fill_level=3]", map("minecraft:cauldron", Map.of("fill_level", 3, "cauldron_liquid", "water")));
		assertEquals("minecraft:piston_head[facing=south,short=false,type=sticky]",
				map("minecraft:sticky_piston_arm_collision", Map.of("facing_direction", 2)));
		assertEquals("minecraft:lever[face=ceiling,facing=east,powered=true]",
				map("minecraft:lever", Map.of("lever_direction", "down_east_west", "open_bit", (byte) 1)));
		assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]",
				map("minecraft:oak_stairs", Map.of("weirdo_direction", 1, "upside_down_bit", (byte) 0, "minecraft:corner", "none")));
		// aliases で oak_slab にしてから Chunker の表を引く (Go と同じ結果)
		assertEquals("minecraft:petrified_oak_slab[type=top,waterlogged=false]", map("minecraft:wooden_slab", Map.of("minecraft:vertical_half", "top")));
		assertEquals("minecraft:hopper[enabled=true,facing=down]", map("minecraft:hopper", Map.of("facing_direction", 0, "toggle_bit", (byte) 0)));
		assertEquals("minecraft:observer[facing=up,powered=true]", map("minecraft:observer", Map.of("minecraft:facing_direction", "up", "powered_bit", (byte) 1)));
	}

	/** 値の字は Go と同じ (Byte は符号なし、Float は float32 の短い字、大きい数は e 表記)。名前は identity で minecraft: を前に付ける。 */
	@Test
	void formatsValuesLikeGo() {
		Map<String, Object> states = new LinkedHashMap<>();
		states.put("a", (byte) -1);
		states.put("b", (short) -3);
		states.put("c", 0.1f);
		states.put("d", 1e6);
		states.put("e", 5L);
		states.put("update_bit", (byte) 1);
		assertEquals("minecraft:example:thing[a=255,b=-3,c=0.1,d=1e+06,e=5]", map("example:thing", states));

		double[] doubles = {0.5, 100, 1234567, 123456, 1e6, 1e21, 0.0001, 0.00001, -2.5, 1.0 / 3, 1e-7, 123456789012.0, 5e-324};
		List<String> want = List.of("0.5", "100", "1.234567e+06", "123456", "1e+06", "1e+21", "0.0001", "1e-05", "-2.5", "0.3333333333333333", "1e-07",
				"1.23456789012e+11", "5e-324");
		List<String> got = new ArrayList<>();

		for (double d : doubles) {
			got.add(BlockMapper.stateValue(d));
		}

		assertEquals(want, got);

		List<String> floats = new ArrayList<>();

		for (float f : new float[] {0.1f, 1e6f, 16777216f, 3.4e38f, 1.5e-5f, 0.3f}) {
			floats.add(BlockMapper.stateValue(f));
		}

		assertEquals(List.of("0.1", "1e+06", "1.6777216e+07", "3.4e+38", "1.5e-05", "0.3"), floats);

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("z", 1);
		m.put("a", "x");
		assertEquals("[255 -3 a b 0.1 map[a:x z:1]]", BlockMapper.stateValue(List.of((byte) -1, (short) -3, "a b", 0.1f, m)));
		assertEquals("[1 255]", BlockMapper.stateValue(new byte[] {1, (byte) 255}));
		assertEquals("[1 -2 3]", BlockMapper.stateValue(new int[] {1, -2, 3}));
		assertEquals("[1 -2 3]", BlockMapper.stateValue(new LittleEndianNbt.IntList(new int[] {1, -2, 3})));
	}

	@Test
	void wildcardLikeGo() {
		assertTrue(BlockMapper.wildcardMatch("minecraft:*button*", "minecraft:oak_button"));
		assertTrue(BlockMapper.wildcardMatch("minecraft:*_slab", "minecraft:oak_slab"));
		assertFalse(BlockMapper.wildcardMatch("minecraft:*_slab", "minecraft:oak_slabs"));
		assertTrue(BlockMapper.wildcardMatch("*update*", "update_bit"));
		assertTrue(BlockMapper.wildcardMatch("minecraft:*", "minecraft:x"));
		assertFalse(BlockMapper.wildcardMatch("minecraft:*", "other:x"));
		assertTrue(BlockMapper.wildcardMatch("a*b*c", "a-b-c"));
		assertFalse(BlockMapper.wildcardMatch("a*b*c", "a-c-b"));
		assertTrue(BlockMapper.wildcardMatch("age", "age"));
		assertFalse(BlockMapper.wildcardMatch("age", "age_bit"));
	}

	/** 表は 1 度だけ読み、どのスレッドからも同じものを使う。 */
	@Test
	void loadsOnce() {
		List<CompletableFuture<BlockMapper>> futures = new ArrayList<>();

		for (int i = 0; i < 8; i++) {
			futures.add(CompletableFuture.supplyAsync(BlockMapper::get));
		}

		BlockMapper first = BlockMapper.get();
		futures.forEach(f -> assertSame(first, f.join()));
		assertTrue(first.redstoneConnectables().contains("minecraft:lever"));
	}
}
