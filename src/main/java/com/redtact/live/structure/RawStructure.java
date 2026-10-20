package com.redtact.live.structure;

import java.util.List;

import com.redtact.live.litematic.BlockStateEntry;

/**
 * 回路のファイルから読んだままの中身 (#70)。パレットはそのファイルの版 ({@link #dataVersion}) の Java 版のブロック状態で、
 * いまの版に直すのは {@link BlockStateFixer}。添字 = {@code (y * sizeZ + z) * sizeX + x} (CircuitState と同じ)。
 * 構造物の空白 (structure void) や読めなかった場所は空気にする。MC に依存しない。
 *
 * @param format      画面に出す形式の名前 (litematic・schem・nbt など)
 * @param dataVersion パレットの版 (Java 版の DataVersion)。分からなければ {@link #UNKNOWN_VERSION}
 * @param notes       読めたが描き切れないことなど、画面に出す補足 (無ければ空)
 */
public record RawStructure(String format, int sizeX, int sizeY, int sizeZ, List<BlockStateEntry> palette, int[] blocks, int dataVersion,
		List<String> notes) {
	/** 版が書いていないファイル。直さずにそのまま使う。 */
	public static final int UNKNOWN_VERSION = -1;

	public RawStructure {
		if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
			throw new IllegalArgumentException("size must be positive: " + sizeX + "x" + sizeY + "x" + sizeZ);
		}

		if ((long) sizeX * sizeY * sizeZ != blocks.length) {
			throw new IllegalArgumentException("blocks length " + blocks.length + " != volume");
		}

		if (palette.isEmpty()) {
			throw new IllegalArgumentException("palette is empty");
		}

		for (int b : blocks) {
			if (b < 0 || b >= palette.size()) {
				throw new IllegalArgumentException("palette index out of range: " + b);
			}
		}

		palette = List.copyOf(palette);
		notes = List.copyOf(notes);
	}

	public long volume() {
		return (long) sizeX * sizeY * sizeZ;
	}
}
