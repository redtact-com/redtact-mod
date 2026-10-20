package com.redtact.live.structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.state.CircuitState;
import org.jspecify.annotations.Nullable;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

/**
 * 読んだパレットをいまの版のブロックに直す (#70)。ゲームが古いワールドや構造物を開くときと同じデータの直し方
 * (DataFixer の BLOCK_STATE) を、パレットの 1 種類ずつにかける (1.12 の名前の平坦化・名前の変更・タグの欄の名前など)。
 * 直したあと同じになった状態は 1 つにまとめる。
 */
public final class BlockStateFixer {
	private BlockStateFixer() {
	}

	/** いまの版 (Java 版の DataVersion)。 */
	public static int currentVersion() {
		return SharedConstants.WORLD_VERSION;
	}

	/** いまの版の CircuitState にする。 */
	public static CircuitState toState(RawStructure raw) {
		List<BlockStateEntry> fixed = fixPalette(raw.palette(), raw.dataVersion());
		List<String> palette = new ArrayList<>();
		Map<String, Integer> index = new HashMap<>();
		int[] remap = new int[fixed.size()];

		for (int p = 0; p < fixed.size(); p++) {
			BlockStateEntry e = fixed.get(p);
			String s = e.isAir() ? BlockStateEntry.AIR.name() : e.toString();
			Integer id = index.get(s);

			if (id == null) {
				id = palette.size();
				palette.add(s);
				index.put(s, id);
			}

			remap[p] = id;
		}

		int[] src = raw.blocks();
		int[] blocks = new int[src.length];

		for (int i = 0; i < src.length; i++) {
			blocks[i] = remap[src[i]];
		}

		return new CircuitState(raw.sizeX(), raw.sizeY(), raw.sizeZ(), palette, blocks, Map.of());
	}

	/** パレットを fromVersion からいまの版へ直す (版が分からない・いまより新しいならそのまま)。直せない状態はそのまま返す。 */
	public static List<BlockStateEntry> fixPalette(List<BlockStateEntry> palette, int fromVersion) {
		int to = currentVersion();

		if (fromVersion == RawStructure.UNKNOWN_VERSION || fromVersion >= to) {
			return palette;
		}

		DataFixer fixer = DataFixers.getDataFixer();
		List<BlockStateEntry> out = new ArrayList<>(palette.size());

		for (BlockStateEntry e : palette) {
			out.add(e.isAir() ? e : fix(fixer, e, fromVersion, to));
		}

		return out;
	}

	private static BlockStateEntry fix(DataFixer fixer, BlockStateEntry e, int from, int to) {
		try {
			Dynamic<Tag> in = new Dynamic<>(NbtOps.INSTANCE, BlockStates.toTag(e, from));
			Tag out = fixer.update(References.BLOCK_STATE, in, from, to).getValue();
			BlockStateEntry fixed = out instanceof CompoundTag c ? BlockStates.fromTag(c) : null;
			return fixed == null ? e : fixed;
		} catch (RuntimeException ex) {
			return e;
		}
	}

	/**
	 * データの直し方 (DataFixer) の準備を先に済ませる (保管庫を開いたとき。#93)。最初の 1 回だけ重く (単体テストでは約 140 ms)、
	 * 最初に選んだファイルを待たせていた。古い名前 (1.20 の grass) を 1 つ直してみる。
	 */
	public static void warmUp() {
		fixPalette(List.of(BlockStateEntry.of("minecraft:grass")), 3465);
	}

	/** テスト用: 1 つだけ直す。 */
	static @Nullable BlockStateEntry fixOne(BlockStateEntry e, int from) {
		List<BlockStateEntry> out = fixPalette(List.of(e), from);
		return out.isEmpty() ? null : out.getFirst();
	}
}
