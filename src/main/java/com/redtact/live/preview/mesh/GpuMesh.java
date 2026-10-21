package com.redtact.live.preview.mesh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.redtact.live.compat.GpuCompat;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
//? if >=26.2 {
import com.mojang.renderpearl.api.pipeline.IndexType;
import org.lwjgl.system.MemoryUtil;
//?} else {
/*import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat.IndexType;
*///?}

//? if >=26.3 {
import net.minecraft.client.renderer.DynamicGpuData;
//?} else {
/*import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
*///?}
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.world.level.block.state.BlockState;

/**
 * GPU に置いた回路の形 (#102)。{@link CircuitMesh} を描画のスレッドで送り、毎コマはゲームの地形と同じ描き方
 * ({@code ChunkSectionsToRender.DrawSeparate}) で描くだけにする (回しても組み立て直さない)。回路全体を原点 (0,0,0) の 1 つの区画として
 * 渡す。半透明の層は、カメラが動いたら遠い順に並べ直す ({@link #resort})。
 */
public final class GpuMesh implements AutoCloseable {
	private record Layer(GpuBuffer vertices, @Nullable GpuBuffer indices, @Nullable IndexType indexType, int indexCount) {
	}

	private final EnumMap<ChunkSectionLayer, Layer> layers;
	private final MeshData.@Nullable SortState translucentSort;
	/** 半透明の並べ直しに使う置き場 (並べ直すたびに書き直す)。 */
	//? if >=26.2 {
	private @Nullable ByteBuffer sortScratch;
	//?} else
	/*private @Nullable ByteBufferBuilder sortScratch;*/
	private final int maxSequentialIndices;
	private final int quads;
	private final int[] specialPositions;
	private final BlockState[] specialStates;

	private GpuMesh(EnumMap<ChunkSectionLayer, Layer> layers, MeshData.@Nullable SortState translucentSort, int maxSequentialIndices, int quads,
			int[] specialPositions, BlockState[] specialStates) {
		this.layers = layers;
		this.translucentSort = translucentSort;
		this.maxSequentialIndices = maxSequentialIndices;
		this.quads = quads;
		this.specialPositions = specialPositions;
		this.specialStates = specialStates;
	}

