package com.redtact.live.preview.view;

import java.util.OptionalDouble;
import java.util.function.Consumer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
//? if >=1.21.11 {
import com.mojang.renderpearl.api.textures.GpuSampler;
//?}
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.redtact.live.compat.GpuCompat;
import com.redtact.live.preview.mesh.GpuMesh;
import com.redtact.live.preview.mesh.SpecialBlocks;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
//? if <26.3 {
/*import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
*///?}

/**
 * 全画面のプレビュー (#102) の描くもの: GPU に置いた回路の形 ({@link GpuMesh}) とカメラ ({@link ViewCamera})。
 * 形はゲームの地形と同じ描き方 (Globals にカメラの位置・TerrainUniform に向き・ChunkSection に原点) で、霧なし・明るさ最大。
 * 形に入らないブロック (チェストなど) は {@link SpecialBlocks} で描き足す (ゲームの「描くもの」の仕組みに積む)。
 *
 * <p>1 コマは {@link #beginFrame} → {@link #drawMesh} → {@link #submitSpecials} (積んだものを描く) → {@link #endFrame}。
 * beginFrame から endFrame までは、ゲームの投影・Globals・霧・向きの行列をこのカメラのものにしておく (endFrame で戻す)。
 * 描画のスレッドだけで使う。
 */
public final class PreviewScene implements AutoCloseable {
	/** 半透明を並べ直すのは、カメラがこれ (ブロック) より動いたとき。 */
	private static final float RESORT_DISTANCE = 0.5F;

	private final ViewCamera camera = new ViewCamera();
	//? if <26.1 {
	/*private final EyeCamera eyeCamera = new EyeCamera();

	// 位置だけを持つカメラ (1.21.x の Globals はカメラの位置を Camera から読む。#112)
	private static final class EyeCamera extends net.minecraft.client.Camera {
		void at(Vec3 position) {
			setPosition(position);
		}
	}
	*///?}
	private @Nullable GpuMesh mesh;
	private long version;
	private @Nullable ProjectionMatrixBuffer projectionBuffer;
	private @Nullable GlobalSettingsUniform globals;
	private @Nullable FogRenderer fog;
	// 絵の拡大縮小のしかた (1.21.10 までは絵の側に付いていて、作らない)
	//? if >=1.21.11 {
	private @Nullable GpuSampler sampler;
	//?}
	private @Nullable SpecialBlocks specials;
	private final Vector3f sortedFrom = new Vector3f(Float.NaN, Float.NaN, Float.NaN);
	// beginFrame から endFrame までの間 (戻すもの)
	private boolean inFrame;
	private @Nullable GpuBuffer previousGlobals;
	private @Nullable GpuBufferSlice previousFog;
	private final Vector3f frameEye = new Vector3f();
	private long frameStarted;
	// 描いた回数と時間 (CPU で命令を積む時間。テストと計測用)
	private int draws;
	private long drawNanos;
	private long drawNanosMax;
	private int resorts;
	private long resortNanos;

	public ViewCamera camera() {
		return camera;
	}

	/** 描き直しが要るたびに増える (同じなら前の絵を使う)。 */
	public long version() {
		return version;
	}

	/** カメラを動かした・形を変えたときに呼ぶ。 */
	public void changed() {
		version++;
	}

	public @Nullable GpuMesh mesh() {
		return mesh;
	}

	/** 形を差し替える (前の形は閉じる)。 */
	public void setMesh(@Nullable GpuMesh next) {
		if (mesh != null && mesh != next) {
			mesh.close();
		}

		mesh = next;
		sortedFrom.set(Float.NaN, Float.NaN, Float.NaN);
		changed();
	}

	/** 形に入らないブロックの見分け (形を組み立てる前に、描き足すものを選ぶ)。 */
	public SpecialBlocks specials() {
		if (specials == null) {
			specials = new SpecialBlocks();
		}

		return specials;
	}

