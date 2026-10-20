package com.redtact.live.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 2 つの CircuitState の差分を作る・当てる。MC に依存しない。 */
public final class StateDiffer {
	private StateDiffer() {
	}

	/**
	 * prev (サーバが版 baseVersion として持っている状態) から cur への差分。
	 * 大きさが違うときは差分にできない (全体を送る)。
	 */
	public static CircuitDiff diff(CircuitState prev, int baseVersion, CircuitState cur) {
		if (!prev.sameSize(cur)) {
			throw new IllegalArgumentException("size changed");
		}

		Map<String, Integer> prevIndex = new HashMap<>();

		for (int p = 0; p < prev.palette().size(); p++) {
			prevIndex.put(prev.palette().get(p), p);
		}

		// cur のパレット添字 -> prev のパレット添字 (無ければ -1)
		int[] curToPrev = new int[cur.palette().size()];

		for (int p = 0; p < curToPrev.length; p++) {
			curToPrev[p] = prevIndex.getOrDefault(cur.palette().get(p), -1);
		}

		int[] curToDiff = new int[cur.palette().size()];
		Arrays.fill(curToDiff, -1);
		List<String> diffPalette = new ArrayList<>();
		int[] changes = new int[16];
		int n = 0;
		int[] prevBlocks = prev.blocks();
		int[] curBlocks = cur.blocks();

		for (int i = 0; i < curBlocks.length; i++) {
			int c = curBlocks[i];

			if (curToPrev[c] == prevBlocks[i]) {
				continue;
			}

			int d = curToDiff[c];

			if (d < 0) {
				d = diffPalette.size();
				diffPalette.add(cur.palette().get(c));
				curToDiff[c] = d;
			}

			if (n + 2 > changes.length) {
				changes = Arrays.copyOf(changes, changes.length * 2);
			}

			changes[n++] = i;
			changes[n++] = d;
		}

		Map<Integer, byte[]> updated = new TreeMap<>();

		for (Map.Entry<Integer, byte[]> e : cur.blockEntities().entrySet()) {
			byte[] before = prev.blockEntities().get(e.getKey());

			if (before == null || !Arrays.equals(before, e.getValue())) {
				updated.put(e.getKey(), e.getValue());
			}
		}

		int[] removed = prev.blockEntities().keySet().stream()
				.filter(i -> !cur.blockEntities().containsKey(i))
				.mapToInt(Integer::intValue)
				.toArray();

		return new CircuitDiff(baseVersion, diffPalette, Arrays.copyOf(changes, n), updated, removed);
	}

	/** 差分が大きすぎて全体を送る方がよいか (変わったブロックが体積の 1/4 を超える)。 */
	public static boolean tooLarge(CircuitDiff diff, int volume) {
		return diff.changeCount() > volume / 4;
	}

	/** prev に差分を当てた状態 (サーバ・閲覧者と同じ手順。確認用)。 */
	public static CircuitState apply(CircuitState prev, CircuitDiff diff) {
		List<String> palette = new ArrayList<>(prev.palette());
		Map<String, Integer> index = new HashMap<>();

		for (int p = 0; p < palette.size(); p++) {
			index.put(palette.get(p), p);
		}

		int[] diffToFull = new int[diff.palette().size()];

		for (int d = 0; d < diffToFull.length; d++) {
			String s = diff.palette().get(d);
			Integer p = index.get(s);

			if (p == null) {
				p = palette.size();
				palette.add(s);
				index.put(s, p);
			}

			diffToFull[d] = p;
		}

		int[] blocks = prev.blocks().clone();
		int[] changes = diff.changes();

		for (int k = 0; k < changes.length; k += 2) {
			blocks[changes[k]] = diffToFull[changes[k + 1]];
		}

		Map<Integer, byte[]> bes = new TreeMap<>(prev.blockEntities());

		for (int i : diff.removedBlockEntities()) {
			bes.remove(i);
		}

		bes.putAll(diff.blockEntities());
		return new CircuitState(prev.sizeX(), prev.sizeY(), prev.sizeZ(), palette, blocks, bes);
	}
}
