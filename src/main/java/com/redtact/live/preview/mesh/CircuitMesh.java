package com.redtact.live.preview.mesh;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 回路の形 (#102。全画面のプレビュー)。ゲームがチャンクの区画を組み立てるのと同じ描き方 ({@code SectionCompiler} と同じく
 * {@code ModelBlockRenderer}・{@code FluidRenderer}) で、回路全体を 1 つの区画として層 (不透明・切り抜き・半透明) ごとに
 * 組み立てる。隣のブロックに隠れる面は省かれ、角の陰 (AO) も付く。頂点の位置は float なので 16 マスを超えてよい。
 *
 * <p>組み立てはどのスレッドでもよい (ゲームも区画を別のスレッドで組み立てる)。できたものは描画のスレッドで
 * {@link GpuMesh#upload} し、そのあと {@link #close()} する。形に入らないブロック (チェストなど。{@link SpecialBlocks}) は
 * 場所だけを覚え、描くたびに描き足す。
 */
public final class CircuitMesh implements AutoCloseable {
	/** 層ごとの頂点の置き場の初めの大きさ (足りなければ伸びる)。 */
	private static final int INITIAL_BYTES = 256 * 1024;
	/** 面の数の上限 (1 面 112 バイト。超える回路は組み立てない。5,015 ブロックの回路で 35,813 面)。 */
	public static final int MAX_QUADS = 1_000_000;

	/** 面が {@link #MAX_QUADS} を超えた。 */
	public static final class TooManyQuadsException extends RuntimeException {
		public TooManyQuadsException() {
			super("more than " + MAX_QUADS + " quads", null, false, false);
		}
	}

	private final EnumMap<ChunkSectionLayer, MeshData> layers;
	private final MeshData.@Nullable SortState translucentSort;
	private final List<ByteBufferBuilder> buffers;
	private final int quads;
	/** 描き足すブロックの場所 (x, y, z の順に 3 つずつ) と状態。 */
	private final int[] specialPositions;
	private final BlockState[] specialStates;

	private CircuitMesh(EnumMap<ChunkSectionLayer, MeshData> layers, MeshData.@Nullable SortState translucentSort, List<ByteBufferBuilder> buffers,
			int quads, int[] specialPositions, BlockState[] specialStates) {
		this.layers = layers;
		this.translucentSort = translucentSort;
		this.buffers = buffers;
		this.quads = quads;
		this.specialPositions = specialPositions;
		this.specialStates = specialStates;
	}

	/**
	 * 組み立てる。yMin〜yMax (両端を含む) の高さだけを見せる (外は空気なので、切り口の面も描かれる)。
	 * 半透明の層は sortFrom (回路の座標) から遠い順に並べておく。specials のブロックは場所を覚える (描き足すもの)。
	 */
	public static CircuitMesh build(CircuitGrid grid, int yMin, int yMax, @Nullable Biome biome, float sortX, float sortY, float sortZ,
			Set<BlockState> specials) {
		Minecraft mc = Minecraft.getInstance();
		BlockStateModelSet blockModels = mc.getModelManager().getBlockStateModelSet();
		boolean ambientOcclusion = mc.options.ambientOcclusion().get();
		boolean cutoutLeaves = mc.options.cutoutLeaves().get();
		ModelBlockRenderer blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, mc.getBlockColors());
		FluidRenderer fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
		CircuitBlockGetter level = new CircuitBlockGetter(grid, yMin, yMax, biome);
		Map<ChunkSectionLayer, ByteBufferBuilder> allocators = new EnumMap<>(ChunkSectionLayer.class);
		Map<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
		Map<ChunkSectionLayer, OffsetVertexConsumer> fluidBuilders = new EnumMap<>(ChunkSectionLayer.class);
		int[] quadCount = {0};
		BlockQuadOutput output = (x, y, z, quad, instance) -> {
			count(quadCount);
			builder(builders, allocators, quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
		};
		BlockQuadOutput opaqueOutput = (x, y, z, quad, instance) -> {
			count(quadCount);
			builder(builders, allocators, ChunkSectionLayer.SOLID).putBlockBakedQuad(x, y, z, quad, instance);
		};
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		IntArrayList specialPositions = new IntArrayList();
		List<BlockState> specialStates = new ArrayList<>();
		BlockModelLighter.enableCaching();

		try {
			for (int y = Math.max(0, yMin); y <= Math.min(grid.sizeY() - 1, yMax); y++) {
				for (int z = 0; z < grid.sizeZ(); z++) {
					for (int x = 0; x < grid.sizeX(); x++) {
						pos.set(x, y, z);
						BlockState state = level.getBlockState(pos);

						if (state.isAir()) {
							continue;
						}

						if (specials.contains(state)) {
							specialPositions.add(x);
							specialPositions.add(y);
							specialPositions.add(z);
							specialStates.add(state);
						}

						FluidState fluid = state.getFluidState();

						if (!fluid.isEmpty()) {
							int fx = x;
							int fy = y;
							int fz = z;
							fluidRenderer.tesselate(level, pos, layer -> {
								OffsetVertexConsumer c = fluidBuilders.computeIfAbsent(layer,
										l -> new OffsetVertexConsumer(builder(builders, allocators, l)));
								c.offset(fx & ~15, fy & ~15, fz & ~15);
								return c;
							}, state, fluid);
						}

						if (state.getRenderShape() == RenderShape.MODEL) {
							blockRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, state) ? opaqueOutput : output, x, y, z, level, pos,
									state, blockModels.get(state), state.getSeed(pos));
						}
					}
				}
			}
		} catch (RuntimeException | Error e) {
			allocators.values().forEach(ByteBufferBuilder::close); // 途中でやめた (面が多すぎるなど): 置き場を返す
			throw e;
		} finally {
			BlockModelLighter.clearCache();
		}

		EnumMap<ChunkSectionLayer, MeshData> meshes = new EnumMap<>(ChunkSectionLayer.class);
		MeshData.SortState sort = null;

		for (Map.Entry<ChunkSectionLayer, BufferBuilder> e : builders.entrySet()) {
			MeshData mesh = e.getValue().build();

			if (mesh == null) {
				continue;
			}

			if (e.getKey() == ChunkSectionLayer.TRANSLUCENT) {
				sort = mesh.sortQuads(allocators.get(e.getKey()), VertexSorting.byDistance(sortX, sortY, sortZ));
			}

			meshes.put(e.getKey(), mesh);
		}

		return new CircuitMesh(meshes, sort, new ArrayList<>(allocators.values()), quadCount[0], specialPositions.toIntArray(),
				specialStates.toArray(BlockState[]::new));
	}

	private static void count(int[] quads) {
		if (++quads[0] > MAX_QUADS) {
			throw new TooManyQuadsException();
		}
	}

	private static BufferBuilder builder(Map<ChunkSectionLayer, BufferBuilder> builders, Map<ChunkSectionLayer, ByteBufferBuilder> allocators,
			ChunkSectionLayer layer) {
		return builders.computeIfAbsent(layer, l -> new BufferBuilder(allocators.computeIfAbsent(l, k -> new ByteBufferBuilder(INITIAL_BYTES)),
				PrimitiveTopology.QUADS, l.vertexFormat()));
	}

	Map<ChunkSectionLayer, MeshData> layers() {
		return layers;
	}

	MeshData.@Nullable SortState translucentSort() {
		return translucentSort;
	}

	/** ブロックの面の数 (液体は数えない)。 */
	public int quads() {
		return quads;
	}

	/** 描き足すブロックの場所 (x, y, z の順に 3 つずつ)。 */
	public int[] specialPositions() {
		return specialPositions;
	}

	/** 描き足すブロックの状態 ({@link #specialPositions()} と同じ順)。 */
	public BlockState[] specialStates() {
		return specialStates;
	}

	@Override
	public void close() {
		layers.values().forEach(MeshData::close);
		layers.clear();
		buffers.forEach(ByteBufferBuilder::close);
		buffers.clear();
	}
}
