package com.redtact.live.structure.bedrock;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import com.redtact.live.i18n.Tr;
import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.structure.RawStructure;
import com.redtact.live.structure.StructureException;
import com.redtact.live.structure.StructureLimits;
import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;

/**
 * 統合版の .mcstructure (#70)。mc-nbt-converter の Go 版 (7f17b26。redtact-live の中継サーバと同じ版) の
 * {@code pkg/mcstruct/reader.go} (DecodeMcstructure) を写したもの。ブロックは {@link BlockMapper} で Java 版 (1.21) の
 * 状態にし、{@link PostProcess} で隣を見て直す。出力は Go の ConvertMcstructureBytes と同じブロック状態になる (試験で全マス比べる)。
 *
 * <p>Go と違うところ: 大きさは {@link StructureLimits} で先に見る。統合版 26.60 (プレビュー) の圧縮 ({@code compression} が 1 で、
 * {@code structure} が zlib で縮めた NBT の ByteArray) も読む (Go は読めない。実物の見本では確かめていない)。
 * ブロックエンティティ (看板の字・チェストの中身など)・エンティティ・{@code block_indices} の 2 層目 (水没の水) は使わない (Go と同じ)。
 */
public final class McstructureReader {
	/** 対応表の出力の版 (mc-nbt-converter の BuildStructureNbt と同じ 1.21)。 */
	public static final int MAPPING_DATA_VERSION = 3953;

	private static final String AIR = "minecraft:air";

	private McstructureReader() {
	}

	/**
	 * リトルエンディアン NBT の .mcstructure (版 1・2。26.60 の縮めた structure も) を読み、Java 版のブロック
	 * (版 {@link #MAPPING_DATA_VERSION}) にする。どのスレッドから呼んでもよい (初めての呼び出しで対応表を読む。20 ms ほど)。
	 */
	/** 統合版の対応表を先に読んでおく (保管庫を開いたとき。最初の .mcstructure を待たせない。#93)。読めなければ投げる。 */
	public static void warmUp() {
		BlockMapper.get();
	}

	public static RawStructure read(byte[] data, StructureLimits limits) throws StructureException {
		try {
			return decode(data, limits);
		} catch (RuntimeException e) {
			// 細工したファイルで思わぬ所が外れても、画面には読めないとだけ出す
			throw new StructureException(Tr.f(".mcstructure を読めません ({0})", e.getClass().getSimpleName()), e);
		}
	}

