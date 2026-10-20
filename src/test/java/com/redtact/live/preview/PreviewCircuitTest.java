package com.redtact.live.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;

/** パレットの文字列をゲームのブロックにする (#70。知らない状態は除いて描き、知らないブロックだけ描かない)。 */
public class PreviewCircuitTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap(); // ブロックの登録 (BuiltInRegistries) を使う
	}

	@Test
	void parsesKnownStates() {
		BlockState r = PreviewCircuit.parse("minecraft:repeater[delay=3,facing=east,locked=false,powered=true]");
		assertNotNull(r);
		assertEquals(Blocks.REPEATER, r.getBlock());
		assertEquals(3, r.getValue(RepeaterBlock.DELAY));
	}

	/** 統合版から直したもの・別の版のファイルの、知らない状態やおかしな値は除いて既定の値で描く。 */
	@Test
	void dropsUnknownPropertiesButKeepsTheBlock() {
		BlockState water = PreviewCircuit.parse("minecraft:water[liquid_depth=0]");
		assertNotNull(water);
		assertEquals(Blocks.WATER, water.getBlock());
		BlockState r = PreviewCircuit.parse("minecraft:repeater[delay=9,facing=west,shiny=yes]");
		assertNotNull(r);
		assertEquals(Blocks.REPEATER, r.getBlock());
		assertEquals(1, r.getValue(RepeaterBlock.DELAY)); // 範囲の外の値は既定
	}

	@Test
	void unknownBlocksAreNull() {
		assertNull(PreviewCircuit.parse("minecraft:no_such_block"));
		assertNull(PreviewCircuit.parse("minecraft:Stone")); // 大文字は名前にならない (統合版の古い名前)
		assertNull(PreviewCircuit.parse("minecraft:foo:bar"));
		assertNull(PreviewCircuit.parse("somemod:widget[a=b]"));
	}
}
