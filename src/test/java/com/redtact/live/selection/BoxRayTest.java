package com.redtact.live.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

class BoxRayTest {
	private static final double EPS = 1e-9;

	@Test
	void hitsTheNearFaceFromOutside() {
		// 箱 (0..4 のブロック) = ワールドの 0..5。x = -10 から +x へ
		Box box = new Box(0, 0, 0, 4, 4, 4);
		assertEquals(10.0, BoxRay.hitDistance(box, -10, 2.5, 2.5, 1, 0, 0, 100), EPS);
		// 反対向き (箱は後ろ) は当たらない
		assertEquals(-1, BoxRay.hitDistance(box, -10, 2.5, 2.5, -1, 0, 0, 100), EPS);
		// 横にそれている
		assertEquals(-1, BoxRay.hitDistance(box, -10, 7, 2.5, 1, 0, 0, 100), EPS);
		// 届く距離より遠い
		assertEquals(-1, BoxRay.hitDistance(box, -10, 2.5, 2.5, 1, 0, 0, 9.5), EPS);
	}

	@Test
	void insideHitsTheExitFace() {
		Box box = new Box(0, 0, 0, 9, 9, 9); // ワールドの 0..10
		assertEquals(8.0, BoxRay.hitDistance(box, 2, 5, 5, 1, 0, 0, 100), EPS);
		assertEquals(2.0, BoxRay.hitDistance(box, 2, 5, 5, -1, 0, 0, 100), EPS);
	}

	@Test
	void diagonalRay() {
		Box box = new Box(10, 10, 10, 10, 10, 10); // 1 ブロック (10..11)
		double s = 1 / Math.sqrt(3);
		assertEquals(Math.sqrt(300), BoxRay.hitDistance(box, 0, 0, 0, s, s, s, 100), 1e-6);
	}

	@Test
	void picksTheInnerBoxWhenStandingInsideTheOuter() {
		Box outer = new Box(0, 0, 0, 99, 20, 99);
		Box inner = new Box(40, 0, 0, 44, 5, 99);
		// 外側の箱の中 (x = 10) から +x を見る: 内側の箱の面 (x = 40) は外側の出口 (x = 100) より近い
		assertSame(inner, BoxRay.pick(List.of(outer, inner), b -> b, 10, 2, 50, 1, 0, 0, 200));
		// 内側の箱から外れた向き (-x) なら外側
		assertSame(outer, BoxRay.pick(List.of(outer, inner), b -> b, 10, 2, 50, -1, 0, 0, 200));
	}

	@Test
	void picksTheNearestAndBreaksTiesBySmallerVolume() {
		Box near = new Box(5, 0, 0, 6, 1, 1);
		Box far = new Box(20, 0, 0, 21, 1, 1);
		assertSame(near, BoxRay.pick(List.of(far, near), b -> b, 0, 0.5, 0.5, 1, 0, 0, 200));

		// 同じ面から始まる 2 つの箱: 小さい方
		Box big = new Box(5, 0, 0, 30, 10, 10);
		Box small = new Box(5, 0, 0, 6, 1, 1);
		assertSame(small, BoxRay.pick(List.of(big, small), b -> b, 0, 0.5, 0.5, 1, 0, 0, 200));
	}

	@Test
	void nothingHit() {
		Box box = new Box(5, 0, 0, 6, 1, 1);
		assertNull(BoxRay.pick(List.of(box), b -> b, 0, 50, 0, 1, 0, 0, 200));
		assertNull(BoxRay.pick(List.<Box>of(), b -> b, 0, 0, 0, 1, 0, 0, 200));
		java.util.ArrayList<Box> withNull = new java.util.ArrayList<>();
		withNull.add(null);
		assertNull(BoxRay.pick(withNull, b -> b, 0, 0, 0, 1, 0, 0, 200));
	}
}
