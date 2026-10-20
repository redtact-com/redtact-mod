package com.redtact.live.library;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.redtact.live.i18n.Tr;
import com.redtact.live.store.Times;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ゲーム内の redtact のダウンロードを保管庫に入れる (#46)。埋め込みのブラウザ (MCEF) のダウンロードの知らせから呼ばれる
 * (このクラスは MCEF の型を使わない)。取り途中は一時フォルダに書かせ、取り終わったら redtact の API で表示名と
 * ファイルの ID を調べてから保管庫に入れる。どのスレッドから呼んでもよい。
 */
public final class LibraryDownloads {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private final Library library;
	private final RedtactApi api;
	private final Supplier<URI> home;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-library").factory());
	private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
	private volatile @Nullable LibraryEntry lastSaved;

	/** 取り途中の 1 件。 */
	private record Pending(int id, String storedName, Path temp, RedtactLinks.@Nullable FileRef ref, @Nullable String pageCircuitId,
			String suggested, URI home) {
	}

	public LibraryDownloads(Library library, RedtactApi api, Supplier<URI> home) {
		this.library = library;
		this.api = api;
		this.home = home;
	}

	public Library library() {
		return library;
	}

	/** 取り途中の数。 */
	public int inFlight() {
		return pending.size();
	}

	/** 直前に保管庫に入れたもの (テスト・表示用)。 */
	public @Nullable LibraryEntry lastSaved() {
		return lastSaved;
	}

	/**
	 * ダウンロードが始まる前。書き込み先 (一時ファイル) を返す。null ならそのダウンロードは止める。
	 *
	 * @param id ブラウザのダウンロードの番号
	 * @param url ダウンロードの URL (期限付き URL。記録には署名を落として残す)
	 * @param suggestedName ブラウザが決めたファイル名 (Content-Disposition)
	 * @param pageUrl そのときのページ (回路のページなら回路 ID を取る)
	 * @param totalBytes 分かっていれば大きさ (分からなければ 0 以下)
	 */
	public @Nullable Path begin(int id, @Nullable String url, @Nullable String suggestedName, @Nullable String pageUrl, long totalBytes) {
		if (totalBytes > Library.MAX_BYTES) {
			Messages.error(Tr.f("ファイルが大きすぎるので保存しません ({0}。上限 {1})", mib(totalBytes), mib(Library.MAX_BYTES)));
			return null;
		}

		URI h = home.get();
		RedtactLinks.FileRef ref = RedtactLinks.fileRef(url, h);
		String suggested = suggestedName != null && !suggestedName.isBlank() ? suggestedName : ref != null ? ref.storedFileName() : "circuit";

		try {
			String name = library.reserve(suggested, ref == null ? null : ref.sourceKey());
			Path temp = library.newTempFile();
			pending.put(id, new Pending(id, name, temp, ref, RedtactLinks.circuitIdFromPage(pageUrl), suggested, h));
			LOGGER.info("[redtact-live] 保管庫へダウンロード: {} → {}", RedtactLinks.redact(url), name);
			Messages.info(Tr.f("保管庫に保存しています: {0}", name));
			return temp;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("[redtact-live] ダウンロードを始められません", e);
			Messages.error(Tr.f("保管庫に保存できません: {0}", e.getMessage()));
			return null;
		}
	}

	/** 取っている途中。false を返したら止める (上限を超えた)。 */
	public boolean progress(int id, long receivedBytes) {
		if (receivedBytes <= Library.MAX_BYTES || !pending.containsKey(id)) {
			return true;
		}

		Messages.error(Tr.f("ファイルが大きすぎるので保存をやめました (上限 {0})", mib(Library.MAX_BYTES)));
		return false;
	}

	/** 取り終わった。 */
	public void complete(int id) {
		Pending p = pending.remove(id);

		if (p != null) {
			worker.execute(() -> finish(p));
		}
	}

	/** 止まった (取り消し・失敗)。 */
	public void canceled(int id) {
		Pending p = pending.remove(id);

		if (p == null) {
			return;
		}

		library.release(p.storedName());

		try {
			Files.deleteIfExists(p.temp());
		} catch (IOException ignored) {
			// 次の起動で消えなくても害は無い (schematics の外)
		}

		Messages.error(Tr.f("ダウンロードが止まりました: {0}", p.storedName()));
	}

	private void finish(Pending p) {
		LibraryEntry e = new LibraryEntry();
		e.storedName = p.storedName();
		e.sourceKey = p.ref() == null ? null : p.ref().sourceKey();
		e.circuitId = p.ref() != null ? p.ref().circuitId() : p.pageCircuitId();
		e.fileName = p.suggested();
		e.label = stem(p.suggested());
		e.savedAt = Times.now();
		e.origin = "downloaded";
		String apiBase = RedtactLinks.apiBase(p.home());

		if (apiBase != null && e.circuitId != null) {
			RedtactApi.Detail d = api.detail(apiBase, e.circuitId);

			if (d != null) {
				if (!d.title().isBlank()) {
					e.label = d.title();
				}

				RedtactApi.FileInfo f = p.ref() == null ? null : d.fileByStoredName(p.ref().storedFileName());

				if (f != null) {
					e.fileId = f.fileId();

					if (!f.name().isBlank()) {
						e.fileName = f.name();
					}
				}
			}
		}

		try {
			library.add(p.temp(), e);
			lastSaved = e;
			Messages.notice(Tr.t("保管庫に保存しました"), e.placeable()
					? Tr.f("{0} → {1}/{2} (redtact タブの「配置」で Litematica に置けます)", e.label, Library.FOLDER, e.storedName)
					: e.label + " → " + Library.FOLDER + "/" + e.storedName);
		} catch (IOException | RuntimeException ex) {
			LOGGER.warn("[redtact-live] 保管庫に入れられません", ex);
			Messages.error(Tr.f("保管庫に入れられません: {0}", ex.getMessage()));
		}
	}

	/** 拡張子を除いた名前。 */
	static String stem(String name) {
		String ext = Library.extension(name);
		return ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
	}

	private static String mib(long bytes) {
		return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
	}

	public void shutdown() {
		worker.shutdown();
	}
}
