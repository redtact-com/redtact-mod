package com.redtact.live.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** メインタブ (redtact・live・保管庫・設定) と live の子タブの並び (#51)。 */
class MenuSectionTest {
	@Test
	void mainTabsAreRedtactLiveLibrarySettings() {
		assertEquals(List.of("redtact", "live", "保管庫", "設定"), Arrays.stream(MenuSection.values()).map(MenuSection::label).toList());
	}

	@Test
	void liveHasFourSubTabsInOrder() {
		assertEquals(List.of(MenuTab.SESSION, MenuTab.LIVE_LIST, MenuTab.RANGE, MenuTab.COMMENTS), MenuSection.LIVE.pages());
		assertEquals(List.of("セッション", "開催中", "範囲", "コメント"), MenuSection.LIVE.pages().stream().map(MenuTab::label).toList());
		assertTrue(MenuSection.LIVE.hasSubTabs());
	}

	@Test
	void otherMainTabsHaveOnePageAndNoSubTabs() {
		assertEquals(List.of(MenuTab.REDTACT), MenuSection.REDTACT.pages());
		assertEquals(List.of(MenuTab.LIBRARY), MenuSection.LIBRARY.pages());
		assertEquals(List.of(MenuTab.SETTINGS), MenuSection.SETTINGS.pages());

		for (MenuSection s : List.of(MenuSection.REDTACT, MenuSection.LIBRARY, MenuSection.SETTINGS)) {
			assertFalse(s.hasSubTabs(), s.name());
		}
	}

	@Test
	void subBarIsHiddenPerMainTab() {
		assertFalse(Menu.subBarHidden(MenuSection.REDTACT));
		assertFalse(Menu.subBarHidden(MenuSection.LIVE));
		Menu.toggleSubBar(MenuSection.REDTACT);

		try {
			assertTrue(Menu.subBarHidden(MenuSection.REDTACT));
			assertFalse(Menu.subBarHidden(MenuSection.LIVE), "redtact の帯を隠しても live の子タブは出たまま");
		} finally {
			Menu.toggleSubBar(MenuSection.REDTACT);
		}

		assertFalse(Menu.subBarHidden(MenuSection.REDTACT), "もう一度押すと出る");
	}

	@Test
	void everyPageBelongsToItsMainTab() {
		for (MenuTab t : MenuTab.values()) {
			assertTrue(t.section().pages().contains(t), t.name());
		}

		assertEquals(MenuTab.values().length, Arrays.stream(MenuSection.values()).mapToInt(s -> s.pages().size()).sum());
	}
}
