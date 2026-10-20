package com.redtact.live.gui;

import java.util.EnumSet;
import java.util.Set;

import com.redtact.live.browser.BrowserSupport;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * メニューの出入り口。画面ごとに Screen を分け、上のメインタブ (階) と live の子タブで行き来する (#51)。
 * 最後に見ていた画面と、live の中で最後に見ていた子タブを覚える (ゲームを閉じるまで)。
 */
public final class Menu {
	/** 最後に見ていた画面。最初は redtact (#51)。 */
	private static MenuTab current = MenuTab.REDTACT;
	/** live の中で最後に見ていた子タブ (メインタブの「live」で開く)。 */
	private static MenuTab lastLive = MenuTab.SESSION;
	/** 子タブの段 (サブヘッダ) を隠しているメインタブ。redtact・live・保管庫 (#70) で別々に、ゲームを閉じるまで覚える (#51)。 */
	private static final Set<MenuSection> HIDDEN_SUB_BARS = EnumSet.noneOf(MenuSection.class);
	/** /rlive コマンドから開くとき (チャット画面が閉じた後に開く)。 */
	private static @Nullable Runnable pending;
	private static int pendingDelay;

	private Menu() {
	}

	public static MenuTab current() {
		return current;
	}

	/** live の中で最後に見ていた子タブ。 */
	public static MenuTab lastLive() {
		return lastLive;
	}

	/** そのメインタブの子タブの段を隠しているか。 */
	public static boolean subBarHidden(MenuSection section) {
		return HIDDEN_SUB_BARS.contains(section);
	}

	/** そのメインタブの子タブの段を隠す・出す (ヘッダの右端のトグル)。 */
	public static void toggleSubBar(MenuSection section) {
		if (!HIDDEN_SUB_BARS.remove(section)) {
			HIDDEN_SUB_BARS.add(section);
		}
	}

	/** 最後に見ていた画面を開く。 */
	public static void open() {
		open(current);
	}

	public static void open(MenuTab tab) {
		remember(tab);
		Minecraft.getInstance().gui.setScreen(create(tab));
	}

	/** メインタブを開く (live は最後に見ていた子タブ)。 */
	public static void open(MenuSection section) {
		open(section == MenuSection.LIVE ? lastLive : section.pages().getFirst());
	}

	/** redtact タブを開き、ゲーム内の redtact で url を出す (null ならいまのページのまま)。用意がまだなら入口の画面。 */
	public static void openRedtact(@Nullable String url) {
		remember(MenuTab.REDTACT);
		Minecraft.getInstance().gui.setScreen(BrowserSupport.tabScreen(url));
	}

	/** コメントタブを、指定した回路を選んだ状態で開く。 */
	public static void openComments(@Nullable String circuitId) {
		remember(MenuTab.COMMENTS);
		Minecraft.getInstance().gui.setScreen(new CommentsScreen(circuitId));
	}

	private static void remember(MenuTab tab) {
		current = tab;

		if (tab.section() == MenuSection.LIVE) {
			lastLive = tab;
		}
	}

	/** メニューの画面か。 */
	public static boolean isMenu(@Nullable Screen screen) {
		return screen instanceof MenuScreen;
	}

	public static Screen create(MenuTab tab) {
		return switch (tab) {
			case REDTACT -> BrowserSupport.tabScreen(null);
			case SESSION -> new SessionScreen();
			case LIVE_LIST -> new LiveListScreen();
			case RANGE -> new RangeScreen();
			case COMMENTS -> new CommentsScreen(null);
			case LIBRARY -> new LibraryScreen();
			case SETTINGS -> new SettingsScreen();
		};
	}

	/** 次の tick 以降に開く (コマンドを打ったチャット画面が閉じるのを待つ)。 */
	public static void openLater(Runnable open) {
		pending = open;
		pendingDelay = 2;
	}

	/** クライアントの tick ごとに呼ぶ。 */
	public static void tick(Minecraft mc) {
		if (pending != null && --pendingDelay <= 0) {
			Runnable r = pending;
			pending = null;
			r.run();
		}
	}
}
