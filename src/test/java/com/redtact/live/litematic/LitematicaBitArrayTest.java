package com.redtact.live.litematic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class LitematicaBitArrayTest {
	@Test
	void bitsForPaletteSize() {
		assertEquals(2, LitematicaBitArray.bitsForPaletteSize(1));
		assertEquals(2, LitematicaBitArray.bitsForPaletteSize(2));
		assertEquals(2, LitematicaBitArray.bitsForPaletteSize(4));
		assertEquals(3, LitematicaBitArray.bitsForPaletteSize(5));
		assertEquals(3, LitematicaBitArray.bitsForPaletteSize(8));
		assertEquals(4, LitematicaBitArray.bitsForPaletteSize(9));
		assertEquals(8, LitematicaBitArray.bitsForPaletteSize(256));
		assertEquals(9, LitematicaBitArray.bitsForPaletteSize(257));
		assertThrows(IllegalArgumentException.class, () -> LitematicaBitArray.bitsForPaletteSize(0));
	}

	@Test
	void longCountRoundsUp() {
		assertEquals(0, LitematicaBitArray.longCount(0, 2));
		assertEquals(1, LitematicaBitArray.longCount(32, 2));
		assertEquals(2, LitematicaBitArray.longCount(33, 2));
		assertEquals(2, LitematicaBitArray.longCount(24, 3)); // 72 bit
		assertEquals(2, LitematicaBitArray.longCount(25, 5)); // 125 bit
		assertEquals(3, LitematicaBitArray.longCount(26, 5)); // 130 bit
	}

	/** 3 bit で 22 番目 (bit 63..65) が long をまたぐ。Minecraft の BitStorage なら 21 個で切り上げるところ。 */
	@Test
	void entrySpanningTwoLongs() {
		int[] values = new int[24];
		values[21] = 0b101;
		long[] packed = LitematicaBitArray.pack(values, 3);
		assertEquals(2, packed.length);
		// 下位 1 bit が long[0] の最上位 bit (符号) に入り、残り 2 bit が long[1] の下位に入る
		assertEquals(Long.MIN_VALUE, packed[0]);
		assertEquals(0b10L, packed[1]);
		assertEquals(0b101, LitematicaBitArray.get(packed, 3, 21));
	}

	@Test
	void knownLayoutTwoBits() {
		// 2 bit: 値 1,2,3 を 0,1,2 番に -> 0b11_10_01
		long[] packed = LitematicaBitArray.pack(new int[] {1, 2, 3}, 2);
		assertArrayEquals(new long[] {0b111001L}, packed);
	}

	@Test
	void roundTripRandom() {
		Random random = new Random(42);

		for (int bits = 2; bits <= 13; bits++) {
			int n = 1000 + random.nextInt(500);
			int[] values = new int[n];

			for (int i = 0; i < n; i++) {
				values[i] = random.nextInt(1 << bits);
			}

			long[] packed = LitematicaBitArray.pack(values, bits);
			assertEquals((n * (long) bits + 63) / 64, packed.length);

			for (int i = 0; i < n; i++) {
				assertEquals(values[i], LitematicaBitArray.get(packed, bits, i), "bits=" + bits + " i=" + i);
			}
		}
	}

	@Test
	void rejectsValueTooWide() {
		assertThrows(IllegalArgumentException.class, () -> LitematicaBitArray.pack(new int[] {4}, 2));
		assertThrows(IllegalArgumentException.class, () -> LitematicaBitArray.pack(new int[] {-1}, 2));
	}

	@Test
	void everyBitPositionSpanning() {
		// 全オフセットで long 境界をまたぐ書き込みが隣を壊さないこと
		for (int bits = 2; bits <= 20; bits++) {
			int n = 64 * 3;
			int[] values = new int[n];
			int max = (1 << bits) - 1;

			for (int i = 0; i < n; i++) {
				values[i] = (i % 2 == 0) ? max : 0;
			}

			long[] packed = LitematicaBitArray.pack(values, bits);

			for (int i = 0; i < n; i++) {
				assertEquals(values[i], LitematicaBitArray.get(packed, bits, i));
			}

			assertTrue(packed.length * 64L >= (long) n * bits);
		}
	}
}
