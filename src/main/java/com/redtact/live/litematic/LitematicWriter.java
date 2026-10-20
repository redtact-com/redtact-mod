package com.redtact.live.litematic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * Litematica 形式 (Version 7 / SubVersion 1) の書き出し。
 *
 * <p>NBT のクラス (CompoundTag 等) だけを使い、レジストリやワールドには触らない。
 * リージョンは 1 つ・Position は 0,0,0・Size は正。
 */
public final class LitematicWriter {
	public static final int VERSION = 7;
	public static final int SUB_VERSION = 1;

	private LitematicWriter() {
	}

	/**
	 * @param name        リージョン名 (= Metadata.Name)。セッションのタイトル
	 * @param timeMillis  TimeCreated / TimeModified
	 */
	public record Metadata(String name, String author, String description, int dataVersion, long timeMillis) {
	}

	/**
	 * @param tileEntities リージョン相対の x,y,z を持つブロックエンティティの NBT
	 */
	public static CompoundTag toNbt(RegionBlocks region, List<CompoundTag> tileEntities, Metadata meta) {
		String regionName = meta.name() == null || meta.name().isBlank() ? "region" : meta.name();

		CompoundTag root = new CompoundTag();
		root.putInt("MinecraftDataVersion", meta.dataVersion());
		root.putInt("Version", VERSION);
		root.putInt("SubVersion", SUB_VERSION);

		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", regionName);
		metadata.putString("Author", meta.author() == null ? "" : meta.author());
		metadata.putString("Description", meta.description() == null ? "" : meta.description());
		metadata.putInt("RegionCount", 1);
		metadata.putInt("TotalVolume", region.volume());
		metadata.putInt("TotalBlocks", region.nonAirCount());
		metadata.putLong("TimeCreated", meta.timeMillis());
		metadata.putLong("TimeModified", meta.timeMillis());
		metadata.put("EnclosingSize", pos(region.sizeX(), region.sizeY(), region.sizeZ()));
		root.put("Metadata", metadata);

		CompoundTag regionTag = new CompoundTag();
		regionTag.put("Position", pos(0, 0, 0));
		regionTag.put("Size", pos(region.sizeX(), region.sizeY(), region.sizeZ()));

		ListTag palette = new ListTag();

		for (BlockStateEntry entry : region.palette()) {
			CompoundTag e = new CompoundTag();
			e.putString("Name", entry.name());

			if (!entry.properties().isEmpty()) {
				CompoundTag props = new CompoundTag();

				for (Map.Entry<String, String> p : entry.properties().entrySet()) {
					props.putString(p.getKey(), p.getValue());
				}

				e.put("Properties", props);
			}

			palette.add(e);
		}

		regionTag.put("BlockStatePalette", palette);

		int bits = LitematicaBitArray.bitsForPaletteSize(region.palette().size());
		regionTag.putLongArray("BlockStates", LitematicaBitArray.pack(region.blocks(), bits));

		ListTag tes = new ListTag();

		for (CompoundTag te : tileEntities) {
			tes.add(te);
		}

		regionTag.put("TileEntities", tes);
		regionTag.put("Entities", new ListTag());
		regionTag.put("PendingBlockTicks", new ListTag());
		regionTag.put("PendingFluidTicks", new ListTag());

		CompoundTag regions = new CompoundTag();
		regions.put(regionName, regionTag);
		root.put("Regions", regions);
		return root;
	}

	/** gzip 圧縮した NBT (= .litematic ファイルの中身)。 */
	public static byte[] toBytes(RegionBlocks region, List<CompoundTag> tileEntities, Metadata meta) {
		return toBytes(toNbt(region, tileEntities, meta));
	}

	public static byte[] toBytes(CompoundTag root) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
			NbtIo.writeCompressed(root, out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static CompoundTag pos(int x, int y, int z) {
		CompoundTag t = new CompoundTag();
		t.putInt("x", x);
		t.putInt("y", y);
		t.putInt("z", z);
		return t;
	}
}
