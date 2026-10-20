package com.redtact.live.library;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * 保管庫タブの検索 (#70)。いま見ているフォルダとその下から、名前に語がすべて入っているフォルダと回路のファイルを探す
 * (保管庫 {@code schematics/redtact/} の回路は redtact の題と redtact でのファイル名でも引ける)。
 * 語は空白 (全角も) で区切り、大文字小文字・全角半角は区別しない (NFKC)。隠しフォルダとリンクの先は見ない。
 * 見るのは {@value #MAX_DEPTH} 段の下まで・{@value #MAX_VISITED} 件まで、出すのは {@value #MAX_RESULTS} 件まで。MC に依存しない。
 */
public final class LibrarySearch {
	public static final int MAX_RESULTS = 300;
	public static final int MAX_VISITED = 20_000;
	public static final int MAX_DEPTH = 16;

	/** 見つけた 1 件。location は入っているフォルダ (パス欄と同じく schematics からの {@code builds/2026/} の形)、entry は保管庫の索引の行。 */
	public record Hit(SchematicsBrowser.Item item, String location, @Nullable LibraryEntry entry) {
	}

	/** 探した結果。truncated は上限で止めた、error は探せなかった理由。 */
	public record Result(Path root, String query, List<Hit> folders, List<Hit> files, boolean truncated, @Nullable String error) {
		public int count() {
			return folders.size() + files.size();
		}
	}

	private LibrarySearch() {
	}

	/** 字を語に分ける (空白・全角空白で区切り、NFKC で揃えて小文字に)。 */
	public static List<String> terms(@Nullable String query) {
		List<String> out = new ArrayList<>();

		if (query != null) {
			for (String t : normalize(query).split("[\\s\\u3000]+")) {
				if (!t.isEmpty()) {
					out.add(t);
				}
			}
		}

		return out;
	}

	static String normalize(String s) {
		return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
	}

	/** 入っているフォルダの書き方 (schematics からの場所 + /。いちばん上は schematics/)。 */
	static String location(SchematicsBrowser browser, Path dir) {
		String rel = browser.relative(dir);
		return rel.isEmpty() ? browser.rootName() + "/" : rel + "/";
	}

	/** どの語もどれかの字に入っているか (語が無ければ false)。 */
	public static boolean matches(List<String> terms, @Nullable String... texts) {
		if (terms.isEmpty()) {
			return false;
		}

		StringBuilder hay = new StringBuilder();

		for (String t : texts) {
			if (t != null) {
				hay.append(normalize(t)).append('\n');
			}
		}

		for (String term : terms) {
			if (hay.indexOf(term) < 0) {
				return false;
			}
		}

		return true;
	}

	/**
	 * root とその下を探す (呼んだスレッドで。重いので画面のスレッドから呼ばない)。
	 *
	 * @param libraryDir  保管庫のフォルダ (この中のファイルは索引の題でも引く)
	 * @param entryByName 保管庫の中のファイル名 → 索引の行 (無ければ null)
	 * @param cancelled   true になったら途中でやめる
	 */
	public static Result search(Path root, String query, SchematicsBrowser browser, Path libraryDir, Function<String, @Nullable LibraryEntry> entryByName,
			BooleanSupplier cancelled) {
		List<String> terms = terms(query);
		List<Hit> folders = new ArrayList<>();
		List<Hit> files = new ArrayList<>();
		boolean[] truncated = {false};
		int[] visited = {0};
		Path start = root.toAbsolutePath().normalize();
		Path lib = libraryDir.toAbsolutePath().normalize();

		if (terms.isEmpty()) {
			return new Result(start, query, List.of(), List.of(), false, null);
		}

		try {
			Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					if (cancelled.getAsBoolean()) {
						return FileVisitResult.TERMINATE;
					}

					if (dir.equals(start)) {
						return FileVisitResult.CONTINUE;
					}

					String name = dir.getFileName().toString();

					if (name.startsWith(".")) {
						return FileVisitResult.SKIP_SUBTREE;
					}

					if (matches(terms, name)) {
						folders.add(new Hit(new SchematicsBrowser.Item(dir, name, true, 0, attrs.lastModifiedTime().toMillis()), location(browser, dir.getParent()), null));
					}

					return count();
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (cancelled.getAsBoolean()) {
						return FileVisitResult.TERMINATE;
					}

					String name = file.getFileName().toString();

					if (attrs.isDirectory()) {
						// 深さの上限で中へ入らないフォルダも、名前は引けるようにする
						if (!name.startsWith(".") && matches(terms, name)) {
							folders.add(new Hit(new SchematicsBrowser.Item(file, name, true, 0, attrs.lastModifiedTime().toMillis()), location(browser, file.getParent()), null));
						}

						return count();
					}

					if (!attrs.isRegularFile() || name.startsWith(".") || !SchematicsBrowser.CIRCUIT_EXTS.contains(Library.extension(name))) {
						return count();
					}

					LibraryEntry entry = lib.equals(file.getParent()) ? entryByName.apply(name) : null;

					if (matches(terms, name, entry == null ? null : entry.label, entry == null ? null : entry.fileName)) {
						files.add(new Hit(new SchematicsBrowser.Item(file, name, false, attrs.size(), attrs.lastModifiedTime().toMillis()), location(browser, file.getParent()), entry));
					}

					return count();
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE; // 読めないフォルダは飛ばす
				}

				private FileVisitResult count() {
					if (++visited[0] >= MAX_VISITED || folders.size() + files.size() >= MAX_RESULTS) {
						truncated[0] = true;
						return FileVisitResult.TERMINATE;
					}

					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException | RuntimeException e) {
			return new Result(start, query, List.of(), List.of(), false, Tr.f("探せません ({0})", e.getClass().getSimpleName()));
		}

		Comparator<Hit> order = Comparator.comparing(Hit::location, SchematicsBrowser.NAME_ORDER).thenComparing(h -> h.item().name(), SchematicsBrowser.NAME_ORDER);
		folders.sort(order);
		files.sort(order);
		return new Result(start, query, List.copyOf(folders), List.copyOf(files), truncated[0], null);
	}

	/**
	 * 別のスレッドで探す。最後に頼んだものだけを探し (前の頼みは途中でやめる)、結果を 1 つ持つ。
	 */
	public static final class Service {
		private record Request(Path root, String query, long stamp) {
		}

		private final SchematicsBrowser browser;
		private final Library library;
		private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-search").factory());
		private final AtomicInteger generation = new AtomicInteger();
		private @Nullable Request wanted;
		private @Nullable Request done;
		private @Nullable Result latest;

		public Service(SchematicsBrowser browser, Library library) {
			this.browser = browser;
			this.library = library;
		}

		/** root の下を query で探す (同じ頼みなら何もしない)。stamp は中身が変わった合図 (保管庫の版など)。 */
		public synchronized void request(Path root, String query, long stamp) {
			Request req = new Request(root.toAbsolutePath().normalize(), query, stamp);

			if (req.equals(wanted)) {
				return;
			}

			wanted = req;
			int gen = generation.incrementAndGet();
			executor.execute(() -> {
				if (generation.get() != gen) {
					return; // もっと新しい頼みがある
				}

				// 索引は 1 回だけ読む (Windows では大文字小文字の違うファイル名も同じファイル)
				Map<String, LibraryEntry> byName = new HashMap<>();

				for (LibraryEntry e : library.list()) {
					byName.put(e.storedName.toLowerCase(Locale.ROOT), e);
				}

				Result r = search(req.root(), req.query(), browser, library.dir(), name -> byName.get(name.toLowerCase(Locale.ROOT)),
						() -> generation.get() != gen);

				synchronized (this) {
					if (generation.get() == gen) {
						latest = r;
						done = req;
					}
				}
			});
		}

		/** その場所と字の、いちばん新しい結果 (中身が変わって探し直している間は前の結果)。無ければ null。 */
		public synchronized @Nullable Result latest(Path root, String query) {
			Result r = latest;
			return r != null && r.root().equals(root.toAbsolutePath().normalize()) && r.query().equals(query) ? r : null;
		}

		/** 頼んだものをまだ探している。 */
		public synchronized boolean busy() {
			return wanted != null && !Objects.equals(wanted, done);
		}

		/** 探すのをやめ、結果を捨てる (検索欄を空にしたとき)。 */
		public synchronized void clear() {
			generation.incrementAndGet();
			wanted = null;
			done = null;
			latest = null;
		}

		public void shutdown() {
			executor.shutdownNow();
		}
	}
}
