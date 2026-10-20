package com.redtact.live.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.litematic.LitematicWriter;
import com.redtact.live.litematic.LitematicWriterTest;
import com.redtact.live.litematic.LitematicaBitArray;
import com.redtact.live.litematic.RegionBlocks;
import com.redtact.live.state.CircuitState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

public class StructureFilesTest {
	@TempDir
	Path dir;

	/** DataFixer はゲームの版 (version.json) を読んでから作る (ゲームの中では起動時に読んでいる)。 */
	@BeforeAll
	static void detectVersion() {
		SharedConstants.tryDetectVersion();
	}

	private Path write(String name, CompoundTag root) throws IOException {
		Path p = dir.resolve(name);
		NbtIo.writeCompressed(root, p);
		return p;
	}

	private static String at(RawStructure s, int x, int y, int z) {
		return s.palette().get(s.blocks()[(y * s.sizeZ() + z) * s.sizeX() + x]).toString();
	}

	private static ListTag ints(int... v) {
		ListTag l = new ListTag();

		for (int i : v) {
			l.add(IntTag.valueOf(i));
		}

		return l;
	}

	private static CompoundTag xyz(int x, int y, int z) {
		CompoundTag t = new CompoundTag();
		t.putInt("x", x);
		t.putInt("y", y);
		t.putInt("z", z);
		return t;
	}

	private static CompoundTag state(String name, Map<String, String> props) {
		return BlockStates.toTag(BlockStateEntry.of(name, props), 3953);
	}

	@Test
	void litematicWrittenByThisModReadsBack() throws Exception {
		RegionBlocks region = LitematicWriterTest.sampleForOtherTests();
		byte[] bytes = LitematicWriter.toBytes(region, List.of(), new LitematicWriter.Metadata("t", "a", "", 3953, 0));
		Path p = dir.resolve("sample.litematic");
		Files.write(p, bytes);
		RawStructure s = StructureFiles.read(p, StructureLimits.PREVIEW);
		assertEquals("litematic", s.format());
		assertEquals(3953, s.dataVersion());
		assertEquals(region.sizeX(), s.sizeX());
		assertEquals(region.sizeY(), s.sizeY());
		assertEquals(region.sizeZ(), s.sizeZ());

		for (int y = 0; y < region.sizeY(); y++) {
			for (int z = 0; z < region.sizeZ(); z++) {
				for (int x = 0; x < region.sizeX(); x++) {
					assertEquals(region.get(x, y, z).toString(), at(s, x, y, z), "at " + x + "," + y + "," + z);
				}
			}
		}
	}

	/** 負のサイズの軸は最小の角から + の向き。2 つの領域は全体の箱に並べる。 */
	@Test
	void litematicNegativeSizeAndTwoRegions() throws Exception {
		CompoundTag regions = new CompoundTag();
		// 領域 A: Position (0, 2, 0)・Size (1, -3, 1) → y = 0..2。下から石・空気・レッドストーンブロック
		regions.put("A", region(xyz(0, 2, 0), xyz(1, -3, 1),
				List.of(state("minecraft:air", Map.of()), state("minecraft:stone", Map.of()), state("minecraft:redstone_block", Map.of())),
				new int[] {1, 0, 2}));
		// 領域 B: Position (2, 0, 0)・Size (-1, 1, 1) → x = 2 だけ (最小の角は 2 + (-1 + 1) = 2)
		regions.put("B", region(xyz(2, 0, 0), xyz(-1, 1, 1), List.of(state("minecraft:gold_block", Map.of())), new int[] {0}));
		CompoundTag root = new CompoundTag();
		root.putInt("MinecraftDataVersion", 3953);
		root.putInt("Version", 6);
		root.put("Regions", regions);
		RawStructure s = StructureFiles.read(write("neg.litematic", root), StructureLimits.PREVIEW);
		assertEquals(3, s.sizeX());
		assertEquals(3, s.sizeY());
		assertEquals(1, s.sizeZ());
		assertEquals("minecraft:stone", at(s, 0, 0, 0));
		assertEquals("minecraft:air", at(s, 0, 1, 0));
		assertEquals("minecraft:redstone_block", at(s, 0, 2, 0));
		assertEquals("minecraft:gold_block", at(s, 2, 0, 0));
		assertEquals("minecraft:air", at(s, 1, 0, 0));
		assertEquals(1, s.notes().size());
	}

	private static CompoundTag region(CompoundTag pos, CompoundTag size, List<CompoundTag> palette, int[] values) {
		CompoundTag r = new CompoundTag();
		r.put("Position", pos);
		r.put("Size", size);
		ListTag p = new ListTag();
		p.addAll(palette);
		r.put("BlockStatePalette", p);
		r.putLongArray("BlockStates", LitematicaBitArray.pack(values, LitematicaBitArray.bitsForPaletteSize(palette.size())));
		return r;
	}

