package com.redtact.live.library;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * redtact に上げられるファイルかの決まり (#60)。redtact の {@code frontend/app/lib/upload.ts} (CIRCUIT_DUMP_MAX・DUMP_EXTS) と
 * {@code CircuitFilesForm.tsx} (MAX_FILES) に合わせる。ゲームに頼らない (単体テストで見る)。
 */
public final class RedtactUpload {
	/** 構造ファイル 1 件の上限 (redtact の CIRCUIT_DUMP_MAX = 50 MiB)。 */
	public static final long MAX_BYTES = 50L * 1024 * 1024;
	/** 1 つの回路に付けられるファイルの数 (redtact の MAX_FILES)。 */
	public static final int MAX_FILES = 20;

	private RedtactUpload() {
	}

	/** redtact に上げられない理由 (上げられるなら null)。 */
	public static @Nullable String unavailableReason(String fileName, long bytes) {
		String ext = Library.extension(fileName);

		if (!SchematicsBrowser.CIRCUIT_EXTS.contains(ext)) {
			return ext.isEmpty() ? Tr.t("拡張子の無いファイル は redtact に上げられません (.litematic・.schem・.schematic・.nbt・.mcstructure・.mcworld)")
					: Tr.f(".{0} は redtact に上げられません (.litematic・.schem・.schematic・.nbt・.mcstructure・.mcworld)", ext);
		}

		if (bytes > MAX_BYTES) {
			return Tr.f("redtact に上げられるのは 1 ファイル {0} までです (このファイルは {1})", mib(MAX_BYTES), mib(bytes));
		}

		return null;
	}

	/**
	 * ページのファイル選択 (input type=file の accept) にそのファイルが合うか。exts は Chromium が accept から作った拡張子の並び
	 * ({@code .litematic}、画像なら {@code .png;.jpg;…} のように ; でつないだもの)。filters と exts のどちらも空なら何でも合う
	 * (accept の無い欄)。
	 */
	public static boolean accepts(List<String> filters, List<String> exts, String fileName) {
		Set<String> allowed = new HashSet<>();

		for (List<String> list : List.of(filters, exts)) {
			for (String entry : list) {
				for (String e : entry.split(";")) {
					String s = e.strip().toLowerCase(Locale.ROOT);

					if (s.startsWith(".") && s.length() > 1) {
						allowed.add(s.substring(1));
					} else if (s.equals("*") || s.equals("*/*")) {
						return true;
					}
				}
			}
		}

		if (allowed.isEmpty()) {
			// 拡張子の指定が無い: accept が空なら何でも、MIME だけ (image/* など) なら拡張子の並びが来ていないので合わないとみる
			return filters.stream().allMatch(String::isBlank) && exts.stream().allMatch(String::isBlank);
		}

		return allowed.contains(Library.extension(fileName));
	}

	static String mib(long bytes) {
		return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
	}
}
