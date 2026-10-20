package com.redtact.live.capture;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** ブロックエンティティの NBT を、送る形 (バイト列) と litematic の形 (相対 x,y,z 付き) にする。 */
public final class BlockEntityNbt {
	/** protocol.md: 1 件 64 KiB まで。 */
	public static final int MAX_BYTES = 64 * 1024;

	private BlockEntityNbt() {
	}

	/** 非圧縮のバイナリ NBT (NbtIo.write の形。root は名前が空の compound)。 */
	public static byte[] encode(CompoundTag tag) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);

			try (DataOutputStream out = new DataOutputStream(bytes)) {
				NbtIo.write(tag, out);
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public record Encoded(SortedMap<Integer, byte[]> blockEntities, int skipped) {
	}

	/** 送る形にする。上限を超えるものは送らない (skipped に数える)。 */
	public static Encoded encodeAll(Map<Integer, CompoundTag> tags) {
		SortedMap<Integer, byte[]> out = new TreeMap<>();
		int skipped = 0;

		for (Map.Entry<Integer, CompoundTag> e : tags.entrySet()) {
			byte[] b = encode(e.getValue());

			if (b.length > MAX_BYTES) {
				skipped++;
				continue;
			}

			out.put(e.getKey(), b);
		}

		return new Encoded(out, skipped);
	}

	/** litematic の TileEntities (リージョン相対の x,y,z を付けた写し)。 */
	public static List<CompoundTag> forLitematic(Map<Integer, CompoundTag> tags, int sizeX, int sizeZ) {
		List<CompoundTag> out = new ArrayList<>(tags.size());

		for (Map.Entry<Integer, CompoundTag> e : tags.entrySet()) {
			int i = e.getKey();
			CompoundTag t = e.getValue().copy();
			t.putInt("x", i % sizeX);
			t.putInt("z", (i / sizeX) % sizeZ);
			t.putInt("y", i / (sizeX * sizeZ));
			out.add(t);
		}

		return out;
	}
}