	/** 描画のスレッドで GPU に送る (mesh はこのあと閉じてよい)。 */
	public static GpuMesh upload(CircuitMesh mesh) {
		GpuDevice device = RenderSystem.getDevice();
		EnumMap<ChunkSectionLayer, Layer> layers = new EnumMap<>(ChunkSectionLayer.class);
		int maxSequential = 0;

		for (Map.Entry<ChunkSectionLayer, MeshData> e : mesh.layers().entrySet()) {
			ChunkSectionLayer layer = e.getKey();
			MeshData data = e.getValue();
			GpuBuffer vertices = device.createBuffer(() -> "redtact-live preview " + layer.label(), GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
			ByteBuffer indexData = data.indexBuffer();
			GpuBuffer indices = null;
			IndexType indexType = null;
			int indexCount = data.drawState().indexCount();

			if (indexData != null) {
				indices = device.createBuffer(() -> "redtact-live preview " + layer.label() + " indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
						indexData);
				indexType = data.drawState().indexType();
			} else {
				maxSequential = Math.max(maxSequential, indexCount);
			}

			layers.put(layer, new Layer(vertices, indices, indexType, indexCount));
		}

		return new GpuMesh(layers, mesh.translucentSort(), maxSequential, mesh.quads(), mesh.specialPositions(), mesh.specialStates());
	}

	/** 描くものが無いか (組み立てた面も、描き足すブロックも無い)。 */
	public boolean isEmpty() {
		return layers.isEmpty() && specialStates.length == 0;
	}

	/** 組み立てた面があるか。 */
	public boolean hasLayers() {
		return !layers.isEmpty();
	}

	/** 描き足すブロックの場所 (x, y, z の順に 3 つずつ。{@link SpecialBlocks})。 */
	public int[] specialPositions() {
		return specialPositions;
	}

	/** 描き足すブロックの状態 ({@link #specialPositions()} と同じ順)。 */
	public BlockState[] specialStates() {
		return specialStates;
	}

	/** ブロックの面の数 (液体は数えない)。 */
	public int quads() {
		return quads;
	}

	/** 半透明の層を (x, y, z) (回路の座標) から遠い順に並べ直す。描画のスレッドで。 */
	public boolean resort(float x, float y, float z) {
		Layer t = layers.get(ChunkSectionLayer.TRANSLUCENT);

		if (t == null || t.indices() == null || translucentSort == null) {
			return false;
		}

		int bytes = t.indexCount() * translucentSort.indexType().bytes;

		//? if >=26.2 {
		if (sortScratch == null || sortScratch.capacity() < bytes) {
			if (sortScratch != null) {
				MemoryUtil.memFree(sortScratch);
			}

			sortScratch = MemoryUtil.memAlloc(bytes);
		}

		sortScratch.clear();
		translucentSort.writeSortedIndexBuffer(sortScratch, VertexSorting.byDistance(x, y, z));
		sortScratch.flip();
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(t.indices().slice(), sortScratch);
		return true;
		//?} else {
		/*// 26.1 まで: 並べた番号はゲームの組み立て場所 (ByteBufferBuilder) に作られる (#112)
		if (sortScratch == null) {
			sortScratch = new ByteBufferBuilder(bytes);
		}

		try (ByteBufferBuilder.Result sorted = translucentSort.buildSortedIndexBuffer(sortScratch, VertexSorting.byDistance(x, y, z))) {
			if (sorted == null) {
				return false;
			}

			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(t.indices().slice(), sorted.byteBuffer());
			return true;
		}
		*///?}
	}

	/**
	 * 毎コマの描く指示 (ゲームの地形の {@code DrawSeparate})。modelView はカメラの向き (位置は Globals のカメラの位置で引く)。eye はカメラの位置
	 * (回路の座標。Globals にカメラの位置が無い 1.21.10 だけ使う)。
	 * 描画のスレッドで、そのコマのうちに使う。
	 */
	public ChunkSectionsToRender prepare(Matrix4fc modelView, GpuTextureView atlas, Vector3fc eye) {
		//? if >=26.3 {
		DynamicGpuData uniforms = RenderSystem.getDynamicUniforms();
		GpuBufferSlice terrain = uniforms.writeTerrainTransform(modelView, atlas.getWidth(0), atlas.getHeight(0));
		GpuBufferSlice[] sections = uniforms.writeChunkSections(new DynamicGpuData.ChunkSectionInfo(0, 0, 0, 1.0F));
		EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws = new EnumMap<>(ChunkSectionLayer.class);

		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			List<RenderPass.Draw<GpuBufferSlice[]>> list = new ArrayList<>(1);
			Layer l = layers.get(layer);

			if (l != null) {
				list.add(new RenderPass.Draw<>(0, l.vertices(), l.indices(), l.indexType(), 0, l.indexCount(), 0,
						(slices, uploader) -> uploader.setUniform("ChunkSection", slices[0])));
			}

			draws.put(layer, list);
		}
		//?} elif >=26.1 {
		/*// 26.1〜26.2: 向きと地形の絵の大きさは区画の情報に入れ、描く指示は層ごとに番号で束ねる (ゲームの LevelRenderer#prepareChunkRenders と同じ。#112)
		GpuBufferSlice[] sections = RenderSystem.getDynamicUniforms().writeChunkSections(
				new DynamicUniforms.ChunkSectionInfo(new Matrix4f(modelView), 0, 0, 0, 1.0F, atlas.getWidth(0), atlas.getHeight(0)));
		EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> draws = new EnumMap<>(ChunkSectionLayer.class);

		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> groups = new Int2ObjectOpenHashMap<>();
			Layer l = layers.get(layer);

			if (l != null) {
				List<RenderPass.Draw<GpuBufferSlice[]>> list = new ArrayList<>(1);
				list.add(new RenderPass.Draw<>(0, l.vertices(), l.indices(), l.indexType(), 0, l.indexCount(), 0,
						(slices, uploader) -> uploader.upload("ChunkSection", slices[0])));
				groups.put(0, list);
			}

			draws.put(layer, groups);
		}
		*///?} elif >=1.21.11 {
		/*// 1.21.11: 層ごとの描く指示は番号で束ねずに並べる。Draw の引数は 7 つ (#112)
		GpuBufferSlice[] sections = RenderSystem.getDynamicUniforms().writeChunkSections(
				new DynamicUniforms.ChunkSectionInfo(new Matrix4f(modelView), 0, 0, 0, 1.0F, atlas.getWidth(0), atlas.getHeight(0)));
		EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws = new EnumMap<>(ChunkSectionLayer.class);

		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			List<RenderPass.Draw<GpuBufferSlice[]>> list = new ArrayList<>(1);
			Layer l = layers.get(layer);

			if (l != null) {
				list.add(new RenderPass.Draw<>(0, l.vertices(), l.indices(), l.indexType(), 0, l.indexCount(),
						(slices, uploader) -> uploader.upload("ChunkSection", slices[0])));
			}

			draws.put(layer, list);
		}
		*///?} else {
		/*// 1.21.10: 形の位置 (原点) はカメラからの差にして、向きと一緒に DynamicTransforms で渡す (ゲームの LevelRenderer#prepareChunkRenders と同じ)
		GpuBufferSlice[] sections = RenderSystem.getDynamicUniforms().writeTransforms(new DynamicUniforms.Transform(new Matrix4f(modelView),
				new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(-eye.x(), -eye.y(), -eye.z()), new Matrix4f(), 1.0F));
		EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws = new EnumMap<>(ChunkSectionLayer.class);

		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			List<RenderPass.Draw<GpuBufferSlice[]>> list = new ArrayList<>(1);
			Layer l = layers.get(layer);

			if (l != null) {
				list.add(new RenderPass.Draw<>(0, l.vertices(), l.indices(), l.indexType(), 0, l.indexCount(),
						(slices, uploader) -> uploader.upload("DynamicTransforms", slices[0])));
			}

			draws.put(layer, list);
		}
		*///?}

		if (maxSequentialIndices > 0) {
			RenderSystem.getSequentialBuffer(GpuCompat.QUADS).getBuffer(maxSequentialIndices); // 足りる大きさにしておく
		}

		//? if >=26.3 {
		return new ChunkSectionsToRender.DrawSeparate(terrain, draws, maxSequentialIndices, sections);
		//?} elif >=1.21.11 {
		/*return new ChunkSectionsToRender(atlas, draws, maxSequentialIndices, sections);
		*///?} else {
		/*return new ChunkSectionsToRender(draws, maxSequentialIndices, sections);
		*///?}
	}

	@Override
	public void close() {
		for (Layer l : layers.values()) {
			l.vertices().close();

			if (l.indices() != null) {
				l.indices().close();
			}
		}

		layers.clear();

		if (sortScratch != null) {
			//? if >=26.2 {
			MemoryUtil.memFree(sortScratch);
			//?} else
			/*sortScratch.close();*/
			sortScratch = null;
		}
	}
}
