package com.redtact.live.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.redtact.live.i18n.Tr;
import com.redtact.live.state.CircuitState;
import com.redtact.live.structure.BlockStateFixer;
import com.redtact.live.structure.RawStructure;
import com.redtact.live.structure.StructureException;
import com.redtact.live.structure.StructureFiles;
import com.redtact.live.structure.StructureLimits;
import com.redtact.live.structure.bedrock.McstructureReader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 保管庫のプレビュー (#70): schematics の下の回路のファイルを専用のスレッドで読み、いまの版のブロックに直して描く用意をして持つ。
 * ファイルの大きさか更新日時が変わったら読み直す (読み直す間は前の絵を見せる)。最近見た {@value #MAX_ENTRIES} 件だけ持つ。
 * 最後に頼んだファイルを先に読み、行を次々に押したときの古い頼みは飛ばす。
 */
public final class FilePreviewStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final int MAX_ENTRIES = 8;

	/** 読めた回路の要点。 */
	public record Info(String format, int sizeX, int sizeY, int sizeZ, int nonAir, List<String> notes) {
	}

	/**
	 * @param bytes    読んだ (読もうとした) ときのファイルの大きさ
	 * @param modified 同じく更新日時
	 * @param circuit  描くもの。LOADING で前の中身があればそれ
	 * @param message  ERROR のときの説明 (そのまま画面に出す)
	 */
	public record Entry(Path path, long bytes, long modified, PreviewStore.Status status, @Nullable PreviewCircuit circuit, @Nullable Info info,
			@Nullable String message) {
	}

	private final StructureLimits limits;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-file-preview").factory());
	private final Map<Path, FilePreviewStore.Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Path, FilePreviewStore.Entry> eldest) {
			return size() > MAX_ENTRIES;
		}
	};
	private @Nullable Path wanted;
	private boolean warmed;

	public FilePreviewStore(StructureLimits limits) {
		this.limits = limits;
	}

	private static Path key(Path p) {
		return p.toAbsolutePath().normalize();
	}

	/**
	 * 統合版の対応表とデータの直し方 (DataFixer) の準備を、読むのと同じスレッドで先に済ませる (保管庫を開いたとき。#93)。
	 * どちらも最初の 1 回だけ重く、最初の .mcstructure の読み込みだけ遅かった (保管庫を開いて 1 秒後に選んで 103〜131 ms → 60〜85 ms。
	 * 準備は 20 ms ほど)。2 回目からは何もしない。
	 */
	public synchronized void warmUp() {
		if (warmed) {
			return;
		}

		warmed = true;
		executor.execute(() -> {
			try {
				McstructureReader.warmUp();
				BlockStateFixer.warmUp();
			} catch (RuntimeException e) {
				LOGGER.warn("[redtact-live] プレビューの準備に失敗 (読むときにもう一度試す)", e);
			}
		});
	}

	public synchronized @Nullable Entry get(Path path) {
		return entries.get(key(path));
	}

	/** まだ読んでいない・ファイルが変わったなら読む (画面のスレッドから呼んでよい。ファイルの属性を見るだけ)。 */
	public void request(Path path) {
		Path p = key(path);
		BasicFileAttributes a;

		try {
			a = Files.readAttributes(p, BasicFileAttributes.class);
		} catch (IOException | RuntimeException e) {
			a = null;
		}

		synchronized (this) {
			wanted = p;
			Entry e = entries.get(p);

			if (a == null || !a.isRegularFile()) {
				if (e == null || e.status() != PreviewStore.Status.ERROR || e.bytes() != -1) {
					entries.put(p, new Entry(p, -1, -1, PreviewStore.Status.ERROR, null, null, Tr.t("ファイルがありません")));
				}

				return;
			}

			long bytes = a.size();
			long modified = a.lastModifiedTime().toMillis();

			if (e != null && e.bytes() == bytes && e.modified() == modified) {
				return; // 同じ中身 (読み中・読めた・読めなかった)
			}

			entries.put(p, new Entry(p, bytes, modified, PreviewStore.Status.LOADING, e == null ? null : e.circuit(), e == null ? null : e.info(), null));
			executor.execute(() -> load(p, bytes, modified));
		}
	}

	private void load(Path p, long bytes, long modified) {
		synchronized (this) {
			Entry e = entries.get(p);

			if (e == null || e.status() != PreviewStore.Status.LOADING || e.bytes() != bytes || e.modified() != modified) {
				return; // もう読んだ・ファイルが変わって頼み直した
			}

			if (!p.equals(wanted)) {
				entries.remove(p); // ほかのファイルを頼まれた。次に頼まれたら読む
				return;
			}
		}

		Entry result;

		try {
			RawStructure raw = StructureFiles.read(p, limits);
			CircuitState state = BlockStateFixer.toState(raw);
			PreviewCircuit c = PreviewCircuit.resolve(p.toString(), 0, state);
			Info info = new Info(raw.format(), raw.sizeX(), raw.sizeY(), raw.sizeZ(), c.model().nonAir(), raw.notes());
			result = new Entry(p, bytes, modified, PreviewStore.Status.READY, c, info, null);
		} catch (StructureException e) {
			result = new Entry(p, bytes, modified, PreviewStore.Status.ERROR, null, null, e.getMessage());
		} catch (RuntimeException e) {
			LOGGER.warn("[redtact-live] プレビューを作れません: {}", p, e);
			result = new Entry(p, bytes, modified, PreviewStore.Status.ERROR, null, null, Tr.f("プレビューを作れません ({0})", e.getClass().getSimpleName()));
		}

		synchronized (this) {
			Entry now = entries.get(p);

			// 読んでいる間にファイルが変わって頼み直していたら、古い結果で上書きしない
			if (now == null || now.bytes() == bytes && now.modified() == modified) {
				entries.put(p, result);
			}
		}
	}

	public void shutdown() {
		executor.shutdownNow();
	}
}
