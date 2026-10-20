package com.redtact.live.library;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * 保管庫の 1 件 ({@code schematics/redtact/library.json} の 1 要素)。項目は redtact の iOS アプリの保管庫
 * (redtact {@code frontend/app/lib/nativeLibrary.ts} の LibraryEntry) と同じにし、MOD だけの項目を足す。
 */
public final class LibraryEntry {
	/** redtact のファイルの ID。API で分からなければ null。 */
	public @Nullable String fileId;
	/** 保管庫のフォルダの中のファイル名 (Litematica の読み込み画面にもこの名前で出る)。 */
	public String storedName = "";
	/** 表示名 (回路の題)。 */
	public String label = "";
	/** redtact でのファイル名 (拡張子込み)。 */
	public String fileName = "";
	public @Nullable String circuitId;
	public long bytes;
	/** 保存した日時 (RFC 3339)。 */
	public String savedAt = "";
	/** iOS と同じ。"downloaded" (redtact から取ってきた) か "created" (live の開催中の自分の回路を入れた。#73)。 */
	public String origin = "downloaded";
	/**
	 * MOD だけの項目: 取ってきたファイルの目印 ({@code <回路 ID>/<redtact の保存先での名前>})。同じファイルを
	 * もう一度取ったら同じ行を置き換える (fileId はダウンロードの時点では分からないため)。期限付き URL は残さない。
	 */
	public @Nullable String sourceKey;

	public LibraryEntry() {
	}

	/** live の開催中の自分の回路を入れた行か (#73。redtact に投稿できる)。 */
	public boolean created() {
		return LiveLibrary.ORIGIN.equals(origin);
	}

	/** 拡張子 (小文字、点なし)。無ければ空。 */
	public String extension() {
		return Library.extension(storedName);
	}

	/** Litematica で読める形式か (.litematic・.schem・.schematic・.nbt)。 */
	public boolean placeable() {
		return Library.PLACEABLE.contains(extension());
	}

	/** 表示用の形式の名前。 */
	public String formatLabel() {
		String ext = extension();
		return ext.isEmpty() ? "?" : ext.toLowerCase(Locale.ROOT);
	}
}
