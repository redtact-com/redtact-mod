package com.redtact.live.structure;

import java.nio.file.Path;
import java.util.Set;

import com.redtact.live.i18n.Tr;
import com.redtact.live.library.Library;
import com.redtact.live.structure.bedrock.McstructureReader;
import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * 回路のファイルを形式ごとに読む入口 (#70)。統合版 (.mcstructure) は拡張子で、Java 版の形式は中身で見分ける。
 * どれも呼んだスレッドで読む (重いので画面のスレッドから呼ばない)。
 */
public final class StructureFiles {
	/** プレビューできる形式 (拡張子・小文字)。 */
	public static final Set<String> PREVIEWABLE = Set.of("litematic", "litematica", "schem", "schematic", "nbt", "mcstructure");

	private StructureFiles() {
	}

	/** プレビューできない理由 (できる形式なら null)。 */
	public static @Nullable String unsupportedReason(String fileName) {
		String ext = Library.extension(fileName);

		if (PREVIEWABLE.contains(ext)) {
			return null;
		}

		if (ext.equals("mcworld")) {
			return Tr.t("統合版のワールド (.mcworld) はプレビューできません");
		}

		return Tr.f("この形式 ({0}) はプレビューできません", ext.isEmpty() ? Tr.t("拡張子なし") : "." + ext);
	}

	/** ファイルを読む (中身はファイルの版のまま。直すのは {@link BlockStateFixer})。 */
	public static RawStructure read(Path file, StructureLimits limits) throws StructureException {
		String name = file.getFileName() == null ? "" : file.getFileName().toString();
		String reason = unsupportedReason(name);

		if (reason != null) {
			throw new StructureException(reason);
		}

		String ext = Library.extension(name);
		byte[] data = NbtFiles.readBytes(file, limits);

		if (ext.equals("mcstructure")) {
			return McstructureReader.read(data, limits);
		}

		CompoundTag root = NbtFiles.readJava(data, limits, "." + ext);

		// 拡張子と中身が違うファイルもある (litematic を .schem で保存するツールなど) ので、中身で見分ける
		if (root.contains("Regions")) {
			return LitematicReader.read(root, limits);
		}

		if (LegacySchematicReader.looksLike(root)) {
			return LegacySchematicReader.read(root, limits);
		}

		if (SpongeSchematicReader.looksLike(root)) {
			return SpongeSchematicReader.read(root, limits, ext.equals("schematic") ? "schematic" : "schem");
		}

		if (root.contains("size") && (root.contains("palette") || root.contains("palettes"))) {
			return StructureNbtReader.read(root, limits);
		}

		throw new StructureException(Tr.f(".{0} を読めません: 知らない形です", ext));
	}
}
