package com.redtact.live.litematic;

/**
 * Litematica の BlockStates の詰め方 (MC に依存しない)。
 *
 * <p>1 エントリ = {@link #bitsForPaletteSize(int)} ビット。エントリは下位ビットから順に詰め、
 * long の境界をまたぐエントリは前の long の上位と次の long の下位に分かれて入る
 * (Minecraft 1.16 以降の BitStorage と違い、long の末尾に余りを残さない)。
 */
public final class LitematicaBitArray {
	private LitematicaBitArray() {
	}

	/** max(2, ceil(log2(paletteSize)))。 */
	public static int bitsForPaletteSize(int paletteSize) {
		if (paletteSize < 1) {
			throw new IllegalArgumentException("paletteSize must be >= 1: " + paletteSize);
		}

		return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
	}

	/** entries 個を bits ビットずつ詰めるのに要る long の数。 */
	public static int longCount(long entries, int bits) {
		checkBits(bits);

		if (entries < 0) {
			throw new IllegalArgumentException("entries must be >= 0: " + entries);
		}

		long longs = (entries * bits + 63L) / 64L;

		if (longs > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("too many entries: " + entries);
		}

		return (int) longs;
	}

	public static long[] pack(int[] values, int bits) {
		checkBits(bits);
		long mask = mask(bits);
		long[] out = new long[longCount(values.length, bits)];

		for (int i = 0; i < values.length; i++) {
			long value = values[i] & 0xFFFFFFFFL;

			if (value > mask) {
				throw new IllegalArgumentException("value " + values[i] + " at " + i + " does not fit in " + bits + " bits");
			}

			long bitIndex = (long) i * bits;
			int start = (int) (bitIndex >>> 6);
			int end = (int) ((bitIndex + bits - 1) >>> 6);
			int offset = (int) (bitIndex & 63L);
			out[start] |= value << offset;

			if (end != start) {
				out[end] |= value >>> (64 - offset);
			}
		}

		return out;
	}

	public static int get(long[] data, int bits, long index) {
		checkBits(bits);
		long mask = mask(bits);
		long bitIndex = index * bits;
		int start = (int) (bitIndex >>> 6);
		int end = (int) ((bitIndex + bits - 1) >>> 6);
		int offset = (int) (bitIndex & 63L);
		long value = data[start] >>> offset;

		if (end != start) {
			value |= data[end] << (64 - offset);
		}

		return (int) (value & mask);
	}

	private static long mask(int bits) {
		return bits == 64 ? -1L : (1L << bits) - 1L;
	}

	private static void checkBits(int bits) {
		if (bits < 1 || bits > 32) {
			throw new IllegalArgumentException("bits must be 1..32: " + bits);
		}
	}
}
