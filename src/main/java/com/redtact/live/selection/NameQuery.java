package com.redtact.live.selection;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 保存済みの範囲の名前の検索 (セッションタブの検索欄。#39)。空白で区切った語をすべて含めば当たり。
 * 大文字と小文字・全角と半角 (NFKC) は区別しない。空なら全部当たる。
 */
public final class NameQuery {
	private final String[] terms;

	private NameQuery(String[] terms) {
		this.terms = terms;
	}

	public static NameQuery of(String text) {
		String n = normalize(text).trim();
		return new NameQuery(n.isEmpty() ? new String[0] : n.split("\\s+"));
	}

	public boolean isEmpty() {
		return terms.length == 0;
	}

	public boolean matches(String name) {
		if (terms.length == 0) {
			return true;
		}

		if (name == null) {
			return false;
		}

		String n = normalize(name);

		for (String t : terms) {
			if (!n.contains(t)) {
				return false;
			}
		}

		return true;
	}

	private static String normalize(String s) {
		return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
	}
}
