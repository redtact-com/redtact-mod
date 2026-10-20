package com.redtact.live.litematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 範囲内のブロック (パレット + 添字)。MC に依存しない。
 *
 * <p>添字 = {@code (y * sizeZ + z) * sizeX + x}。パレットの 0 番は {@code minecraft:air}。
 */
public final class RegionBlocks {
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final List<BlockStateEntry> palette;
	private final int[] blocks;

	public RegionBlocks(int sizeX, int sizeY, int sizeZ, List<BlockStateEntry> palette, int[] blocks) {
		if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
			throw new IllegalArgumentException("size must be positive: " + sizeX + "x" + sizeY + "x" + sizeZ);
		}

		if ((long) sizeX * sizeY * sizeZ != blocks.length) {
			throw new IllegalArgumentException("blocks length " + blocks.length + " != volume");
		}

		if (palette.isEmpty() || !palette.getFirst().equals(BlockStateEntry.AIR)) {
			throw new IllegalArgumentException("palette[0] must be minecraft:air");
		}

		for (int b : blocks) {
			if (b < 0 || b >= palette.size()) {
				throw new IllegalArgumentException("palette index out of range: " + b);
			}
		}

		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = List.copyOf(palette);
		this.blocks = blocks;
	}

	public int sizeX() {
		return sizeX;
	}

	public int sizeY() {
		return sizeY;
	}

	public int sizeZ() {
		return sizeZ;
	}

	public int volume() {
		return blocks.length;
	}

	public List<BlockStateEntry> palette() {
		return palette;
	}

	/** 直接参照を返す (書き換えない)。 */
	public int[] blocks() {
		return blocks;
	}

	public int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	public BlockStateEntry get(int x, int y, int z) {
		return palette.get(blocks[index(x, y, z)]);
	}

	/** 空気以外のブロック数。 */
	public int nonAirCount() {
		int n = 0;

		for (int b : blocks) {
			if (b != 0) {
				n++;
			}
		}

		return n;
	}

	/** テストや小さな範囲向けの組み立て。 */
	public static final class Builder {
		private final int sizeX;
		private final int sizeY;
		private final int sizeZ;
		private final List<BlockStateEntry> palette = new ArrayList<>();
		private final Map<BlockStateEntry, Integer> indexOf = new HashMap<>();
		private final int[] blocks;

		public Builder(int sizeX, int sizeY, int sizeZ) {
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.blocks = new int[Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)];
			paletteIndex(BlockStateEntry.AIR);
		}

		public int paletteIndex(BlockStateEntry entry) {
			if (entry.isAir()) {
				entry = BlockStateEntry.AIR;
			}

			Integer idx = indexOf.get(entry);

			if (idx == null) {
				idx = palette.size();
				palette.add(entry);
				indexOf.put(entry, idx);
			}

			return idx;
		}

		public Builder set(int x, int y, int z, BlockStateEntry entry) {
			if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
				throw new IndexOutOfBoundsException(x + "," + y + "," + z);
			}

			blocks[(y * sizeZ + z) * sizeX + x] = paletteIndex(entry);
			return this;
		}

		public RegionBlocks build() {
			return new RegionBlocks(sizeX, sizeY, sizeZ, palette, blocks);
		}
	}
}