	/** Sponge の版 2: 番号が 128 以上 (varint が 2 byte) でも読める。 */
	@Test
	void spongeVersion2() throws Exception {
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:air", 0);
		palette.putInt("minecraft:repeater[delay=2,facing=north,locked=false,powered=false]", 200);
		palette.putInt("minecraft:stone", 1);
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		// 2x1x2: (0,0,0)=石 (1,0,0)=リピーター (0,0,1)=空気 (1,0,1)=石
		varint(data, 1);
		varint(data, 200);
		varint(data, 0);
		varint(data, 1);
		CompoundTag root = new CompoundTag();
		root.putInt("Version", 2);
		root.putInt("DataVersion", 3953);
		root.putShort("Width", (short) 2);
		root.putShort("Height", (short) 1);
		root.putShort("Length", (short) 2);
		root.put("Palette", palette);
		root.putByteArray("BlockData", data.toByteArray());
		RawStructure s = StructureFiles.read(write("v2.schem", root), StructureLimits.PREVIEW);
		assertEquals("minecraft:stone", at(s, 0, 0, 0));
		assertEquals("minecraft:repeater[delay=2,facing=north,locked=false,powered=false]", at(s, 1, 0, 0));
		assertEquals("minecraft:air", at(s, 0, 0, 1));
		assertEquals("minecraft:stone", at(s, 1, 0, 1));
	}

	/** Sponge の版 3 (Schematic の中の Blocks)。.schematic の拡張子でも中身で見分ける。 */
	@Test
	void spongeVersion3AsSchematic() throws Exception {
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:redstone_wire[east=side,north=none,power=15,south=none,west=side]", 0);
		CompoundTag blocks = new CompoundTag();
		blocks.put("Palette", palette);
		blocks.putByteArray("Data", new byte[] {0});
		CompoundTag schem = new CompoundTag();
		schem.putInt("Version", 3);
		schem.putInt("DataVersion", 3953);
		schem.putShort("Width", (short) 1);
		schem.putShort("Height", (short) 1);
		schem.putShort("Length", (short) 1);
		schem.put("Blocks", blocks);
		CompoundTag root = new CompoundTag();
		root.put("Schematic", schem);
		RawStructure s = StructureFiles.read(write("v3.schematic", root), StructureLimits.PREVIEW);
		assertEquals("schematic", s.format());
		assertEquals("minecraft:redstone_wire[east=side,north=none,power=15,south=none,west=side]", at(s, 0, 0, 0));
	}

	/** MCEdit の旧形式: 数字の ID とデータ値を 1.13 の名前に。AddBlocks で 256 以上 (MOD) は描かない。 */
	@Test
	void legacySchematic() throws Exception {
		CompoundTag root = new CompoundTag();
		root.putShort("Width", (short) 4);
		root.putShort("Height", (short) 1);
		root.putShort("Length", (short) 1);
		root.putString("Materials", "Alpha");
		root.putByteArray("Blocks", new byte[] {1, 1, 55, 1});
		root.putByteArray("Data", new byte[] {0, 1, 15, 0});
		root.putByteArray("AddBlocks", new byte[] {0x00, 0x01}); // 4 つ目 (奇数番目の下位) だけ +256
		RawStructure s = StructureFiles.read(write("old.schematic", root), StructureLimits.PREVIEW);
		assertEquals(LegacySchematicReader.FLATTENED_DATA_VERSION, s.dataVersion());
		assertEquals("minecraft:stone", at(s, 0, 0, 0));
		assertEquals("minecraft:granite", at(s, 1, 0, 0));
		assertTrue(at(s, 2, 0, 0).startsWith("minecraft:redstone_wire[") && at(s, 2, 0, 0).contains("power=15"), at(s, 2, 0, 0));
		assertEquals("minecraft:air", at(s, 3, 0, 0));
		assertEquals(1, s.notes().size());
	}

	/** 構造ブロックの .nbt: 書いていない場所は空気。palettes なら 1 つ目。 */
	@Test
	void structureNbt() throws Exception {
		CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", 3953);
		root.put("size", ints(2, 2, 1));
		ListTag palettes = new ListTag();
		ListTag p0 = new ListTag();
		p0.add(state("minecraft:stone", Map.of()));
		p0.add(state("minecraft:lever", Map.of("face", "floor", "facing", "north", "powered", "true")));
		ListTag p1 = new ListTag();
		p1.add(state("minecraft:dirt", Map.of()));
		p1.add(state("minecraft:dirt", Map.of()));
		palettes.add(p0);
		palettes.add(p1);
		root.put("palettes", palettes);
		ListTag blocks = new ListTag();
		blocks.add(block(0, 0, 0, 0));
		blocks.add(block(0, 1, 0, 1));
		blocks.add(block(5, 5, 5, 0)); // 範囲の外は捨てる
		root.put("blocks", blocks);
		RawStructure s = StructureFiles.read(write("s.nbt", root), StructureLimits.PREVIEW);
		assertEquals("minecraft:stone", at(s, 0, 0, 0));
		assertEquals("minecraft:lever[face=floor,facing=north,powered=true]", at(s, 0, 1, 0));
		assertEquals("minecraft:air", at(s, 1, 0, 0));
		assertEquals(1, s.notes().size());
	}

	private static CompoundTag block(int x, int y, int z, int state) {
		CompoundTag b = new CompoundTag();
		b.put("pos", ints(x, y, z));
		b.putInt("state", state);
		return b;
	}

