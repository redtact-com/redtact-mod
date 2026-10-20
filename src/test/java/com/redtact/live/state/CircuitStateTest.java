package com.redtact.live.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.litematic.RegionBlocks;
import com.redtact.live.net.Json;
import org.junit.jupiter.api.Test;

class CircuitStateTest {
	@Test
	void blockStateStringSortsKeysAndOmitsEmptyBrackets() {
		assertEquals("minecraft:stone", BlockStateEntry.of("minecraft:stone").toString());
		assertEquals("minecraft:repeater[delay=1,facing=north,locked=false,powered=false]",
				BlockStateEntry.of("minecraft:repeater", Map.of("powered", "false", "facing", "north", "locked", "false", "delay", "1")).toString());
	}

	@Test
	void blocksAreUint16LittleEndian() {
		byte[] b = CircuitState.encodeBlocks(new int[] {0, 1, 300, 65535});
		assertArrayEquals(new byte[] {0, 0, 1, 0, 0x2C, 0x01, (byte) 0xFF, (byte) 0xFF}, b);
		assertArrayEquals(new int[] {0, 1, 300, 65535}, CircuitState.decodeBlocks(b));
		assertThrows(IllegalArgumentException.class, () -> CircuitState.encodeBlocks(new int[] {65536}));
	}

	@Test
	void fromRegionUsesProtocolIndexAndStrings() {
		RegionBlocks region = new RegionBlocks.Builder(2, 2, 3)
				.set(1, 0, 2, BlockStateEntry.of("minecraft:stone"))
				.set(0, 1, 0, BlockStateEntry.of("minecraft:redstone_wire", Map.of("power", "15", "east", "side")))
				.build();
		CircuitState s = CircuitState.of(region, Map.of(5, new byte[] {10, 0, 0, 0}));
		// (y * sizeZ + z) * sizeX + x
		assertEquals("minecraft:stone", s.blockAt((0 * 3 + 2) * 2 + 1));
		assertEquals("minecraft:redstone_wire[east=side,power=15]", s.blockAt((1 * 3 + 0) * 2 + 0));
		assertEquals("minecraft:air", s.palette().get(0));
		assertEquals(2, s.nonAirCount());
	}

	@Test
	void jsonShapeMatchesProtocol() {
		CircuitState s = new CircuitState(2, 1, 1, List.of("minecraft:air", "minecraft:stone"), new int[] {1, 0},
				Map.of(1, new byte[] {10, 0, 0, 0}));
		JsonObject o = JsonParser.parseString(Json.GSON.toJson(s.toJson())).getAsJsonObject();
		assertFalse(o.has("version"), "MOD からは version を送らない");
		assertEquals("[2,1,1]", o.get("size").toString());
		assertEquals("[\"minecraft:air\",\"minecraft:stone\"]", o.get("palette").toString());
		assertArrayEquals(new byte[] {1, 0, 0, 0}, Base64.getDecoder().decode(o.get("blocks").getAsString()));
		JsonArray bes = o.getAsJsonArray("blockEntities");
		assertEquals(1, bes.size());
		assertEquals(1, bes.get(0).getAsJsonObject().get("i").getAsInt());
		assertArrayEquals(new byte[] {10, 0, 0, 0}, Base64.getDecoder().decode(bes.get(0).getAsJsonObject().get("nbt").getAsString()));

		CircuitState back = CircuitState.fromJson(Json.GSON.fromJson(o, StateJson.State.class));
		assertTrue(back.sameContent(s));
	}

	@Test
	void sameContentIgnoresPaletteOrder() {
		CircuitState a = new CircuitState(2, 1, 1, List.of("minecraft:air", "minecraft:stone"), new int[] {1, 0}, Map.of());
		CircuitState b = new CircuitState(2, 1, 1, List.of("minecraft:stone", "minecraft:air", "minecraft:dirt"), new int[] {0, 1}, Map.of());
		assertTrue(a.sameContent(b));
		CircuitState c = new CircuitState(2, 1, 1, List.of("minecraft:air", "minecraft:stone"), new int[] {0, 1}, Map.of());
		assertFalse(a.sameContent(c));
	}

	@Test
	void gzipRoundTrip() throws Exception {
		byte[] data = Json.GSON.toJson(new CircuitState(1, 1, 1, List.of("minecraft:air"), new int[] {0}, Map.of()).toJson())
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] gz = Gzip.compress(data);
		assertEquals((byte) 0x1f, gz[0]);
		assertEquals((byte) 0x8b, gz[1]);
		assertArrayEquals(data, new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz)).readAllBytes());
	}
}
