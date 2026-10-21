package com.redtact.live.input;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.compat.GameInput;
import com.redtact.live.config.ConfigHotkey;
import com.redtact.live.config.Configs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

/**
 * ホットキーの受け口。キーボードとマウスの入力をゲームより先に受け (mixin)、当たったホットキーを動かす。
 *
 * <ul>
 * <li>押下を数えるのはゲーム中 (画面が開いていない) だけ。画面の文字入力 (チャットの r と l) では動かない</li>
 * <li>押した順に覚え、末尾が組み合わせと同じなら当たり ({@link HotkeyMatcher})。前から押していたキー (W・Shift) は気にしない</li>
 * <li>ホットキーが「打ち消す」と返したら、そのキーの押下と離したのをゲームに渡さない (R+L の L で進捗の画面が開かない)</li>
 * <li>窓の外で離したキーは離したことが届かないので、押下のたびに実際に押されているかを確かめて覚えを直す</li>
 * </ul>
 *
 * クライアントのスレッドからだけ呼ぶ。
 */
public final class HotkeyManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	/** 押している順のキー (キーボードはスキャンコード、マウスは KeyNames のコード)。 */
	private static final List<Integer> PRESSED = new ArrayList<>();
	/** 打ち消した押下 (離したのも打ち消す)。 */
	private static final Set<Integer> CONSUMED = new HashSet<>();

	private HotkeyManager() {
	}

	/**
	 * キーボードの入力 (KeyboardHandler.keyPress の頭)。
	 *
	 * @param action 1 = 押した・0 = 離した・2 = 押しっぱなしの繰り返し
	 * @return true ならゲームに渡さない
	 */
	public static boolean onKeyboard(int scancode, int action) {
		return onInput(scancode, action, inGame());
	}

	/**
	 * マウスのボタン (MouseHandler.onButton の頭)。
	 *
	 * @param button 1 = 左・2 = 中・3 = 右
	 * @param action 1 = 押した・0 = 離した
	 * @return true ならゲームに渡さない
	 */
	public static boolean onMouse(int button, int action) {
		return onInput(KeyNames.mouseCode(button), action, inGame());
	}

	private static boolean onInput(int code, int action, boolean inGame) {
		if (action == 0) {
			PRESSED.remove(Integer.valueOf(code));
			return CONSUMED.remove(code);
		}

		if (action != 1) {
			return CONSUMED.contains(code); // 押しっぱなしの繰り返し
		}

		if (!inGame) {
			return false;
		}

		prune();
		PRESSED.remove(Integer.valueOf(code));
		PRESSED.add(code);
		ConfigHotkey hit = HotkeyMatcher.best(PRESSED, Configs.Hotkeys.HOTKEY_LIST, h -> h.getKeybind().getKeys());

		if (hit == null) {
			return false;
		}

		boolean consume;

		try {
			consume = hit.getKeybind().callback().onPress();
		} catch (RuntimeException e) {
			LOGGER.error("[redtact-live] ホットキー {} の処理に失敗", hit.name(), e);
			consume = true;
		}

		if (consume) {
			CONSUMED.add(code);
		}

		return consume;
	}

	/** 実際には押されていないキーを覚えから外す (窓の外で離した・画面を開いている間に離した)。 */
	private static void prune() {
		Minecraft mc = Minecraft.getInstance();
		PRESSED.removeIf(code -> KeyNames.isMouse(code) ? !mouseDown(mc, KeyNames.mouseButton(code)) : !GameInput.isKeyDown(code));
	}

	private static boolean mouseDown(Minecraft mc, int button) {
		return switch (button) {
			case 1 -> mc.mouseHandler.isLeftPressed();
			case 2 -> mc.mouseHandler.isMiddlePressed();
			case 3 -> mc.mouseHandler.isRightPressed();
			default -> true; // 4 以降は覚えを信じる
		};
	}

	private static boolean inGame() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gui.screen() == null && mc.gui.overlay() == null && mc.player != null;
	}

	/** いま押していると覚えているキー (テスト用)。 */
	public static List<Integer> pressed() {
		return List.copyOf(PRESSED);
	}

	/** 覚えを消す (テスト用)。 */
	public static void reset() {
		PRESSED.clear();
		CONSUMED.clear();
	}
}
