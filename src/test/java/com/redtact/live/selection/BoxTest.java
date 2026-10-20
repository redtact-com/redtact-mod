package com.redtact.live.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxTest {
	@Test
	void ofNormalizesCorners() {
		Box b = Box.of(5, 70, -3, 2, 64, 4);
		assertEquals(new Box(2, 64, -3, 5, 70, 4), b);
		assertEquals(4, b.sizeX());
		assertEquals(7, b.sizeY());
		assertEquals(8, b.sizeZ());
		assertEquals(4L * 7 * 8, b.volume());
		assertTrue(b.contains(2, 64, -3));
		assertTrue(b.contains(5, 70, 4));
	}

	@Test
	void limits() {
		assertNull(Box.of(0, 0, 0, 255, 0, 0).limitError());
		assertNotNull(Box.of(0, 0, 0, 256, 0, 0).limitError()); // 257
		assertNull(Box.of(0, 0, 0, 127, 127, 127).limitError()); // 128^3 ちょうど
		assertNotNull(Box.of(0, 0, 0, 128, 127, 127).limitError()); // 129*128*128
		assertNull(Box.of(0, 0, 0, 255, 63, 127).limitError()); // 256*64*128 = 128^3
	}
}
