package com.redtact.live.preview;

import java.util.List;
import java.util.function.IntPredicate;

import com.redtact.live.state.CircuitState;

/**
 * プレビューに描く回路 (#34): 範囲の大きさと、外から見えるブロックの位置とパレットの添字。
 * 6 面とも「光を通さない四角いブロック」に囲まれたブロックは描いても見えないので除く (中の詰まった回路でも軽く描ける)。
 * ゲームのクラスに触れないので単体テストで確かめられる。
 */
public final class PreviewModel {
	/** これより多く見えるブロックがある回路は描かない (描くたびに全部のブロックを組み立て直し、1 万ブロックで約 40 ms かかるため。#93)。 */
	public static final int MAX_VISIBLE = 40_000;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final List<String> palette;
	private final int nonAir;
	/** 見えるブロックの添字 i = (y * sizeZ + z) * sizeX + x。多すぎるときは空。 */
	private final int[] positions;
	private final int[] paletteIds;
	private final int visible;

	private PreviewModel(int sizeX, int sizeY, int sizeZ, List<String> palette, int nonAir, int[] positions, int[] paletteIds, int visible) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = palette;
		this.nonAir = nonAir;
		this.positions = positions;
		this.paletteIds = paletteIds;
		this.visible = visible;
	}

	/**
	 * @param air       そのパレットの添字が空気か (描かない)
	 * @param occluding そのパレットの添字が、隣を隠す四角いブロックか (6 面とも隠れたブロックは描かない)
	 */
	public static PreviewModel build(CircuitState state, IntPredicate air, IntPredicate occluding) {
		int sx = state.sizeX();
		int sy = state.sizeY();
		int sz = state.sizeZ();
		int[] blocks = state.blocks();
		int nonAir = 0;
		int visible = 0;
		int[] positions = new int[Math.min(blocks.length, MAX_VISIBLE)];
		int[] ids = new int[positions.length];

		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					int i = (y * sz + z) * sx + x;
					int p = blocks[i];

					if (air.test(p)) {
						continue;
					}

					nonAir++;

					if (hidden(blocks, sx, sy, sz, x, y, z, occluding)) {
						continue;
					}

					if (visible < positions.length) {
						positions[visible] = i;
						ids[visible] = p;
					}

					visible++;
				}
			}
		}

		if (visible > MAX_VISIBLE) {
			return new PreviewModel(sx, sy, sz, state.palette(), nonAir, new int[0], new int[0], visible);
		}

		return new PreviewModel(sx, sy, sz, state.palette(), nonAir, trim(positions, visible), trim(ids, visible), visible);
	}

	/** 6 方向とも範囲の中にあり、隣を隠すブロックなら true (範囲の外は見えるものとする)。 */
	private static boolean hidden(int[] blocks, int sx, int sy, int sz, int x, int y, int z, IntPredicate occluding) {
		if (x == 0 || y == 0 || z == 0 || x == sx - 1 || y == sy - 1 || z == sz - 1) {
			return false;
		}

		return occluding.test(blocks[(y * sz + z) * sx + x - 1]) && occluding.test(blocks[(y * sz + z) * sx + x + 1])
				&& occluding.test(blocks[((y - 1) * sz + z) * sx + x]) && occluding.test(blocks[((y + 1) * sz + z) * sx + x])
				&& occluding.test(blocks[(y * sz + z - 1) * sx + x]) && occluding.test(blocks[(y * sz + z + 1) * sx + x]);
	}

	private static int[] trim(int[] a, int n) {
		if (a.length == n) {
			return a;
		}

		int[] out = new int[n];
		System.arraycopy(a, 0, out, 0, n);
		return out;
	}

	/**
	 * 回した範囲の箱が width × height の窓に収まる 1 ブロックの大きさ。左右は x と z の幅の見かけの和、
	 * 上下は高さと奥行きの見かけの和 (見下ろす角度の分だけ奥行きが縦に出る)。
	 */
	public float fitScale(float yawDeg, float pitchDeg, int width, int height) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double cos = Math.abs(Math.cos(yaw));
		double sin = Math.abs(Math.sin(yaw));
		double across = sizeX * cos + sizeZ * sin;
		double depth = sizeX * sin + sizeZ * cos;
		double tall = sizeY * Math.abs(Math.cos(pitch)) + depth * Math.abs(Math.sin(pitch));
		double s = Math.min(width / Math.max(1.0, across), height / Math.max(1.0, tall));
		return (float) Math.max(0.5, s);
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

	public List<String> palette() {
		return palette;
	}

	/** 空気以外のブロックの数。 */
	public int nonAir() {
		return nonAir;
	}

	/** 外から見えるブロックの数 (多すぎて描かないときも数える)。 */
	public int visibleCount() {
		return visible;
	}

	/** 見えるブロックが多すぎて描かない。 */
	public boolean tooLarge() {
		return visible > MAX_VISIBLE;
	}

	/** 描くブロックの数 (多すぎるときは 0)。 */
	public int drawCount() {
		return positions.length;
	}

	public int position(int k) {
		return positions[k];
	}

	public int paletteId(int k) {
		return paletteIds[k];
	}

	public int x(int k) {
		return positions[k] % sizeX;
	}

	public int y(int k) {
		return positions[k] / (sizeX * sizeZ);
	}

	public int z(int k) {
		return positions[k] / sizeX % sizeZ;
	}
}
