package com.redtact.live.state;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** 前の版からの差分 (protocol.md の CircuitDiff)。MC に依存しない。 */
public final class CircuitDiff {
	private final int baseVersion;
	private final List<String> palette;
	private final int[] changes;
	private final SortedMap<Integer, byte[]> blockEntities;
	private final int[] removedBlockEntities;

	public CircuitDiff(int baseVersion, List<String> palette, int[] changes, Map<Integer, byte[]> blockEntities, int[] removedBlockEntities) {
		if (changes.length % 2 != 0) {
			throw new IllegalArgumentException("changes must be pairs");
		}

		this.baseVersion = baseVersion;
		this.palette = List.copyOf(palette);
		this.changes = changes;
		this.blockEntities = Collections.unmodifiableSortedMap(new TreeMap<>(blockEntities));
		this.removedBlockEntities = removedBlockEntities;
	}

	public int baseVersion() {
		return baseVersion;
	}

	public List<String> palette() {
		return palette;
	}

	/** [添字, この差分の palette の添字, ...] */
	public int[] changes() {
		return changes;
	}

	public int changeCount() {
		return changes.length / 2;
	}

	public SortedMap<Integer, byte[]> blockEntities() {
		return blockEntities;
	}

	public int[] removedBlockEntities() {
		return removedBlockEntities;
	}

	public boolean isEmpty() {
		return changes.length == 0 && blockEntities.isEmpty() && removedBlockEntities.length == 0;
	}

	public StateJson.Diff toJson() {
		StateJson.Diff d = new StateJson.Diff();
		d.baseVersion = baseVersion;
		d.palette = palette;
		d.changes = changes;
		d.blockEntities = CircuitState.blockEntitiesJson(blockEntities);
		d.removedBlockEntities = removedBlockEntities;
		return d;
	}

	public static CircuitDiff fromJson(StateJson.Diff d) {
		Map<Integer, byte[]> bes = new HashMap<>();

		if (d.blockEntities != null) {
			for (StateJson.BlockEntity be : d.blockEntities) {
				bes.put(be.i, Base64.getDecoder().decode(be.nbt));
			}
		}

		return new CircuitDiff(d.baseVersion, d.palette == null ? List.of() : d.palette, d.changes == null ? new int[0] : d.changes,
				bes, d.removedBlockEntities == null ? new int[0] : d.removedBlockEntities);
	}
}
