package com.redtact.live.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redtact.live.config.Configs;
import org.jspecify.annotations.Nullable;

/**
 * 画面の言語 (#79)。画面に出す文は日本語の原文で書き、英語の画面ではその原文を鍵にして対応表
 * ({@code assets/redtact-live/i18n/en.json}) を引く (redtact 本体の英語対応 #535 と同じ考え方)。英語に無い文は日本語のまま出す。
 *
 * <p>言語は設定の「言語」({@link UiLanguage})。自動はマイクラの言語が日本語なら日本語、ほかは英語。
 * 文に値を差し込むときは、つなぎ合わせずに {@code {0}} {@code {1}} … の入った 1 つの文にする (英語で語順を変えられるように):
 * {@code Tr.f("「{0}」を再開する", name)}。数で英語の単数と複数が変わる文は {@link #n} (英語の訳は「単数|複数」)。
 * 設定の名前と説明はマイクラの言語ファイル ({@code lang/ja_jp.json}・{@code lang/en_us.json}) の鍵で {@link #key} から引く。
 */
public final class Tr {
	static final String EN_PATH = "/assets/redtact-live/i18n/en.json";
	private static final String LANG_JA = "/assets/redtact-live/lang/ja_jp.json";
	private static final String LANG_EN = "/assets/redtact-live/lang/en_us.json";
	/** マイクラの言語 (例 ja_jp)。ゲームの外 (単体テスト) では日本語。 */
	private static volatile Supplier<@Nullable String> gameLanguage = () -> "ja_jp";
	/** テストで言語を決め打ちにする (null なら設定どおり)。 */
	private static volatile @Nullable Boolean forced;

	private Tr() {
	}

	/** 英語の対応表と言語ファイル (初めて使うときに読む)。 */
	private static final class Tables {
		static final Map<String, String> EN = load(EN_PATH);
		static final Map<String, String> LANG_JA_MAP = load(LANG_JA);
		static final Map<String, String> LANG_EN_MAP = load(LANG_EN);
	}

	/**
	 * 訳す文の印。その場では訳さずに日本語の原文のまま持つ (定数・enum の名前など、画面に出すときに {@link #t} を通すもの)。
	 * 英語の対応表に訳を持つことはテストで確かめる。
	 */
	public static String ja(String ja) {
		return ja;
	}

	/** どの言語の画面でもこのまま出す文の印 (言語の名前「日本語」など。訳さない)。 */
	public static String raw(String s) {
		return s;
	}

	/** 文をいまの言語にする。 */
	public static String t(String ja) {
		return english() ? Tables.EN.getOrDefault(ja, ja) : ja;
	}

	/** 文をいまの言語にして、{@code {0}} {@code {1}} … に値を入れる。 */
	public static String f(String ja, Object... args) {
		return fill(t(ja), args);
	}

	/**
	 * 数の入る文 ({@code {0}} が数、{@code {1}} からがほかの値)。英語の訳は「単数|複数」と書けば数で選ぶ
	 * (例 {@code "コメント {0} 件"} → {@code "{0} comment|{0} comments"})。
	 */
	public static String n(String ja, long count, Object... more) {
		String s = t(ja);
		int bar = s.indexOf('|');

		if (bar >= 0) {
			s = count == 1 ? s.substring(0, bar) : s.substring(bar + 1);
		}

		Object[] args = new Object[more.length + 1];
		args[0] = count;
		System.arraycopy(more, 0, args, 1, more.length);
		return fill(s, args);
	}

	/** 言語ファイルの鍵 (設定の名前と説明など) をいまの言語で引く。無ければもう一方の言語、それも無ければ鍵。 */
	public static String key(String langKey) {
		Map<String, String> first = english() ? Tables.LANG_EN_MAP : Tables.LANG_JA_MAP;
		Map<String, String> second = english() ? Tables.LANG_JA_MAP : Tables.LANG_EN_MAP;
		String s = first.get(langKey);
		return s != null ? s : second.getOrDefault(langKey, langKey);
	}

	/** いま英語で出すか。 */
	public static boolean english() {
		Boolean f = forced;

		if (f != null) {
			return f;
		}

		return switch (Configs.Generic.LANGUAGE.getOptionListValue()) {
			case JA -> false;
			case EN -> true;
			case AUTO -> !gameJapanese();
		};
	}

	/** マイクラの言語が日本語か。 */
	public static boolean gameJapanese() {
		String code;

		try {
			code = gameLanguage.get();
		} catch (RuntimeException e) {
			return true;
		}

		return code == null || code.toLowerCase(Locale.ROOT).startsWith("ja");
	}

	/** マイクラの言語の取り方 (MOD を起動したときに入れる)。 */
	public static void gameLanguage(Supplier<@Nullable String> supplier) {
		gameLanguage = supplier;
	}

	/** テスト用: 言語を決め打ちにする (true で英語・false で日本語・null で設定どおり)。 */
	public static void forceForTests(@Nullable Boolean english) {
		forced = english;
	}

	/** 英語の対応表 (テスト用)。 */
	public static Map<String, String> englishTable() {
		return Collections.unmodifiableMap(Tables.EN);
	}

	/** {@code {0}} {@code {1}} … に値を入れる (無い番号はそのまま残す)。 */
	static String fill(String s, Object... args) {
		if (args.length == 0 || s.indexOf('{') < 0) {
			return s;
		}

		StringBuilder out = new StringBuilder(s.length() + 16);
		int i = 0;

		while (i < s.length()) {
			char c = s.charAt(i);

			if (c == '{') {
				int close = s.indexOf('}', i + 1);

				if (close > i + 1) {
					String n = s.substring(i + 1, close);

					if (n.chars().allMatch(Character::isDigit) && n.length() <= 2) {
						int index = Integer.parseInt(n);

						if (index < args.length) {
							out.append(args[index]);
							i = close + 1;
							continue;
						}
					}
				}
			}

			out.append(c);
			i++;
		}

		return out.toString();
	}

	private static Map<String, String> load(String path) {
		Map<String, String> out = new LinkedHashMap<>();

		try (InputStream in = Tr.class.getResourceAsStream(path)) {
			if (in == null) {
				return out;
			}

			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonObject o = JsonParser.parseReader(r).getAsJsonObject();

				for (Map.Entry<String, JsonElement> e : o.entrySet()) {
					if (e.getValue().isJsonPrimitive()) {
						out.put(e.getKey(), e.getValue().getAsString());
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// 読めなければ日本語のまま出す
		}

		return out;
	}
}
