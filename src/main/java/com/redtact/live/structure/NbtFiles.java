package com.redtact.live.structure;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.redtact.live.i18n.Tr;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;

/** 回路のファイルの中身を上限付きで読む (#70)。 */
final class NbtFiles {
	private NbtFiles() {
	}

	/** ファイルをそのまま読む (先に大きさを見る)。 */
	static byte[] readBytes(Path file, StructureLimits limits) throws StructureException {
		long size;

		try {
			size = Files.size(file);
		} catch (NoSuchFileException e) {
			throw new StructureException(Tr.t("ファイルがありません"));
		} catch (IOException | RuntimeException e) {
			throw new StructureException(Tr.f("ファイルを読めません ({0})", e.getClass().getSimpleName()));
		}

		if (size > limits.maxFileBytes()) {
			throw new StructureException(Tr.f("ファイルが大きすぎるので読みません ({0}。{1} まで)", mb(size), mb(limits.maxFileBytes())));
		}

		try (InputStream in = Files.newInputStream(file)) {
			byte[] data = in.readNBytes((int) limits.maxFileBytes() + 1);

			if (data.length > limits.maxFileBytes()) {
				throw new StructureException(Tr.f("ファイルが大きすぎるので読みません ({0} まで)", mb(limits.maxFileBytes())));
			}

			return data;
		} catch (IOException | RuntimeException e) {
			throw new StructureException(Tr.f("ファイルを読めません ({0})", e.getClass().getSimpleName()));
		}
	}

	/** Java 版の NBT (gzip ならほどく) を読む。中身の量は {@link StructureLimits#maxNbtBytes()} まで。 */
	static CompoundTag readJava(byte[] data, StructureLimits limits, String what) throws StructureException {
		NbtAccounter accounter = NbtAccounter.create(limits.maxNbtBytes());

		try {
			if (isGzip(data)) {
				return NbtIo.readCompressed(new ByteArrayInputStream(data), accounter);
			}

			return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)), accounter);
		} catch (NbtAccounterException e) {
			throw new StructureException(Tr.f("{0} の中身が大きすぎるので読みません", what));
		} catch (IOException | RuntimeException e) {
			throw new StructureException(Tr.f("{0} を読めません (壊れているか、形式が違います)", what));
		}
	}

	static boolean isGzip(byte[] data) {
		return data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
	}

	private static String mb(long bytes) {
		return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
	}
}
