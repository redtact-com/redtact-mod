package com.redtact.live.selection;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;

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
		LevelExtractionEvents.END_EXTRACTION.register(this::extract);
	}

	private void extract(LevelExtractionContext context) {
		ClientLevel level = context.level();

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
				Gizmos.cuboid(aabb.inflate(0.01), GizmoStyle.stroke(BOX_COLOR, 2.5F)).setAlwaysOnTop();
			}

			marker(selection.pos1(), POS1_COLOR);
			marker(selection.pos2(), POS2_COLOR);
		} catch (IllegalStateException e) {
			// コレクタが無い場面 (想定外) では描かない
		}
	}

	private static void marker(BlockPos pos, int color) {
		if (pos != null) {
			Gizmos.cuboid(pos, 0.02F, GizmoStyle.stroke(color, 2.0F)).setAlwaysOnTop();
		}
	}
}
