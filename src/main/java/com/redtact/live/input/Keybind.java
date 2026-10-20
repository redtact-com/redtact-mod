package com.redtact.live.input;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * ホットキーの組み合わせ (押す順に並べたキー)。設定ファイルには {@code "R,L"} のように名前を , でつないで書く (malilib の頃と同じ)。
 * MC に依存しない。
 */
public final class Keybind {
	/** ホットキーを押したときの動き。true を返すと、押したキーをゲームに渡さない (打ち消す)。 */
	@FunctionalInterface
	public interface Callback {
		boolean onPress();
	}

	private final List<Integer> defaultKeys;
	private volatile List<Integer> keys;
	private volatile Callback callback = () -> false;

	public Keybind(String defaultValue) {
		List<Integer> parsed = parse(defaultValue);

		if (parsed == null) {
			throw new IllegalArgumentException(Tr.f("知らないキーの名前: {0}", defaultValue));
		}

		this.defaultKeys = List.copyOf(parsed);
		this.keys = this.defaultKeys;
	}

	/**
	 * {@code "R,L"} を読む。空なら割り当てなし (空のリスト)。知らない名前が混じっていれば null。
	 */
	public static @Nullable List<Integer> parse(String s) {
		List<Integer> out = new ArrayList<>();

		if (s == null || s.isBlank()) {
			return out;
		}

		for (String part : s.split(",")) {
			if (part.isBlank()) {
				continue;
			}

			OptionalInt code = KeyNames.code(part);

			if (code.isEmpty()) {
				return null;
			}

			if (!out.contains(code.getAsInt())) {
				out.add(code.getAsInt());
			}
		}

		return out;
	}

	/** 設定ファイルに書く形 ({@code "R,L"})。 */
	public static String format(List<Integer> keys) {
		List<String> names = new ArrayList<>(keys.size());

		for (int k : keys) {
			String n = KeyNames.name(k);

			if (n != null) {
				names.add(n);
			}
		}

		return String.join(",", names);
	}

	public List<Integer> getKeys() {
		return keys;
	}

	public void setKeys(List<Integer> keys) {
		this.keys = List.copyOf(keys);
	}

	/** 設定ファイルの値を入れる。知らない名前が混じっていれば入れずに false。 */
	public boolean setValueFromString(String s) {
		List<Integer> parsed = parse(s);

		if (parsed == null) {
			return false;
		}

		this.keys = List.copyOf(parsed);
		return true;
	}

	public String getStringValue() {
		return format(keys);
	}

	public String getDefaultStringValue() {
		return format(defaultKeys);
	}

	public boolean isDefault() {
		return keys.equals(defaultKeys);
	}

	public void resetToDefault() {
		keys = defaultKeys;
	}

	public boolean isUnbound() {
		return keys.isEmpty();
	}

	public void setCallback(Callback callback) {
		this.callback = callback;
	}

	public Callback callback() {
		return callback;
	}
}
