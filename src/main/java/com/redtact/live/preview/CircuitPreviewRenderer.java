package com.redtact.live.preview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if <26.2 {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
//? if >=26.1 {
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
//?} else
/*import com.redtact.live.preview.mesh.SpecialBlocks;*/
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 回路のプレビューを描く (#34)。ブロックはゲームのモデル (額縁の中身やエンダーマンの持つブロックと同じ描き方) で、
 * 見えるブロックだけを 1 つずつ置く。向きと大きさが変わらなければ前の絵を使い回す。
 */
public final class CircuitPreviewRenderer extends PictureInPictureRenderer<CircuitPreviewRenderState> {
	private static final int FULL_BRIGHT = 15728880;
	//? if >=26.1 {
	private static final BlockDisplayContext CONTEXT = BlockDisplayContext.create();
	private final BlockModelRenderState block = new BlockModelRenderState();
	private @Nullable BlockModelResolver resolver;
	//?}
	private CircuitPreviewRenderState.@Nullable Key drawn;

	//? if <26.2 {
	/*public CircuitPreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
		super(bufferSource);
	}

	*///?}
	@Override
	public Class<CircuitPreviewRenderState> getRenderStateClass() {
		return CircuitPreviewRenderState.class;
	}

	@Override
	//? if >=26.2 {
	protected void renderToTexture(CircuitPreviewRenderState s, PoseStack pose, SubmitNodeCollector out) {
		Minecraft mc = Minecraft.getInstance();
	//?} else {
	/*protected void renderToTexture(CircuitPreviewRenderState s, PoseStack pose) {
		Minecraft mc = Minecraft.getInstance();
		// 26.1 までは描くものの置き場を渡されないので、ゲームの置き場に積んで最後に自分で描かせる (バニラの GuiEntityRenderer と同じ。#112)
		SubmitNodeCollector out = mc.gameRenderer.getFeatureRenderDispatcher().getSubmitNodeStorage();
	*///?}

		//? if >=26.1 {
		if (resolver == null) {
			resolver = new BlockModelResolver(mc.getModelManager());
		}
		//?}

		mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
		PreviewModel m = s.circuit().model();
		// 画面の座標は下向きが +y なので、ワールドと同じ上向きにする
		pose.scale(1.0F, -1.0F, -1.0F);
		//? if >=26.3 {
		pose.rotateDegrees(Axis.XP, s.pitch());
		pose.rotateDegrees(Axis.YP, s.yaw());
		//?} else {
		/*pose.mulPose(Axis.XP.rotationDegrees(s.pitch()));
		pose.mulPose(Axis.YP.rotationDegrees(s.yaw()));
		*///?}
		pose.translate(-m.sizeX() / 2.0F, -m.sizeY() / 2.0F, -m.sizeZ() / 2.0F);

		for (int k = 0; k < m.drawCount(); k++) {
			BlockState state = s.circuit().state(m.paletteId(k));

			if (state == null) {
				continue;
			}

			//? if >=26.1 {
			resolver.update(block, state, CONTEXT);

			if (block.isEmpty()) {
				continue;
			}

			pose.pushPose();
			pose.translate(m.x(k), m.y(k), m.z(k));
			block.submit(pose, out, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
			//?} else {
			/*// 1.21.x: ゲームのブロックの描き方 (形と特別な描き方の両方)。チェスト・頭は向きを付ける
			if (state.isAir()) {
				continue;
			}

			pose.pushPose();
			pose.translate(m.x(k), m.y(k), m.z(k));
			SpecialBlocks.orient(state, pose);
			out.submitBlock(pose, state, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
			*///?}
		}

		drawn = s.key();
		//? if <26.2 {
		/*mc.gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
		*///?}
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
