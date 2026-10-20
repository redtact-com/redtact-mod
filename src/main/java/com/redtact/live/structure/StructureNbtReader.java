package com.redtact.live.structure;

import java.util.List;

import com.redtact.live.i18n.Tr;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * 構造ブロックの .nbt (#70)。{@code size}・{@code palette} (複数あれば {@code palettes} の 1 つ目)・{@code blocks}
 * ({@code pos} と {@code state}) を並べる。書いていない場所は構造物の空白なので空気にする。
 * 版が無いファイルはゲームと同じく 500 とみる (StructureTemplateManager)。
 */
final class StructureNbtReader {
	static final int MISSING_DATA_VERSION = 500;

	private StructureNbtReader() {
	}

	static RawStructure read(CompoundTag root, StructureLimits limits) throws StructureException {
		ListTag size = root.getListOrEmpty("size");

		if (size.size() != 3) {
			throw new StructureException(Tr.t(".nbt を読めません: size がありません (構造ブロックのファイルではないかもしれません)"));
		}

		int sx = size.getIntOr(0, 0);
		int sy = size.getIntOr(1, 0);
		int sz = size.getIntOr(2, 0);
		String reason = limits.volumeReason(sx, sy, sz);

		if (reason != null) {
			throw new StructureException(reason);
		}

		ListTag paletteTag = root.getList("palette").orElse(null);
		List<String> notes = List.of();

		if (paletteTag == null) {
			ListTag palettes = root.getListOrEmpty("palettes");
			paletteTag = palettes.getListOrEmpty(0);

			if (palettes.size() > 1) {
				notes = List.of(Tr.n("パレット {0} 種のうち 1 つ目を描いています", palettes.size()));
			}
		}

		PaletteBuilder palette = new PaletteBuilder();
		int[] ids = new int[paletteTag.size()];

		for (int p = 0; p < ids.length; p++) {
			ids[p] = palette.id(paletteTag.getCompound(p).map(BlockStates::fromTag).orElse(null));
		}

		int[] blocks = new int[sx * sy * sz];
		ListTag list = root.getListOrEmpty("blocks");

		for (int i = 0; i < list.size(); i++) {
			CompoundTag b = list.getCompoundOrEmpty(i);
			ListTag pos = b.getListOrEmpty("pos");
			int state = b.getIntOr("state", -1);

			if (pos.size() != 3 || state < 0 || state >= ids.length) {
				continue;
			}

			int x = pos.getIntOr(0, -1);
			int y = pos.getIntOr(1, -1);
			int z = pos.getIntOr(2, -1);

			if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
				continue;
			}

			blocks[(y * sz + z) * sx + x] = ids[state];
		}

		int version = root.getIntOr("DataVersion", MISSING_DATA_VERSION);
		return new RawStructure("nbt", sx, sy, sz, palette.entries(), blocks, version, notes);
	}
}
