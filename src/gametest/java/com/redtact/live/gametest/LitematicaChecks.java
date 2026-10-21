package com.redtact.live.gametest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.redtact.live.compat.litematica.LitematicaPlacer;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Litematica の配置を調べる (Litematica が入っているときだけ呼ぶ。-PnoLitematica の実行では読み込まれない)。
 * クライアントスレッドから呼ぶ。
 */
final class LitematicaChecks {
	private LitematicaChecks() {
	}

	/**
	 * 配置の要約。blocks は schematic の中身を (y, z, x) の順に並べたもの (CircuitState と同じ添字)。
	 * blockEntityIds は Litematica が読み込んだブロックエンティティの添字 → id。
	 */
	record Placed(BlockPos origin, String name, Path file, int sizeX, int sizeY, int sizeZ, List<BlockState> blocks,
			Map<Integer, String> blockEntityIds, int sameSessionPlacements) {
	}

	static @Nullable Placed placed(String sessionId) {
		SchematicPlacement p = LitematicaPlacer.placement(sessionId);

		if (p == null) {
			return null;
		}

		LitematicaSchematic schematic = p.getSchematic();
		String region = schematic.getAreaSizes().keySet().iterator().next();
		BlockPos size = schematic.getAreaSize(region);
		LitematicaBlockStateContainer c = schematic.getSubRegionContainer(region);
		int sx = Math.abs(size.getX());
		int sy = Math.abs(size.getY());
		int sz = Math.abs(size.getZ());
		List<BlockState> blocks = new ArrayList<>(sx * sy * sz);

		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					blocks.add(c.get(x, y, z));
				}
			}
		}

		Map<Integer, String> blockEntityIds = new TreeMap<>();
		// 1.21.10 の Litematica はゲームの CompoundTag を返す (getString は Optional。#112)
		schematic.getBlockEntityMapForRegion(region).forEach((pos, data) ->
				//? if >=1.21.11 {
				blockEntityIds.put((pos.getY() * sz + pos.getZ()) * sx + pos.getX(), data.getString("id")));
				//?} else
				/*blockEntityIds.put((pos.getY() * sz + pos.getZ()) * sx + pos.getX(), data.getStringOr("id", "")));*/

		Path dir = p.getSchematicFile().getParent();
		int same = (int) DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
				.filter(q -> q.getSchematicFile() != null && dir.equals(q.getSchematicFile().getParent()))
				.count();
		return new Placed(p.getOrigin(), p.getName(), p.getSchematicFile(), sx, sy, sz, blocks, blockEntityIds, same);
	}

	/** 保管庫の配置 (#46): 鍵の配置の原点・ファイルと、同じファイルの配置の数。無ければ null。 */
	record LibraryPlacement(BlockPos origin, Path file, int sameFile) {
	}

	static @Nullable LibraryPlacement libraryPlacement(String key) {
		SchematicPlacement p = LitematicaPlacer.placement(key);

		if (p == null) {
			return null;
		}

		Path file = p.getSchematicFile().toAbsolutePath().normalize();
		int same = (int) DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
				.filter(q -> q.getSchematicFile() != null && file.equals(q.getSchematicFile().toAbsolutePath().normalize()))
				.count();
		return new LibraryPlacement(p.getOrigin(), file, same);
	}

	/** ゲームを起動し直したとき (MOD は前の配置を覚えていない) の再現。 */
	static void forgetTracked() {
		LitematicaPlacer.forgetTracked();
	}

	/** 配置全体を回す (利用者が Litematica で回したときの再現)。 */
	static void rotate(String sessionId, net.minecraft.world.level.block.Rotation rotation) {
		SchematicPlacement p = LitematicaPlacer.placement(sessionId);

		if (p == null) {
			throw new AssertionError("配置が無い: " + sessionId);
		}

		p.setRotation(rotation, new fi.dy.masa.malilib.gui.interfaces.IMessageConsumer() {
			@Override
			public void addMessage(fi.dy.masa.malilib.gui.Message.MessageType type, String messageKey, Object... args) {
			}

			@Override
			public void addMessage(fi.dy.masa.malilib.gui.Message.MessageType type, int lifeTime, String messageKey, Object... args) {
			}
		});
	}

	/** いま選ばれている配置の名前。 */
	static @Nullable String selectedName() {
		SchematicPlacement p = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
		return p == null ? null : p.getName();
	}
}
