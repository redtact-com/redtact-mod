package com.redtact.live.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.redtact.live.RedtactLive;
import com.redtact.live.i18n.Tr;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.Times;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

/**
 * live の開催中の自分の回路を保管庫に入れる (#73)。最後に同期した版を MOD で .litematic に書き出し (中継サーバから取らないので
 * 複製の許可に関係ない)、{@code schematics/redtact/<回路名>-v<版>.litematic} に置いて origin {@link #ORIGIN} の行を足す。
 * 同じ回路の同じ版をもう一度入れたら同じファイルに上書きし、版が進めば別の行にする。
 * 「redtact に投稿」は入れたあと {@link LibraryActions#post} (redtact タブの左に保管庫・右に投稿の画面) に渡す。
 */
public final class LiveLibrary {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	/** 保管庫の行の出どころ (redtact の iOS アプリの保管庫 nativeLibrary.ts の "created" と同じ値)。 */
	public static final String ORIGIN = "created";
	private static final AtomicBoolean BUSY = new AtomicBoolean();
	private static volatile @Nullable LibraryEntry lastSaved;

	private LiveLibrary() {
	}

	/** 保管庫の行の目印 (同じ回路の同じ版は同じ行)。 */
	public static String sourceKey(String circuitId, int version) {
		return "live/" + circuitId + "/v" + version;
	}

	/** 保管庫でのファイル名の元 (同じ名前があれば Library が連番にする)。 */
	public static String fileName(String circuitName, int version) {
		return Library.safeName(circuitName.strip() + "-v" + version + ".litematic");
	}

	/** 入れている途中か。 */
	public static boolean busy() {
		return BUSY.get();
	}

	/** 直前に入れた行 (テスト・表示用)。 */
	public static @Nullable LibraryEntry lastSaved() {
		return lastSaved;
	}

	/** 保管庫へ入れられない理由 (入れられるなら null)。 */
	public static @Nullable String unavailableReason(SessionManager sessions) {
		return BUSY.get() ? Tr.t("保管庫に入れています") : sessions.exportUnavailableReason();
	}

	/** 保管庫へ入れる (クライアントスレッドから)。post なら入れたあと redtact の投稿の画面へ進む。 */
	public static void save(boolean post) {
		RedtactLive live = RedtactLive.get();
		String reason = live.sessions().exportUnavailableReason();

		if (reason != null) {
			Messages.error(reason);
			return;
		}

		if (!BUSY.compareAndSet(false, true)) {
			Messages.error(Tr.t("保管庫に入れています"));
			return;
		}

		Library library = live.library();
		live.sessions().exportSynced().thenApply(e -> {
			try {
				return add(library, e);
			} catch (IOException ex) {
				throw new CompletionException(ex);
			}
		}).whenComplete((entry, err) -> Minecraft.getInstance().execute(() -> {
			BUSY.set(false);

			if (err != null) {
				Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				LOGGER.warn("[redtact-live] 開催中の回路を保管庫に入れられません", cause);
				Messages.error(Tr.f("保管庫に入れられません: {0}", cause.getMessage()));
				return;
			}

			lastSaved = entry;

			if (post) {
				LibraryActions.post(library.file(entry));
			} else {
				Messages.notice(Tr.t("保管庫に入れました"), Tr.f("{0}/{1} (保管庫タブから配置・redtact に投稿できます)", Library.FOLDER, entry.storedName));
			}
		}));
	}

	/** 書き出した版を保管庫に入れる (どのスレッドからでも)。 */
	public static LibraryEntry add(Library library, SessionManager.Export e) throws IOException {
		String key = sourceKey(e.circuitId(), e.version());
		String name = library.reserve(fileName(e.circuitName(), e.version()), key);
		Path temp = null;

		try {
			temp = library.newTempFile();
			Files.write(temp, e.litematic());
		} catch (IOException | RuntimeException ex) {
			library.release(name);

			if (temp != null) {
				Files.deleteIfExists(temp);
			}

			throw ex;
		}

		LibraryEntry entry = new LibraryEntry();
		entry.storedName = name;
		entry.sourceKey = key;
		entry.label = e.circuitName() + " v" + e.version();
		entry.fileName = name;
		entry.savedAt = Times.now();
		entry.origin = ORIGIN;
		return library.add(temp, entry);
	}
}
