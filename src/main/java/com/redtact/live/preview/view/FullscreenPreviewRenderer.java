package com.redtact.live.preview.view;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.redtact.live.mixin.PictureInPictureRendererAccessor;
import org.jspecify.annotations.Nullable;

//? if <26.2 {
/*import net.minecraft.client.Minecraft;
*///?}
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if <26.2 {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * 全画面のプレビュー (#102) を描く。バニラの picture-in-picture の絵 (消してある色と深度) に、{@link PreviewScene} が
 * ゲームの地形と同じ描き方で形を描き込み、形に入らないブロック (チェストなど) をバニラの「描くもの」の仕組みに積む
 * (バニラがこのあと同じ絵に描く)。カメラも形も変わらなければ前の絵を使い回す。
 *
 * <p>積んだものを描くときも全画面のカメラの投影と向きが要るので、{@link PreviewScene#beginFrame} から
 * {@link PreviewScene#endFrame} (バニラが積んだものを描き終えてから呼ぶ {@link #blitTexture}) まで、ゲームの投影などを
 * 全画面のカメラのものにしておく。26.1 まではバニラが積んだものを描かないので、ゲームの置き場に積んで自分で描かせる (#112)。
 */
public final class FullscreenPreviewRenderer extends PictureInPictureRenderer<FullscreenPreviewRenderState> {
	private long drawnVersion = -1;
	/** 描き始めて、まだ戻していない場面。 */
	private @Nullable PreviewScene open;

	//? if <26.2 {
	/*public FullscreenPreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
		super(bufferSource);
	}

	*///?}
	@Override
	public Class<FullscreenPreviewRenderState> getRenderStateClass() {
		return FullscreenPreviewRenderState.class;
	}

	@Override
	//? if >=26.2 {
	protected void renderToTexture(FullscreenPreviewRenderState state, PoseStack pose, SubmitNodeCollector out) {
	//?} else {
	/*protected void renderToTexture(FullscreenPreviewRenderState state, PoseStack pose) {
		SubmitNodeCollector out = Minecraft.getInstance().gameRenderer.getFeatureRenderDispatcher().getSubmitNodeStorage();
	*///?}
		PictureInPictureRendererAccessor textures = (PictureInPictureRendererAccessor) (Object) this;
		GpuTextureView color = textures.redtactLive$textureView();
		GpuTextureView depth = textures.redtactLive$depthTextureView();
		PreviewScene scene = state.scene();
		drawnVersion = scene.version();

		if (open != null) {
			open.endFrame(); // 前のコマが途中で止まった (積んだものを描く前に失敗した)
			open = null;
		}

		if (color == null || depth == null || !scene.beginFrame(color.getWidth(0), color.getHeight(0))) {
			return;
		}

		open = scene;
		scene.drawMesh(color, depth);
		scene.submitSpecials(out);
		//? if <26.2 {
		/*Minecraft.getInstance().gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
		*///?}
	}

	@Override
	protected void blitTexture(FullscreenPreviewRenderState state, GuiRenderState guiRenderState) {
		// バニラは積んだものを描き終えてからここを呼ぶ (描き直さないコマも呼ぶ)
		if (open != null) {
			open.endFrame();
			open = null;
		}

		super.blitTexture(state, guiRenderState);
	}

	@Override
	protected boolean textureIsReadyToBlit(FullscreenPreviewRenderState state) {
		return state.scene().version() == drawnVersion;
	}

	@Override
	protected String getTextureLabel() {
		return "redtact-live fullscreen preview";
	}
}
