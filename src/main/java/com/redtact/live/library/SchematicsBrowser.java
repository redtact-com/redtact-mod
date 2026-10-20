package com.redtact.live.library;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * 保管庫タブで行き来するフォルダ (#60)。Litematica の schematics フォルダを根にして、その下のフォルダと回路のファイルを並べる。
 * 根の外へは出ない (Litematica の読み込み画面と同じ範囲。2026-10-18 ユーザ選択)。
 *
 * <p>パス欄の字は schematics からの相対 (先頭の {@code /} と、根の名前の {@code schematics/} は付いていてもよい) か、
 * 根の下の絶対パス。区切りは {@code /} でも {@code \} でもよい。どのメソッドもゲームに頼らない (単体テストで見る)。
 */
public final class SchematicsBrowser {
	/** 一覧に出すファイルの形式 (redtact に上げられる形式。redtact の {@code frontend/app/lib/upload.ts} の DUMP_EXTS と同じ)。 */
	public static final Set<String> CIRCUIT_EXTS = Set.of("litematic", "litematica", "nbt", "schem", "schematic", "mcstructure", "mcworld");
	/** 1 つのフォルダで出す数の上限 (フォルダとファイルを合わせて。超えた分は数だけ出す)。 */
	public static final int MAX_ITEMS = 500;
	/** 名前の並び (大文字小文字を区別せず、数字は数として比べる: 回路2 → 回路10)。 */
	public static final Comparator<String> NAME_ORDER = SchematicsBrowser::compareNames;

	/** 一覧の 1 行 (フォルダかファイル)。 */
	public record Item(Path path, String name, boolean folder, long bytes, long modifiedMillis) {
		/** 拡張子 (小文字・点なし。フォルダは空)。 */
		public String extension() {
			return folder ? "" : Library.extension(name);
		}
	}

	/** フォルダの一覧。omitted は上限で出さなかった数、error は読めなかった理由 (読めたら null)。 */
	public record Listing(Path dir, List<Item> folders, List<Item> files, int omitted, @Nullable String error) {
		public boolean isEmpty() {
			return folders.isEmpty() && files.isEmpty();
		}
	}

	private final Supplier<Path> root;

	/** @param root schematics のフォルダ (呼ぶたびに求める。Litematica の設定で場所が変わりうる) */
	public SchematicsBrowser(Supplier<Path> root) {
		this.root = root;
	}

	public Path root() {
		return root.get().toAbsolutePath().normalize();
	}

	/** 根 (schematics) の下か (根そのものも含む。字の上で見る)。 */
	public boolean contains(Path p) {
		return p.toAbsolutePath().normalize().startsWith(root());
	}

	/** 根からの場所 (根は空。区切りは /)。根の外なら絶対パス。 */
	public String relative(Path dir) {
		Path r = root();
		Path d = dir.toAbsolutePath().normalize();

		if (!d.startsWith(r)) {
			return d.toString();
		}

		StringBuilder sb = new StringBuilder();

		for (Path part : r.relativize(d)) {
			String s = part.toString();

			if (s.isEmpty()) {
				continue;
			}

			if (!sb.isEmpty()) {
				sb.append('/');
			}

			sb.append(s);
		}

		return sb.toString();
	}

	/** 画面に出す場所 ({@code schematics/redtact/} の形。根は {@code schematics/})。 */
	public String display(Path dir) {
		String rel = relative(dir);
		String name = rootName();
		return rel.isEmpty() ? name + "/" : name + "/" + rel + "/";
	}

	/** 根のフォルダの名前 (ふつうは schematics)。 */
	public String rootName() {
		Path n = root().getFileName();
		return n == null ? "schematics" : n.toString();
	}

	/** 一つ上 (根なら null)。 */
	public @Nullable Path parent(Path dir) {
		Path d = dir.toAbsolutePath().normalize();
		Path r = root();

		if (d.equals(r) || !d.startsWith(r)) {
			return null;
		}

		return d.getParent();
	}

	/**
	 * パス欄の字を場所にする。フォルダがあるかは見ない ({@link #openReason})。
	 *
	 * @throws IllegalArgumentException 根の外・使えない字 (メッセージが理由)
	 */
	public Path resolve(@Nullable String text) {
		String t = text == null ? "" : text.strip();
		Path r = root();
		Path abs = absoluteOrNull(t);

		if (abs != null && abs.startsWith(r)) {
			return abs;
		}

		// 根の外の絶対パス。区切りで始まらないもの (C:\… など) と、実際にある場所は外を指したとみる。
		// 区切りで始まって無い場所 (Linux の /builds など) は、schematics からの相対として読み直す
		if (abs != null && (!t.startsWith("/") && !t.startsWith("\\") || Files.exists(abs))) {
			throw new IllegalArgumentException(Tr.f("{0} の外は開けません: {1}", rootName(), t));
		}

		List<String> parts = new ArrayList<>();

		for (String seg : t.replace('\\', '/').split("/")) {
			if (!seg.isEmpty() && !seg.equals(".")) {
				parts.add(seg);
			}
		}

		// 先頭の「schematics/」は根のこととして読む (根の下に同じ名前のフォルダがあればそちら)
		if (!parts.isEmpty() && parts.getFirst().equalsIgnoreCase(rootName()) && !Files.exists(r.resolve(parts.getFirst()))) {
			parts.removeFirst();
		}

		Path p = r;

		try {
			for (String seg : parts) {
				p = p.resolve(seg);
			}
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException(Tr.f("パスに使えない字があります: {0}", t));
		}

		p = p.normalize();

		if (!p.startsWith(r)) {
			throw new IllegalArgumentException(Tr.f("{0} の外は開けません: {1}", rootName(), t));
		}

		return p;
	}

	/** 開けない理由 (開けるなら null)。 */
	public @Nullable String openReason(Path dir) {
		if (!contains(dir)) {
			return Tr.f("{0} の外は開けません", rootName());
		}

		if (Files.isDirectory(dir)) {
			return null;
		}

		return Files.exists(dir) ? Tr.f("フォルダではありません: {0}", relative(dir)) : Tr.f("フォルダがありません: {0}", display(dir));
	}

	/** そのフォルダの子フォルダと回路のファイル (名前の順。隠しファイル・ほかの形式は出さない)。 */
	public Listing list(Path dir) {
		List<Item> folders = new ArrayList<>();
		List<Item> files = new ArrayList<>();

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				String name = p.getFileName().toString();

				if (name.startsWith(".")) {
					continue;
				}

				BasicFileAttributes a;

				try {
					a = Files.readAttributes(p, BasicFileAttributes.class);
				} catch (IOException | RuntimeException e) {
					continue; // 壊れたリンクなど
				}

				if (a.isDirectory()) {
					folders.add(new Item(p, name, true, 0, a.lastModifiedTime().toMillis()));
				} else if (a.isRegularFile() && CIRCUIT_EXTS.contains(Library.extension(name))) {
					files.add(new Item(p, name, false, a.size(), a.lastModifiedTime().toMillis()));
				}
			}
		} catch (NoSuchFileException e) {
			return new Listing(dir, List.of(), List.of(), 0, Tr.t("フォルダがありません"));
		} catch (IOException | RuntimeException e) {
			return new Listing(dir, List.of(), List.of(), 0, Tr.f("読めません ({0})", e.getClass().getSimpleName()));
		}

		Comparator<Item> byName = Comparator.comparing(Item::name, NAME_ORDER);
		folders.sort(byName);
		files.sort(byName);
		int total = folders.size() + files.size();

		if (total <= MAX_ITEMS) {
			return new Listing(dir, List.copyOf(folders), List.copyOf(files), 0, null);
		}

		// フォルダを先に残し、残りの枠をファイルに
		List<Item> keptFolders = folders.subList(0, Math.min(folders.size(), MAX_ITEMS));
		List<Item> keptFiles = files.subList(0, Math.min(files.size(), MAX_ITEMS - keptFolders.size()));
		return new Listing(dir, List.copyOf(keptFolders), List.copyOf(keptFiles), total - keptFolders.size() - keptFiles.size(), null);
	}

	private static @Nullable Path absoluteOrNull(String t) {
		if (t.isEmpty()) {
			return null;
		}

		try {
			Path p = Path.of(t);
			return p.isAbsolute() ? p.normalize() : null;
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** 大文字小文字を区別せず、続く数字は数として比べる。 */
	static int compareNames(String a, String b) {
		int i = 0;
		int j = 0;

		while (i < a.length() && j < b.length()) {
			char ca = a.charAt(i);
			char cb = b.charAt(j);

			if (Character.isDigit(ca) && Character.isDigit(cb)) {
				int si = i;
				int sj = j;

				while (i < a.length() && Character.isDigit(a.charAt(i))) {
					i++;
				}

				while (j < b.length() && Character.isDigit(b.charAt(j))) {
					j++;
				}

				// 先頭の 0 を除いた桁数 → 字の順 (同じ数なら 0 の多い方を後に)
				String na = stripZeros(a.substring(si, i));
				String nb = stripZeros(b.substring(sj, j));
				int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);

				if (c != 0) {
					return c;
				}

				c = Integer.compare(i - si, j - sj);

				if (c != 0) {
					return c;
				}

				continue;
			}

			int c = Character.compare(Character.toLowerCase(Character.toUpperCase(ca)), Character.toLowerCase(Character.toUpperCase(cb)));

			if (c != 0) {
				return c;
			}

			i++;
			j++;
		}

		int c = Integer.compare(a.length() - i, b.length() - j);
		return c != 0 ? c : a.compareTo(b);
	}

	private static String stripZeros(String digits) {
		int k = 0;

		while (k < digits.length() - 1 && digits.charAt(k) == '0') {
			k++;
		}

		return digits.substring(k);
	}
}
