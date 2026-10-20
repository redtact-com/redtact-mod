package com.redtact.live.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.redtact.live.gui.kit.HostFace;

/** 未確認のホストの顔 (名前の 1 文字目。#56)。 */
class HostFaceTest {
	@Test
	void initialIsFirstCodePoint() {
		assertEquals("S", HostFace.initial("steve"));
		assertEquals("あ", HostFace.initial("  あいう "));
		assertEquals("𠮷", HostFace.initial("𠮷野家"), "サロゲートペアは 1 文字");
		assertEquals("?", HostFace.initial(""));
		assertEquals("?", HostFace.initial(null));
	}
}
