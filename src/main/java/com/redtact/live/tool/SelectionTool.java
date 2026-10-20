package com.redtact.live.tool;

import java.lang.ref.WeakReference;
import java.util.List;

import com.redtact.live.RedtactLive;
import com.redtact.live.config.Configs;
import com.redtact.live.i18n.Tr;
import com.redtact.live.selection.BoxRay;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 範囲指定の道具 (既定は羽根)。持って左クリックで角 1 (始点)、右クリックで角 2 (終点)。
 *
 * <p>Litematica の道具と同じく、マウスの入力の段階 (ホットキー MOUSE_LEFT / MOUSE_RIGHT。{@link com.redtact.live.input.HotkeyManager}) で受け、
 * 道具を持っているときだけクリックを打ち消す (ブロックの破壊・設置・操作・アイテムの使用は起きず、
 * サーバにも送らない)。視線の先は届く距離 (既定 200) までのブロック。何も当たらなければ角は変えない。
 * スニーク中は当たった面の隣のブロック (Litematica と同じ)。
 *
 * <p>中クリックで、視線の先の保存済みの範囲を選ぶ (#39。Litematica の道具の中クリックと同じ)。
 * 道具を持っているあいだは中クリックを打ち消す (ピックブロックはしない)。
 */
public final class SelectionTool {
	/** ワールドの識別子の覚え (毎フレーム求めないよう、ワールドが変わったときだけ求め直す)。抜けたワールドを掴まないよう弱参照。 */
	private static WeakReference<ClientLevel> keyLevel = new WeakReference<>(null);
	private static String keyCache = "unknown";

	private SelectionTool() {
	}

	public static boolean enabled() {
		return Configs.Generic.TOOL_ENABLED.getBooleanValue();
	}

	/** 道具を持っているか (メインハンドかオフハンド。Litematica と同じ)。 */
	public static boolean isHolding(@Nullable Player player) {
		if (player == null) {
			return false;
		}

		String configured = Configs.Generic.TOOL_ITEM.getStringValue();
		return isTool(configured, player.getItemInHand(InteractionHand.MAIN_HAND)) || isTool(configured, player.getItemInHand(InteractionHand.OFF_HAND));
	}

	private static boolean isTool(String configured, ItemStack stack) {
		return !stack.isEmpty() && ToolItemId.matches(configured, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	/** いまのクリックを道具として扱うか (打ち消すか)。 */
	public static boolean active(Minecraft mc) {
		return enabled() && mc.player != null && mc.level != null && mc.gui.screen() == null && isHolding(mc.player);
	}

	/**
	 * 道具のクリック。クライアントスレッドから呼ぶ。
	 *
	 * @return 道具として扱った (クリックを打ち消す) なら true
	 */
	public static boolean onClick(int corner) {
		Minecraft mc = Minecraft.getInstance();

		if (!active(mc)) {
			return false;
		}

		int range = Configs.Generic.TOOL_RANGE.getIntegerValue();
		BlockPos pos = target(mc, range);

		if (pos == null) {
			Messages.overlay(Tr.n("視線の先にブロックがありません ({0} ブロックまで)", range));
			return true;
		}

		RedtactLive.get().setPos(corner, pos, mc.level.dimension().identifier().toString());
		return true;
	}

	/**
	 * 道具の中クリック: 視線の先の保存済みの範囲を選ぶ。クライアントスレッドから呼ぶ。
	 *
	 * @return 道具として扱った (クリックを打ち消す) なら true
	 */
	public static boolean onPick() {
		Minecraft mc = Minecraft.getInstance();

		if (!active(mc)) {
			return false;
		}

		CircuitRecord r = lookedAtSaved(mc);

		if (r == null) {
			Messages.overlay(Tr.n("視線の先に保存済みの範囲がありません ({0} ブロックまで)", Configs.Generic.TOOL_RANGE.getIntegerValue()));
			return true;
		}

		RedtactLive.get().pickSavedRange(r);
		return true;
	}

	/** 視線の先の保存済みの範囲 (このワールド・ディメンション。届く距離は道具と同じ)。無ければ null。 */
	public static @Nullable CircuitRecord lookedAtSaved(Minecraft mc) {
		Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;

		if (camera == null || mc.level == null) {
			return null;
		}

		List<CircuitRecord> list = RedtactLive.get().savedRanges().visible(worldKey(mc), mc.level.dimension().identifier().toString());

		if (list.isEmpty()) {
			return null;
		}

		Vec3 eye = camera.getEyePosition(1.0F);
		Vec3 dir = camera.getViewVector(1.0F);
		return BoxRay.pick(list, CircuitRecord::box, eye.x, eye.y, eye.z, dir.x, dir.y, dir.z, Configs.Generic.TOOL_RANGE.getIntegerValue());
	}

	/** いまのワールドの識別子 ({@link SessionManager#worldKey})。ワールドが変わったときだけ求め直す。 */
	public static String worldKey(Minecraft mc) {
		if (mc.level != keyLevel.get()) {
			keyLevel = new WeakReference<>(mc.level);
			keyCache = SessionManager.worldKey(mc);
		}

		return keyCache;
	}

	/** 視線の先のブロック (スニーク中は当たった面の隣)。当たらなければ null。 */
	public static @Nullable BlockPos target(Minecraft mc, double range) {
		Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;

		if (camera == null) {
			return null;
		}

		HitResult hit = camera.pick(range, 1.0F, false);

		if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}

		BlockPos pos = bhr.getBlockPos();
		return mc.player != null && mc.player.isShiftKeyDown() ? pos.relative(bhr.getDirection()) : pos;
	}
}
