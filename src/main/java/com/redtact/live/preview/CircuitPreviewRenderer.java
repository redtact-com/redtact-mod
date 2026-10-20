package com.redtact.live.preview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 回路のプレビューを描く (#34)。ブロックはゲームのモデル (額縁の中身やエンダーマンの持つブロックと同じ描き方) で、
 * 見えるブロックだけを 1 つずつ置く。向きと大きさが変わらなければ前の絵を使い回す。
 */
public final class CircuitPreviewRenderer extends PictureInPictureRenderer<CircuitPreviewRenderState> {
	private static final BlockDisplayContext CONTEXT = BlockDisplayContext.create();
	private static final int FULL_BRIGHT = 15728880;
	private final BlockModelRenderState block = new BlockModelRenderState();
	private @Nullable BlockModelResolver resolver;
	private CircuitPreviewRenderState.@Nullable Key drawn;

	@Override
	public Class<CircuitPreviewRenderState> getRenderStateClass() {
		return CircuitPreviewRenderState.class;
	}

	@Override
	protected void renderToTexture(CircuitPreviewRenderState s, PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();

		if (resolver == null) {
			resolver = new BlockModelResolver(mc.getModelManager());
		}

		mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
		PreviewModel m = s.circuit().model();
		// 画面の座標は下向きが +y なので、ワールドと同じ上向きにする
		pose.scale(1.0F, -1.0F, -1.0F);
		pose.rotateDegrees(Axis.XP, s.pitch());
		pose.rotateDegrees(Axis.YP, s.yaw());
		pose.translate(-m.sizeX() / 2.0F, -m.sizeY() / 2.0F, -m.sizeZ() / 2.0F);

		for (int k = 0; k < m.drawCount(); k++) {
			BlockState state = s.circuit().state(m.paletteId(k));

			if (state == null) {
				continue;
			}

			resolver.update(block, state, CONTEXT);

			if (block.isEmpty()) {
				continue;
			}

			pose.pushPose();
			pose.translate(m.x(k), m.y(k), m.z(k));
			block.submit(pose, out, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}

		drawn = s.key();
	}

	@Override
	protected boolean textureIsReadyToBlit(CircuitPreviewRenderState s) {
		return s.key().equals(drawn);
	}

	@Override
	protected float getTranslateY(int height, int guiScale) {
		return height / 2.0F;
	}

	@Override
	protected String getTextureLabel() {
		return "redtact-live circuit preview";
	}
}
