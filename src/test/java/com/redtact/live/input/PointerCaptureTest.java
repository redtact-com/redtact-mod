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
		PointerCapture.begin(0, 0);
		assertTrue(PointerCapture.active());
		assertTrue(PointerCapture.onMove(3, -2));
		assertTrue(PointerCapture.onMove(4.5, -1));
		assertArrayEquals(new double[] {7.5, -3}, PointerCapture.drain());
		assertNull(PointerCapture.drain(), "取り出したのに残っている");
	}

	@Test
	void endAndBeginDropCollectedMoves() {
		PointerCapture.begin(0, 0);
		PointerCapture.onMove(10, 10);
		PointerCapture.end();
		assertFalse(PointerCapture.active());
		assertNull(PointerCapture.drain(), "やめたのにためた動きが残っている");

		PointerCapture.begin(0, 0);
		assertNull(PointerCapture.drain(), "始め直したのに前の動きが残っている");
	}

	@Test
	void turnsGlfwPositionsIntoMovesFromWhereItWasCaptured() {
		assertFalse(PointerCapture.onMoveTo(500, 300), "閉じ込めていないのに動きを取った");

		// 26.2 まで (GLFW) は位置だけが来る。閉じ込めたところから仮想の位置が進み続けるので、前の位置との差をためる (#112)
		PointerCapture.begin(100, 50);
		assertTrue(PointerCapture.onMoveTo(140, 34));
		assertTrue(PointerCapture.onMoveTo(180, 18));
		assertTrue(PointerCapture.onMoveTo(220, 2));
		assertArrayEquals(new double[] {120, -48}, PointerCapture.drain());
		assertArrayEquals(new double[] {220, 2}, PointerCapture.lastCursor());

		// 始め直すと、前の閉じ込めの位置ではなく新しく閉じ込めたところからの差になる
		PointerCapture.end();
		PointerCapture.begin(400, 200);
		assertTrue(PointerCapture.onMoveTo(390, 205));
		assertArrayEquals(new double[] {-10, 5}, PointerCapture.drain());
	}
}
