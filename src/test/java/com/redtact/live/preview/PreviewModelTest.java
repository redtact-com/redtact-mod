package com.redtact.live.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.redtact.live.state.CircuitState;
import org.junit.jupiter.api.Test;

class PreviewModelTest {
	private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:stone", "minecraft:redstone_wire", "minecraft:glass");

	private static PreviewModel build(int sx, int sy, int sz, int[] blocks) {
		CircuitState state = new CircuitState(sx, sy, sz, PALETTE, blocks, Map.of());
		// 石だけが隣を隠す (レッドストーンとガラスは透ける)
		return PreviewModel.build(state, p -> p == 0, p -> p == 1);
	}

	private static Set<String> drawn(PreviewModel m) {
		Set<String> out = new HashSet<>();

		for (int k = 0; k < m.drawCount(); k++) {
			out.add(m.x(k) + "," + m.y(k) + "," + m.z(k));
		}

		return out;
	}

	@Test
	void solidCubeHidesOnlyTheCenter() {
		int[] blocks = new int[27];
		Arrays.fill(blocks, 1);
		PreviewModel m = build(3, 3, 3, blocks);
		assertEquals(27, m.nonAir());
		assertEquals(26, m.visibleCount());
		assertFalse(drawn(m).contains("1,1,1"));
		assertFalse(m.tooLarge());
	}

	@Test
	void transparentNeighborKeepsTheCenterVisible() {
		int[] blocks = new int[27];
		Arrays.fill(blocks, 1);
		blocks[(1 * 3 + 1) * 3 + 2] = 3; // (2,1,1) はガラス
		PreviewModel m = build(3, 3, 3, blocks);
		assertEquals(27, m.visibleCount());
		assertTrue(drawn(m).contains("1,1,1"));
	}

	@Test
	void airIsSkippedAndIndexIsProtocolOrder() {
		// 2x1x2: (1,0,0)=レッドストーン, (0,0,1)=石。添字 = (y * sizeZ + z) * sizeX + x
		int[] blocks = {0, 2, 1, 0};
		PreviewModel m = build(2, 1, 2, blocks);
		assertEquals(2, m.nonAir());
		assertEquals(Set.of("1,0,0", "0,0,1"), drawn(m));

		for (int k = 0; k < m.drawCount(); k++) {
			int expected = m.x(k) == 1 ? 2 : 1;
			assertEquals(expected, m.paletteId(k));
		}
	}

	@Test
	void tooManyVisibleBlocksAreNotDrawn() {
		// 1 段の板は全部見える: MAX_VISIBLE を超えたら描かない (数は数える)
		int side = 210;
		int[] blocks = new int[side * side];
		Arrays.fill(blocks, 2);
		PreviewModel m = build(side, 1, side, blocks);
		assertTrue(m.tooLarge());
		assertEquals(side * side, m.visibleCount());
		assertEquals(0, m.drawCount());
	}

	@Test
	void fitScaleFitsTheRotatedBox() {
		PreviewModel m = build(4, 1, 2, new int[8]);
		// 真正面 (回さない・見下ろさない) なら幅 4・高さ 1 が 100x100 に収まる
		assertEquals(25.0f, m.fitScale(0, 0, 100, 100), 1e-4);
		// 真上から見下ろすと縦は奥行き (2)
		assertEquals(25.0f, m.fitScale(0, 90, 100, 100), 1e-3);
		assertEquals(50.0f, m.fitScale(0, 90, 400, 100), 1e-3);
		// 90 度回すと幅は z (2)
		assertEquals(50.0f, m.fitScale(90, 0, 100, 400), 1e-3);
		// 小さな窓でも 0.5 より小さくしない
		assertEquals(0.5f, m.fitScale(0, 0, 1, 1), 1e-6);
	}
}
