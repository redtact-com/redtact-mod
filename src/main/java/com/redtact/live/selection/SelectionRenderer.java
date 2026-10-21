package com.redtact.live.selection;

import com.redtact.live.compat.WorldGizmos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

//? if >=26.2 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
//?} elif >=26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
*///?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*///?}

/**
 * 選択範囲の枠線。抽出フェーズ (LevelExtractionEvents.END_EXTRACTION) でバニラの Gizmos に積む。
 * 1.21.9 以降の Gizmos はフレームごとのコレクタがあり、デバッグ表示と同じ経路で描かれる。
 */
public final class SelectionRenderer {
	private static final int BOX_COLOR = 0xFFFF5555;
	private static final int POS1_COLOR = 0xFF55FF55;
	private static final int POS2_COLOR = 0xFF5555FF;

	private final Selection selection;

	public SelectionRenderer(Selection selection) {
		this.selection = selection;
	}

	public void register() {
		// 同じ抽出の終わりのイベントが、26.1 の Fabric API は LevelRenderEvents、1.21.x は WorldRenderEvents にある (#112)
		//? if >=26.2 {
		LevelExtractionEvents.END_EXTRACTION.register(this::extract);
		//?} elif >=26.1 {
		/*LevelRenderEvents.END_EXTRACTION.register(this::extract);
		*///?} else {
		/*WorldRenderEvents.END_EXTRACTION.register(this::extract);
		*///?}
	}

	//? if >=26.1 {
	private void extract(LevelExtractionContext context) {
		ClientLevel level = context.level();
	//?} else {
	/*private void extract(WorldExtractionContext context) {
		ClientLevel level = context.world();
	*///?}

		if (level == null || selection.isEmpty()) {
			return;
		}

		String dim = level.dimension().identifier().toString();

		if (!dim.equals(selection.dimension())) {
			return;
		}

		try {
			Box box = selection.box();

			if (box != null) {
				AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
				WorldGizmos.box(aabb.inflate(0.01), BOX_COLOR, 2.5F);
			}

			marker(selection.pos1(), POS1_COLOR);
			marker(selection.pos2(), POS2_COLOR);
		} catch (IllegalStateException e) {
			// コレクタが無い場面 (想定外) では描かない
		}
	}

	private static void marker(BlockPos pos, int color) {
		if (pos != null) {
			WorldGizmos.box(pos, 0.02F, color, 2.0F);
		}
	}
}
