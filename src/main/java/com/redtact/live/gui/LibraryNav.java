package com.redtact.live.gui;

import java.nio.file.Path;

import com.redtact.live.RedtactLive;
import com.redtact.live.library.SchematicsBrowser;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;

/**
 * 保管庫のいま見ているフォルダと、redtact タブの横に保管庫を出しているか (#60)。保管庫タブと redtact タブの横の保管庫で
 * 同じフォルダを見る。保管庫タブの検索の字とプレビューに選んだファイル (#70) も持つ。ゲームを閉じるまで覚える。
 * 最初は保管庫 ({@code schematics/redtact/})。
 */
public final class LibraryNav {
	/** いま見ているフォルダ (null なら保管庫)。 */
	private static @Nullable Path current;
	/** redtact タブの左半分に保管庫を出しているか (Chromium のスレッドのファイル選択からも読む)。 */
	private static volatile boolean paneShown;
	/** 行き来するたびに増やす (画面が一覧を作り直す合図)。 */
	private static int revision;
	/** 保管庫タブの検索欄の字 (#70。空なら検索しない)。 */
	private static String query = "";
	/** 保管庫タブでプレビューに選んだファイル (#70。無ければ null)。 */
	private static @Nullable Path selected;
	/** 保管庫タブの右に立体のプレビューを出しているか (#70。ヘッダの右端のトグル)。 */
	private static boolean previewShown = true;

	private LibraryNav() {
	}

	private static SchematicsBrowser schematics() {
		return RedtactLive.get().schematics();
	}

	/** 保管庫 (redtact から取ってきた回路) のフォルダ。 */
	public static Path libraryDir() {
		return RedtactLive.get().library().dir().toAbsolutePath().normalize();
	}

	/** いま見ているフォルダ。schematics の場所が変わって外になっていたら保管庫に戻す。 */
	public static Path current() {
		Path c = current;

		if (c == null || !schematics().contains(c)) {
			return libraryDir();
		}

		return c;
	}

	/** いま見ているのが保管庫 ({@code schematics/redtact/}) か。 */
	public static boolean inLibrary() {
		return current().equals(libraryDir());
	}

	public static int revision() {
		return revision;
	}

	/** そのフォルダへ移る (開けなければ理由を知らせて移らない)。移れたら true。保管庫はまだ無くても移れる。 */
	public static boolean go(Path dir) {
		Path d = dir.toAbsolutePath().normalize();
		String reason = d.equals(libraryDir()) ? null : schematics().openReason(d);

		if (reason != null) {
			Messages.error(reason);
			return false;
		}

		if (!d.equals(current())) {
			current = d;
			revision++;
		}

		return true;
	}

	/** パス欄の字のフォルダへ移る。 */
	public static boolean go(String text) {
		Path d;

		try {
			d = schematics().resolve(text);
		} catch (IllegalArgumentException e) {
			Messages.error(e.getMessage());
			return false;
		}

		return go(d);
	}

	/** 一つ上へ (schematics のいちばん上なら何もしない)。 */
	public static boolean up() {
		Path p = schematics().parent(current());
		return p != null && go(p);
	}

	/** 中身が変わったので一覧を読み直させる。 */
	public static void changed() {
		revision++;
	}

	/** 保管庫タブの検索欄の字。 */
	public static String query() {
		return query;
	}

	public static void setQuery(String text) {
		query = text == null ? "" : text;
	}

	/** 保管庫タブでプレビューに選んだファイル。 */
	public static @Nullable Path selected() {
		return selected;
	}

	public static void select(@Nullable Path file) {
		selected = file == null ? null : file.toAbsolutePath().normalize();
	}

	/** 保管庫タブの右に立体のプレビューを出しているか。 */
	public static boolean previewShown() {
		return previewShown;
	}

	public static void setPreviewShown(boolean shown) {
		previewShown = shown;
	}

	public static boolean paneShown() {
		return paneShown;
	}

	public static void setPaneShown(boolean shown) {
		paneShown = shown;
	}
}
