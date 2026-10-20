package com.redtact.live.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.redtact.live.config.Configs;
import com.redtact.live.gui.RangeScreen;
import com.redtact.live.gui.SessionScreen;
import com.redtact.live.i18n.Tr;
import com.redtact.live.input.KeyDisplay;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.tool.SelectionTool;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;

/**
 * 保存済みの範囲の枠と名前 (#39)。羽根 (範囲指定の道具) を持っているあいだと、メニューの「セッション」「範囲」を開いているあいだだけ、
 * このワールド・ディメンションの範囲を薄い枠で描く。視線の先 (中クリックで選ぶもの) は金色、選んでいる範囲は銅色。
 * 枠は近い順に {@value #MAX_BOXES} 個まで、名前は {@value #LABEL_DISTANCE} ブロック以内 (視線の先・選んでいる範囲はいつも)。
 */
public final class SavedRangeRenderer {
	private static final int MAX_BOXES = 64;
	private static final double MAX_DISTANCE = 192;
	private static final double LABEL_DISTANCE = 64;
	private static final int FRAME_COLOR = 0xB0D8CCBC;
	private static final int HOVER_COLOR = 0xFFFFD24D;
	private static final int PICKED_COLOR = 0xFFE0A26A;

	private final SavedRanges ranges;
	private final Selection selection;

	public SavedRangeRenderer(SavedRanges ranges, Selection selection) {
		this.ranges = ranges;
		this.selection = selection;
	}

	public void register() {
		LevelExtractionEvents.END_EXTRACTION.register(this::extract);
	}

	/** いま枠を描くか (道具を持っている・メニューの「セッション」「範囲」を開いている)。 */
	public static boolean shown(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return false;
		}

		if (mc.gui.screen() instanceof SessionScreen || mc.gui.screen() instanceof RangeScreen) {
			return true;
		}

		return mc.gui.screen() == null && SelectionTool.enabled() && SelectionTool.isHolding(mc.player);
	}

	private void extract(LevelExtractionContext context) {
		ClientLevel level = context.level();
		Minecraft mc = Minecraft.getInstance();

		if (level == null || !shown(mc)) {
			return;
		}

		Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
		Vec3 eye = camera.getEyePosition(1.0F);
		String dim = level.dimension().identifier().toString();
		List<CircuitRecord> list = new ArrayList<>(ranges.visible(SelectionTool.worldKey(mc), dim));

		if (list.isEmpty()) {
			return;
		}

		CircuitRecord hovered = mc.gui.screen() == null ? SelectionTool.lookedAtSaved(mc) : null;
		String picked = ranges.pickedId();
		list.sort(Comparator.comparingDouble(r -> distance(r.box(), eye)));
		Box sel = dim.equals(selection.dimension()) ? selection.box() : null;

		try {
			int drawn = 0;

			for (CircuitRecord r : list) {
				boolean isHover = hovered != null && hovered.circuitId.equals(r.circuitId);
				boolean isPicked = r.circuitId.equals(picked);
				Box box = r.box();
				double d = distance(box, eye);

				if (!isHover && !isPicked && (drawn >= MAX_BOXES || d > MAX_DISTANCE)) {
					continue;
				}

				drawn++;
				int color = isHover ? HOVER_COLOR : isPicked ? PICKED_COLOR : FRAME_COLOR;

				// 選択と同じ箱は選択の枠 (赤) が描くので、名前だけ
				if (!box.equals(sel) || isHover) {
					AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
					Gizmos.cuboid(aabb.inflate(isHover ? 0.03 : 0.02), GizmoStyle.stroke(color, isHover ? 3.0F : isPicked ? 2.5F : 1.5F)).setAlwaysOnTop();
				}

				if (isHover || isPicked || d <= LABEL_DISTANCE) {
					Vec3 top = new Vec3((box.minX() + box.maxX() + 1) / 2.0, box.maxY() + 1.6, (box.minZ() + box.maxZ() + 1) / 2.0);
					String label = isPicked ? Tr.f("{0} (選択中)", r.name)
							: isHover ? Tr.f("{0}  {1} で選ぶ", r.name, KeyDisplay.combo(Configs.Hotkeys.TOOL_PICK.getKeybind().getKeys())) : r.name;
					// 遠くても読めるよう、距離に合わせて大きくする (近くは既定の大きさ)
					double far = Math.sqrt(top.distanceToSqr(eye));
					float scale = (float) Math.clamp(TextGizmo.Style.DEFAULT_SCALE * far / 6.0, TextGizmo.Style.DEFAULT_SCALE, 2.5);
					Gizmos.billboardText(label, top, TextGizmo.Style.forColorAndCentered(color).withScale(scale)).setAlwaysOnTop();
				}
			}
		} catch (IllegalStateException e) {
			// コレクタが無い場面 (想定外) では描かない
		}
	}

	/** 目から箱までの距離 (中にいれば 0)。 */
	private static double distance(Box box, Vec3 eye) {
		double dx = Math.max(0, Math.max(box.minX() - eye.x, eye.x - (box.maxX() + 1)));
		double dy = Math.max(0, Math.max(box.minY() - eye.y, eye.y - (box.maxY() + 1)));
		double dz = Math.max(0, Math.max(box.minZ() - eye.z, eye.z - (box.maxZ() + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
