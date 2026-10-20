package com.redtact.live.litematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

public class LitematicWriterTest {
	/** Node (@taku128/java-schematic) で読み直す検証用に書き出す先。 */
	static final Path OUT_DIR = Path.of("build", "test-output");

	static final BlockStateEntry STONE = BlockStateEntry.of("minecraft:stone");
	static final BlockStateEntry WIRE_7 = BlockStateEntry.of("minecraft:redstone_wire",
			Map.of("power", "7", "east", "side", "west", "side", "north", "none", "south", "none"));
	static final BlockStateEntry WIRE_0 = BlockStateEntry.of("minecraft:redstone_wire",
			Map.of("power", "0", "east", "none", "west", "none", "north", "side", "south", "up"));
	static final BlockStateEntry REPEATER = BlockStateEntry.of("minecraft:repeater",
			Map.of("facing", "north", "delay", "2", "locked", "false", "powered", "true"));
	static final BlockStateEntry COMPARATOR = BlockStateEntry.of("minecraft:comparator",
			Map.of("facing", "west", "mode", "subtract", "powered", "false"));

	/** 3x2x4。y=0 は石の床 (1 マス空き)、y=1 に回路。 */
	static RegionBlocks sample() {
		RegionBlocks.Builder b = new RegionBlocks.Builder(3, 2, 4);

		for (int z = 0; z < 4; z++) {
			for (int x = 0; x < 3; x++) {
				if (!(x == 2 && z == 3)) {
					b.set(x, 0, z, STONE);
				}
			}
		}

		b.set(0, 1, 0, WIRE_7).set(1, 1, 0, WIRE_7).set(2, 1, 0, WIRE_0);
		b.set(1, 1, 1, REPEATER);
		b.set(1, 1, 2, COMPARATOR);
		b.set(0, 1, 3, WIRE_0); // 添字 21 = 3 bit で long をまたぐ位置 (パレット 3 = 0b011)
		return b.build();
	}

	public static RegionBlocks sampleForOtherTests() {
		return sample();
	}

	static CompoundTag comparatorTe() {
		CompoundTag te = new CompoundTag();
		te.putString("id", "minecraft:comparator");
		te.putInt("OutputSignal", 5);
		te.putInt("x", 1);
		te.putInt("y", 1);
		te.putInt("z", 2);
		return te;
	}

	@Test
	void writesLitematicaLayout() throws IOException {
		RegionBlocks region = sample();
		assertEquals(6, region.palette().size()); // air, stone, wire7, wire0, repeater, comparator
		assertEquals(21, region.index(0, 1, 3));

		byte[] bytes = LitematicWriter.toBytes(region, List.of(comparatorTe()),
				new LitematicWriter.Metadata("テスト回路", "tester", "unit test", 4671, 1_700_000_000_000L));

		// gzip
		assertEquals((byte) 0x1f, bytes[0]);
		assertEquals((byte) 0x8b, bytes[1]);

		CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
		assertEquals(4671, root.getIntOr("MinecraftDataVersion", -1));
		assertEquals(7, root.getIntOr("Version", -1));
		assertEquals(1, root.getIntOr("SubVersion", -1));

		CompoundTag meta = root.getCompoundOrEmpty("Metadata");
		assertEquals("テスト回路", meta.getStringOr("Name", ""));
		assertEquals(1, meta.getIntOr("RegionCount", -1));
		assertEquals(24, meta.getIntOr("TotalVolume", -1));
		assertEquals(region.nonAirCount(), meta.getIntOr("TotalBlocks", -1));
		assertEquals(17, region.nonAirCount());

		CompoundTag r = root.getCompoundOrEmpty("Regions").getCompoundOrEmpty("テスト回路");
		assertEquals(0, r.getCompoundOrEmpty("Position").getIntOr("x", -1));
		assertEquals(3, r.getCompoundOrEmpty("Size").getIntOr("x", -1));
		assertEquals(2, r.getCompoundOrEmpty("Size").getIntOr("y", -1));
		assertEquals(4, r.getCompoundOrEmpty("Size").getIntOr("z", -1));

		ListTag palette = r.getListOrEmpty("BlockStatePalette");
		assertEquals(6, palette.size());
		assertEquals("minecraft:air", palette.getCompoundOrEmpty(0).getStringOr("Name", ""));
		assertFalse(palette.getCompoundOrEmpty(0).contains("Properties"));
		CompoundTag wireProps = palette.getCompoundOrEmpty(2).getCompoundOrEmpty("Properties");
		assertEquals("7", wireProps.getStringOr("power", ""));
		assertEquals("side", wireProps.getStringOr("east", ""));

		long[] states = r.getLongArray("BlockStates").orElseThrow();
		assertEquals(2, states.length); // 24 * 3 bit = 72 bit
		assertTrue(states[0] < 0, "long をまたぐエントリの下位 bit が符号 bit に入る");

		for (int y = 0; y < 2; y++) {
			for (int z = 0; z < 4; z++) {
				for (int x = 0; x < 3; x++) {
					int idx = LitematicaBitArray.get(states, 3, region.index(x, y, z));
					String name = palette.getCompoundOrEmpty(idx).getStringOr("Name", "");
					assertEquals(region.get(x, y, z).name(), name, x + "," + y + "," + z);
				}
			}
		}

		ListTag tes = r.getListOrEmpty("TileEntities");
		assertEquals(1, tes.size());
		assertEquals(5, tes.getCompoundOrEmpty(0).getIntOr("OutputSignal", -1));
		assertEquals(Tag.TAG_LIST, r.get("Entities").getId());
	}

	/** Node 側で読み直すためのファイルと、期待値 (ブロックごとの名前とプロパティ) を書き出す。 */
	@Test
	void writeSampleForExternalCheck() throws IOException {
		RegionBlocks region = sample();
		byte[] bytes = LitematicWriter.toBytes(region, List.of(comparatorTe()),
				new LitematicWriter.Metadata("sample", "tester", "", 4671, System.currentTimeMillis()));
		Files.createDirectories(OUT_DIR);
		Files.write(OUT_DIR.resolve("sample-3x2x4.litematic"), bytes);

		StringBuilder json = new StringBuilder();
		json.append("{\"size\":[3,2,4],\"blocks\":[");
		boolean first = true;

		for (int y = 0; y < 2; y++) {
			for (int z = 0; z < 4; z++) {
				for (int x = 0; x < 3; x++) {
					BlockStateEntry e = region.get(x, y, z);

					if (!first) {
						json.append(',');
					}

					first = false;
					json.append("{\"pos\":[").append(x).append(',').append(y).append(',').append(z).append("],\"name\":\"")
							.append(e.name()).append("\",\"properties\":{");
					boolean firstProp = true;

					for (Map.Entry<String, String> p : e.properties().entrySet()) {
						if (!firstProp) {
							json.append(',');
						}

						firstProp = false;
						json.append('"').append(p.getKey()).append("\":\"").append(p.getValue()).append('"');
					}

					json.append("}}");
				}
			}
		}

		json.append("],\"tileEntities\":[{\"pos\":[1,1,2],\"id\":\"minecraft:comparator\",\"OutputSignal\":5}]}");
		Files.writeString(OUT_DIR.resolve("sample-3x2x4.expected.json"), json.toString(), StandardCharsets.UTF_8);
	}
}
