package com.redtact.live.mixin;

import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;

/**
 * ブロックのモデルの「特別な描き方」(チェスト・ベルの本体・エンチャントテーブルの本など) を読む口 (#102)。
 * 全画面のプレビューは、ふつうのモデルの面は組み立てた形で描くので、特別な描き方の分だけを描き足す。
 */
@Mixin(BlockModelRenderState.class)
public interface BlockModelRenderStateAccessor {
	@Accessor("specialRenderer")
	@Nullable SpecialModelRenderer<?> redtactLive$specialRenderer();

	@Accessor("specialRendererTransformation")
	@Nullable Matrix4fc redtactLive$specialTransformation();
}
