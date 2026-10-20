package com.redtact.live.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * redtact から取ってきた回路ファイルの保管庫 (#46)。iOS アプリの保管庫 (redtact {@code frontend/app/lib/nativeLibrary.ts}) と
 * 同じく、フォルダにファイルを置いて索引 ({@code library.json}、項目の配列) で一覧・削除する。
 *
 * <p>フォルダは Litematica の schematics フォルダの下の {@code redtact/} なので、Litematica の読み込み画面にもそのまま出る。
 * そのためファイル名は ID を付けない読める名前にし、同じ名前があれば「名前 (2).拡張子」にする。
 * 同じファイル (sourceKey が同じ) をもう一度取ったら、同じファイル名に上書きして行を置き換える (iOS と同じく二重に増やさない)。
 * 利用者が自分で置いたファイル (索引に無いもの) は上書きしない。取り途中のファイルは schematics の外 (一時フォルダ) に置く。
 *
 * <p>索引が読めなくてもファイルは消さない。壊れた索引は {@code library.json.broken} に移してから書き直す。
 * どのメソッドもスレッドをまたいで呼べる (同期する)。
 */
public final class Library {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** schematics の下のフォルダの名前。 */
	public static final String FOLDER = "redtact";
	public static final String INDEX = "library.json";
	/** Litematica が読める拡張子 (Litematica 0.29 の FileType: litematic・Schematica・Sponge・バニラの構造物)。 */
	static final Set<String> PLACEABLE = Set.of("litematic", "schematic", "schem", "nbt");
	/** 1 つのファイルの上限 (これより大きいダウンロードは止める)。 */
	public static final long MAX_BYTES = 256L * 1024 * 1024;
	private static final int MAX_NAME = 100;
	private static final Set<String> WINDOWS_RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6",
			"com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	private final Supplier<Path> dir;
	private final Path tempDir;
	/** 取り途中のダウンロードが押さえているファイル名 (小文字)。 */
	private final Set<String> reserved = new HashSet<>();
	/** 索引を変えるたびに増やす (画面が一覧を読み直す合図)。 */
	private volatile int revision;

	/**
	 * @param dir 保管庫のフォルダ (呼ぶたびに求める。Litematica の設定で schematics の場所が変わりうる)
	 * @param tempDir 取り途中のファイルを置くフォルダ (schematics の外)
	 */
	public Library(Supplier<Path> dir, Path tempDir) {
		this.dir = dir;
		this.tempDir = tempDir;
	}

	public Path dir() {
		return dir.get();
	}

	/** Litematica で読める拡張子か (小文字・点なし)。 */
	public static boolean isPlaceable(String ext) {
		return PLACEABLE.contains(ext);
	}

	public int revision() {
		return revision;
	}

	/** 一覧。新しい順 (同じ時刻なら索引の順のまま。並べ替えのたびに行が入れ替わらないように)。 */
	public synchronized List<LibraryEntry> list() {
		List<LibraryEntry> entries = new ArrayList<>(readIndex(dir()));
		entries.sort(Comparator.comparing((LibraryEntry e) -> e.savedAt).reversed());
		return entries;
	}

	public synchronized @Nullable LibraryEntry find(String storedName) {
		for (LibraryEntry e : readIndex(dir())) {
			if (e.storedName.equals(storedName)) {
				return e;
			}
		}

		return null;
	}

	/** その行のファイル (保管庫のフォルダの中だけ。索引に変な名前があっても外を指さない)。 */
	public Path file(LibraryEntry e) {
		Path base = dir().toAbsolutePath().normalize();
		Path f = base.resolve(e.storedName).normalize();

		if (!f.getParent().equals(base)) {
			throw new IllegalArgumentException(Tr.f("保管庫の外のファイル名: {0}", e.storedName));
		}

		return f;
	}

	public boolean exists(LibraryEntry e) {
		try {
			return Files.isRegularFile(file(e));
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/**
	 * これから取るファイルの名前を決めて押さえる。同じ sourceKey の行があればその名前 (上書き)、無ければ suggested を
	 * 安全にした名前 (フォルダのファイル・索引・取り途中のものとぶつかれば連番)。終わったら {@link #add} か {@link #release} を呼ぶ。
	 */
	public synchronized String reserve(String suggested, @Nullable String sourceKey) {
		Path base = dir();
		List<LibraryEntry> index = readIndex(base);

		if (sourceKey != null) {
			for (LibraryEntry e : index) {
				if (sourceKey.equals(e.sourceKey) && !reserved.contains(lower(e.storedName)) && isPlainName(e.storedName)) {
					reserved.add(lower(e.storedName));
					return e.storedName;
				}
			}
		}

		Set<String> taken = new HashSet<>(reserved);
		index.forEach(e -> taken.add(lower(e.storedName)));
		taken.add(lower(INDEX));

		try (DirectoryStream<Path> files = Files.newDirectoryStream(base)) {
			files.forEach(p -> taken.add(lower(p.getFileName().toString())));
		} catch (IOException ignored) {
			// フォルダがまだ無い
		}

		String name = uniqueName(safeName(suggested), n -> taken.contains(lower(n)));
		reserved.add(lower(name));
		return name;
	}

	/** 押さえた名前を手放す (ダウンロードが止まったとき)。 */
	public synchronized void release(String storedName) {
		reserved.remove(lower(storedName));
	}

	/** 取り途中のファイルの置き場 (schematics の外。呼ぶたびに別の名前)。 */
	public Path newTempFile() throws IOException {
		Files.createDirectories(tempDir);
		return tempDir.resolve(UUID.randomUUID() + ".part");
	}

	/**
	 * 取り終わったファイル (downloaded) を保管庫に入れ、行を書く。entry の storedName は {@link #reserve} で押さえた名前。
	 * 同じ sourceKey か同じ storedName の行は置き換える。downloaded は動かす (残らない)。
	 */
	public synchronized LibraryEntry add(Path downloaded, LibraryEntry entry) throws IOException {
		Path base = dir();

		try {
			Files.createDirectories(base);
			Path target = file(entry);
			move(downloaded, target);
			entry.bytes = Files.size(target);
			List<LibraryEntry> rest = new ArrayList<>();

			for (LibraryEntry e : readIndex(base)) {
				boolean same = e.storedName.equalsIgnoreCase(entry.storedName) || (entry.sourceKey != null && entry.sourceKey.equals(e.sourceKey));

				if (!same) {
					rest.add(e);
				}
			}

			rest.add(entry);
			writeIndex(base, rest);
			return entry;
		} finally {
			release(entry.storedName);
			Files.deleteIfExists(downloaded);
		}
	}

	/** 行を消す。索引に無くてもファイルの削除は試す (iOS と同じく取りこぼしを残さない)。消せたら true。 */
	public synchronized boolean remove(String storedName) throws IOException {
		Path base = dir();
		List<LibraryEntry> index = readIndex(base);
		List<LibraryEntry> rest = index.stream().filter(e -> !e.storedName.equals(storedName)).toList();
		LibraryEntry probe = new LibraryEntry();
		probe.storedName = storedName;
		boolean deleted = Files.deleteIfExists(file(probe));

		if (rest.size() != index.size()) {
			writeIndex(base, rest);
			return true;
		}

		return deleted;
	}

	// ---- 索引

	private List<LibraryEntry> readIndex(Path base) {
		Path f = base.resolve(INDEX);

		if (!Files.isRegularFile(f)) {
			return List.of();
		}

		try {
			JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));

			if (!root.isJsonArray()) {
				throw new JsonParseException(Tr.t("配列ではない"));
			}

			List<LibraryEntry> out = new ArrayList<>();

			for (JsonElement el : root.getAsJsonArray()) {
				LibraryEntry e = el.isJsonObject() ? GSON.fromJson(el, LibraryEntry.class) : null;

				// 名前の無い行・保管庫の外を指す行は飛ばす (ファイルは消さない)
				if (e != null && e.storedName != null && isPlainName(e.storedName)) {
					e.label = Objects.requireNonNullElse(e.label, "");
					e.fileName = Objects.requireNonNullElse(e.fileName, "");
					e.savedAt = Objects.requireNonNullElse(e.savedAt, "");
					e.origin = Objects.requireNonNullElse(e.origin, "downloaded");
					out.add(e);
				}
			}

			return out;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("[redtact-live] 保管庫の索引 {} を読めません ({})。空として扱い、次の書き込みの前に .broken へ移します", f, e.toString());
			return List.of();
		}
	}

	private void writeIndex(Path base, List<LibraryEntry> entries) throws IOException {
		Path f = base.resolve(INDEX);

		// 読めなかった索引は上書きする前に控える (中身を手で戻せるように)
		if (Files.isRegularFile(f) && !readable(f)) {
			Files.move(f, base.resolve(INDEX + ".broken"), StandardCopyOption.REPLACE_EXISTING);
		}

		JsonArray arr = new JsonArray();
		entries.forEach(e -> arr.add(GSON.toJsonTree(e)));
		Path tmp = base.resolve(INDEX + ".tmp");
		Files.writeString(tmp, GSON.toJson(arr), StandardCharsets.UTF_8);
		move(tmp, f);
		revision++;
	}

	private static boolean readable(Path f) {
		try {
			return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).isJsonArray();
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			// 一時フォルダと schematics が別のドライブ
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// ---- 名前

	/** 区切り・予約文字・制御文字を _ に、先頭の点と末尾の点・空白を落とし、長さを抑える (拡張子は残す)。空なら "circuit"。 */
	public static String safeName(@Nullable String suggested) {
		String s = suggested == null ? "" : suggested.strip();
		StringBuilder sb = new StringBuilder();
		s.codePoints().forEach(c -> sb.appendCodePoint(c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c));
		String out = sb.toString().strip();

		boolean dotted = out.startsWith(".");

		while (out.startsWith(".")) {
			out = out.substring(1);
		}

		while (out.endsWith(".") || out.endsWith(" ")) {
			out = out.substring(0, out.length() - 1);
		}

		String ext;
		String stem;

		if (dotted && !out.isEmpty() && out.indexOf('.') < 0) {
			// 名前が空で拡張子だけ (".litematic") なら、それを拡張子として残す
			ext = out.toLowerCase(Locale.ROOT);
			stem = "";
		} else {
			ext = extension(out);
			stem = ext.isEmpty() ? out : out.substring(0, out.length() - ext.length() - 1);
		}

		if (stem.isBlank()) {
			stem = "circuit";
		}

		if (WINDOWS_RESERVED.contains(stem.toLowerCase(Locale.ROOT))) {
			stem = stem + "_";
		}

		int maxStem = Math.max(1, MAX_NAME - (ext.isEmpty() ? 0 : ext.length() + 1));

		if (stem.codePointCount(0, stem.length()) > maxStem) {
			stem = stem.substring(0, stem.offsetByCodePoints(0, maxStem)).strip();
		}

		return ext.isEmpty() ? stem : stem + "." + ext;
	}

	/** taken に当たらない名前 (「名前 (2).拡張子」「名前 (3).拡張子」…)。 */
	public static String uniqueName(String name, Predicate<String> taken) {
		if (!taken.test(name)) {
			return name;
		}

		String ext = extension(name);
		String stem = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);

		for (int i = 2; ; i++) {
			String n = stem + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext);

			if (!taken.test(n)) {
				return n;
			}
		}
	}

	/** 拡張子 (小文字、点なし)。無ければ空。 */
	public static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** フォルダの区切りや親を含まない、ただのファイル名か。 */
	static boolean isPlainName(String name) {
		return !name.isBlank() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0 && name.indexOf('\\') < 0
				&& name.indexOf(':') < 0 && name.indexOf('\0') < 0;
	}

	private static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
