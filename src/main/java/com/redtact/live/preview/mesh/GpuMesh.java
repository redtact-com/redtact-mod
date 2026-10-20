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
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.renderer.DynamicGpuData;
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
	private @Nullable ByteBuffer sortScratch;
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
	}

	/**
	 * 毎コマの描く指示 (ゲームの地形の {@code DrawSeparate})。modelView はカメラの向き (位置は Globals のカメラの位置で引く)。
	 * 描画のスレッドで、そのコマのうちに使う。
	 */
	public ChunkSectionsToRender prepare(Matrix4fc modelView, GpuTextureView atlas) {
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

		if (maxSequentialIndices > 0) {
			RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(maxSequentialIndices); // 足りる大きさにしておく
		}

		return new ChunkSectionsToRender.DrawSeparate(terrain, draws, maxSequentialIndices, sections);
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
			MemoryUtil.memFree(sortScratch);
			sortScratch = null;
		}
	}
}
