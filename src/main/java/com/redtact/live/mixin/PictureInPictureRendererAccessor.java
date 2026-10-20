package com.redtact.live.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;

/**
 * picture-in-picture の絵の置き場 (色と深度) を読む口 (#102)。全画面のプレビューは、バニラの「描くもの」の仕組みを使わずに
 * ゲームの地形と同じ描き方で直接描き込むため。
 */
@Mixin(PictureInPictureRenderer.class)
public interface PictureInPictureRendererAccessor {
	@Accessor("textureView")
	@Nullable GpuTextureView redtactLive$textureView();

	@Accessor("depthTextureView")
	@Nullable GpuTextureView redtactLive$depthTextureView();
}
