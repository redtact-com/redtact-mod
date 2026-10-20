package com.redtact.live.litematic;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** パレットの 1 エントリ (ブロック名とプロパティ)。MC に依存しない。 */
public record BlockStateEntry(String name, SortedMap<String, String> properties) {
	public static final BlockStateEntry AIR = new BlockStateEntry("minecraft:air", Collections.emptySortedMap());

	public BlockStateEntry {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("name is empty");
		}

		properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties == null ? Map.of() : properties));
	}

	public static BlockStateEntry of(String name) {
		return new BlockStateEntry(name, Collections.emptySortedMap());
	}

	public static BlockStateEntry of(String name, Map<String, String> properties) {
		return new BlockStateEntry(name, new TreeMap<>(properties));
	}

	public boolean isAir() {
		return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
	}

	@Override
	public String toString() {
		if (properties.isEmpty()) {
			return name;
		}

		StringBuilder sb = new StringBuilder(name).append('[');
		boolean first = true;

		for (Map.Entry<String, String> e : properties.entrySet()) {
			if (!first) {
				sb.append(',');
			}

			sb.append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}

		return sb.append(']').toString();
	}
}
