package com.redtact.live.preview.view;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** 全画面のプレビュー (#102) を画面に描く指示 (バニラの picture-in-picture。{@link FullscreenPreviewRenderer} が描く)。 */
public record FullscreenPreviewRenderState(PreviewScene scene, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea,
		@Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
	public FullscreenPreviewRenderState(PreviewScene scene, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea) {
		this(scene, x0, y0, x1, y1, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
	}

	@Override
	public float scale() {
		return 1.0F;
	}
}
