package com.redtact.live.preview.mesh;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 全画面のプレビュー (#102) で形を組み立てる回路のマス目。添字 {@code (y * sizeZ + z) * sizeX + x} のパレットの番号と、
 * 番号ごとのこのゲームのブロック (空気・知らないブロックは null)。中身は変えない (組み立ては別のスレッドで読む)。
 *
 * <p>プレビューを覚えている間ずっと持つので、番号はパレットの数に合わせて 1 マス 1 バイト (254 種まで)・2 バイト (65,534 種まで)・
 * 4 バイトに詰める (範囲が 4M ブロックでも 1 バイトなら 4 MB)。
 */
public final class CircuitGrid {
	private static final int NONE_BYTE = 0xFF;
	private static final int NONE_SHORT = 0xFFFF;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final @Nullable BlockState[] palette;
	private final byte @Nullable [] bytes;
	private final short @Nullable [] shorts;
	private final int @Nullable [] ints;

	/** blocks はパレットの番号 (範囲の外の番号は空気)。写して詰めるので、あとで変えてよい。 */
	public CircuitGrid(int sizeX, int sizeY, int sizeZ, @Nullable BlockState[] palette, int[] blocks) {
		if ((long) sizeX * sizeY * sizeZ != blocks.length) {
			throw new IllegalArgumentException("blocks length " + blocks.length + " != volume");
		}

		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = palette;
		int n = palette.length;

		if (n < NONE_BYTE) {
			byte[] b = new byte[blocks.length];

			for (int i = 0; i < blocks.length; i++) {
				int p = blocks[i];
				b[i] = (byte) (p >= 0 && p < n ? p : NONE_BYTE);
			}

			bytes = b;
			shorts = null;
			ints = null;
		} else if (n < NONE_SHORT) {
			short[] s = new short[blocks.length];

			for (int i = 0; i < blocks.length; i++) {
				int p = blocks[i];
				s[i] = (short) (p >= 0 && p < n ? p : NONE_SHORT);
			}

			bytes = null;
			shorts = s;
			ints = null;
		} else {
			bytes = null;
			shorts = null;
			ints = blocks.clone();
		}
	}

	public int sizeX() {
		return sizeX;
	}

	public int sizeY() {
		return sizeY;
	}

	public int sizeZ() {
		return sizeZ;
	}

	/** 番号ごとのブロック (空気・知らないブロックは null)。写しではないので変えないこと。 */
	public @Nullable BlockState[] palette() {
		return palette;
	}

	/** 1 マスに使うバイト数 (テスト用)。 */
	public int bytesPerCell() {
		return bytes != null ? 1 : shorts != null ? 2 : 4;
	}

	/** そのマスのブロック。範囲の外・空気・知らないブロックは null。 */
	public @Nullable BlockState at(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return null;
		}

		int i = (y * sizeZ + z) * sizeX + x;
		int p;

		if (bytes != null) {
			p = bytes[i] & 0xFF;
		} else if (shorts != null) {
			p = shorts[i] & 0xFFFF;
		} else {
			p = ints[i];
		}

		return p < 0 || p >= palette.length ? null : palette[p];
	}
}
