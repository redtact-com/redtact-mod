package com.redtact.live.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * 画面の英語 (#79) の抜けを見つける。MOD のソース (src/main/java) の日本語の文字列は、すべて {@code Tr.t} {@code Tr.f}
 * {@code Tr.n} {@code Tr.ja} の 1 つ目の引数 (文字列だけを + でつないだもの) に書き、英語の対応表 (en.json) に訳を持つこと。
 * ログ ({@code LOGGER.}) の文と {@code Tr.raw} (どの言語でもそのまま出す文) は訳さない。対応表には使っていない文を残さない。差し込みの {@code {0}} は原文と訳でそろえる。
 */
class TrCoverageTest {
	private static final Path SRC = Path.of("src/main/java");
	private static final Pattern JAPANESE = Pattern.compile("[\\u3000-\\u30ff\\u4e00-\\u9fff\\uff00-\\uffef]");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d{1,2})\\}");
	private static final Pattern CALL = Pattern.compile("\\bTr\\.(t|f|n|ja)\\(\\s*$");
	/** どの言語でもこのまま出す文 (訳さない)。 */
	private static final Pattern RAW = Pattern.compile("\\bTr\\.raw\\(\\s*$");

	/** ソースから見つけた文字列 (1 つの式にまとまった文字列の連結は 1 つ)。 */
	record Literal(Path file, int line, String value, boolean keyed, boolean logged) {
		/** 訳さなくてよいもの (ログ・Tr.raw)。 */
	}

	@Test
	void everyJapaneseStringHasEnglish() throws IOException {
		Map<String, String> en = englishTable();
		List<String> problems = new ArrayList<>();
		Set<String> used = new LinkedHashSet<>();

		for (Literal l : literals()) {
			if (!JAPANESE.matcher(l.value()).find()) {
				continue;
			}

			if (l.keyed()) {
				used.add(l.value());

				if (!en.containsKey(l.value())) {
					problems.add(where(l) + " 英語が無い: " + l.value());
				}
			} else if (!l.logged()) {
				problems.add(where(l) + " Tr を通していない日本語: " + l.value());
			}
		}

		for (String k : en.keySet()) {
			if (!used.contains(k)) {
				problems.add("en.json に使っていない文: " + k);
			}
		}

		assertTrue(problems.isEmpty(), problems.size() + " 件\n" + String.join("\n", problems));
	}

	@Test
	void translationsKeepPlaceholdersAndHaveNoJapanese() throws IOException {
		List<String> problems = new ArrayList<>();

		for (Map.Entry<String, String> e : englishTable().entrySet()) {
			Set<String> want = placeholders(e.getKey());

			for (String part : e.getValue().split("\\|", -1)) {
				if (!placeholders(part).equals(want)) {
					problems.add("差し込みが違う: " + e.getKey() + " → " + e.getValue());
				}
			}

			if (JAPANESE.matcher(e.getValue()).find()) {
				problems.add("訳に日本語が残っている: " + e.getKey() + " → " + e.getValue());
			}

			if (e.getValue().isBlank()) {
				problems.add("訳が空: " + e.getKey());
			}
		}

		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}

	@Test
	void englishTableHasNoDuplicateKeys() throws IOException {
		// 対応表は 1 つの鍵を 1 回だけ持つ (JSON の重複した鍵は後ろが勝って気づけない)
		String raw = Files.readString(Path.of("src/main/resources" + Tr.EN_PATH));
		Matcher m = Pattern.compile("^\\t\"((?:[^\"\\\\]|\\\\.)*)\":", Pattern.MULTILINE).matcher(raw);
		Set<String> seen = new LinkedHashSet<>();
		List<String> dup = new ArrayList<>();

		while (m.find()) {
			if (!seen.add(m.group(1))) {
				dup.add(m.group(1));
			}
		}

		assertEquals(List.of(), dup, "en.json に同じ鍵が 2 つある");
	}

	private static String where(Literal l) {
		return SRC.relativize(l.file()) + ":" + l.line();
	}

	private static Set<String> placeholders(String s) {
		Set<String> out = new TreeSet<>();
		Matcher m = PLACEHOLDER.matcher(s);

		while (m.find()) {
			out.add(m.group(1));
		}

		return out;
	}

	static Map<String, String> englishTable() throws IOException {
		Map<String, String> out = new LinkedHashMap<>();

		try (InputStream in = Files.newInputStream(Path.of("src/main/resources" + Tr.EN_PATH))) {
			JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				out.put(e.getKey(), e.getValue().getAsString());
			}
		}

		return out;
	}

	/** ソースの文字列 (コメントの中は除く)。Tr の 1 つ目の引数かと、ログの文の中かを付ける。 */
	static List<Literal> literals() throws IOException {
		List<Literal> out = new ArrayList<>();

		try (Stream<Path> files = Files.walk(SRC)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
				scan(p, Files.readString(p), out);
			}
		}

		return out;
	}

	/** 文字列・文字・コメントを見分けながら読む小さな字句解析。 */
	static void scan(Path file, String src, List<Literal> out) {
		StringBuilder code = new StringBuilder(); // コメントと文字列を除いたコード (前にある呼び出しと文の始まりを見る)
		int i = 0;
		int line = 1;
		int n = src.length();
		// 連結の途中: Tr の引数の文字列が + でつながっているあいだ
		Literal pending = null;
		int pendingEnd = -1;

		while (i < n) {
			char c = src.charAt(i);

			if (c == '\n') {
				line++;
			}

			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				int e = src.indexOf('\n', i);
				i = e < 0 ? n : e;
				continue;
			}

			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int e = src.indexOf("*/", i + 2);
				int end = e < 0 ? n : e + 2;
				line += count(src, i, end, '\n');
				i = end;
				continue;
			}

			if (c == '\'') {
				int e = i + 1;

				while (e < n && src.charAt(e) != '\'') {
					e += src.charAt(e) == '\\' ? 2 : 1;
				}

				i = e + 1;
				code.append("' '");
				continue;
			}

			if (c == '"') {
				boolean block = src.startsWith("\"\"\"", i);
				int start = line;
				StringBuilder value = new StringBuilder();
				int e;

				if (block) {
					e = src.indexOf("\"\"\"", i + 3);
					e = e < 0 ? n : e;
					value.append(src, i + 3, e);
					line += count(src, i, e, '\n');
					i = e + 3;
				} else {
					e = i + 1;

					while (e < n && src.charAt(e) != '"') {
						if (src.charAt(e) == '\\' && e + 1 < n) {
							value.append(unescape(src.charAt(e + 1)));
							e += 2;
						} else {
							value.append(src.charAt(e));
							e++;
						}
					}

					i = e + 1;
				}

				String before = code.toString();

				if (pending != null && before.substring(pendingEnd).trim().equals("+")) {
					// Tr の引数の続き: 前の文字列につなぐ
					Literal p = pending;
					pending = new Literal(p.file(), p.line(), p.value() + value, true, false);
					out.set(out.size() - 1, pending);
				} else {
					pending = null;
					boolean keyed = CALL.matcher(before).find();
					Literal l = new Literal(file, start, value.toString(), keyed, inLog(before) || RAW.matcher(before).find());
					out.add(l);

					if (keyed) {
						pending = l;
					}
				}

				code.append("\"\"");
				pendingEnd = code.length();
				continue;
			}

			code.append(c);

			if (pending != null && !Character.isWhitespace(c) && c != '+') {
				pending = null;
			}

			i++;
		}
	}

	/** いまの文 (前の ; { } から) がログの呼び出しか。 */
	private static boolean inLog(String before) {
		int s = Math.max(before.lastIndexOf(';'), Math.max(before.lastIndexOf('{'), before.lastIndexOf('}')));
		return before.substring(s + 1).contains("LOGGER.");
	}

	private static int count(String s, int from, int to, char c) {
		int k = 0;

		for (int j = from; j < to && j < s.length(); j++) {
			if (s.charAt(j) == c) {
				k++;
			}
		}

		return k;
	}

	private static String unescape(char c) {
		return switch (c) {
			case 'n' -> "\n";
			case 't' -> "\t";
			case 'r' -> "\r";
			case '0' -> "\0";
			default -> String.valueOf(c);
		};
	}
}
