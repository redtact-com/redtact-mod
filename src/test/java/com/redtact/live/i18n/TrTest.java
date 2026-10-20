package com.redtact.live.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redtact.live.config.Configs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 画面の言語の切り替え (#79): 日本語の原文を鍵に英語を引く・値の差し込み・単数と複数・設定の「言語」。 */
class TrTest {
	@AfterEach
	void reset() {
		Tr.forceForTests(null);
		Tr.gameLanguage(() -> "ja_jp");
		Configs.Generic.LANGUAGE.resetToDefault();
	}

	@Test
	void japaneseIsTheSourceText() {
		Tr.forceForTests(false);
		assertEquals("2 回押す", Tr.t("2 回押す"));
		assertEquals("「全般」をすべて既定に戻す", Tr.f("「{0}」をすべて既定に戻す", "全般"));
	}

	@Test
	void englishLooksUpTheSourceText() {
		Tr.forceForTests(true);
		assertEquals("Press twice", Tr.t("2 回押す"));
		assertEquals("Reset all of \"General\" to defaults", Tr.f("「{0}」をすべて既定に戻す", "General"));
		// 対応表に無い文は日本語のまま
		assertEquals("対応表に無い文", Tr.t("対応表に無い文"));
	}

	@Test
	void pluralFormsPickByCount() {
		Tr.forceForTests(true);
		// 単数|複数 の訳を持つ文が無いときも、日本語のまま差し込む
		assertEquals("ほか 1 件", Tr.n("ほか {0} 件", 1));
		Tr.forceForTests(false);
		assertEquals("コメント 3 件・a", Tr.n("コメント {0} 件・{1}", 3, "a"));
	}

	@Test
	void fillLeavesUnknownBracesAlone() {
		assertEquals("a b {2} {x} {}", Tr.fill("{0} {1} {2} {x} {}", "a", "b"));
		assertEquals("{0}", Tr.fill("{0}"));
		assertEquals("10{0}", Tr.fill("{1}{0}", "{0}", 10)); // 入れた値の中の {0} はもう埋めない
	}

	@Test
	void autoFollowsTheGameLanguage() {
		Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.AUTO);
		Tr.gameLanguage(() -> "ja_jp");
		assertFalse(Tr.english());
		Tr.gameLanguage(() -> "en_us");
		assertTrue(Tr.english());
		Tr.gameLanguage(() -> "de_de");
		assertTrue(Tr.english(), "日本語以外は英語");
		Tr.gameLanguage(() -> null);
		assertFalse(Tr.english(), "分からなければ日本語");
	}

	@Test
	void settingOverridesTheGameLanguage() {
		Tr.gameLanguage(() -> "en_us");
		Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.JA);
		assertFalse(Tr.english());
		Tr.gameLanguage(() -> "ja_jp");
		Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.EN);
		assertTrue(Tr.english());
	}

	@Test
	void settingNamesComeFromTheLanguageFiles() {
		Tr.forceForTests(false);
		assertEquals("言語 (Language)", Tr.key("redtact-live.config.generic.name.language"));
		Tr.forceForTests(true);
		assertEquals("Language (言語)", Tr.key("redtact-live.config.generic.name.language"));
		assertEquals("no.such.key", Tr.key("no.such.key"));
	}

	@Test
	void languageNamesStayInTheirOwnLanguage() {
		Tr.forceForTests(true);
		assertEquals("日本語", UiLanguage.JA.label());
		assertEquals("English", UiLanguage.EN.label());
		assertEquals("Auto (follow the game)", UiLanguage.AUTO.label());
	}
}
