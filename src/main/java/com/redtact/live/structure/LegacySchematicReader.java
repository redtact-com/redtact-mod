package com.redtact.live.structure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redtact.live.i18n.Tr;
import com.redtact.live.litematic.BlockStateEntry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.fixes.BlockStateData;

/**
 * MCEdit・Schematica の旧形式の .schematic (1.12 まで) (#70)。ブロックは数字の ID とデータ値で、
 * ゲームのデータの直し方 (BlockStateData。古いワールドを開くときと同じ表) で 1.13 の名前にする。
 * ID の上位 4 bit は AddBlocks (2 ブロックで 1 byte) か Schematica の Add (1 ブロック 1 byte)。
 * 1.12 までは導線のつながりや扉の上下などを置いた場所で決めていたので、そこは既定の形で描く。
 */
final class LegacySchematicReader {
	/** BlockStateData が返す状態の版 (BlockStateStructureTemplateFix と同じ 1.13 の平坦化の直後)。 */
	static final int FLATTENED_DATA_VERSION = 1450;

	private LegacySchematicReader() {
	}

	/** 旧形式か (Blocks と Data を数字で持つ)。 */
	static boolean looksLike(CompoundTag root) {
		return root.getByteArray("Blocks").isPresent();
	}

	static RawStructure read(CompoundTag root, StructureLimits limits) throws StructureException {
		String materials = root.getStringOr("Materials", "Alpha");

		if (!materials.equalsIgnoreCase("Alpha")) {
			throw new StructureException(Tr.f("この .schematic の形式 ({0}) は読めません", materials));
		}

		int w = root.getShortOr("Width", (short) 0) & 0xFFFF;
		int h = root.getShortOr("Height", (short) 0) & 0xFFFF;
		int l = root.getShortOr("Length", (short) 0) & 0xFFFF;
		String reason = limits.volumeReason(w, h, l);

		if (reason != null) {
			throw new StructureException(reason);
		}

		int volume = w * h * l;
		byte[] ids = root.getByteArray("Blocks").orElse(new byte[0]);
		byte[] meta = root.getByteArray("Data").orElse(new byte[0]);
		byte[] addNibbles = root.getByteArray("AddBlocks").orElse(null);
		byte[] add = root.getByteArray("Add").orElse(null);

		if (ids.length < volume) {
			throw new StructureException(Tr.t(".schematic を読めません: ブロックが足りません (壊れています)"));
		}

		PaletteBuilder palette = new PaletteBuilder();
		Map<Integer, Integer> byLegacy = new HashMap<>();
		int[] blocks = new int[volume];
		int modded = 0;

		for (int i = 0; i < volume; i++) {
			int id = ids[i] & 0xFF;

			if (addNibbles != null && (i >> 1) < addNibbles.length) {
				id |= ((i & 1) == 0 ? addNibbles[i >> 1] >> 4 & 0xF : addNibbles[i >> 1] & 0xF) << 8;
			} else if (add != null && i < add.length) {
				id |= (add[i] & 0xFF) << 8;
			}

			if (id > 0xFF) {
				modded++; // MOD のブロック (表に無い)
				continue;
			}

			int legacy = id << 4 | (i < meta.length ? meta[i] & 0xF : 0);
			Integer pid = byLegacy.get(legacy);

			if (pid == null) {
				pid = palette.id(flattened(legacy));
				byLegacy.put(legacy, pid);
			}

			blocks[i] = pid;
		}

		List<String> notes = modded > 0 ? List.of(Tr.n("MOD のブロック {0} 個は描いていません", modded)) : List.of();
		return new RawStructure("schematic", w, h, l, palette.entries(), blocks, FLATTENED_DATA_VERSION, notes);
	}

	/** 数字の ID とデータ値 (id << 4 | data) の 1.13 の状態。 */
	static BlockStateEntry flattened(int legacy) {
		Object v = BlockStateData.getTag(legacy).getValue();
		BlockStateEntry e = v instanceof CompoundTag c ? BlockStates.fromTag(c) : null;
		return e == null ? BlockStateEntry.AIR : e;
	}
}
