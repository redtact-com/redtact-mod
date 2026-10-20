package com.redtact.live.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ページがマウスを閉じ込めている間の動きのため方 (#88)。 */
class PointerCaptureTest {
	@AfterEach
	void tearDown() {
		PointerCapture.end();
	}

	@Test
	void passesMovesThroughWhenNotCaptured() {
		assertFalse(PointerCapture.onMove(5, 5), "閉じ込めていないのに動きを取った (ゲームの画面へ届かない)");
		assertNull(PointerCapture.drain());
	}

	@Test
	void collectsRelativeMovesUntilDrained() {
		PointerCapture.begin();
		assertTrue(PointerCapture.active());
		assertTrue(PointerCapture.onMove(3, -2));
		assertTrue(PointerCapture.onMove(4.5, -1));
		assertArrayEquals(new double[] {7.5, -3}, PointerCapture.drain());
		assertNull(PointerCapture.drain(), "取り出したのに残っている");
	}

	@Test
	void endAndBeginDropCollectedMoves() {
		PointerCapture.begin();
		PointerCapture.onMove(10, 10);
		PointerCapture.end();
		assertFalse(PointerCapture.active());
		assertNull(PointerCapture.drain(), "やめたのにためた動きが残っている");

		PointerCapture.begin();
		assertNull(PointerCapture.drain(), "始め直したのに前の動きが残っている");
	}
}
