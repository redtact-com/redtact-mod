package com.redtact.live.structure.bedrock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.redtact.live.litematic.BlockStateEntry;

/**
 * 隣を見て決まる状態を直す (#70)。mc-nbt-converter の Go 版 (7f17b26) の {@code pkg/buildnbt/postprocess.go}
 * (PostProcessBlocks) を写したもの: ピストンの頭が付いた本体を {@code extended=true} に、赤石の線のつながり (north・south・
 * east・west) を隣から決める。Go と同じく x → y → z の順に 1 つずつ見て、その場で書き換える (新しい状態はパレットの後ろに足す)。
 * 同じ状態・同じつながりの線は 1 度だけ組み立てる (大きな回路で線ごとに状態を作らない)。
 */
final class PostProcess {
	private static final Map<String, int[]> DIR_OFFSETS = Map.of("down", new int[] {0, -1, 0}, "up", new int[] {0, 1, 0}, "north",
			new int[] {0, 0, -1}, "south", new int[] {0, 0, 1}, "west", new int[] {-1, 0, 0}, "east", new int[] {1, 0, 0});
	private static final String WIRE = "minecraft:redstone_wire";
	/** 線の 1 方向のつながり (Go の "none"・"side"・"up")。 */
	private static final int NONE = 0;
	private static final int SIDE = 1;
	private static final int UP = 2;
	private static final String[] SIDES = {"none", "side", "up"};
	/** パレットの番号ごとの性質 ({@link #flags})。 */
	private static final int KNOWN = 1;
	private static final int IS_WIRE = 2;
	private static final int LINKS_X = 4;
	private static final int LINKS_Z = 8;

	private final int[] grid;
	private final int sx;
	private final int sy;
	private final int sz;
	private final JavaPalette palette;
	private final Set<String> connectables;
	private byte[] flags = new byte[64];
	/** (線の番号, つながり) → 直した番号。 */
	private final Map<Long, Integer> wires = new HashMap<>();

	private PostProcess(int[] grid, int sx, int sy, int sz, JavaPalette palette, Set<String> connectables) {
		this.grid = grid;
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
		this.palette = palette;
		this.connectables = connectables;
	}

	/** grid (添字 = (y * sz + z) * sx + x、0 は空気) をその場で直す。新しい状態は palette に足す。 */
	static void apply(int[] grid, int sx, int sy, int sz, JavaPalette palette, Set<String> connectables) {
		new PostProcess(grid, sx, sy, sz, palette, connectables).run();
	}

	private void run() {
		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					int i = (y * sz + z) * sx + x;
					int id = grid[i];

					if (id == 0) {
						continue;
					}

					if ((flags(id) & IS_WIRE) != 0) {
						grid[i] = connectWire(id, x, y, z);
					} else if (palette.get(id).name().equals("minecraft:piston_head")) {
						extendBase(palette.get(id), x, y, z);
					}
				}
			}
		}
	}

	/** 頭の向きの反対にピストンの本体があれば伸ばす。 */
	private void extendBase(BlockStateEntry head, int x, int y, int z) {
		String facing = head.properties().get("facing");
		int[] off = facing == null ? null : DIR_OFFSETS.get(facing);

		if (off == null) {
			return;
		}

		int b = index(x - off[0], y - off[1], z - off[2]);

		if (b < 0) {
			return;
		}

		BlockStateEntry base = palette.get(grid[b]);

		if (base.name().equals("minecraft:piston") || base.name().equals("minecraft:sticky_piston")) {
			Map<String, String> props = new HashMap<>(base.properties());
			props.put("extended", "true");
			grid[b] = palette.id(base.name(), props);
		}
	}

	private int connectWire(int id, int x, int y, int z) {
		int north = side(x, y, z, 0, -1);
		int south = side(x, y, z, 0, 1);
		int east = side(x, y, z, 1, 0);
		int west = side(x, y, z, -1, 0);
		boolean ns = north != NONE || south != NONE;
		boolean ew = east != NONE || west != NONE;

		// 1 方向だけつながる線は、その向きにまっすぐ通す
		if (ns && !ew) {
			north = north == NONE ? SIDE : north;
			south = south == NONE ? SIDE : south;
		} else if (ew && !ns) {
			east = east == NONE ? SIDE : east;
			west = west == NONE ? SIDE : west;
		}

		long key = (long) id << 8 | north << 6 | south << 4 | east << 2 | west;
		Integer done = wires.get(key);

		if (done != null) {
			return done;
		}

		BlockStateEntry wire = palette.get(id);
		Map<String, String> props = new HashMap<>(wire.properties());
		props.put("north", SIDES[north]);
		props.put("south", SIDES[south]);
		props.put("east", SIDES[east]);
		props.put("west", SIDES[west]);
		int out = palette.id(wire.name(), props);
		wires.put(key, out);
		return out;
	}

	/** 線の dx・dz の側: つながるブロックなら side、1 段上に線があれば up、1 段下に線があれば side。 */
	private int side(int x, int y, int z, int dx, int dz) {
		int beside = index(x + dx, y, z + dz);

		if (beside >= 0 && (flags(grid[beside]) & (dx != 0 ? LINKS_X : LINKS_Z)) != 0) {
			return SIDE;
		}

		int up = index(x + dx, y + 1, z + dz);

		if (up >= 0 && (flags(grid[up]) & IS_WIRE) != 0) {
			return UP;
		}

		int down = index(x + dx, y - 1, z + dz);
		return down >= 0 && (flags(grid[down]) & IS_WIRE) != 0 ? SIDE : NONE;
	}

	/** その番号の状態の性質 (初めて見たときに決めて覚える。番号の状態は後から変わらない)。 */
	private int flags(int id) {
		if (id >= flags.length) {
			flags = Arrays.copyOf(flags, Math.max(flags.length * 2, id + 1));
		}

		int f = flags[id];

		if (f == 0) {
			BlockStateEntry e = palette.get(id);
			f = KNOWN | (e.name().equals(WIRE) ? IS_WIRE : 0) | (connectable(e, 1, 0) ? LINKS_X : 0) | (connectable(e, 0, 1) ? LINKS_Z : 0);
			flags[id] = (byte) f;
		}

		return f;
	}

	/** Go の isRedstoneConnectable (dx・dz はどちらか 1 つだけ 0 でない)。 */
	private boolean connectable(BlockStateEntry e, int dx, int dz) {
		String name = e.name();

		if (name.equals(WIRE)) {
			return true;
		}

		if (name.equals("minecraft:repeater") || name.equals("minecraft:comparator") || name.equals("minecraft:observer")) {
			String facing = e.properties().get("facing");
			return dx != 0 && ("east".equals(facing) || "west".equals(facing)) || dz != 0 && ("north".equals(facing) || "south".equals(facing));
		}

		return connectables.contains(name) || name.contains("button") || name.contains("pressure_plate") || name.contains("trapdoor")
				|| name.contains("door") || name.contains("rail");
	}

	/** 範囲の中の空気でない場所の添字 (外か空気なら -1)。 */
	private int index(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
			return -1;
		}

		int i = (y * sz + z) * sx + x;
		return grid[i] == 0 ? -1 : i;
	}
}
