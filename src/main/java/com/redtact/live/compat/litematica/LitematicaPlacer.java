package com.redtact.live.compat.litematica;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redtact.live.branch.PlacementGeometry;

import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.copy.LitematicaSupport;
import com.redtact.live.i18n.Tr;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Litematica の API を呼ぶところ。Litematica が入っているときだけ読み込まれる ({@link LitematicaSupport} から)。
 * クライアントスレッドから呼ぶ。
 */
public final class LitematicaPlacer {
	/** この起動中に作ったセッションごとの配置。 */
	private static final Map<String, SchematicPlacement> PLACED = new HashMap<>();

	private LitematicaPlacer() {
	}

	public static Path schematicsDir() {
		return DataManager.getSchematicsBaseDirectory();
	}

	/**
	 * file を読み込んで配置を作る。同じセッションの配置 (この起動中に作ったもの、無ければ同じセッションのフォルダの
	 * schematic の配置) があれば、それを外して同じ原点に新しい版を置く。無ければ feet に置く。
	 * fixedOrigin を渡したら、前の配置があってもなくてもその原点に置く (分岐した子を自分の範囲に重ねるとき)。
	 */
	public static LitematicaSupport.Placed place(String sessionId, Path file, String name, BlockPos feet, @Nullable BlockPos fixedOrigin) {
		SchematicHolder holder = SchematicHolder.getInstance();
		SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
		SchematicPlacement old = existing(sessionId, file.getParent(), manager);

		// 版ごとに別のファイルなので、新しい版は必ず読み直しになる (同じ版なら中身も同じなので読み込み済みを使う)
		LitematicaSchematic loaded = holder.getOrLoad(file);

		if (loaded == null) {
			throw new IllegalStateException(Tr.f("Litematica が {0} を読めません", file.getFileName()));
		}

		BlockPos origin = fixedOrigin != null ? fixedOrigin : feet;
		boolean replaced = false;

		if (old != null) {
			origin = fixedOrigin != null ? fixedOrigin : old.getOrigin();
			replaced = true;
			LitematicaSchematic oldSchematic = old.getSchematic();
			manager.removeSchematicPlacement(old);

			// 前の版を使う配置がもう無ければ、読み込み済みの一覧からも外す (メモリと一覧の掃除)
			if (oldSchematic != null && oldSchematic != loaded && manager.getAllPlacementsOfSchematic(oldSchematic).isEmpty()) {
				holder.removeSchematic(oldSchematic);
			}
		}

		SchematicPlacement placement = SchematicPlacement.createFor(loaded, origin, name, true, true);
		manager.addSchematicPlacement(placement, false);
		manager.setSelectedSchematicPlacement(placement);
		PLACED.put(sessionId, placement);
		return new LitematicaSupport.Placed(origin, replaced);
	}

	/**
	 * 保管庫のファイルを足元に置く (#46)。同じファイルの配置 (この起動中に作ったもの、無ければ前の起動のもの) があれば
	 * 外して足元に置き直す。ファイルを取り直して中身が変わっていることがあるので、ほかに使っている配置が無ければ
	 * 読み込み済みの schematic も捨てて読み直す。
	 */
	public static LitematicaSupport.Placed placeFile(String key, Path file, String name, BlockPos feet) {
		SchematicHolder holder = SchematicHolder.getInstance();
		SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
		Path target = file.toAbsolutePath().normalize();
		boolean replaced = false;

		for (SchematicPlacement p : new ArrayList<>(manager.getAllSchematicsPlacements())) {
			Path f = p.getSchematicFile();

			if (p == PLACED.get(key) || f != null && f.toAbsolutePath().normalize().equals(target)) {
				manager.removeSchematicPlacement(p);
				replaced = true;
			}
		}

		for (LitematicaSchematic loaded : new ArrayList<>(holder.getAllSchematics())) {
			Path f = loaded.getFile();

			if (f != null && f.toAbsolutePath().normalize().equals(target) && manager.getAllPlacementsOfSchematic(loaded).isEmpty()) {
				holder.removeSchematic(loaded);
			}
		}

		LitematicaSchematic schematic = holder.getOrLoad(file);

		if (schematic == null) {
			throw new IllegalStateException(Tr.f("Litematica が {0} を読めません", file.getFileName()));
		}

		SchematicPlacement placement = SchematicPlacement.createFor(schematic, feet, name, true, true);
		manager.addSchematicPlacement(placement, false);
		manager.setSelectedSchematicPlacement(placement);
		PLACED.put(key, placement);
		return new LitematicaSupport.Placed(feet, replaced);
	}

