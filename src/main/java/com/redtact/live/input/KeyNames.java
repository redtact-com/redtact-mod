package com.redtact.live.input;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * キーの名前とコード。設定ファイルの名前は malilib の頃と同じ (ScanCodes の SCAN_ を外した名前)。MC に依存しない。
 *
 * <ul>
 * <li>キーボード: SDL のスキャンコード (キーの物理的な位置。26.x の InputConstants.KEY_* と同じ値)</li>
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
}
