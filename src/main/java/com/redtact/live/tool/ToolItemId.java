package com.redtact.live.tool;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** 範囲指定の道具のアイテム ID の解釈 (MC に依存しない)。 */
public final class ToolItemId {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

	private ToolItemId() {
	}

	/**
	 * 設定の文字列を正規の ID にする。名前空間が無ければ {@code minecraft:} を足す。
	 * 空・不正なら empty (道具なし)。
	 */
	public static Optional<String> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}

		String s = raw.trim().toLowerCase(Locale.ROOT);

		if (s.isEmpty()) {
			return Optional.empty();
		}

		if (!s.contains(":")) {
			s = "minecraft:" + s;
		}

		return ID.matcher(s).matches() ? Optional.of(s) : Optional.empty();
	}

	/** 手に持っているアイテムの ID が道具か。 */
	public static boolean matches(String configured, String heldItemId) {
		return parse(configured).map(id -> id.equals(heldItemId)).orElse(false);
	}
}
