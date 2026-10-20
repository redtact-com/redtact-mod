package com.redtact.live.structure;

import java.util.ArrayList;
import java.util.List;

import com.redtact.live.i18n.Tr;
import com.redtact.live.litematic.LitematicaBitArray;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Litematica の .litematic (#70)。領域 (Regions) ごとのパレットと詰めた BlockStates を、全部の領域を囲む箱に並べる。
 * Size の負の軸は選んだ向きを書いているだけで、中身はいつも最小の角から + の向きに入っている
 * (litemapy・@taku128/java-schematic と同じ。最小の角 = Position + (Size &lt; 0 ? Size + 1 : 0))。
 * 領域が重なるところは後の領域の空気でないブロックを置く。
 */
final class LitematicReader {
	private LitematicReader() {
	}

	private record Region(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, ListTag palette, long[] states) {
	}

	static RawStructure read(CompoundTag root, StructureLimits limits) throws StructureException {
		CompoundTag regionsTag = root.getCompound("Regions").orElseThrow(() -> new StructureException(Tr.t(".litematic を読めません: Regions がありません")));
		List<Region> regions = new ArrayList<>();
		long minX = Long.MAX_VALUE;
		long minY = Long.MAX_VALUE;
		long minZ = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		long maxY = Long.MIN_VALUE;
		long maxZ = Long.MIN_VALUE;

		for (String name : regionsTag.keySet()) {
			CompoundTag r = regionsTag.getCompound(name).orElse(null);

			if (r == null) {
				continue;
			}

			CompoundTag pos = r.getCompoundOrEmpty("Position");
			CompoundTag size = r.getCompoundOrEmpty("Size");
			int sx = size.getIntOr("x", 0);
			int sy = size.getIntOr("y", 0);
			int sz = size.getIntOr("z", 0);

			if (sx == 0 || sy == 0 || sz == 0) {
				continue; // 空の領域
			}

			int ax = Math.abs(sx);
			int ay = Math.abs(sy);
			int az = Math.abs(sz);
			String reason = limits.volumeReason(ax, ay, az);

			if (reason != null) {
				throw new StructureException(reason);
			}

			int ox = pos.getIntOr("x", 0) + (sx < 0 ? sx + 1 : 0);
			int oy = pos.getIntOr("y", 0) + (sy < 0 ? sy + 1 : 0);
			int oz = pos.getIntOr("z", 0) + (sz < 0 ? sz + 1 : 0);
			ListTag palette = r.getListOrEmpty("BlockStatePalette");
			long[] states = r.getLongArray("BlockStates").orElse(new long[0]);

			if (palette.isEmpty()) {
				throw new StructureException(Tr.f(".litematic を読めません: 領域「{0}」にパレットがありません", name));
			}

			long volume = (long) ax * ay * az;
			int bits = LitematicaBitArray.bitsForPaletteSize(palette.size());

			if (states.length < LitematicaBitArray.longCount(volume, bits)) {
				throw new StructureException(Tr.f(".litematic を読めません: 領域「{0}」のブロックが足りません (壊れています)", name));
			}

			regions.add(new Region(ox, oy, oz, ax, ay, az, palette, states));
			minX = Math.min(minX, ox);
			minY = Math.min(minY, oy);
			minZ = Math.min(minZ, oz);
			maxX = Math.max(maxX, (long) ox + ax - 1);
			maxY = Math.max(maxY, (long) oy + ay - 1);
			maxZ = Math.max(maxZ, (long) oz + az - 1);
		}

		if (regions.isEmpty()) {
			throw new StructureException(Tr.t("空の回路です (.litematic に領域がありません)"));
		}

		long gx = maxX - minX + 1;
		long gy = maxY - minY + 1;
		long gz = maxZ - minZ + 1;
		String reason = limits.volumeReason(gx, gy, gz);

		if (reason != null) {
			throw new StructureException(reason);
		}

		int sx = (int) gx;
		int sy = (int) gy;
		int sz = (int) gz;
		int[] blocks = new int[sx * sy * sz];
		PaletteBuilder palette = new PaletteBuilder();

		for (Region r : regions) {
			int[] ids = new int[r.palette().size()];

			for (int p = 0; p < ids.length; p++) {
				ids[p] = palette.id(r.palette().getCompound(p).map(BlockStates::fromTag).orElse(null));
			}

			int bits = LitematicaBitArray.bitsForPaletteSize(ids.length);
			int dx = (int) (r.minX() - minX);
			int dy = (int) (r.minY() - minY);
			int dz = (int) (r.minZ() - minZ);
			long i = 0;

			for (int y = 0; y < r.sizeY(); y++) {
				for (int z = 0; z < r.sizeZ(); z++) {
					for (int x = 0; x < r.sizeX(); x++, i++) {
						int v = LitematicaBitArray.get(r.states(), bits, i);
						int id = v < ids.length ? ids[v] : 0;

						if (id != 0) {
							blocks[((y + dy) * sz + z + dz) * sx + x + dx] = id;
						}
					}
				}
			}
		}

		List<String> notes = regions.size() > 1 ? List.of(Tr.n("領域 {0} 個を 1 つの箱に並べています", regions.size())) : List.of();
		int version = root.getIntOr("MinecraftDataVersion", RawStructure.UNKNOWN_VERSION);
		return new RawStructure("litematic", sx, sy, sz, palette.entries(), blocks, version, notes);
	}
}
