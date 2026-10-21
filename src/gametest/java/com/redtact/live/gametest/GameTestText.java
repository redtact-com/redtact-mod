package com.redtact.live.gametest;

//? if >=26.3 {
import org.lwjgl.sdl.SDLKeyboard;
//?}

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/**
 * 文字入力とキーの擬似入力の、マイクラの版で違うところ (#112)。26.3 は SDL3、26.2 までは GLFW。
 */
final class GameTestText {
	/** ゲームが文字入力を始めたり止めたりするか。1.21.x は切り替えが無く字はいつも届くので、止まったことは確かめない (#112)。 */
	//? if >=26.1 {
	static final boolean TEXT_INPUT_TOGGLES = true;
	//?} else
	/*static final boolean TEXT_INPUT_TOGGLES = false;*/

	private GameTestText() {
	}

	/**
	 * ゲームが文字入力を受け付けている (実機で字がページへ届く) か。26.1〜26.2 は TextInputManager の印を読む。1.21.x は文字入力の
	 * 切り替えが無く、字はいつも届く。
	 */
	static boolean textInputActive(Minecraft mc) {
		//? if >=26.3 {
		return SDLKeyboard.SDL_TextInputActive(mc.getWindow().handle());
		//?} elif >=26.1 {
		/*return field(mc.textInputManager(), "textInputEnabled");
		*///?} else {
		/*return true;
		*///?}
	}

	/** バニラのキーの入口 (KeyboardHandler#keyPress) へ直に入れる。26.2 までは private なので反射で。 */
	static void keyPress(Minecraft mc, int action, KeyEvent event) {
		//? if >=26.3 {
		mc.keyboardHandler.keyPress(mc.getWindow().handle(), action, event);
		//?} else {
		/*try {
			java.lang.reflect.Method m = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
			m.setAccessible(true);
			m.invoke(mc.keyboardHandler, mc.getWindow().handle(), action, event);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
		*///?}
	}

	//? if <26.3 {
	/*private static boolean field(Object target, String name) {
		try {
			java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.getBoolean(target);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
	*///?}
}
