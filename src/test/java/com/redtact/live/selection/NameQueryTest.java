package com.redtact.live.selection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NameQueryTest {
	@Test
	void emptyMatchesEverything() {
		assertTrue(NameQuery.of("").matches("自動仕分け機"));
		assertTrue(NameQuery.of("   ").matches("x"));
		assertTrue(NameQuery.of(null).isEmpty());
	}

	@Test
	void substringIgnoringCaseAndWidth() {
		assertTrue(NameQuery.of("仕分け").matches("自動仕分け機"));
		assertTrue(NameQuery.of("v2").matches("仕分け機 V2"));
		assertTrue(NameQuery.of("ｖ２").matches("仕分け機 V2")); // 全角
		assertTrue(NameQuery.of("Sorter").matches("item sorter"));
		assertFalse(NameQuery.of("クロック").matches("自動仕分け機"));
		assertFalse(NameQuery.of("x").matches(null));
	}

	@Test
	void allTermsMustMatch() {
		assertTrue(NameQuery.of("仕分け  v2").matches("仕分け機 v2"));
		assertTrue(NameQuery.of("仕分け　v2").matches("仕分け機 v2")); // 全角の空白でも区切る
		assertFalse(NameQuery.of("仕分け v3").matches("仕分け機 v2"));
	}
}