	@Test
	void errors() throws Exception {
		Path broken = dir.resolve("broken.litematic");
		Files.write(broken, new byte[] {0x1F, (byte) 0x8B, 8, 0, 0, 0});
		assertTrue(assertThrows(StructureException.class, () -> StructureFiles.read(broken, StructureLimits.PREVIEW)).getMessage().contains("読めません"));

		CompoundTag huge = new CompoundTag();
		huge.put("size", ints(1000, 256, 1000));
		huge.put("palette", new ListTag());
		assertTrue(assertThrows(StructureException.class, () -> StructureFiles.read(write("huge.nbt", huge), StructureLimits.PREVIEW))
				.getMessage().contains("大きすぎる"));

		Path world = dir.resolve("w.mcworld");
		Files.write(world, new byte[] {1});
		assertTrue(assertThrows(StructureException.class, () -> StructureFiles.read(world, StructureLimits.PREVIEW)).getMessage().contains(".mcworld"));
		assertNotNull(StructureFiles.unsupportedReason("a.mcworld"));
		assertNull(StructureFiles.unsupportedReason("A.Litematic"));

		// 小さい上限で、ファイルの大きさと NBT の中身の量で止まる
		CompoundTag big = new CompoundTag();
		big.putByteArray("x", new byte[4096]);
		Path bigFile = write("big.nbt", big);
		assertThrows(StructureException.class, () -> StructureFiles.read(bigFile, new StructureLimits(16, 1 << 20, 1 << 20)));
		assertTrue(assertThrows(StructureException.class, () -> StructureFiles.read(bigFile, new StructureLimits(1 << 20, 1024, 1 << 20)))
				.getMessage().contains("大きすぎる"));
	}

	/** 体積の上限: 3 辺の積が long で桁あふれする大きさでも止める。ちょうど上限は通す。 */
	@Test
	void volumeLimitDoesNotOverflow() {
		StructureLimits l = StructureLimits.PREVIEW;
		assertNotNull(l.volumeReason(4194304, 4194304, 4194304)); // 積は 2^66 (long で 0 になる)
		assertNotNull(l.volumeReason(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertNotNull(l.volumeReason(1, 1, l.maxVolume() + 1));
		assertNull(l.volumeReason(1, 1, l.maxVolume()));
		assertNull(l.volumeReason(256, 64, 256));
		assertNotNull(l.volumeReason(0, 5, 5));
	}

	/** 古い版のブロックはいまの版の名前に直す (1.20.3 の grass → short_grass・1.21.9 の chain → iron_chain・1.12 の stone の variant)。 */
	@Test
	void fixerUpgradesOldStates() {
		assertEquals("minecraft:short_grass", BlockStateFixer.fixOne(BlockStateEntry.of("minecraft:grass"), 3465).toString());
		assertEquals("minecraft:iron_chain", BlockStateFixer.fixOne(BlockStateEntry.of("minecraft:chain"), 3953).name());
		assertEquals("minecraft:granite", BlockStateFixer.fixOne(BlockStateEntry.of("minecraft:stone", Map.of("variant", "granite")), 1343).toString());
		// いまの版・版なしはそのまま
		assertEquals("minecraft:grass", BlockStateFixer.fixOne(BlockStateEntry.of("minecraft:grass"), RawStructure.UNKNOWN_VERSION).toString());
		assertEquals("minecraft:chain", BlockStateFixer.fixOne(BlockStateEntry.of("minecraft:chain"), BlockStateFixer.currentVersion()).toString());
	}

	/** 直して同じになった状態は 1 つにまとめ、空気は 0 番のまま。 */
	@Test
	void toStateMergesFixedDuplicates() {
		List<BlockStateEntry> palette = List.of(BlockStateEntry.AIR, BlockStateEntry.of("minecraft:grass"), BlockStateEntry.of("minecraft:short_grass"));
		RawStructure raw = new RawStructure("nbt", 3, 1, 1, palette, new int[] {0, 1, 2}, 3465, List.of());
		CircuitState c = BlockStateFixer.toState(raw);
		assertEquals(List.of("minecraft:air", "minecraft:short_grass"), c.palette());
		assertEquals(0, c.blocks()[0]);
		assertEquals(c.blocks()[1], c.blocks()[2]);
	}

	@Test
	void parseStateStrings() {
		assertEquals("minecraft:stone", BlockStates.parse("stone").toString());
		assertEquals("minecraft:lever[face=wall,powered=false]", BlockStates.parse("minecraft:lever[powered=false, face=wall]").toString());
		assertThrows(IllegalArgumentException.class, () -> BlockStates.parse("minecraft:lever[powered"));
		CompoundTag newStyle = new CompoundTag();
		newStyle.putString("id", "minecraft:repeater");
		CompoundTag props = new CompoundTag();
		props.putString("delay", "3");
		newStyle.put("properties", props);
		assertEquals("minecraft:repeater[delay=3]", BlockStates.fromTag(newStyle).toString());
	}

	private static void varint(ByteArrayOutputStream out, int v) {
		while ((v & ~0x7F) != 0) {
			out.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}

		out.write(v);
	}
}
