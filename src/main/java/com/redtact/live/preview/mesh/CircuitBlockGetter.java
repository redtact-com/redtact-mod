package com.redtact.live.preview.mesh;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
//? if >=26.1 {
import net.minecraft.world.level.CardinalLighting;
//?}
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * 形を組み立てるときにゲームの描き方 ({@code ModelBlockRenderer}・{@code FluidRenderer}) へ渡す「ワールド」(#102)。
 * 回路のマス目を返し、外と見せない高さは空気 (隣の面を省くかどうかと影の付き方がワールドと同じになる)。
 * 明るさはいつも最大 (昼夜や置き場所によらず明るく見せる)、草や葉の色は平原。ブロックエンティティは持たない。
 */
final class CircuitBlockGetter implements BlockAndTintGetter {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	/** 平原が取れないとき (ワールドの外) の草・葉・水の色。 */
	private static final int FALLBACK_TINT = 0xFF91BD59;

	private final CircuitGrid grid;
	private final int yMin;
	private final int yMax;
	private final @Nullable Biome biome;
	private final LevelLightEngine light;

	CircuitBlockGetter(CircuitGrid grid, int yMin, int yMax, @Nullable Biome biome) {
		this.grid = grid;
		this.yMin = yMin;
		this.yMax = yMax;
		this.biome = biome;
		// getBrightness を上書きするので光のエンジンは使わない (読まれても 0 を返すだけの空のもの)
		this.light = new LevelLightEngine(new LightChunkGetter() {
			@Override
			public @Nullable LightChunk getChunkForLighting(int x, int z) {
				return null;
			}

			@Override
			public BlockGetter getLevel() {
				return CircuitBlockGetter.this;
			}
		}, false, false);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int y = pos.getY();

		if (y < yMin || y > yMax) {
			return AIR;
		}

		BlockState s = grid.at(pos.getX(), y, pos.getZ());
		return s == null ? AIR : s;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public int getHeight() {
		return grid.sizeY() + 2;
	}

	@Override
	public int getMinY() {
		return -1;
	}

	//? if >=26.1 {
	@Override
	public CardinalLighting cardinalLighting() {
		return CardinalLighting.DEFAULT;
	}
	//?} else {
	/*// 1.21.x は面の向きごとの明るさを getShade で返す (ゲームの地上と同じ値。#112)
	@Override
	public float getShade(net.minecraft.core.Direction direction, boolean shade) {
		if (!shade) {
			return 1.0F;
		}

		return switch (direction) {
			case DOWN -> 0.5F;
			case UP -> 1.0F;
			case NORTH, SOUTH -> 0.8F;
			case WEST, EAST -> 0.6F;
		};
	}
	*///?}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver resolver) {
		return biome == null ? FALLBACK_TINT : resolver.getColor(biome, pos.getX(), pos.getZ());
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return light;
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return 15;
	}

	@Override
	public int getRawBrightness(BlockPos pos, int darkening) {
		return 15;
	}

	@Override
	public boolean canSeeSky(BlockPos pos) {
		return true;
	}
}
