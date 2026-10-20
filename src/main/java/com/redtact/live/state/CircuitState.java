package com.redtact.live.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.litematic.RegionBlocks;

/**
 * 回路の全体 (protocol.md の CircuitState)。MC に依存しない。
 *
 * <p>ブロックはパレットの文字列 ({@code 名前[キー=値,...]}) と添字で持ち、
 * ブロックエンティティは添字 → 非圧縮のバイナリ NBT。添字 = {@code (y * sizeZ + z) * sizeX + x}。
 */
public final class CircuitState {
	public static final int MAX_PALETTE = 65_535;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final List<String> palette;
	private final int[] blocks;
	private final SortedMap<Integer, byte[]> blockEntities;

	public CircuitState(int sizeX, int sizeY, int sizeZ, List<String> palette, int[] blocks, Map<Integer, byte[]> blockEntities) {
		if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
			throw new IllegalArgumentException("size must be positive");
		}

		if ((long) sizeX * sizeY * sizeZ != blocks.length) {
			throw new IllegalArgumentException("blocks length " + blocks.length + " != volume");
		}

		if (palette.isEmpty() || palette.size() > MAX_PALETTE) {
			throw new IllegalArgumentException("palette size must be 1.." + MAX_PALETTE + ": " + palette.size());
		}

		for (int b : blocks) {
			if (b < 0 || b >= palette.size()) {
				throw new IllegalArgumentException("palette index out of range: " + b);
			}
		}

		for (int i : blockEntities.keySet()) {
			if (i < 0 || i >= blocks.length) {
				throw new IllegalArgumentException("block entity index out of range: " + i);
			}
		}

		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = List.copyOf(palette);
		this.blocks = blocks;
		this.blockEntities = Collections.unmodifiableSortedMap(new TreeMap<>(blockEntities));
	}

	/** litematic 用の RegionBlocks から作る。パレットは文字列にする。 */
	public static CircuitState of(RegionBlocks region, Map<Integer, byte[]> blockEntities) {
		List<String> palette = new ArrayList<>(region.palette().size());

		for (BlockStateEntry e : region.palette()) {
			palette.add(e.toString());
		}

		return new CircuitState(region.sizeX(), region.sizeY(), region.sizeZ(), palette, region.blocks(), blockEntities);
	}

	public int sizeX() {
		return sizeX;
	}

	public int sizeY() {
		return sizeY;
	}

	public int sizeZ() {
		return sizeZ;
	}

	public int[] size() {
		return new int[] {sizeX, sizeY, sizeZ};
	}

	public boolean sameSize(CircuitState other) {
		return sizeX == other.sizeX && sizeY == other.sizeY && sizeZ == other.sizeZ;
	}

	public int volume() {
		return blocks.length;
	}

	public List<String> palette() {
		return palette;
	}

	/** 直接参照 (書き換えない)。 */
	public int[] blocks() {
		return blocks;
	}

	public SortedMap<Integer, byte[]> blockEntities() {
		return blockEntities;
	}

	public String blockAt(int index) {
		return palette.get(blocks[index]);
	}

	public int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	/** 空気以外のブロック数 (サーバも同じ数え方)。 */
	public int nonAirCount() {
		boolean[] air = new boolean[palette.size()];

		for (int p = 0; p < air.length; p++) {
			air[p] = isAir(palette.get(p));
		}

		int n = 0;

		for (int b : blocks) {
			if (!air[b]) {
				n++;
			}
		}

		return n;
	}

	public static boolean isAir(String state) {
		return state.equals("minecraft:air") || state.equals("minecraft:cave_air") || state.equals("minecraft:void_air");
	}

	/** 中身が同じか (パレットの並びは問わず、各位置の文字列とブロックエンティティのバイト列で比べる)。 */
	public boolean sameContent(CircuitState other) {
		if (!sameSize(other) || !blockEntities.keySet().equals(other.blockEntities.keySet())) {
			return false;
		}

		for (int i = 0; i < blocks.length; i++) {
			if (!blockAt(i).equals(other.blockAt(i))) {
				return false;
			}
		}

		for (Map.Entry<Integer, byte[]> e : blockEntities.entrySet()) {
			if (!Arrays.equals(e.getValue(), other.blockEntities.get(e.getKey()))) {
				return false;
			}
		}

		return true;
	}

	/** blocks を uint16 リトルエンディアンにする。 */
	public static byte[] encodeBlocks(int[] blocks) {
		byte[] out = new byte[blocks.length * 2];

		for (int i = 0; i < blocks.length; i++) {
			int v = blocks[i];

			if (v < 0 || v > 0xFFFF) {
				throw new IllegalArgumentException("palette index does not fit in uint16: " + v);
			}

			out[2 * i] = (byte) v;
			out[2 * i + 1] = (byte) (v >>> 8);
		}

		return out;
	}

	public static int[] decodeBlocks(byte[] data) {
		if (data.length % 2 != 0) {
			throw new IllegalArgumentException("odd length: " + data.length);
		}

		int[] out = new int[data.length / 2];

		for (int i = 0; i < out.length; i++) {
			out[i] = (data[2 * i] & 0xFF) | (data[2 * i + 1] & 0xFF) << 8;
		}

		return out;
	}

	public StateJson.State toJson() {
		StateJson.State s = new StateJson.State();
		s.size = size();
		s.palette = palette;
		s.blocks = Base64.getEncoder().encodeToString(encodeBlocks(blocks));
		s.blockEntities = blockEntitiesJson(blockEntities);
		return s;
	}

	public static CircuitState fromJson(StateJson.State s) {
		if (s.size == null || s.size.length != 3 || s.palette == null || s.blocks == null) {
			throw new IllegalArgumentException("incomplete state");
		}

		Map<Integer, byte[]> bes = new HashMap<>();

		if (s.blockEntities != null) {
			for (StateJson.BlockEntity be : s.blockEntities) {
				bes.put(be.i, Base64.getDecoder().decode(be.nbt));
			}
		}

		return new CircuitState(s.size[0], s.size[1], s.size[2], s.palette, decodeBlocks(Base64.getDecoder().decode(s.blocks)), bes);
	}

	static List<StateJson.BlockEntity> blockEntitiesJson(Map<Integer, byte[]> map) {
		List<StateJson.BlockEntity> list = new ArrayList<>(map.size());

		for (Map.Entry<Integer, byte[]> e : map.entrySet()) {
			list.add(new StateJson.BlockEntity(e.getKey(), Base64.getEncoder().encodeToString(e.getValue())));
		}

		return list;
	}
}
