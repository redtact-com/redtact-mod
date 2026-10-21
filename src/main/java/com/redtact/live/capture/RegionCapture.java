package com.redtact.live.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.redtact.live.i18n.Tr;
import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.litematic.RegionBlocks;
import com.redtact.live.selection.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 範囲内のブロック状態とブロックエンティティを読み取る。<b>クライアントスレッドで呼ぶ</b>。
 * 戻り値はワールドへの参照を持たないので、別スレッドで litematic 化してよい。
 */
public final class RegionCapture {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");

	private RegionCapture() {
	}

	/**
	 * @param blockEntities 添字 → ブロックエンティティの NBT (id・components などを含み、x,y,z は除く)
	 */
	public record Result(RegionBlocks blocks, SortedMap<Integer, CompoundTag> blockEntities, int nonAirCount) {
	}

	public static final class CaptureException extends Exception {
		public CaptureException(String message) {
			super(message);
		}
	}

	public static Result capture(Level level, Box box) throws CaptureException {
		int cx0 = box.minX() >> 4;
		int cz0 = box.minZ() >> 4;
		int cx1 = box.maxX() >> 4;
		int cz1 = box.maxZ() >> 4;
		int cw = cx1 - cx0 + 1;
		LevelChunk[] chunks = new LevelChunk[cw * (cz1 - cz0 + 1)];
		int missing = 0;

		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);

				if (chunk == null) {
					missing++;
				}

				chunks[(cz - cz0) * cw + (cx - cx0)] = chunk;
			}
		}

		if (missing > 0) {
			throw new CaptureException(Tr.n("範囲のうち {0} チャンクが読み込まれていません。近づいてからもう一度試してください", missing));
		}

		int sx = box.sizeX();
		int sy = box.sizeY();
		int sz = box.sizeZ();
		int[] blocks = new int[sx * sy * sz];
		List<BlockStateEntry> palette = new ArrayList<>();
		palette.add(BlockStateEntry.AIR);
		Map<BlockState, Integer> indexOfState = new IdentityHashMap<>();
		Map<BlockStateEntry, Integer> indexOfEntry = new HashMap<>();
		indexOfEntry.put(BlockStateEntry.AIR, 0);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		int nonAir = 0;
		int i = 0;

		for (int y = 0; y < sy; y++) {
			int wy = box.minY() + y;

			for (int z = 0; z < sz; z++) {
				int wz = box.minZ() + z;

				for (int x = 0; x < sx; x++, i++) {
					int wx = box.minX() + x;

					if (wy < minY || wy > maxY) {
						continue; // 建築範囲外は空気
					}

					LevelChunk chunk = chunks[((wz >> 4) - cz0) * cw + ((wx >> 4) - cx0)];
					BlockState state = chunk.getBlockState(pos.set(wx, wy, wz));

					if (state.isAir()) {
						continue;
					}

					Integer idx = indexOfState.get(state);

					if (idx == null) {
						BlockStateEntry entry = toEntry(state);
						idx = indexOfEntry.get(entry);

						if (idx == null) {
							idx = palette.size();
							palette.add(entry);
							indexOfEntry.put(entry, idx);
						}

						indexOfState.put(state, idx);
					}

					blocks[i] = idx;
					nonAir++;
				}
			}
		}

		return new Result(new RegionBlocks(sx, sy, sz, palette, blocks), blockEntities(level, chunks, box), nonAir);
	}

	/**
	 * 範囲内のブロックエンティティだけを読む。そのレベルのスレッドで呼ぶ
	 * (シングルプレイでは統合サーバのレベルから読むと、チェストの中身やコンパレーターの出力も取れる)。
	 */
	public static SortedMap<Integer, CompoundTag> captureBlockEntities(Level level, Box box) {
		int cx0 = box.minX() >> 4;
		int cz0 = box.minZ() >> 4;
		int cx1 = box.maxX() >> 4;
		int cz1 = box.maxZ() >> 4;
		List<LevelChunk> chunks = new ArrayList<>();

		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);

				if (chunk != null) {
					chunks.add(chunk);
				}
			}
		}

		return blockEntities(level, chunks.toArray(LevelChunk[]::new), box);
	}

	private static SortedMap<Integer, CompoundTag> blockEntities(Level level, LevelChunk[] chunks, Box box) {
		SortedMap<Integer, CompoundTag> tileEntities = new TreeMap<>();

		for (LevelChunk chunk : chunks) {
			if (chunk == null) {
				continue;
			}

			for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
				BlockPos p = e.getKey();

				if (!box.contains(p.getX(), p.getY(), p.getZ())) {
					continue;
				}

				try {
					CompoundTag tag = e.getValue().saveWithFullMetadata(level.registryAccess());
					// 位置は添字で表す
					tag.remove("x");
					tag.remove("y");
					tag.remove("z");
					int index = ((p.getY() - box.minY()) * box.sizeZ() + (p.getZ() - box.minZ())) * box.sizeX() + (p.getX() - box.minX());
					tileEntities.put(index, tag);
				} catch (RuntimeException ex) {
					LOGGER.warn("[redtact-live] ブロックエンティティ {} を保存できません: {}", p, ex.toString());
				}
			}
		}

		return tileEntities;
	}

	public static BlockStateEntry toEntry(BlockState state) {
		String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		Map<String, String> props = new TreeMap<>();
		//? if >=26.1 {
		state.getValues().forEach(v -> props.put(v.property().getName(), v.valueName()));
		//?} else
		/*state.getValues().forEach((p, v) -> props.put(p.getName(), valueName(p, v)));*/
		return BlockStateEntry.of(name, props);
	}

	//? if <26.1 {
	/*// 1.21.x の状態の値は Property と値の組 (値の名前は Property から引く)
	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String valueName(net.minecraft.world.level.block.state.properties.Property<T> property, Comparable<?> value) {
		return property.getName((T) value);
	}
	*///?}
}
