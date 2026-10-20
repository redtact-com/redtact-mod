package com.redtact.live.input;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * いま押しているキー (押した順) にどのホットキーが当たるか。MC に依存しない。
 *
 * <p>押した順の<b>末尾</b>がホットキーのキーと同じ並びなら当たり。前に押していたキー (歩く W・しゃがむ Shift など) は
 * 気にしない。R を押しながら L なら [R, L] に当たり、L だけなら当たらない (ゲームの L = 進捗がそのまま開く)。
 * 当たりが複数 ([R, L] と [L] など) なら、キーの多いほうを選ぶ。
 */
public final class HotkeyMatcher {
	private HotkeyMatcher() {
	}

	public static boolean matches(List<Integer> pressed, List<Integer> keys) {
		int n = keys.size();

		if (n == 0 || n > pressed.size()) {
			return false;
		}

		int offset = pressed.size() - n;

		for (int i = 0; i < n; i++) {
			if (!pressed.get(offset + i).equals(keys.get(i))) {
				return false;
			}
		}

		return true;
	}

	/** 当たったもののうちキーの多いもの。無ければ null。 */
	public static <T> @Nullable T best(List<Integer> pressed, List<T> candidates, Function<T, List<Integer>> keysOf) {
		T best = null;
		int bestSize = 0;

		for (T c : candidates) {
			List<Integer> keys = keysOf.apply(c);

			if (keys.size() > bestSize && matches(pressed, keys)) {
				best = c;
				bestSize = keys.size();
			}
		}

		return best;
	}
}
