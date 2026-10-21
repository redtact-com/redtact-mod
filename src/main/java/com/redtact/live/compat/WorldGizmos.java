package com.redtact.live.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
//? if >=1.21.11 {
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
//?} else {
/*import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.util.ARGB;
*///?}

/**
 * ワールドに線の箱と字を描く (範囲の枠・保存した範囲の名前。#112)。抽出の終わりのイベントの中で呼ぶ。1.21.11 からはゲームの Gizmos に
 * 積む (いつも手前に見える)。Gizmos の無い 1.21.10 は頼みをためておき、ワールドを描く途中 (デバッグの表示の前) で線と字を描く。
 * 1.21.10 の線はブロックに隠れる (深度を見ない線の描き方が無い)。字は壁ごしにも見える。
 */
public final class WorldGizmos {
	/** 字の既定の大きさ (Gizmos の TextGizmo.Style.DEFAULT_SCALE と同じ。字の 1 画素がこれ / 16 ブロック)。 */
	public static final float DEFAULT_TEXT_SCALE = 0.32F;

	//? if <1.21.11 {
	/*private record Box(AABB box, int color) {
	}

	private record Label(String text, Vec3 pos, int color, float scale) {
	}

	private static final List<Box> BOXES = new ArrayList<>();
	private static final List<Label> LABELS = new ArrayList<>();
	*///?}

	private WorldGizmos() {
	}

	/** 描く用意 (MOD の初期化で 1 度。1.21.10 だけ描く段に登録する)。 */
	public static void register() {
		//? if <1.21.11 {
		/*WorldRenderEvents.BEFORE_DEBUG_RENDER.register(WorldGizmos::draw);
		*///?}
	}

	/** 線の箱 (いつも手前。1.21.10 はブロックに隠れる)。width は線の太さ (1.21.10 は見ない)。 */
	public static void box(AABB box, int color, float width) {
		//? if >=1.21.11 {
		Gizmos.cuboid(box, GizmoStyle.stroke(color, width)).setAlwaysOnTop();
		//?} else
		/*BOXES.add(new Box(box, color));*/
	}

	/** ブロック 1 つ分の箱を inflate だけ広げて描く。 */
	public static void box(BlockPos pos, float inflate, int color, float width) {
		//? if >=1.21.11 {
		Gizmos.cuboid(pos, inflate, GizmoStyle.stroke(color, width)).setAlwaysOnTop();
		//?} else
		/*BOXES.add(new Box(new AABB(pos).inflate(inflate), color));*/
	}

	/** カメラの方を向く字 (真ん中ぞろえ・いつも手前)。scale は Gizmos と同じ (既定は {@link #DEFAULT_TEXT_SCALE})。 */
	public static void text(String text, Vec3 pos, int color, float scale) {
		//? if >=1.21.11 {
		Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(color).withScale(scale)).setAlwaysOnTop();
		//?} else
		/*LABELS.add(new Label(text, pos, color, scale));*/
	}

	//? if <1.21.11 {
	/*private static void draw(WorldRenderContext context) {
		if (BOXES.isEmpty() && LABELS.isEmpty()) {
			return;
		}

		Vec3 cam = context.worldState().cameraRenderState.pos;
		PoseStack pose = context.matrices();
		MultiBufferSource buffers = context.consumers();
		VertexConsumer lines = buffers.getBuffer(RenderType.lines());

		for (Box b : BOXES) {
			int c = b.color();
			ShapeRenderer.renderLineBox(pose.last(), lines, b.box().move(-cam.x, -cam.y, -cam.z), ARGB.redFloat(c), ARGB.greenFloat(c), ARGB.blueFloat(c),
					ARGB.alphaFloat(c));
		}

		for (Label l : LABELS) {
			// ゲームのデバッグ用の浮いた字 (カメラの方を向く・壁ごしにも見える)。Gizmos と同じく字の 1 画素が scale / 16 ブロック
			DebugRenderer.renderFloatingText(pose, buffers, l.text(), l.pos().x, l.pos().y, l.pos().z, l.color(), l.scale() / 16.0F, true, 0.0F, true);
		}

		BOXES.clear();
		LABELS.clear();
	}
	*///?}
}
