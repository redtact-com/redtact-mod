package com.redtact.live.preview;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/**
 * 画面の中に立体の回路を描く指示 (バニラの picture-in-picture。{@link CircuitPreviewRenderer} が描く)。
 *
 * @param yaw   左右の向き (度)
 * @param pitch 見下ろす角度 (度)
 * @param scale 1 ブロックの大きさ (GUI の 1 単位で)
 */
public record CircuitPreviewRenderState(PreviewCircuit circuit, float yaw, float pitch, int x0, int y0, int x1, int y1, float scale,
		@Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
	public CircuitPreviewRenderState(PreviewCircuit circuit, float yaw, float pitch, int x0, int y0, int x1, int y1, float scale,
			@Nullable ScreenRectangle scissorArea) {
		this(circuit, yaw, pitch, x0, y0, x1, y1, scale, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
	}

	/** 描いたものと同じか (同じなら描き直さずに前の絵を使う)。 */
	Key key() {
		return new Key(circuit, yaw, pitch, scale, x1 - x0, y1 - y0);
	}

	record Key(PreviewCircuit circuit, float yaw, float pitch, float scale, int width, int height) {
	}
}
