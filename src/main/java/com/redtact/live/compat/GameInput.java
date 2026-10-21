package com.redtact.live.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;

/**
 * マイクラの版で形が違う入力の呼び出し (#112)。26.3 は SDL3、26.2 までは GLFW。呼ぶ側は版を気にせずここを使う。
 */
public final class GameInput {
	private GameInput() {
	}

	/** そのキー (InputConstants.KEY_* と同じ番号) が押されているか。 */
	public static boolean isKeyDown(int key) {
		//? if >=26.3 {
		return InputConstants.isKeyDown(key);
		//?} else
		/*return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key);*/
	}

	/** カーソルを隠してマウスを閉じ込める (動いた量だけを受け取る)。x, y はいまのカーソルの位置。 */
	public static void grabMouse(Window window, double x, double y) {
		//? if >=26.3 {
		InputConstants.grabMouse(window, x, y);
		//?} else
		/*InputConstants.grabOrReleaseMouse(window, InputConstants.CURSOR_DISABLED, x, y);*/
	}

	/** 閉じ込めを解き、カーソルを x, y に戻す。 */
	public static void releaseMouse(Window window, double x, double y) {
		//? if >=26.3 {
		InputConstants.releaseMouse(window, x, y);
		//?} else
		/*InputConstants.grabOrReleaseMouse(window, InputConstants.CURSOR_NORMAL, x, y);*/
	}

	/** 文字入力を始める (26.3 は持ち主ごと。26.2 までは持ち主を見ない。1.21.x は文字入力の切り替えが無く、字はいつも届く)。 */
	public static void startTextInput(Minecraft mc, Object owner) {
		//? if >=26.3 {
		mc.textInputManager().startTextInput(owner);
		//?} elif >=26.1 {
		/*mc.textInputManager().startTextInput();
		*///?}
	}

	/** 文字入力を止める。 */
	public static void stopTextInput(Minecraft mc, Object owner) {
		//? if >=26.3 {
		mc.textInputManager().stopTextInput(owner);
		//?} elif >=26.1 {
		/*mc.textInputManager().stopTextInput();
		*///?}
	}
}
