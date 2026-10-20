package com.redtact.live.selection;

import net.minecraft.core.BlockPos;

/** 範囲指定の状態。クライアントスレッドだけで触る。 */
public final class Selection {
	private BlockPos pos1;
	private BlockPos pos2;
	/** pos を指定したディメンション (例: minecraft:overworld)。 */
	private String dimension;

	public BlockPos pos1() {
		return pos1;
	}

	public BlockPos pos2() {
		return pos2;
	}

	public String dimension() {
		return dimension;
	}

	public void setPos1(BlockPos pos, String dim) {
		switchDimension(dim);
		pos1 = pos.immutable();
	}

	public void setPos2(BlockPos pos, String dim) {
		switchDimension(dim);
		pos2 = pos.immutable();
	}

	public void set(Box box, String dim) {
		dimension = dim;
		pos1 = new BlockPos(box.minX(), box.minY(), box.minZ());
		pos2 = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
	}

	public void clear() {
		pos1 = null;
		pos2 = null;
		dimension = null;
	}

	public boolean isEmpty() {
		return pos1 == null && pos2 == null;
	}

	/** 両端が決まっていれば直方体。 */
	public Box box() {
		if (pos1 == null || pos2 == null) {
			return null;
		}

		return Box.of(pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX(), pos2.getY(), pos2.getZ());
	}

	private void switchDimension(String dim) {
		if (dimension != null && !dimension.equals(dim)) {
			// 別のディメンションの点とは組ませない
			pos1 = null;
			pos2 = null;
		}

		dimension = dim;
	}
}
