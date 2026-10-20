package com.redtact.live.selection;

import java.util.List;
import java.util.function.Function;

/**
 * 視線 (点と向き) と範囲の箱の当たり (MC に依存しない)。保存済みの範囲を羽根の中クリックで選ぶのに使う (#39)。
 *
 * <p>箱は両端のブロックを含むので、ワールドの座標では {@code [min, max + 1]}。当たる距離は、視線が最初に当たる面までの距離
 * (箱の外にいれば入る面、中にいれば出ていく面)。こうすると、大きい箱の中に立って内側の小さい箱を見たときは小さい箱が近くなり、
 * 内側の箱を選べる。
 */
public final class BoxRay {
	private static final double EPS = 1e-9;

	private BoxRay() {
	}

	/**
	 * 視線が最初に当たる面までの距離。向き (dx, dy, dz) は長さ 1 にしておく。当たらない・max より遠いなら -1。
	 */
	public static double hitDistance(Box box, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
		double near = Double.NEGATIVE_INFINITY;
		double far = Double.POSITIVE_INFINITY;
		double[] o = {ox, oy, oz};
		double[] d = {dx, dy, dz};
		double[] lo = {box.minX(), box.minY(), box.minZ()};
		double[] hi = {box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0};

		for (int i = 0; i < 3; i++) {
			if (Math.abs(d[i]) < EPS) {
				// この軸には進まない: 箱の厚みの外なら当たらない
				if (o[i] < lo[i] || o[i] > hi[i]) {
					return -1;
				}

				continue;
			}

			double t1 = (lo[i] - o[i]) / d[i];
			double t2 = (hi[i] - o[i]) / d[i];
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
		}

		if (far < Math.max(near, 0)) {
			return -1; // 外れた・後ろにある
		}

		double t = near >= 0 ? near : far; // 中にいれば出ていく面
		return t <= max ? t : -1;
	}

	/**
	 * 視線がいちばん近くで当たる箱。同じ距離なら体積の小さい方。どれにも当たらなければ null。
	 */
	public static <T> T pick(List<T> items, Function<T, Box> boxOf, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
		T best = null;
		double bestT = Double.POSITIVE_INFINITY;
		long bestVolume = Long.MAX_VALUE;

		for (T item : items) {
			Box box = boxOf.apply(item);

			if (box == null) {
				continue;
			}

			double t = hitDistance(box, ox, oy, oz, dx, dy, dz, max);

			if (t < 0) {
				continue;
			}

			long volume = box.volume();

			if (t < bestT - EPS || (Math.abs(t - bestT) <= EPS && volume < bestVolume)) {
				best = item;
				bestT = t;
				bestVolume = volume;
			}
		}

		return best;
	}
}
