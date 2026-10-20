package com.redtact.live.selection;

import com.redtact.live.i18n.Tr;

/** 両端を含む直方体 (MC に依存しない)。 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public static final int MAX_SIDE = 256;
	public static final long MAX_VOLUME = 128L * 128L * 128L;

	public Box {
		if (minX > maxX || minY > maxY || minZ > maxZ) {
			throw new IllegalArgumentException("min > max");
		}
	}

	public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
		return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
	}

	public int sizeX() {
		return maxX - minX + 1;
	}

	public int sizeY() {
		return maxY - minY + 1;
	}

	public int sizeZ() {
		return maxZ - minZ + 1;
	}

	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	public boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	/** 上限 (1 辺 256・体積 128^3) を超えていれば理由、収まっていれば null。 */
	public String limitError() {
		if (sizeX() > MAX_SIDE || sizeY() > MAX_SIDE || sizeZ() > MAX_SIDE) {
			return Tr.f("範囲が大きすぎます ({0}。1 辺は {1} まで)", sizeText(), MAX_SIDE);
		}

		if (volume() > MAX_VOLUME) {
			return Tr.f("範囲が大きすぎます ({0} = {1} ブロック。体積は 128^3 = {2} まで)", sizeText(), volume(), MAX_VOLUME);
		}

		return null;
	}

	public String sizeText() {
		return sizeX() + "x" + sizeY() + "x" + sizeZ();
	}

	public int[] size() {
		return new int[] {sizeX(), sizeY(), sizeZ()};
	}

	@Override
	public String toString() {
		return "(" + minX + ", " + minY + ", " + minZ + ") - (" + maxX + ", " + maxY + ", " + maxZ + ")";
	}
}