	/**
	 * 描き始める: ゲームの投影・Globals・霧・向きの行列を、このカメラ (width × height の画素) のものにする。描くものが無ければ
	 * 何もせず false。true なら必ず {@link #endFrame} を呼ぶ。
	 */
	public boolean beginFrame(int width, int height) {
		GpuMesh m = mesh;

		if (m == null || m.isEmpty() || inFrame) {
			return false;
		}

		frameStarted = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();

		if (projectionBuffer == null) {
			projectionBuffer = new ProjectionMatrixBuffer("redtact-live preview");
			globals = new GlobalSettingsUniform();
			fog = new FogRenderer();
			//? if >=1.21.11 {
			sampler = RenderSystem.getDevice()
					.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
			//?}
		}

		Vector3f eye = camera.eye();
		frameEye.set(eye);

		if (Float.isNaN(sortedFrom.x) || eye.distance(sortedFrom) > RESORT_DISTANCE) {
			long r0 = System.nanoTime();

			if (m.resort(eye.x, eye.y, eye.z)) {
				resorts++;
				resortNanos += System.nanoTime() - r0;
			}

			sortedFrom.set(eye);
		}

		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(camera.projection(width, height, GpuCompat.zZeroToOne(), GpuCompat.REVERSED_Z)),
				camera.mode() == ViewCamera.Mode.TOP ? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE);
		previousGlobals = RenderSystem.getGlobalSettingsUniform();
		previousFog = RenderSystem.getShaderFog();
		long gameTime = mc.level == null ? 0 : mc.level.getGameTime();
		//? if >=26.3 {
		globals.update(width, height, 0.0, gameTime, 0.0F, 0, new Vec3(eye.x, eye.y, eye.z), false);
		//?} elif >=26.1 {
		/*globals.update(width, height, 0.0, gameTime, DeltaTracker.ZERO, 0, new Vec3(eye.x, eye.y, eye.z), false);
		*///?} elif >=1.21.11 {
		/*eyeCamera.at(new Vec3(eye.x, eye.y, eye.z));
		globals.update(width, height, 0.0, gameTime, DeltaTracker.ZERO, 0, eyeCamera, false);
		*///?} else {
		/*// 1.21.10: Globals にカメラの位置は無い (形の位置はカメラからの差で GpuMesh#prepare が DynamicTransforms に入れる)
		globals.update(width, height, 0.0, gameTime, DeltaTracker.ZERO, 0);
		*///?}
		RenderSystem.setShaderFog(fog.getBuffer(FogRenderer.FogMode.NONE));
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.set(camera.view());
		mc.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
		inFrame = true;
		return true;
	}

	/** 組み立てた形を color・depth に描き足す (beginFrame のあと。どちらも消してあること)。 */
	public void drawMesh(GpuTextureView color, GpuTextureView depth) {
		GpuMesh m = mesh;

		if (!inFrame || m == null || !m.hasLayers()) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		GpuTextureView atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		ChunkSectionsToRender toRender = m.prepare(RenderSystem.getModelViewMatrixCopy(), atlas, frameEye);

		//? if <26.3 {
		/*// 26.2 まで: ChunkSectionsToRender#renderGroup はゲームの画面に描くので、同じ中身を color・depth へ描くパスで行う (#112)
		RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(GpuCompat.QUADS);
		GpuBuffer defaultIndexBuffer = toRender.maxIndicesRequired() == 0 ? null : autoIndices.getBuffer(toRender.maxIndicesRequired());
		var defaultIndexType = toRender.maxIndicesRequired() == 0 ? null : autoIndices.type(); // 型の場所が版で違う (26.1 までは VertexFormat の中)
		*///?}

		for (ChunkSectionLayerGroup group : ChunkSectionLayerGroup.values()) {
			try (RenderPass pass = GpuCompat.openPass(() -> "redtact-live preview " + group.label(), color, depth)) {
				RenderSystem.bindDefaultUniforms(pass);
				//? if >=26.3 {
				toRender.renderGroup(group, pass, sampler, atlas, false);
				//?} elif >=26.1 {
				/*pass.bindTexture("Sampler0", atlas, sampler);
				pass.bindTexture("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

				for (ChunkSectionLayer layer : group.layers()) {
					pass.setPipeline(layer.pipeline());

					for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : toRender.drawGroupsPerLayer().get(layer).values()) {
						if (!draws.isEmpty()) {
							pass.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, List.of("ChunkSection"), toRender.chunkSectionInfos());
						}
					}
				}
				*///?} elif >=1.21.11 {
				/*// 1.21.11: 層ごとの描く指示は番号で束ねていない (ゲームの ChunkSectionsToRender#renderGroup と同じ)
				pass.bindTexture("Sampler0", atlas, sampler);
				pass.bindTexture("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

				for (ChunkSectionLayer layer : group.layers()) {
					List<RenderPass.Draw<GpuBufferSlice[]>> draws = toRender.drawsPerLayer().get(layer);

					if (!draws.isEmpty()) {
						pass.setPipeline(layer.pipeline());
						pass.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, List.of("ChunkSection"), toRender.chunkSectionInfos());
					}
				}
				*///?} else {
				/*// 1.21.10: 絵の拡大縮小のしかたは絵の側に付いている (層ごとの絵)。区画の情報は DynamicTransforms (ゲームと同じ)
				pass.bindSampler("Sampler2", mc.gameRenderer.lightmap());

				for (ChunkSectionLayer layer : group.layers()) {
					List<RenderPass.Draw<GpuBufferSlice[]>> draws = toRender.drawsPerLayer().get(layer);

					if (!draws.isEmpty()) {
						pass.setPipeline(layer.pipeline());
						pass.bindSampler("Sampler0", layer.textureView());
						pass.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, List.of("DynamicTransforms"), toRender.dynamicTransforms());
					}
				}
				*///?}
			}
		}
	}

	/** 形に入らないブロック (チェストなど) を out に積む (beginFrame のあと。積んだものは endFrame の前に描くこと)。 */
	public void submitSpecials(SubmitNodeCollector out) {
		GpuMesh m = mesh;

		if (!inFrame || m == null) {
			return;
		}

		int[] p = m.specialPositions();
		BlockState[] states = m.specialStates();
		SpecialBlocks s = specials();
		PoseStack pose = new PoseStack();

		for (int i = 0; i < states.length; i++) {
			pose.pushPose();
			// 向きは行列 (modelView) に入っているので、位置だけカメラからの差にする (ゲームのブロックエンティティと同じ)
			pose.translate(p[3 * i] - frameEye.x, p[3 * i + 1] - frameEye.y, p[3 * i + 2] - frameEye.z);
			s.submit(states[i], pose, out);
			pose.popPose();
		}
	}

	/** 描き終える: ゲームの投影・Globals・霧・向きの行列を戻す。 */
	public void endFrame() {
		if (!inFrame) {
			return;
		}

		inFrame = false;
		RenderSystem.getModelViewStack().popMatrix();
		RenderSystem.setShaderFog(previousFog);

		if (previousGlobals != null) {
			RenderSystem.setGlobalSettingsUniform(previousGlobals);
		}

		previousGlobals = null;
		previousFog = null;
		RenderSystem.restoreProjectionMatrix();
		long t = System.nanoTime() - frameStarted;
		draws++;
		drawNanos += t;
		drawNanosMax = Math.max(drawNanosMax, t);
	}

	/**
	 * 描いた回数・合計と最大の時間・半透明を並べ直した回数と合計の時間 (ナノ秒) を {draws, total, max, resorts, resortTotal} で返して
	 * 0 に戻す (テストと計測用)。
	 */
	public long[] takeDrawStats() {
		long[] out = {draws, drawNanos, drawNanosMax, resorts, resortNanos};
		draws = 0;
		drawNanos = 0;
		drawNanosMax = 0;
		resorts = 0;
		resortNanos = 0;
		return out;
	}

	/**
	 * いまのカメラで背景なし (透明) の width × height の絵を撮り、できたら done に渡す (GPU から読み終えたとき。描画のスレッドから
	 * 呼ばれる)。done は絵を閉じる。描くものが無ければ何もしない。チェストなども描き足す (画面と同じ)。
	 */
	public void capture(int width, int height, Consumer<NativeImage> done) {
		if (mesh == null || mesh.isEmpty()) {
			return;
		}

		TextureTarget target = GpuCompat.captureTarget("redtact-live preview capture", width, height);
		GpuCompat.clearTransparent(target);

		if (beginFrame(width, height)) {
			try {
				drawMesh(target.getColorTextureView(), target.getDepthTextureView());

				//? if >=26.3 {
				SubmitNodeStorage storage = new SubmitNodeStorage();
				submitSpecials(storage);

				try (FeatureRenderDispatcher.PreparedFrame frame = Minecraft.getInstance().gameRenderer.featureRenderDispatcher().prepareFrame(storage);
						RenderPass pass = GpuCompat.openPass(() -> "redtact-live preview capture", target.getColorTextureView(), target.getDepthTextureView())) {
					RenderSystem.bindDefaultUniforms(pass);
					FeatureRenderDispatcher.renderAllFeatures(pass, frame);
				}
				//?} elif >=26.2 {
				/*// 26.2: チェストなどは描き先の上書きに描く (ゲームの LevelRenderer と同じ。#112)
				SubmitNodeStorage storage = new SubmitNodeStorage();
				submitSpecials(storage);
				RenderSystem.outputColorTextureOverride = target.getColorTextureView();
				RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();

				try {
					Minecraft.getInstance().gameRenderer.featureRenderDispatcher().renderAllFeatures(storage);
				} finally {
					RenderSystem.outputColorTextureOverride = null;
					RenderSystem.outputDepthTextureOverride = null;
				}
				*///?} else {
				/*// 26.1 まで: 描くものはゲームの置き場に積んで描かせる (描いたら空になる)。描き先は上書きで
				FeatureRenderDispatcher features = Minecraft.getInstance().gameRenderer.getFeatureRenderDispatcher();
				submitSpecials(features.getSubmitNodeStorage());
				RenderSystem.outputColorTextureOverride = target.getColorTextureView();
				RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();

				try {
					features.renderAllFeatures();
				} finally {
					RenderSystem.outputColorTextureOverride = null;
					RenderSystem.outputDepthTextureOverride = null;
				}
				*///?}
			} finally {
				endFrame();
			}
		}

		int bytes = GpuCompat.colorBytes(target);
		GpuBuffer buffer = RenderSystem.getDevice()
				.createBuffer(() -> "redtact-live preview capture", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, width * height * bytes); // 1.21.10 までは int
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), buffer, 0, () -> {
			NativeImage image = new NativeImage(width, height, false);

			try {
				GpuCompat.read(buffer, data -> {
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							// 色の並び RGBA の 4 バイトを int で読むと ABGR (透明度はそのまま。ゲームのスクリーンショットは不透明に塗る)
							image.setPixelABGR(x, height - y - 1, straightAlpha(data.getInt((x + y * width) * bytes)));
						}
					}
				});
			} finally {
				buffer.close();
				target.destroyBuffers();
			}

			done.accept(image);
		}, 0);
	}

	/**
	 * 透明の上に半透明 (水・色付きガラス) を重ねた画素は、色に透明度が掛かったまま (premultiplied) なので、PNG のふつうの形
	 * (色と透明度が別) に戻す。ABGR。
	 */
	static int straightAlpha(int abgr) {
		int a = abgr >>> 24;

		if (a == 0 || a == 255) {
			return a == 0 ? 0 : abgr;
		}

		int b = Math.min(255, ((abgr >> 16) & 0xFF) * 255 / a);
		int g = Math.min(255, ((abgr >> 8) & 0xFF) * 255 / a);
		int r = Math.min(255, (abgr & 0xFF) * 255 / a);
		return a << 24 | b << 16 | g << 8 | r;
	}

	@Override
	public void close() {
		endFrame();
		setMesh(null);

		if (projectionBuffer != null) {
			projectionBuffer.close();
			projectionBuffer = null;
		}

		if (globals != null) {
			globals.close();
			globals = null;
		}

		if (fog != null) {
			fog.close();
			fog = null;
		}

		//? if >=1.21.11 {
		if (sampler != null) {
			sampler.close();
			sampler = null;
		}
		//?}
	}
}
