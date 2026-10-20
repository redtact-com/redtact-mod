package com.redtact.live.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.Blaze3D;
import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.copy.LitematicaSupport;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.i18n.Tr;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** 保管庫の行への操作 (配置・削除・フォルダを開く・redtact に投稿)。クライアントスレッドから呼ぶ。 */
public final class LibraryActions {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	/** Litematica の配置を覚えるときの鍵の頭 (セッションの ID とぶつからない)。 */
	public static final String KEY_PREFIX = "library:";
	/** 保管庫の外 (schematics の下のほかのフォルダ) のファイルの配置の鍵の頭 (#60。後ろは絶対パス)。 */
	public static final String FILE_KEY_PREFIX = "file:";
	/** フォルダを開くところ (テストでは差し替える)。 */
	private static volatile Consumer<Path> folderOpener = LibraryActions::openWithSystem;

	private LibraryActions() {
	}

	/** 配置できない理由 (できるなら null)。 */
	public static @Nullable String placeUnavailableReason(Library library, LibraryEntry e) {
		if (!LitematicaSupport.available()) {
			return Tr.t("Litematica が入っていません (保存とフォルダはそのまま使えます)");
		}

		if (!e.placeable()) {
			return "mcstructure".equals(e.extension()) ? Tr.f(".{0} は Litematica で読めません (統合版の形式)", e.extension())
					: Tr.f(".{0} は Litematica で読めません", e.extension());
		}

		if (!library.exists(e)) {
			return Tr.t("ファイルが見つかりません (消されたか、名前が変わりました)");
		}

		if (Minecraft.getInstance().player == null) {
			return Tr.t("ワールドに入ってから置けます");
		}

		return null;
	}

	/** Litematica の配置を足元に作る (同じファイルの配置があれば足元へ置き直す)。置けたら true。 */
	public static boolean place(Library library, LibraryEntry e) {
		String reason = placeUnavailableReason(library, e);

		if (reason != null) {
			Messages.error(reason);
			return false;
		}

		Minecraft mc = Minecraft.getInstance();
		BlockPos feet = mc.player.blockPosition();

		try {
			LitematicaSupport.Placed p = LitematicaSupport.placeFile(KEY_PREFIX + e.storedName, library.file(e), e.label + " (redtact)", feet);
			Messages.notice(Tr.t("Litematica に置きました"), p.replaced() ? Tr.f("{0}・前の配置を外して足元 ({1}) に置きました", e.label, feet.toShortString())
					: Tr.f("{0}・足元 ({1}) に置きました", e.label, feet.toShortString()));
			return true;
		} catch (RuntimeException | LinkageError ex) {
			LOGGER.warn("[redtact-live] 保管庫のファイルを Litematica に置けません", ex);
			Messages.error(Tr.f("Litematica に置けません: {0}", ex.getMessage()));
			return false;
		}
	}

	/** 保管庫から消す (ファイルも消す)。 */
	public static boolean delete(Library library, LibraryEntry e) {
		try {
			boolean removed = library.remove(e.storedName);
			Messages.info(removed ? Tr.f("保管庫から消しました: {0}", e.storedName) : Tr.f("もう消えていました: {0}", e.storedName));
			return removed;
		} catch (IOException | RuntimeException ex) {
			Messages.error(Tr.f("消せません: {0}", ex.getMessage()));
			return false;
		}
	}

	/** フォルダを OS のファイルの画面で開く (#60。保管庫タブの「フォルダを開く」はいま見ているフォルダ)。 */
	public static void openFolder(Path dir) {
		folderOpener.accept(dir);
	}

	/** フォルダを開くところを差し替える (テスト用。null で元に戻す)。 */
	public static void setFolderOpener(@Nullable Consumer<Path> opener) {
		folderOpener = opener == null ? LibraryActions::openWithSystem : opener;
	}

	private static void openWithSystem(Path dir) {
		try {
			Blaze3D.openPath(dir);
		} catch (RuntimeException ex) {
			Messages.error(Tr.f("フォルダを開けません: {0}", ex.getMessage()));
		}
	}

	// ---- 保管庫の外のファイル (schematics の下のほかのフォルダ。#60)

