package com.redtact.live.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ToolItemIdTest {
	@Test
	void parsesAndNormalizes() {
		assertEquals(Optional.of("minecraft:feather"), ToolItemId.parse("minecraft:feather"));
		assertEquals(Optional.of("minecraft:feather"), ToolItemId.parse("feather"), "名前空間が無ければ minecraft:");
		assertEquals(Optional.of("minecraft:feather"), ToolItemId.parse("  Minecraft:Feather "), "前後の空白と大文字");
		assertEquals(Optional.of("somemod:tools/wand"), ToolItemId.parse("somemod:tools/wand"));
	}

	@Test
	void rejectsEmptyAndInvalid() {
		assertEquals(Optional.empty(), ToolItemId.parse(""));
		assertEquals(Optional.empty(), ToolItemId.parse("   "));
		assertEquals(Optional.empty(), ToolItemId.parse(null));
		assertEquals(Optional.empty(), ToolItemId.parse("minecraft:feather[count=2]"));
		assertEquals(Optional.empty(), ToolItemId.parse("a:b:c"));
		assertEquals(Optional.empty(), ToolItemId.parse("minecraft:"));
	}

	@Test
	void matchesHeldItem() {
		assertTrue(ToolItemId.matches("minecraft:feather", "minecraft:feather"));
		assertTrue(ToolItemId.matches("feather", "minecraft:feather"));
		assertFalse(ToolItemId.matches("minecraft:feather", "minecraft:stick"), "Litematica の道具 (棒) ではない");
		assertFalse(ToolItemId.matches("", "minecraft:air"), "空の設定は何にも当たらない (素手を道具にしない)");
		assertFalse(ToolItemId.matches("not valid!", "minecraft:feather"));
	}
}
