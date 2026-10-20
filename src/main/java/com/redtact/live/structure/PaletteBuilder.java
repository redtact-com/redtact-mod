package com.redtact.live.structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redtact.live.litematic.BlockStateEntry;
import org.jspecify.annotations.Nullable;

/** 読んだブロック状態に番号を振る (#70)。0 番は空気。 */
final class PaletteBuilder {
	private final List<BlockStateEntry> entries = new ArrayList<>(List.of(BlockStateEntry.AIR));
	private final Map<BlockStateEntry, Integer> ids = new HashMap<>(Map.of(BlockStateEntry.AIR, 0));

	/** その状態の番号 (null と空気は 0)。 */
	int id(@Nullable BlockStateEntry e) {
		if (e == null || e.isAir()) {
			return 0;
		}

		Integer id = ids.get(e);

		if (id == null) {
			id = entries.size();
			entries.add(e);
			ids.put(e, id);
		}

		return id;
	}

	List<BlockStateEntry> entries() {
		return entries;
	}
}