	private static @Nullable SchematicPlacement existing(String sessionId, @Nullable Path sessionDir, SchematicPlacementManager manager) {
		List<SchematicPlacement> all = manager.getAllSchematicsPlacements();
		SchematicPlacement tracked = PLACED.get(sessionId);

		if (tracked != null && all.contains(tracked)) {
			return tracked;
		}

		PLACED.remove(sessionId);

		if (sessionDir == null) {
			return null;
		}

		// 前の起動で作った配置 (Litematica がワールドごとに覚えている) も、同じセッションのフォルダのものなら置き換える
		Path dir = sessionDir.toAbsolutePath().normalize();

		for (SchematicPlacement p : all) {
			Path f = p.getSchematicFile();

			if (f != null && f.toAbsolutePath().normalize().getParent() != null && f.toAbsolutePath().normalize().getParent().equals(dir)) {
				return p;
			}
		}

		return null;
	}

	/**
	 * そのセッションの配置の範囲 (回転・反転していれば囲む箱) と、schematic の空気以外のブロックのうち、
	 * level の同じ位置に同じブロック (状態は見ない) があるものの数。配置が無ければ null。
	 */
	public static LitematicaSupport.@Nullable PlacementInfo info(String sessionId, @Nullable Level level) {
		SchematicPlacement p = existing(sessionId, null, DataManager.getSchematicPlacementManager());

		if (p == null || p.getSchematic() == null) {
			return null;
		}

		LitematicaSchematic schematic = p.getSchematic();
		List<com.redtact.live.selection.Box> boxes = new ArrayList<>();
		int nonAir = 0;
		int matched = 0;
		PlacementGeometry.Transform shown = null;

		for (SubRegionPlacement sub : p.getAllSubRegionsPlacements()) {
			BlockPos size = schematic.getAreaSize(sub.getName());

			if (!sub.isEnabled() || size == null) {
				continue;
			}

			PlacementGeometry.Transform t = new PlacementGeometry.Transform(p.getMirror(), p.getRotation(), sub.getMirror(), sub.getRotation());
			shown = shown == null ? t : shown;
			boxes.add(PlacementGeometry.regionBox(p.getOrigin(), sub.getPos(), size, t));
			LitematicaBlockStateContainer c = schematic.getSubRegionContainer(sub.getName());

			if (level == null || c == null) {
				continue;
			}

			// 容器の (0,0,0) はサブリージョンの最小の角 (大きさが負の軸は終わりの側)
			BlockPos end = PlacementGeometry.endOf(size);
			int bx = Math.min(0, end.getX());
			int by = Math.min(0, end.getY());
			int bz = Math.min(0, end.getZ());
			int sx = Math.abs(size.getX());
			int sy = Math.abs(size.getY());
			int sz = Math.abs(size.getZ());

			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					for (int x = 0; x < sx; x++) {
						BlockState st = c.get(x, y, z);

						if (st == null || st.isAir()) {
							continue;
						}

						nonAir++;
						BlockPos world = PlacementGeometry.toWorld(new BlockPos(bx + x, by + y, bz + z), p.getOrigin(), sub.getPos(), t);

						if (level.getBlockState(world).getBlock() == st.getBlock()) {
							matched++;
						}
					}
				}
			}
		}

		com.redtact.live.selection.Box box = PlacementGeometry.enclose(boxes);
		return box == null ? null : new LitematicaSupport.PlacementInfo(box, p.getOrigin(), p.getName(),
				shown == null ? "" : PlacementGeometry.describe(shown), nonAir, matched);
	}

	/** この起動中に作ったセッションの配置 (テスト用)。外されていれば null。 */
	public static @Nullable SchematicPlacement placement(String sessionId) {
		SchematicPlacement p = PLACED.get(sessionId);
		return p != null && DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(p) ? p : null;
	}

	/** この起動中に作った配置の記録を忘れる (ゲームを起動し直したときの再現。テスト用)。 */
	public static void forgetTracked() {
		PLACED.clear();
	}

	/** {@link CircuitLoader#FOLDER} の下か (テスト用)。 */
	public static boolean isOurs(SchematicPlacement p) {
		Path f = p.getSchematicFile();
		return f != null && f.toString().contains(CircuitLoader.FOLDER);
	}
}
