package com.redtact.live.compat;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//? if >=26.2 {
import java.util.Optional;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.joml.Vector4f;
//?} else {
/*import java.util.OptionalInt;
import com.mojang.blaze3d.vertex.VertexFormat;
*///?}

/**
 * マイクラの版で場所や形が違う描画の呼び出し (#112)。呼ぶ側は版を気にせずここを使う。
 *
 * <p>26.3 は GPU の層が {@code com.mojang.renderpearl.api}、26.2 は {@code com.mojang.blaze3d} (import の名前は build.gradle の
 * replacements で戻す)。26.1 まで (1.21.x も) は OpenGL だけで、深度も逆向きでない (近いほど小さい・奥を 1 で消す)。
 */
public final class GpuCompat {
	/** 深度が逆向き (近いほど大きい・奥を 0 で消す) か。ゲームは 26.2 から逆向き。 */
	//? if >=26.2 {
	public static final boolean REVERSED_Z = true;
	//?} else
	/*public static final boolean REVERSED_Z = false;*/

	/** 四角形 (4 頂点ずつ) の並べ方。型を書かずに BufferBuilder や番号の作り置きに渡せるように (26.1 までは VertexFormat.Mode)。 */
	//? if >=26.2 {
	public static final PrimitiveTopology QUADS = PrimitiveTopology.QUADS;
	//?} else
	/*public static final VertexFormat.Mode QUADS = VertexFormat.Mode.QUADS;*/

	private GpuCompat() {
	}

	/** GPU の深度の範囲が 0〜1 か (-1〜1 でなく)。 */
	public static boolean zZeroToOne() {
		//? if >=26.2 {
		return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		//?} elif >=26.1 {
		/*return RenderSystem.getDevice().isZZeroToOne();
		*///?} else {
		/*return false; // 1.21.x は OpenGL だけで、深度は -1〜1
		*///?}
	}

	/** color・depth に描くパスを開く (消さずに描き足す)。 */
	public static RenderPass openPass(Supplier<String> label, GpuTextureView color, GpuTextureView depth) {
		//? if >=26.2 {
		return RenderSystem.getDevice().createCommandEncoder().createRenderPass(label, color, Optional.empty(), depth, OptionalDouble.empty());
		//?} else
		/*return RenderSystem.getDevice().createCommandEncoder().createRenderPass(label, color, OptionalInt.empty(), depth, OptionalDouble.empty());*/
	}

	/** 撮影の描き先 (色は RGBA の 8 ビットずつ・深度つき)。 */
	public static TextureTarget captureTarget(String label, int width, int height) {
		//? if >=26.3 {
		return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		//?} elif >=26.2 {
		/*return new TextureTarget(label, width, height, true, GpuFormat.RGBA8_UNORM);
		*///?} else {
		/*return new TextureTarget(label, width, height, true);
		*///?}
	}

	/** 描き先を透明と、いちばん奥の深度で消す。 */
	public static void clearTransparent(TextureTarget target) {
		//? if >=26.2 {
		RenderSystem.getDevice().createCommandEncoder()
				.clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0.0);
		//?} else {
		/*RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
		*///?}
	}

	/** 描き先の色の 1 画素のバイト数。 */
	public static int colorBytes(TextureTarget target) {
		//? if >=26.2 {
		return target.getColorTexture().getFormat().blockSize();
		//?} else
		/*return target.getColorTexture().getFormat().pixelSize();*/
	}

	/** GPU から読むバッファ (USAGE_MAP_READ) の中身を reader に渡す。読み終えたら写しを閉じる (バッファは閉じない)。 */
	public static void read(GpuBuffer buffer, Consumer<ByteBuffer> reader) {
		//? if >=26.2 {
		try (var view = buffer.map(true, false)) {
			reader.accept(view.data());
		}
		//?} else {
		/*try (var view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)) {
			reader.accept(view.data());
		}
		*///?}
	}
}
