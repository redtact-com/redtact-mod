package com.redtact.live.browser.cef;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ゲーム内の redtact のページの code の補正 (Linux は #88・Windows は #94)。 */
class PageKeyCodesTest {
	@Test
	void neededOnLinuxAndWindows() {
		assertTrue(PageKeyCodes.needed("Linux"), "jcef が keyChar を X の keysym として渡し、code が違うキーになる");
		assertTrue(PageKeyCodes.needed("Windows 11"), "jcef が lParam の形で渡し、code が空になる");
		assertTrue(PageKeyCodes.needed("Windows 10"));
		assertTrue(PageKeyCodes.needed("Windows Server 2022"));
	}

	@Test
	void notNeededElsewhere() {
		assertFalse(PageKeyCodes.needed("Mac OS X"), "試していないので入れない");
		assertFalse(PageKeyCodes.needed(""));
	}

	@Test
	void scriptOnlyTouchesTrustedKeyDownAndUp() {
		assertTrue(PageKeyCodes.SCRIPT.contains("this.isTrusted"));
		assertTrue(PageKeyCodes.SCRIPT.contains("\"keydown\"") && PageKeyCodes.SCRIPT.contains("\"keyup\""));
		assertTrue(PageKeyCodes.SCRIPT.contains("if (window.__redtactLiveCodeFix) return;"), "同じページに 2 回入れても何もしない");
		assertFalse(PageKeyCodes.SCRIPT.contains("%"), "書式の埋め込みは無い");
	}
}
