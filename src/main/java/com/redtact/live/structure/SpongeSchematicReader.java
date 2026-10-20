package com.redtact.live.structure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redtact.live.i18n.Tr;
import com.redtact.live.litematic.BlockStateEntry;

import net.minecraft.nbt.CompoundTag;

/**
 * Sponge Schematic (WorldEdit の .schem。版 1〜3) (#70)。パレットは「ブロック状態の文字列 → 番号」、
 * ブロックは番号の可変長整数 (varint) を {@code (y * Length + z) * Width + x} の順に並べたもの。
 * 版 3 は {@code Schematic} の中の {@code Blocks} に入る。版 1 は版を書かないので 1.13.2 とみる (WorldEdit と同じ)。
 */
final class SpongeSchematicReader {
	/** 版 1 のファイルの版 (1.13.2)。 */
	static final int VERSION_1_DATA_VERSION = 1631;

	private SpongeSchematicReader() {
	}

	/** Sponge の形か (版 3 の Schematic の入れ物・Palette と BlockData)。 */
	static boolean looksLike(CompoundTag root) {
		CompoundTag s = root.getCompound("Schematic").orElse(root);
		return s.contains("Blocks") && s.getCompoundOrEmpty("Blocks").contains("Palette") || s.contains("Palette") && s.contains("BlockData");
	}

	static RawStructure read(CompoundTag root, StructureLimits limits, String format) throws StructureException {
		CompoundTag s = root.getCompound("Schematic").orElse(root);
		int version = s.getIntOr("Version", 1);
		int w = s.getShortOr("Width", (short) 0) & 0xFFFF;
		int h = s.getShortOr("Height", (short) 0) & 0xFFFF;
		int l = s.getShortOr("Length", (short) 0) & 0xFFFF;
		String reason = limits.volumeReason(w, h, l);

		if (reason != null) {
			throw new StructureException(reason);
		}

		CompoundTag paletteTag;
		byte[] data;

		if (version >= 3 || s.contains("Blocks")) {
			CompoundTag blocks = s.getCompoundOrEmpty("Blocks");
			paletteTag = blocks.getCompoundOrEmpty("Palette");
			data = blocks.getByteArray("Data").orElse(null);
		} else {
			paletteTag = s.getCompoundOrEmpty("Palette");
			data = s.getByteArray("BlockData").orElse(null);
		}

		if (data == null || paletteTag.isEmpty()) {
			throw new StructureException(Tr.f(".{0} を読めません: ブロックかパレットがありません", format));
		}

		PaletteBuilder palette = new PaletteBuilder();
		Map<Integer, Integer> ids = new HashMap<>();

		for (String key : paletteTag.keySet()) {
			int fileId = paletteTag.getIntOr(key, -1);

			if (fileId < 0) {
				continue;
			}

			BlockStateEntry e;

			try {
				e = BlockStates.parse(key);
			} catch (IllegalArgumentException ex) {
				e = null; // 読めない状態は空気にする
			}

			ids.put(fileId, palette.id(e));
		}

		int volume = w * h * l;
		int[] blocks = new int[volume];
		int pos = 0;

		for (int i = 0; i < volume; i++) {
			int value = 0;
			int shift = 0;

			while (true) {
				if (pos >= data.length) {
					throw new StructureException(Tr.f(".{0} を読めません: ブロックが足りません (壊れています)", format));
				}

				byte b = data[pos++];
				value |= (b & 0x7F) << shift;

				if ((b & 0x80) == 0) {
					break;
				}

				shift += 7;

				if (shift > 28) {
					throw new StructureException(Tr.f(".{0} を読めません: ブロックの番号が長すぎます (壊れています)", format));
				}
			}

			blocks[i] = ids.getOrDefault(value, 0);
		}

		int dataVersion = s.getIntOr("DataVersion", version == 1 ? VERSION_1_DATA_VERSION : RawStructure.UNKNOWN_VERSION);
		return new RawStructure(format, w, h, l, palette.entries(), blocks, dataVersion, List.of());
	}
}
