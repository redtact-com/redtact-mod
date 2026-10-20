package com.redtact.live.preview.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 全画面のプレビューのマス目 (#102): 添字の並び・空気と範囲の外・パレットの数で詰め方を変える。 */
class CircuitGridTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void indexesYThenZThenX() {
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		// 2 × 2 × 3: (y * 3 + z) * 2 + x
		int[] blocks = new int[12];
		blocks[(1 * 3 + 2) * 2 + 1] = 2; // (1, 1, 2) = glass
		blocks[0] = 1; // (0, 0, 0) = stone
		blocks[5] = 7; // パレットの外 → 空気
		CircuitGrid g = new CircuitGrid(2, 2, 3, new BlockState[] {null, stone, glass}, blocks);
		assertSame(stone, g.at(0, 0, 0));
		assertSame(glass, g.at(1, 1, 2));
		assertNull(g.at(1, 0, 0)); // 0 = 空気 (null)
		assertNull(g.at(1, 0, 2)); // パレットの外
		assertNull(g.at(-1, 0, 0));
		assertNull(g.at(0, 2, 0));
		assertNull(g.at(0, 0, 3));
		blocks[0] = 2; // 写して持つので、あとで変えても変わらない
		assertSame(stone, g.at(0, 0, 0));
	}

	@Test
	void packsByPaletteSize() {
		assertEquals(1, grid(254).bytesPerCell());
		assertEquals(2, grid(255).bytesPerCell());
		assertEquals(2, grid(65_534).bytesPerCell());
		assertEquals(4, grid(65_535).bytesPerCell());
		// 詰めても一番大きい番号を引ける
		CircuitGrid g = grid(300);
		assertSame(Blocks.GLASS.defaultBlockState(), g.at(0, 0, 0));
	}

	@Test
	void rejectsWrongLength() {
		assertThrows(IllegalArgumentException.class, () -> new CircuitGrid(2, 2, 2, new BlockState[1], new int[7]));
	}

	/** パレットが size 個で、1 マスだけ一番大きい番号 (ガラス) の回路。 */
	private static CircuitGrid grid(int size) {
		BlockState[] palette = new BlockState[size];
		palette[size - 1] = Blocks.GLASS.defaultBlockState();
		return new CircuitGrid(1, 1, 1, palette, new int[] {size - 1});
	}
}
