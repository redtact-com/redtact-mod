package com.redtact.live.browser.cef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Pointer Lock の代役 (#88) の頼みの読み方と、ページへ返す JS。 */
class PointerLockBridgeTest {
	@Test
	void parsesOnlyPositiveSequenceNumbers() {
		assertEquals(1, PointerLockBridge.parseSeq("1"));
		assertEquals(123456789, PointerLockBridge.parseSeq("123456789"));
		assertEquals(0, PointerLockBridge.parseSeq(null));
		assertEquals(0, PointerLockBridge.parseSeq(""));
		assertEquals(0, PointerLockBridge.parseSeq("-1"));
		assertEquals(0, PointerLockBridge.parseSeq("1e3"));
		assertEquals(0, PointerLockBridge.parseSeq("1234567890"), "桁あふれ");
		assertEquals(0, PointerLockBridge.parseSeq("1);alert(1"));
	}

	@Test
	void movesUseDotsInAnyLocale() {
		Locale saved = Locale.getDefault();

		try {
			Locale.setDefault(Locale.GERMANY); // 小数点が , の地域でも JS の数になるように
			assertEquals("window.__redtactLivePointerLock && window.__redtactLivePointerLock._move(1.500,-0.250);", PointerLockBridge.move(1.5, -0.25));
		} finally {
			Locale.setDefault(saved);
		}
	}

	@Test
	void scriptAsksThroughTheInterceptedPath() {
		assertTrue(PointerLockBridge.SCRIPT.contains("var PATH = \"" + PointerLockBridge.PATH + "\";"));
		assertFalse(PointerLockBridge.SCRIPT.contains("%s"), "埋め込み忘れ");
		assertTrue(PointerLockBridge.heartbeat(7).contains(PointerLockBridge.PATH + "?op=unlock&seq=7"));
		assertTrue(PointerLockBridge.heartbeat(7).contains("_holds(7)"));
		assertEquals("window.__redtactLivePointerLock && window.__redtactLivePointerLock._granted(3);", PointerLockBridge.granted(3));
	}
}
