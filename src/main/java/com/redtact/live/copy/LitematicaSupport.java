package com.redtact.live.copy;

import java.nio.file.Path;

import com.redtact.live.compat.litematica.LitematicaPlacer;
import com.redtact.live.i18n.Tr;
import com.redtact.live.selection.Box;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Litematica (任意の依存) への入口。Litematica のクラスは {@link LitematicaPlacer} だけが使い、
 * それは {@link #available()} が true のときにだけ呼ぶ (呼ばなければ読み込まれない)。
 */
public final class LitematicaSupport {
	private static final boolean AVAILABLE = FabricLoader.getInstance().isModLoaded("litematica");

	/** 配置の結果。replaced: 同じセッションの前の配置を置き換えた (原点は前のまま)。 */
	public record Placed(BlockPos origin, boolean replaced) {
	}

	/**
	 * 配置のいまの様子。box は配置の範囲 (回転・反転していれば囲む箱)、transform は「回転 90°」などの表示、
	 * matched は schematic の空気以外のブロック nonAir 個のうち、ワールドの同じ位置に同じブロックがある数。
	 */
	public record PlacementInfo(Box box, BlockPos origin, String name, String transform, int nonAir, int matched) {
	}

	private LitematicaSupport() {
	}

	public static boolean available() {
		return AVAILABLE;
	}

	/** クライアントスレッドから。 */
	public static Placed place(String sessionId, Path file, String name, BlockPos feet, @Nullable BlockPos fixedOrigin) {
		requireAvailable();
		return LitematicaPlacer.place(sessionId, file, name, feet, fixedOrigin);
	}

	/** 保管庫のファイルを足元に置く (同じファイルの配置は置き直す。#46)。クライアントスレッドから。 */
	public static Placed placeFile(String key, Path file, String name, BlockPos feet) {
		requireAvailable();
		return LitematicaPlacer.placeFile(key, file, name, feet);
	}

	/** そのセッションの配置の様子 (クライアントスレッドから)。Litematica が無い・配置が無ければ null。level が null なら一致は数えない。 */
	public static @Nullable PlacementInfo placementInfo(String sessionId, @Nullable Level level) {
		return AVAILABLE ? LitematicaPlacer.info(sessionId, level) : null;
	}

	public static Path schematicsDir() {
		requireAvailable();
		return LitematicaPlacer.schematicsDir();
	}

	private static void requireAvailable() {
		if (!AVAILABLE) {
			throw new IllegalStateException(Tr.t("Litematica が入っていません"));
		}
	}
}
