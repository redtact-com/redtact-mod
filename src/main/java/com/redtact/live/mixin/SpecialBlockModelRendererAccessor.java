package com.redtact.live.mixin;

// ゲームの「ブロックの特別な描き方」の表 (チェスト・頭・旗・ベッドなど) を読む口。1.21.x だけ (26.1 からは
// BlockModelRenderStateAccessor)。1.21.x のこれらのブロックは形が空で、見た目はすべて特別な描き方で出る。
// 1.21.x では mixin の一覧に入れる (build.gradle の version_mixins。#112)
//? if <26.1 {
/*import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.SpecialBlockModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.level.block.Block;

@Mixin(SpecialBlockModelRenderer.class)
public interface SpecialBlockModelRendererAccessor {
	@Accessor("renderers")
	Map<Block, SpecialModelRenderer<?>> redtactLive$renderers();
}
*///?}
