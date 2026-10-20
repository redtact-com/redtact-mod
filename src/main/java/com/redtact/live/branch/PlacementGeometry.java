package com.redtact.live.branch;

import java.util.Collection;

import com.redtact.live.i18n.Tr;
import com.redtact.live.selection.Box;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Litematica の配置の位置の計算 (Litematica の PositionUtils と同じ式。Litematica に依存しない)。
 * 回転・反転はまず配置全体、次にサブリージョンの順に当てる。
 */
public final class PlacementGeometry {
	private PlacementGeometry() {
	}

	/** 配置全体とサブリージョンの回転・反転。 */
	public record Transform(Mirror mainMirror, Rotation mainRotation, Mirror subMirror, Rotation subRotation) {
		public static final Transform NONE = new Transform(Mirror.NONE, Rotation.NONE, Mirror.NONE, Rotation.NONE);

		public boolean isIdentity() {
			return mainMirror == Mirror.NONE && mainRotation == Rotation.NONE && subMirror == Mirror.NONE && subRotation == Rotation.NONE;
		}
	}

	/** 反転してから原点まわりに回す (Litematica の getTransformedBlockPos)。 */
	public static BlockPos transform(BlockPos pos, Mirror mirror, Rotation rotation) {
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();

		switch (mirror) {
			case LEFT_RIGHT -> z = -z;
			case FRONT_BACK -> x = -x;
			default -> {
			}
		}

		return switch (rotation) {
			case CLOCKWISE_90 -> new BlockPos(-z, y, x);
			case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
			case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
			default -> new BlockPos(x, y, z);
		};
	}

	/**
	 * サブリージョンの中の位置 local を、ワールドの位置にする。
	 *
	 * @param origin 配置の原点
	 * @param subPos サブリージョンの配置の中の位置 (回す前)
	 */
	public static BlockPos toWorld(BlockPos local, BlockPos origin, BlockPos subPos, Transform t) {
		BlockPos regionOrigin = transform(subPos, t.mainMirror(), t.mainRotation()).offset(origin);
		BlockPos p = transform(local, t.mainMirror(), t.mainRotation());
		p = transform(p, t.subMirror(), t.subRotation());
		return p.offset(regionOrigin);
	}

	/** 大きさ size (負もあり) のサブリージョンの最後の角 (Litematica の getRelativeEndPositionFromAreaSize)。 */
	public static BlockPos endOf(Vec3i size) {
		return new BlockPos(size.getX() >= 0 ? size.getX() - 1 : size.getX() + 1,
				size.getY() >= 0 ? size.getY() - 1 : size.getY() + 1,
				size.getZ() >= 0 ? size.getZ() - 1 : size.getZ() + 1);
	}

	/** サブリージョン 1 つがワールドで占める箱。 */
	public static Box regionBox(BlockPos origin, BlockPos subPos, Vec3i size, Transform t) {
		BlockPos a = toWorld(BlockPos.ZERO, origin, subPos, t);
		BlockPos b = toWorld(endOf(size), origin, subPos, t);
		return Box.of(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
	}

	/** 箱をすべて囲む箱。空なら null。 */
	public static Box enclose(Collection<Box> boxes) {
		Box out = null;

		for (Box b : boxes) {
			out = out == null ? b : new Box(Math.min(out.minX(), b.minX()), Math.min(out.minY(), b.minY()), Math.min(out.minZ(), b.minZ()),
					Math.max(out.maxX(), b.maxX()), Math.max(out.maxY(), b.maxY()), Math.max(out.maxZ(), b.maxZ()));
		}

		return out;
	}

	/** 表示用 (例「回転 90°・反転 左右」)。何もしていなければ空文字。 */
	public static String describe(Transform t) {
		int deg = (degrees(t.mainRotation()) + degrees(t.subRotation())) % 360;
		boolean mirrored = t.mainMirror() != Mirror.NONE || t.subMirror() != Mirror.NONE;

		if (deg != 0) {
			return mirrored ? Tr.f("回転 {0}°・反転", deg) : Tr.f("回転 {0}°", deg);
		}

		return mirrored ? Tr.t("反転") : "";
	}

	private static int degrees(Rotation r) {
		return switch (r) {
			case CLOCKWISE_90 -> 90;
			case CLOCKWISE_180 -> 180;
			case COUNTERCLOCKWISE_90 -> 270;
			default -> 0;
		};
	}
}
