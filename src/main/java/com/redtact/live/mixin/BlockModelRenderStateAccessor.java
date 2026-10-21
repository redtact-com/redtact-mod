package com.redtact.live.mixin;

// ブロックのモデルの「特別な描き方」(チェスト・ベルの本体・エンチャントテーブルの本など) を読む口 (#102)。
// 全画面のプレビューは、ふつうのモデルの面は組み立てた形で描くので、特別な描き方の分だけを描き足す。
// BlockModelRenderState は 26.1 からなので、1.21.x では中身が無く、mixin の一覧からも外す (build.gradle の version_mixins。#112)
//? if >=26.1 {
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;

@Mixin(BlockModelRenderState.class)
public interface BlockModelRenderStateAccessor {
	@Accessor("specialRenderer")
	@Nullable SpecialModelRenderer<?> redtactLive$specialRenderer();

	@Accessor("specialRendererTransformation")
	@Nullable Matrix4fc redtactLive$specialTransformation();
}
//?}