	/** そのファイルを配置できない理由 (できるなら null)。 */
	public static @Nullable String placeUnavailableReason(Path file) {
		if (!LitematicaSupport.available()) {
			return Tr.t("Litematica が入っていません");
		}

		String ext = Library.extension(file.getFileName().toString());

		if (!Library.PLACEABLE.contains(ext)) {
			return "mcstructure".equals(ext) ? Tr.f(".{0} は Litematica で読めません (統合版の形式)", ext)
					: "mcworld".equals(ext) ? Tr.f(".{0} は Litematica で読めません (統合版のワールド)", ext) : Tr.f(".{0} は Litematica で読めません", ext);
		}

		if (!Files.isRegularFile(file)) {
			return Tr.t("ファイルが見つかりません (消されたか、名前が変わりました)");
		}

		if (Minecraft.getInstance().player == null) {
			return Tr.t("ワールドに入ってから置けます");
		}

		return null;
	}

	/** Litematica の配置を足元に作る (同じファイルの配置があれば足元へ置き直す)。置けたら true。 */
	public static boolean place(Path file) {
		String reason = placeUnavailableReason(file);

		if (reason != null) {
			Messages.error(reason);
			return false;
		}

		Minecraft mc = Minecraft.getInstance();
		BlockPos feet = mc.player.blockPosition();
		Path abs = file.toAbsolutePath().normalize();
		String name = LibraryDownloads.stem(abs.getFileName().toString());

		try {
			LitematicaSupport.Placed p = LitematicaSupport.placeFile(FILE_KEY_PREFIX + abs, abs, name, feet);
			Messages.notice(Tr.t("Litematica に置きました"), p.replaced() ? Tr.f("{0}・前の配置を外して足元 ({1}) に置きました", name, feet.toShortString())
					: Tr.f("{0}・足元 ({1}) に置きました", name, feet.toShortString()));
			return true;
		} catch (RuntimeException | LinkageError ex) {
			LOGGER.warn("[redtact-live] {} を Litematica に置けません", abs, ex);
			Messages.error(Tr.f("Litematica に置けません: {0}", ex.getMessage()));
			return false;
		}
	}

	/** redtact に投稿できない理由 (できるなら null)。 */
	public static @Nullable String postUnavailableReason(Path file) {
		long bytes;

		try {
			bytes = Files.size(file);
		} catch (IOException | RuntimeException e) {
			return Tr.t("ファイルが見つかりません (消されたか、名前が変わりました)");
		}

		return RedtactUpload.unavailableReason(file.getFileName().toString(), bytes);
	}

	/**
	 * redtact に投稿する: ゲーム内の redtact (MCEF Modern) があれば redtact タブの左半分に保管庫を出し、そのファイルを選んで
	 * 投稿の画面 ({@code /circuits/new}) を開く (左からページのファイルの欄へドラッグするか、ページの「ファイルを追加」で入る)。
	 * 無ければ外のブラウザで投稿の画面を開き、ファイルのあるフォルダも開く (そこからブラウザへドラッグ)。
	 */
	public static void post(Path file) {
		String reason = postUnavailableReason(file);

		if (reason != null) {
			Messages.error(reason);
			return;
		}

		Path abs = file.toAbsolutePath().normalize();
		String name = abs.getFileName().toString();
		String url = RedtactLinks.newCircuitPage(BrowserSupport.home());
		BrowserSupport.State state = BrowserSupport.status().state();

		if (state == BrowserSupport.State.MISSING || state == BrowserSupport.State.FAILED) {
			BrowserSupport.openExternal(url);
			openFolder(abs.getParent());
			Messages.notice(Tr.t("外のブラウザで投稿の画面を開きました"), Tr.f("開いたフォルダから {0} を投稿の画面のファイルの欄へドラッグしてください", name));
			return;
		}

		LibraryNav.go(abs.getParent());
		RedtactLive.get().fileSelection().set(List.of(abs));
		LibraryNav.setPaneShown(true);
		BrowserSupport.open(url);
		Messages.notice(Tr.t("投稿の画面を開きました"), Tr.f("左の保管庫の {0} を右のページのファイルの欄へドラッグ (ページの「ファイルを追加」を押しても入ります)", name));
	}
}
