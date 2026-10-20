package com.redtact.live.structure.bedrock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.structure.RawStructure;
import com.redtact.live.structure.StructureException;
import com.redtact.live.structure.StructureLimits;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * 統合版の .mcstructure (#70)。Go 版 (mc-nbt-converter 7f17b26 = 中継サーバと同じ版) と全マス同じブロック状態になるかを、
 * Go が書いた期待値と比べる。
 *
 * <p>期待値 ({@code *.expected.nbt}) は Go の ConvertMcstructureBytes の出力そのまま (gzip の Java 版の構造 NBT。
 * blocks に無い場所は空気)。elevator は mc-nbt-converter の {@code packages/go/test/testdata/expected_mcstructure.nbt}
 * (7f17b26 の Go で作り直したものとパレットの並びまで同じことを確かめた)。ほかは
 * {@code git -C mc-nbt-converter archive 7f17b26 packages/go | tar -x -C <dir>} として、{@code <dir>/packages/go} を
 * replace した小さな Go のプログラム (go1.24.0) で {@code os.WriteFile(out, ConvertMcstructureBytes(in))} として作った。
 * 見本の .mcstructure は mc-nbt-converter の {@code packages/go/test/testdata/elevator.mcstructure} (版 1) と
 * {@code tests/golden/fixtures} (BDS 1.26.52 が書いた版 2 の 2 つ・向き付きブロックを並べた版 1) と、乱数で作った random-palette。
 * 乱数の見本 2000 個と、見本のバイトを乱数で書き換えた 1500 個でも Go と比べた (壊れたものはどちらも読めず、ほかは全マス同じ。
 * 違うのは字の中の UTF-8 として正しくないバイトだけで、Go はそのまま、ここは U+FFFD にする)。
 */
class McstructureReaderTest {
	static final String DIR = "/structure/bedrock/";

	@Test
	void elevatorMatchesGo() throws Exception {
		RawStructure s = assertMatchesGo("elevator");
		assertEquals(List.of(), s.notes());
	}

	/** 版 2 (IntArray の層)。2 層目に水没した階段の水がある。 */
	@Test
	void bdsV2SmallMatchesGo() throws Exception {
		RawStructure s = assertMatchesGo("bds-26.52-v2-small");
		assertEquals(List.of("水に浸かったブロック 1 個の水は描いていません"), s.notes());
	}

	/** 版 2 で 2 層目が無い (水没が無いので省かれた)。 */
	@Test
	void bdsV2OneLayerMatchesGo() throws Exception {
		RawStructure s = assertMatchesGo("bds-26.52-v2-one-layer");
		assertEquals(List.of(), s.notes());
	}

	@Test
	void orientedBlocksMatchesGo() throws Exception {
		assertMatchesGo("oriented-blocks");
	}

	/**
	 * 乱数で作った合成の見本 (20×6×20・パレット 300。対応表の名前と状態のキー・値を型 (Byte・Int・Short・Long・Float・Double・字) を
	 * 混ぜて選び、-1 と外れた番号も入れた)。Java 版の 108 種・454 状態になり、対応表の手順を広く通る。
	 */
	@Test
	void randomPaletteMatchesGo() throws Exception {
		assertMatchesGo("random-palette");
	}

	/** Go の試験 (TestConvertMcstructureV2MatchesV1) と同じく、版 1 の elevator を版 2 の形に書き直しても同じになる。 */
	@Test
	void v2LayersMatchV1() throws Exception {
		RawStructure v1 = read("elevator.mcstructure");

		for (boolean keepSecond : new boolean[] {true, false}) {
			Map<String, Object> root = parseAll(resource("elevator.mcstructure"));
			Map<String, Object> structure = child(root, "structure");
			List<Object> layers = new ArrayList<>();

			for (Object l : (List<?>) structure.get("block_indices")) {
				layers.add(((LittleEndianNbt.IntList) l).values());
			}

			structure.put("block_indices", keepSecond ? layers : layers.subList(0, 1));
			root.put("format_version", 2);
			assertSame(v1, McstructureReader.read(LeNbtWriter.write(root), StructureLimits.PREVIEW));
		}
	}

	/**
	 * 統合版 26.60 (プレビュー) の圧縮: compression が 1 で、structure が「structure の Compound を NBT にして zlib で縮めた ByteArray」
	 * (変更履歴の書き方どおりに作ったもの。実物では確かめていない)。
	 */
	@Test
	void readsZlibCompressedStructure() throws Exception {
		RawStructure plain = read("bds-26.52-v2-small.mcstructure");
		byte[] packed = compressedSmall(1);
		assertSame(plain, McstructureReader.read(packed, StructureLimits.PREVIEW));
		assertEquals(plain.notes(), McstructureReader.read(packed, StructureLimits.PREVIEW).notes());

		StructureException unknown = assertThrows(StructureException.class,
				() -> McstructureReader.read(compressedSmall(2), StructureLimits.PREVIEW));
		assertTrue(unknown.getMessage().contains("compression=2"), unknown.getMessage());
	}

	@Test
	void rejectsBrokenOrHugeZlib() throws Exception {
		Map<String, Object> root = parseAll(resource("bds-26.52-v2-small.mcstructure"));
		root.put("compression", 1);
		byte[] zlib = zlib(LeNbtWriter.write(child(root, "structure")));
		root.put("structure", Arrays.copyOf(zlib, zlib.length / 2));
		assertBroken(LeNbtWriter.write(root), StructureLimits.PREVIEW, "途中で切れています");

		root.put("structure", new byte[] {0x78, (byte) 0x9C, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}); // 使えないブロックの型
		assertBroken(LeNbtWriter.write(root), StructureLimits.PREVIEW, "壊れています");

		// 4 MB の 0 は数 KB に縮む。ほどく量はファイルの上限 (ここでは 1 MB) まで
		root.put("structure", zlib(new byte[4 << 20]));
		assertBroken(LeNbtWriter.write(root), new StructureLimits(1 << 20, 128L << 20, 4L << 20), "大きすぎる");
	}

	@Test
	void rejectsTruncatedFile() throws Exception {
		byte[] data = resource("elevator.mcstructure");

		for (int cut : new int[] {0, 1, 3, 100, data.length / 2, data.length - 1}) {
			assertBroken(Arrays.copyOf(data, cut), StructureLimits.PREVIEW, "途中で切れています");
		}
	}

	@Test
	void rejectsWrongRoot() throws Exception {
		// List がルート
		assertBroken(new LeNbtWriter().u8(LittleEndianNbt.LIST).string("").u8(LittleEndianNbt.INT).i32(0).bytes(), StructureLimits.PREVIEW,
				"統合版の構造ファイルではありません");
		// Java 版の構造 NBT (gzip)
		assertBroken(resource("elevator.expected.nbt"), StructureLimits.PREVIEW, "gzip");
	}

	/** 宣言だけ大きい長さ (数バイトのファイル) で、確保も待ちもせずすぐ断る。持つ所と読み飛ばす所の両方で見る。 */
	@Test
	void rejectsHugeDeclaredLengths() {
		for (String key : new String[] {"size", "entities"}) {
			assertHuge(key, w -> w.u8(LittleEndianNbt.INT_ARRAY).string(key).i32(Integer.MAX_VALUE));
			assertHuge(key, w -> w.u8(LittleEndianNbt.LONG_ARRAY).string(key).i32(Integer.MAX_VALUE).i64(1));
			assertHuge(key, w -> w.u8(LittleEndianNbt.BYTE_ARRAY).string(key).i32(Integer.MAX_VALUE));
			assertHuge(key, w -> w.u8(LittleEndianNbt.STRING).string(key).raw(new byte[] {(byte) 0xFF, (byte) 0xFF, 'a'}));
			assertHuge(key, w -> w.u8(LittleEndianNbt.LIST).string(key).u8(LittleEndianNbt.INT).i32(Integer.MAX_VALUE));
			assertHuge(key, w -> w.u8(LittleEndianNbt.LIST).string(key).u8(LittleEndianNbt.COMPOUND).i32(Integer.MAX_VALUE).u8(0));
			assertHuge(key, w -> w.u8(LittleEndianNbt.LIST).string(key).u8(LittleEndianNbt.LIST).i32(Integer.MAX_VALUE));
			// 型の無い (End の) List は 1 要素 0 バイトなので、数を見ないと延々と回る (Go の validateNbtLE はここを通す)
			assertHuge(key, w -> w.u8(LittleEndianNbt.LIST).string(key).u8(LittleEndianNbt.END).i32(Integer.MAX_VALUE));
			assertHuge(key, w -> w.u8(LittleEndianNbt.LIST).string(key).u8(LittleEndianNbt.INT).i32(-1));
			assertHuge(key, w -> w.u8(42).string(key));
		}
	}

	/** List を 1000 段重ねても StackOverflowError にならず断る (深さは NbtAccounter で 512 まで)。 */
	@Test
	void rejectsDeepNesting() {
		LeNbtWriter w = new LeNbtWriter().u8(LittleEndianNbt.COMPOUND).string("").u8(LittleEndianNbt.LIST).string("entities");

		for (int i = 0; i < 1000; i++) {
			w.u8(LittleEndianNbt.LIST).i32(1);
		}

		w.u8(LittleEndianNbt.INT).i32(0);
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			StructureException e = assertThrows(StructureException.class, () -> McstructureReader.read(w.bytes(), StructureLimits.PREVIEW));
			assertTrue(e.getMessage().contains("大きすぎる"), e.getMessage());
		});
	}

	@Test
	void rejectsOverQuota() throws Exception {
		assertBroken(resource("elevator.mcstructure"), new StructureLimits(32L << 20, 10_000, 4L << 20), "大きすぎる");
	}

	@Test
	void rejectsTooLargeVolumeBeforeAllocating() {
		// 体積の上限 (100) を超える
		StructureLimits small = new StructureLimits(32L << 20, 128L << 20, 100);
		assertBroken(structureBytes(new int[] {10, 10, 2}, new int[0]), small, "範囲が大きすぎるので描きません (10×10×2)");
		// 3 辺の積が long で桁あふれする大きさ (2^22 の 3 乗 = 2^66 は 0 になる) も通さない
		int big = 1 << 22;
		assertBroken(structureBytes(new int[] {big, big, big}, new int[0]), StructureLimits.PREVIEW, "範囲が大きすぎるので描きません");
		assertBroken(structureBytes(new int[] {0, 1, 1}, new int[0]), StructureLimits.PREVIEW, "回路の大きさがありません");
		assertBroken(structureBytes(new int[] {-1, -1, 1}, new int[0]), StructureLimits.PREVIEW, "回路の大きさがありません");
	}

	@Test
	void rejectsBlockIndicesLengthMismatch() {
		assertBroken(structureBytes(new int[] {2, 2, 2}, new int[7]), StructureLimits.PREVIEW, "block_indices の数が合いません (7 個。8 個のはず)");
	}

	@Test
	void rejectsMissingParts() {
		Map<String, Object> root = structureRoot(new int[] {1, 1, 1}, new int[1]);
		root.put("size", new int[] {1, 1, 1}); // IntArray の size は Go も読まない
		assertBroken(LeNbtWriter.write(root), StructureLimits.PREVIEW, "size がありません");

		root = structureRoot(new int[] {1, 1, 1}, new int[1]);
		root.remove("structure");
		assertBroken(LeNbtWriter.write(root), StructureLimits.PREVIEW, "structure がありません");

		root = structureRoot(new int[] {1, 1, 1}, new int[1]);
		child(root, "structure").remove("palette");
		assertBroken(LeNbtWriter.write(root), StructureLimits.PREVIEW, "palette がありません");

		root = structureRoot(new int[] {1, 1, 1}, new int[1]);
		child(child(root, "structure"), "palette").remove("default");
		assertBroken(LeNbtWriter.write(root), StructureLimits.PREVIEW, "palette.default がありません");
	}

	/** -1 (構造物の空白) と外れた番号は空気。パレットが無くても (Go と同じく) 空気だけの回路として読む。 */
	@Test
	void voidAndOutOfRangeAreAir() throws Exception {
		RawStructure s = McstructureReader.read(structureBytes(new int[] {4, 1, 1}, new int[] {-1, 0, 5, 0}), StructureLimits.PREVIEW);
		assertEquals(List.of(BlockStateEntry.AIR, BlockStateEntry.of("minecraft:stone")), s.palette());
		assertArrayEquals(new int[] {0, 1, 0, 1}, s.blocks());

		Map<String, Object> root = structureRoot(new int[] {2, 1, 1}, new int[] {0, 0});
		child(child(child(root, "structure"), "palette"), "default").remove("block_palette");
		RawStructure empty = McstructureReader.read(LeNbtWriter.write(root), StructureLimits.PREVIEW);
		assertEquals(List.of(BlockStateEntry.AIR), empty.palette());
		assertArrayEquals(new int[] {0, 0}, empty.blocks());
	}

	/** 添字の並び: .mcstructure は x が外側 (x*sy*sz + y*sz + z)、RawStructure は (y * sizeZ + z) * sizeX + x。 */
	@Test
	void reordersIndicesToYzx() throws Exception {
		int sx = 2;
		int sy = 3;
		int sz = 4;
		int[] indices = new int[sx * sy * sz];

		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					indices[x * sy * sz + y * sz + z] = x == 1 && y == 2 && z == 3 ? 0 : 1; // 0 = stone、1 = air
				}
			}
		}

		Map<String, Object> root = structureRoot(new int[] {sx, sy, sz}, indices);
		List<Object> palette = palette(root);
		palette.add(Map.of("name", "minecraft:air", "states", new LinkedHashMap<>()));
		RawStructure s = McstructureReader.read(LeNbtWriter.write(root), StructureLimits.PREVIEW);
		int stone = s.palette().indexOf(BlockStateEntry.of("minecraft:stone"));

		for (int i = 0; i < s.blocks().length; i++) {
			assertEquals(i == (2 * sz + 3) * sx + 1 ? stone : 0, s.blocks()[i], "index " + i);
		}
	}

	// ---- 比べ方 ----

	/** 全マスのブロック状態とパレットの並び (0 番の空気を除く) が Go の出力と同じ。 */
	static RawStructure assertMatchesGo(String name) throws Exception {
		RawStructure s = read(name + ".mcstructure");
		CompoundTag want = NbtIo.readCompressed(new ByteArrayInputStream(resource(name + ".expected.nbt")), NbtAccounter.unlimitedHeap());
		ListTag size = want.getListOrEmpty("size");
		assertEquals(List.of(size.getIntOr(0, -1), size.getIntOr(1, -1), size.getIntOr(2, -1)), List.of(s.sizeX(), s.sizeY(), s.sizeZ()));
		assertEquals(want.getIntOr("DataVersion", -1), s.dataVersion());
		assertEquals(McstructureReader.MAPPING_DATA_VERSION, s.dataVersion());
		assertEquals("mcstructure", s.format());

		List<BlockStateEntry> palette = new ArrayList<>();
		ListTag paletteTag = want.getListOrEmpty("palette");

		for (int i = 0; i < paletteTag.size(); i++) {
			CompoundTag p = paletteTag.getCompoundOrEmpty(i);
			CompoundTag propsTag = p.getCompoundOrEmpty("Properties");
			Map<String, String> props = new TreeMap<>();
			propsTag.keySet().forEach(k -> props.put(k, propsTag.getStringOr(k, "")));
			palette.add(BlockStateEntry.of(p.getStringOr("Name", ""), props));
		}

		assertEquals(BlockStateEntry.AIR, s.palette().get(0));
		assertEquals(palette, s.palette().subList(1, s.palette().size()), "palette (Go と同じ並び)");

		Map<Integer, BlockStateEntry> expected = new HashMap<>();
		ListTag blocks = want.getListOrEmpty("blocks");

		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompoundOrEmpty(i);
			ListTag pos = b.getListOrEmpty("pos");
			expected.put((pos.getIntOr(1, -1) * s.sizeZ() + pos.getIntOr(2, -1)) * s.sizeX() + pos.getIntOr(0, -1),
					palette.get(b.getIntOr("state", -1)));
		}

		int mismatches = 0;
		StringBuilder first = new StringBuilder();

		for (int i = 0; i < s.blocks().length; i++) {
			String want1 = expected.getOrDefault(i, BlockStateEntry.AIR).toString();
			String got = s.palette().get(s.blocks()[i]).toString();

			if (!want1.equals(got) && mismatches++ < 5) {
				int x = i % s.sizeX();
				int z = i / s.sizeX() % s.sizeZ();
				int y = i / s.sizeX() / s.sizeZ();
				first.append("\n  ").append(x).append(',').append(y).append(',').append(z).append(": Go ").append(want1).append(" / Java ").append(got);
			}
		}

		assertEquals(0, mismatches, name + ": " + mismatches + " マス違う" + first);
		assertEquals(blocks.size(), Arrays.stream(s.blocks()).filter(b -> b != 0).count(), "空気でないブロックの数");
		return s;
	}

	/** 2 つの読んだ結果が全マス同じ。 */
	static void assertSame(RawStructure want, RawStructure got) {
		assertEquals(List.of(want.sizeX(), want.sizeY(), want.sizeZ()), List.of(got.sizeX(), got.sizeY(), got.sizeZ()));
		assertEquals(want.palette(), got.palette());
		assertArrayEquals(want.blocks(), got.blocks());
	}

	static void assertBroken(byte[] data, StructureLimits limits, String part) {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			StructureException e = assertThrows(StructureException.class, () -> McstructureReader.read(data, limits));
			assertTrue(e.getMessage().contains(part), "\"" + e.getMessage() + "\" に \"" + part + "\" が無い");
		});
	}

	interface Body {
		void write(LeNbtWriter w);
	}

	/** ルートの key に細工した値を 1 つだけ置いたファイル。 */
	static void assertHuge(String key, Body body) {
		LeNbtWriter w = new LeNbtWriter().u8(LittleEndianNbt.COMPOUND).string("");
		body.write(w);
		byte[] data = w.bytes();
		Executable read = () -> McstructureReader.read(data, StructureLimits.PREVIEW);
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			StructureException e = assertThrows(StructureException.class, read);
			assertTrue(e.getMessage().startsWith(".mcstructure を読めません"), key + ": " + e.getMessage());
		}, key);
	}

	/** 石 1 種のパレットの .mcstructure (版 1・1 層)。 */
	static Map<String, Object> structureRoot(int[] size, int[] indices) {
		Map<String, Object> def = new LinkedHashMap<>();
		List<Object> palette = new ArrayList<>();
		palette.add(new LinkedHashMap<>(Map.of("name", "minecraft:stone", "states", new LinkedHashMap<>(), "version", 18168865)));
		def.put("block_palette", palette);
		def.put("block_position_data", new LinkedHashMap<>());
		Map<String, Object> palettes = new LinkedHashMap<>();
		palettes.put("default", def);
		Map<String, Object> structure = new LinkedHashMap<>();
		structure.put("block_indices", new ArrayList<>(List.of(new LittleEndianNbt.IntList(indices))));
		structure.put("entities", new ArrayList<>());
		structure.put("palette", palettes);
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format_version", 1);
		root.put("size", new LittleEndianNbt.IntList(size));
		root.put("structure", structure);
		root.put("structure_world_origin", new LittleEndianNbt.IntList(new int[3]));
		return root;
	}

	static byte[] structureBytes(int[] size, int[] indices) {
		return LeNbtWriter.write(structureRoot(size, indices));
	}

	@SuppressWarnings("unchecked")
	static List<Object> palette(Map<String, Object> root) {
		return (List<Object>) child(child(child(root, "structure"), "palette"), "default").get("block_palette");
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> child(Map<String, Object> m, String key) {
		return (Map<String, Object>) m.get(key);
	}

	/** bds-26.52-v2-small の structure を zlib で縮めて ByteArray にしたもの。 */
	static byte[] compressedSmall(int compression) throws IOException, StructureException {
		Map<String, Object> root = parseAll(resource("bds-26.52-v2-small.mcstructure"));
		byte[] inner = LeNbtWriter.write(child(root, "structure"));
		Map<String, Object> out = new LinkedHashMap<>(root);
		out.put("compression", compression);
		out.put("structure", zlib(inner));
		return LeNbtWriter.write(out);
	}

	static byte[] zlib(byte[] data) {
		Deflater d = new Deflater();
		d.setInput(data);
		d.finish();
		byte[] buf = new byte[data.length + 1024];
		int n = d.deflate(buf);
		d.end();
		return Arrays.copyOf(buf, n);
	}

	static Map<String, Object> parseAll(byte[] data) throws StructureException {
		return LittleEndianNbt.read(data, NbtAccounter.unlimitedHeap(), LittleEndianNbt.Keep.ALL);
	}

	static RawStructure read(String file) throws IOException, StructureException {
		return McstructureReader.read(resource(file), StructureLimits.PREVIEW);
	}

	static byte[] resource(String file) throws IOException {
		try (InputStream in = McstructureReaderTest.class.getResourceAsStream(DIR + file)) {
			if (in == null) {
				throw new IOException("missing " + DIR + file);
			}

			return in.readAllBytes();
		}
	}
}
