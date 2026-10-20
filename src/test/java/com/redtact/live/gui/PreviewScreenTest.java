package com.redtact.live.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 全画面のプレビューの撮影のファイル名 (#102。Windows で使えない字を _ に)。 */
class PreviewScreenTest {
	@Test
	void fileNameReplacesCharactersWindowsRejects() {
		assertEquals("a_b_c_d_e_f_g_h_i", PreviewScreen.fileName("a\\b/c:d*e?f\"g<h>i"));
		assertEquals("回路 1", PreviewScreen.fileName("回路 1"));
		assertEquals("x_y", PreviewScreen.fileName("x|y"));
		assertEquals("末尾", PreviewScreen.fileName("末尾..."));
		assertEquals("circuit", PreviewScreen.fileName("   "));
		assertEquals(60, PreviewScreen.fileName("あ".repeat(100)).length());
	}
}
