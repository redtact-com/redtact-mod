package com.redtact.live.input;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.i18n.Tr;

/** 画面に出すキーの名前。いまの配列に合わせて MC が付ける (日本語配列で LEFT_BRACKET の位置は「@」)。 */
public final class KeyDisplay {
	private KeyDisplay() {
	}

	public static String name(int code) {
		InputConstants.Key key = KeyNames.isMouse(code)
				? InputConstants.Type.MOUSE.getOrCreate(KeyNames.gameButton(KeyNames.mouseButton(code)))
				//? if >=26.3 {
				: InputConstants.Type.KEYBOARD.getOrCreate(code);
				//?} else
				/*: InputConstants.Type.KEYSYM.getOrCreate(code);*/
		return key.getDisplayName().getString();
	}

	/** 「R + L」。割り当てが無ければ「なし」。 */
	public static String combo(List<Integer> keys) {
		if (keys.isEmpty()) {
			return Tr.t("なし");
		}

		List<String> names = new ArrayList<>(keys.size());

		for (int k : keys) {
			names.add(name(k));
		}

		return String.join(" + ", names);
	}
}
