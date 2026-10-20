package com.redtact.live.preview.view;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.redtact.live.preview.mesh.GpuMesh;
import com.redtact.live.preview.mesh.SpecialBlocks;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
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
	private @Nullable GpuMesh mesh;
	private long version;
	private @Nullable ProjectionMatrixBuffer projectionBuffer;
	private @Nullable GlobalSettingsUniform globals;
	private @Nullable FogRenderer fog;
	private @Nullable GpuSampler sampler;
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
			sampler = RenderSystem.getDevice()
					.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
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

		boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(camera.projection(width, height, zZeroToOne)),
				camera.mode() == ViewCamera.Mode.TOP ? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE);
		previousGlobals = RenderSystem.getGlobalSettingsUniform();
		previousFog = RenderSystem.getShaderFog();
		long gameTime = mc.level == null ? 0 : mc.level.getGameTime();
		globals.update(width, height, 0.0, gameTime, 0.0F, 0, new Vec3(eye.x, eye.y, eye.z), false);
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
		ChunkSectionsToRender toRender = m.prepare(RenderSystem.getModelViewMatrixCopy(), atlas);

		for (ChunkSectionLayerGroup group : ChunkSectionLayerGroup.values()) {
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
					.createRenderPass(() -> "redtact-live preview " + group.label(), color, Optional.empty(), depth, OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				toRender.renderGroup(group, pass, sampler, atlas, false);
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

		TextureTarget target = new TextureTarget("redtact-live preview capture", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		RenderSystem.getDevice().createCommandEncoder()
				.clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0.0);

		if (beginFrame(width, height)) {
			try {
				drawMesh(target.getColorTextureView(), target.getDepthTextureView());
				SubmitNodeStorage storage = new SubmitNodeStorage();
				submitSpecials(storage);

				try (FeatureRenderDispatcher.PreparedFrame frame = Minecraft.getInstance().gameRenderer.featureRenderDispatcher().prepareFrame(storage);
						RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "redtact-live preview capture",
								target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
					RenderSystem.bindDefaultUniforms(pass);
					FeatureRenderDispatcher.renderAllFeatures(pass, frame);
				}
			} finally {
				endFrame();
			}
		}

		int bytes = target.getColorTexture().getFormat().blockSize();
		GpuBuffer buffer = RenderSystem.getDevice()
				.createBuffer(() -> "redtact-live preview capture", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) width * height * bytes);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), buffer, 0L, () -> {
			NativeImage image = new NativeImage(width, height, false);

			try (var view = buffer.map(true, false)) {
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						// 色の並び RGBA の 4 バイトを int で読むと ABGR (透明度はそのまま。ゲームのスクリーンショットは不透明に塗る)
						image.setPixelABGR(x, height - y - 1, straightAlpha(view.data().getInt((x + y * width) * bytes)));
					}
				}
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

		if (sampler != null) {
			sampler.close();
			sampler = null;
		}
	}
}
