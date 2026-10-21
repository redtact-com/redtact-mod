package com.redtact.live.input;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * キーの名前とコード。設定ファイルの名前は malilib の頃と同じ (ScanCodes の SCAN_ を外した名前)。MC に依存しない。
 *
 * <ul>
 * <li>キーボード: 26.3 は SDL のスキャンコード (キーの物理的な位置。InputConstants.KEY_* と同じ値)。26.2 までは GLFW のキー (#112)。
 * 設定ファイルは名前なので、版をまたいでも同じキーになる</li>
 * <li>マウス: {@link #MOUSE_OFFSET} + ボタンの番号 (1 = 左・2 = 中・3 = 右・4 = 戻る・5 = 進む)。左 = -99 で malilib と同じ</li>
 * </ul>
 *
 * <p>画面に出すキーの名前は、いまの配列に合わせて MC が付ける (日本語配列で LEFT_BRACKET の位置は「@」)。
 */
public final class KeyNames {
	public static final int MOUSE_OFFSET = -100;
	public static final int MOUSE_LEFT = MOUSE_OFFSET + 1;
	public static final int MOUSE_MIDDLE = MOUSE_OFFSET + 2;
	public static final int MOUSE_RIGHT = MOUSE_OFFSET + 3;

	private static final Map<String, Integer> BY_NAME = new HashMap<>();
	private static final Map<Integer, String> BY_CODE = new HashMap<>();

	static {
		//? if >=26.3 {
		Object[] table = {
			"A", 4, "B", 5, "C", 6, "D", 7, "E", 8, "F", 9,
			"G", 10, "H", 11, "I", 12, "J", 13, "K", 14, "L", 15,
			"M", 16, "N", 17, "O", 18, "P", 19, "Q", 20, "R", 21,
			"S", 22, "T", 23, "U", 24, "V", 25, "W", 26, "X", 27,
			"Y", 28, "Z", 29, "1", 30, "2", 31, "3", 32, "4", 33,
			"5", 34, "6", 35, "7", 36, "8", 37, "9", 38, "0", 39,
			"RETURN", 40, "ESCAPE", 41, "BACKSPACE", 42, "TAB", 43, "SPACE", 44, "MINUS", 45,
			"EQUALS", 46, "LEFT_BRACKET", 47, "RIGHT_BRACKET", 48, "BACKSLASH", 49, "NON_US_HASH", 50, "SEMICOLON", 51,
			"APOSTROPHE", 52, "GRAVE", 53, "COMMA", 54, "PERIOD", 55, "SLASH", 56, "CAPS_LOCK", 57,
			"F1", 58, "F2", 59, "F3", 60, "F4", 61, "F5", 62, "F6", 63,
			"F7", 64, "F8", 65, "F9", 66, "F10", 67, "F11", 68, "F12", 69,
			"PRINT_SCREEN", 70, "SCROLL_LOCK", 71, "PAUSE", 72, "INSERT", 73, "HOME", 74, "PAGE_UP", 75,
			"DELETE", 76, "END", 77, "PAGE_DOWN", 78, "RIGHT", 79, "LEFT", 80, "DOWN", 81,
			"UP", 82, "NUM_LOCK", 83, "KP_DIVIDE", 84, "KP_MULTIPLY", 85, "KP_MINUS", 86, "KP_PLUS", 87,
			"KP_ENTER", 88, "KP_1", 89, "KP_2", 90, "KP_3", 91, "KP_4", 92, "KP_5", 93,
			"KP_6", 94, "KP_7", 95, "KP_8", 96, "KP_9", 97, "KP_0", 98, "KP_PERIOD", 99,
			"NON_US_BACKSLASH", 100, "KP_EQUALS", 103, "F13", 104, "F14", 105, "F15", 106, "F16", 107,
			"F17", 108, "F18", 109, "F19", 110, "F20", 111, "F21", 112, "F22", 113,
			"F23", 114, "F24", 115, "KP_COMMA", 133, "LEFT_CONTROL", 224, "LEFT_SHIFT", 225, "LEFT_ALT", 226,
			"LEFT_GUI", 227, "RIGHT_CONTROL", 228, "RIGHT_SHIFT", 229, "RIGHT_ALT", 230, "RIGHT_GUI", 231,
			"MOUSE_LEFT", MOUSE_LEFT, "MOUSE_MIDDLE", MOUSE_MIDDLE, "MOUSE_RIGHT", MOUSE_RIGHT,
			"MOUSE_BACK", MOUSE_OFFSET + 4, "MOUSE_FORWARD", MOUSE_OFFSET + 5,
		};
		//?} else {
		/*// 26.2 まで (GLFW) のキーの番号 (InputConstants.KEY_* と同じ値。#112)。名前は 26.3 と同じ
		Object[] table = {
			"A", 65, "B", 66, "C", 67, "D", 68, "E", 69, "F", 70,
			"G", 71, "H", 72, "I", 73, "J", 74, "K", 75, "L", 76,
			"M", 77, "N", 78, "O", 79, "P", 80, "Q", 81, "R", 82,
			"S", 83, "T", 84, "U", 85, "V", 86, "W", 87, "X", 88,
			"Y", 89, "Z", 90, "1", 49, "2", 50, "3", 51, "4", 52,
			"5", 53, "6", 54, "7", 55, "8", 56, "9", 57, "0", 48,
			"RETURN", 257, "ESCAPE", 256, "BACKSPACE", 259, "TAB", 258, "SPACE", 32, "MINUS", 45,
			"EQUALS", 61, "LEFT_BRACKET", 91, "RIGHT_BRACKET", 93, "BACKSLASH", 92, "NON_US_HASH", 161, "SEMICOLON", 59,
			"APOSTROPHE", 39, "GRAVE", 96, "COMMA", 44, "PERIOD", 46, "SLASH", 47, "CAPS_LOCK", 280,
			"F1", 290, "F2", 291, "F3", 292, "F4", 293, "F5", 294, "F6", 295,
			"F7", 296, "F8", 297, "F9", 298, "F10", 299, "F11", 300, "F12", 301,
			"PRINT_SCREEN", 283, "SCROLL_LOCK", 281, "PAUSE", 284, "INSERT", 260, "HOME", 268, "PAGE_UP", 266,
			"DELETE", 261, "END", 269, "PAGE_DOWN", 267, "RIGHT", 262, "LEFT", 263, "DOWN", 264,
			"UP", 265, "NUM_LOCK", 282, "KP_DIVIDE", 331, "KP_MULTIPLY", 332, "KP_MINUS", 333, "KP_PLUS", 334,
			"KP_ENTER", 335, "KP_1", 321, "KP_2", 322, "KP_3", 323, "KP_4", 324, "KP_5", 325,
			"KP_6", 326, "KP_7", 327, "KP_8", 328, "KP_9", 329, "KP_0", 320, "KP_PERIOD", 330,
			"NON_US_BACKSLASH", 162, "KP_EQUALS", 336, "F13", 302, "F14", 303, "F15", 304, "F16", 305,
			"F17", 306, "F18", 307, "F19", 308, "F20", 309, "F21", 310, "F22", 311,
			"F23", 312, "F24", 313, "LEFT_CONTROL", 341, "LEFT_SHIFT", 340, "LEFT_ALT", 342,
			"LEFT_GUI", 343, "RIGHT_CONTROL", 345, "RIGHT_SHIFT", 344, "RIGHT_ALT", 346, "RIGHT_GUI", 347,
			"MOUSE_LEFT", MOUSE_LEFT, "MOUSE_MIDDLE", MOUSE_MIDDLE, "MOUSE_RIGHT", MOUSE_RIGHT,
			"MOUSE_BACK", MOUSE_OFFSET + 4, "MOUSE_FORWARD", MOUSE_OFFSET + 5,
		};
		*///?}

		for (int i = 0; i < table.length; i += 2) {
			String name = (String) table[i];
			int code = (Integer) table[i + 1];
			BY_NAME.put(name, code);
			BY_CODE.putIfAbsent(code, name);
		}

		// 別名 (GLFW の頃の malilib の名前など)。読むときだけ使い、書くときは上の名前にする
		alias("ENTER", "RETURN");
		alias("GRAVE_ACCENT", "GRAVE");
		alias("EQUAL", "EQUALS");
		alias("KP_ADD", "KP_PLUS");
		alias("KP_SUBTRACT", "KP_MINUS");
		alias("KP_EQUAL", "KP_EQUALS");
		alias("KP_DECIMAL", "KP_PERIOD");
		alias("LEFT_SUPER", "LEFT_GUI");
		alias("RIGHT_SUPER", "RIGHT_GUI");
	}

	private KeyNames() {
	}

	private static void alias(String alias, String name) {
		BY_NAME.put(alias, BY_NAME.get(name));
	}

	/** 名前からコード (大文字小文字は問わない)。知らない名前なら空。 */
	public static OptionalInt code(String name) {
		Integer c = name == null ? null : BY_NAME.get(name.trim().toUpperCase(java.util.Locale.ROOT));
		return c == null ? OptionalInt.empty() : OptionalInt.of(c);
	}

	/** コードから設定ファイルの名前。知らないコードなら null。 */
	public static String name(int code) {
		return BY_CODE.get(code);
	}

	public static boolean isMouse(int code) {
		return code < 0;
	}

	/** マウスのコードからボタンの番号 (1 = 左)。 */
	public static int mouseButton(int code) {
		return code - MOUSE_OFFSET;
	}

	/** ボタンの番号 (1 = 左) からマウスのコード。 */
	public static int mouseCode(int button) {
		return MOUSE_OFFSET + button;
	}

	/**
	 * ゲームのマウスのボタンの番号 ({@code MouseButtonInfo#button}) から、この MOD のボタンの番号 (1 = 左・2 = 中・3 = 右)。
	 * 26.3 は SDL で同じ番号、26.2 までは GLFW で 0 = 左・1 = 右・2 = 中 (右と中の順が違う。#112)。
	 */
	public static int fromGameButton(int button) {
		//? if >=26.3 {
		return button;
		//?} else {
		/*return switch (button) {
			case 0 -> 1; // 左
			case 1 -> 3; // 右
			case 2 -> 2; // 中
			default -> button + 1; // 3 = 戻る → 4・4 = 進む → 5
		};
		*///?}
	}

	/** この MOD のボタンの番号 (1 = 左・2 = 中・3 = 右) から、ゲームのマウスのボタンの番号 (InputConstants.Type.MOUSE など)。 */
	public static int gameButton(int button) {
		//? if >=26.3 {
		return button;
		//?} else {
		/*return switch (button) {
			case 1 -> 0; // 左
			case 3 -> 1; // 右
			case 2 -> 2; // 中
			default -> button - 1;
		};
		*///?}
	}
}
