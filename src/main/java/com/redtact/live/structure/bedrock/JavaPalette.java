package com.redtact.live.structure.bedrock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redtact.live.litematic.BlockStateEntry;

/**
 * 直した Java 版のブロック状態に番号を振る (#70)。0 番は空気。同じ状態かは Go と同じ {@code 名前|k=v,…} (キー順) の字で見る
 * (mc-nbt-converter の makePaletteKey)。
 */
final class JavaPalette {
	private final List<BlockStateEntry> entries = new ArrayList<>(List.of(BlockStateEntry.AIR));
	private final Map<String, Integer> ids = new HashMap<>(Map.of(key(BlockStateEntry.AIR), 0));

	int id(String name, Map<String, String> props) {
		return id(BlockStateEntry.of(name, props));
	}

	int id(BlockStateEntry e) {
		return ids.computeIfAbsent(key(e), k -> {
			entries.add(e);
			return entries.size() - 1;
		});
	}

	BlockStateEntry get(int id) {
		return entries.get(id);
	}

	List<BlockStateEntry> entries() {
		return entries;
	}

	private static String key(BlockStateEntry e) {
		StringBuilder sb = new StringBuilder(e.name()).append('|');
		boolean first = true;

		for (Map.Entry<String, String> p : e.properties().entrySet()) {
			if (!first) {
				sb.append(',');
			}

			sb.append(p.getKey()).append('=').append(p.getValue());
			first = false;
		}

		return sb.toString();
	}
}
