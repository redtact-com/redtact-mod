package com.redtact.live.browser.cef;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.KeyEvent;

import org.junit.jupiter.api.Test;

/** Linux の jcef の修飾キーの補正 (#88)。ページの code の補正は {@link PageKeyCodesTest}。 */
class LinuxKeysTest {
	@Test
	void modifiersBecomeTheirLeftKeysyms() {
		// jcef はこれを X の keysym とみなし、keyCode を VK_SHIFT・VK_CONTROL・VK_MENU・VK_LWIN にする
		assertEquals(0xffe1, LinuxKeys.keyChar(KeyEvent.VK_SHIFT), "XK_Shift_L");
		assertEquals(0xffe3, LinuxKeys.keyChar(KeyEvent.VK_CONTROL), "XK_Control_L");
		assertEquals(0xffe9, LinuxKeys.keyChar(KeyEvent.VK_ALT), "XK_Alt_L");
		assertEquals(0xffeb, LinuxKeys.keyChar(KeyEvent.VK_META), "XK_Super_L");
		assertEquals(0xffeb, LinuxKeys.keyChar(KeyEvent.VK_WINDOWS), "XK_Super_L");
	}

	@Test
	void otherKeysStayAsMcefSendsThem() {
		assertEquals('W', LinuxKeys.keyChar(KeyEvent.VK_W), "字は MCEF Modern と同じ (keyCode は jcef で正しくなる)");
		assertEquals(' ', LinuxKeys.keyChar(KeyEvent.VK_SPACE));
		assertEquals((char) KeyEvent.VK_BACK_SPACE, LinuxKeys.keyChar(KeyEvent.VK_BACK_SPACE), "jcef が自分で直す 9 つのキー");
	}
}