	private static RawStructure decode(byte[] data, StructureLimits limits) throws StructureException {
		NbtAccounter accounter = NbtAccounter.create(limits.maxNbtBytes());
		Map<String, Object> root = LittleEndianNbt.read(data, accounter, LittleEndianNbt.Keep.MCSTRUCTURE);

		if (!(root.get("size") instanceof LittleEndianNbt.IntList(int[] size)) || size.length != 3) {
			throw new StructureException(Tr.t(".mcstructure を読めません: size がありません"));
		}

		int sx = size[0];
		int sy = size[1];
		int sz = size[2];
		String reason = limits.volumeReason(sx, sy, sz);

		if (reason != null) {
			throw new StructureException(reason);
		}

		int volume = sx * sy * sz;
		Map<?, ?> structure = structure(root, accounter, limits);
		List<?> layers = structure.get("block_indices") instanceof List<?> l ? l : List.of();
		int[] indices = layer(layers, 0);

		if (indices == null || indices.length != volume) {
			throw new StructureException(Tr.f(".mcstructure を読めません: block_indices の数が合いません ({0} 個。{1} 個のはず)", indices == null ? 0 : indices.length,
					volume));
		}

		if (!(structure.get("palette") instanceof Map<?, ?> palettes)) {
			throw new StructureException(Tr.t(".mcstructure を読めません: palette がありません"));
		}

		if (!(palettes.get("default") instanceof Map<?, ?> def)) {
			throw new StructureException(Tr.t(".mcstructure を読めません: palette.default がありません"));
		}

		List<Map<?, ?>> raw = new ArrayList<>();

		// Compound でない要素は飛ばして詰める (Go と同じ)
		if (def.get("block_palette") instanceof List<?> list) {
			for (Object e : list) {
				if (e instanceof Map<?, ?> m) {
					raw.add(m);
				}
			}
		}

		BlockMapper mapper = mapper();
		JavaPalette palette = new JavaPalette();
		int[] rawToId = new int[raw.size()];
		Arrays.fill(rawToId, -1);
		int[] grid = new int[volume];

		// Go と同じ x → y → z の順に見て、パレットの並びも Go に合わせる (block_indices は x が外側: x*sy*sz + y*sz + z)
		for (int x = 0, idx = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++, idx++) {
					int p = indices[idx];

					// -1 は構造物の空白。番号が外れていても空気にする (Go と同じ)
					if (p < 0 || p >= raw.size()) {
						continue;
					}

					if (rawToId[p] < 0) {
						rawToId[p] = javaId(mapper, raw.get(p), palette);
					}

					grid[(y * sz + z) * sx + x] = rawToId[p];
				}
			}
		}

		PostProcess.apply(grid, sx, sy, sz, palette, mapper.redstoneConnectables());
		return new RawStructure("mcstructure", sx, sy, sz, palette.entries(), grid, MAPPING_DATA_VERSION, notes(layers, raw, volume));
	}

	/** 統合版のパレットの 1 つを Java 版の番号にする (空気は 0)。 */
	private static int javaId(BlockMapper mapper, Map<?, ?> entry, JavaPalette palette) {
		String name = entry.get("name") instanceof String s ? s : AIR;

		if (name.equals(AIR)) {
			return 0;
		}

		Map<String, Object> states = new HashMap<>();

		if (entry.get("states") instanceof Map<?, ?> m) {
			m.forEach((k, v) -> states.put((String) k, v));
		}

		BlockStateEntry e = mapper.map(name, states);
		return e.name().equals(AIR) ? 0 : palette.id(e);
	}

	/**
	 * ルートの structure。統合版 26.60 (プレビュー) からは compression が 1 なら structure が zlib で縮めた NBT の ByteArray になる
	 * (変更履歴「the entire structure compound is serialized as NBT and stored as a zlib-compressed NBT byte array」)。
	 */
	private static Map<?, ?> structure(Map<String, Object> root, NbtAccounter accounter, StructureLimits limits) throws StructureException {
		Object s = root.get("structure");

		if (s instanceof byte[] packed) {
			long compression = root.get("compression") instanceof Number n && !(n instanceof Float || n instanceof Double) ? n.longValue() : 0;

			if (compression != 1) {
				throw new StructureException(Tr.f(".mcstructure を読めません: structure の圧縮 (compression={0}) を知りません", compression));
			}

			s = LittleEndianNbt.read(inflate(packed, limits.maxFileBytes(), accounter), accounter, LittleEndianNbt.Keep.STRUCTURE);
		}

		if (!(s instanceof Map<?, ?> m)) {
			throw new StructureException(Tr.t(".mcstructure を読めません: structure がありません"));
		}

		return m;
	}

	/** zlib をほどく。ほどいた大きさは非圧縮のファイルと同じ上限まで (縮めた爆弾で大きく確保しない)。持つ量としても数える。 */
	private static byte[] inflate(byte[] packed, long maxBytes, NbtAccounter accounter) throws StructureException {
		Inflater inflater = new Inflater();

		try {
			inflater.setInput(packed);
			ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(packed.length * 4L, 1 << 20));
			byte[] chunk = new byte[64 << 10];

			while (!inflater.finished()) {
				int n = inflater.inflate(chunk);

				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					throw new StructureException(Tr.t(".mcstructure を読めません (圧縮された structure が途中で切れています)"));
				}

				if (out.size() + (long) n > maxBytes) {
					throw new StructureException(Tr.t(".mcstructure の中身が大きすぎるので読みません"));
				}

				accounter.accountBytes(n);
				out.write(chunk, 0, n);
			}

			return out.toByteArray();
		} catch (DataFormatException e) {
			throw new StructureException(Tr.t(".mcstructure を読めません (圧縮された structure が壊れています)"));
		} catch (NbtAccounterException e) {
			throw new StructureException(Tr.t(".mcstructure の中身が大きすぎるので読みません"));
		} finally {
			inflater.end();
		}
	}

	/**
	 * block_indices の i 層目 (Go の blockLayer)。版 1 は List[List[Int]]、版 2 (統合版 26.50〜) は List[IntArray] で、
	 * 版 2 の 2 層目は空なら無い。ほかの形なら null。
	 */
	private static int @Nullable [] layer(List<?> layers, int i) {
		if (layers.size() <= i) {
			return null;
		}

		return switch (layers.get(i)) {
			case LittleEndianNbt.IntList(int[] v) -> v;
			case int[] v -> v;
			default -> null;
		};
	}

	/** 描いていないことの補足。2 層目 (水没したブロックの水) は Go と同じく使わないので、数だけ出す。 */
	private static List<String> notes(List<?> layers, List<Map<?, ?>> raw, int volume) {
		int[] second = layer(layers, 1);

		if (second == null || second.length != volume) {
			return List.of();
		}

		int water = 0;

		for (int p : second) {
			if (p >= 0 && p < raw.size() && raw.get(p).get("name") instanceof String s && !s.equals(AIR)) {
				water++;
			}
		}

		return water == 0 ? List.of() : List.of(Tr.n("水に浸かったブロック {0} 個の水は描いていません", water));
	}

	private static BlockMapper mapper() throws StructureException {
		try {
			return BlockMapper.get();
		} catch (RuntimeException e) {
			throw new StructureException(Tr.f("統合版のブロックの対応表を読めません ({0})", e.getClass().getSimpleName()), e);
		}
	}
}
