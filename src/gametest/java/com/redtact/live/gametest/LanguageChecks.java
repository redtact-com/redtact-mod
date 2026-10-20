package com.redtact.live.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.redtact.live.config.Configs;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.SettingsScreen;
import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.i18n.Tr;
import com.redtact.live.i18n.UiLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * 画面の言語 (#79)。設定の「言語」を本物のマウスで日本語 → English に切り替えると、その場で設定の画面が英語になり、
 * メニューのどのタブもボタンの字・アイコンの名前・タブの名前に日本語が残らない。480×270 (1080p・GUI 4) で撮る。最後に日本語に戻す。
 */
@SuppressWarnings("UnstableApiUsage")
final class LanguageChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	private static final int TIMEOUT = 20 * 30;
	private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u4e00-\\u9fff\\uff00-\\uffef]");
	private static final MenuTab[] TABS = {MenuTab.SESSION, MenuTab.LIVE_LIST, MenuTab.RANGE, MenuTab.COMMENTS, MenuTab.LIBRARY, MenuTab.SETTINGS};

	private LanguageChecks() {
	}

	static void run(ClientGameTestContext context) {
		LiveClientGameTestShots.resize(context, new int[] {960, 540, 2}); // 480×270

		try {
			// 比べるために、日本語のどのタブも同じ大きさで撮っておく
			context.runOnClient(mc -> Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.JA));

			for (MenuTab tab : TABS) {
				open(context, tab);
				context.takeScreenshot("redtact-live-lang-ja-" + tab.name().toLowerCase(java.util.Locale.ROOT));
			}

			// 日本語の設定の画面で「言語」を押すと English に (自動 → 日本語 → English の順。いまは日本語)
			context.runOnClient(mc -> Menu.open(MenuTab.SETTINGS));
			context.waitForScreen(SettingsScreen.class);
			// カードの部品の位置は描くときに決まるので、描かれるまで待ってから押す
			context.waitFor(mc -> settings(mc).choiceButton(Configs.Generic.LANGUAGE) != null && settings(mc).choiceButton(Configs.Generic.LANGUAGE).getY() > 0,
					TIMEOUT);
			context.waitTicks(2);
			check("言語 (Language)".equals(context.computeOnClient(mc -> settings(mc).optionName(Configs.Generic.LANGUAGE))),
					"日本語の画面の「言語」の名前が違う");
			click(context, mc -> settings(mc).choiceButton(Configs.Generic.LANGUAGE));
			context.waitFor(mc -> Configs.Generic.LANGUAGE.getOptionListValue() == UiLanguage.EN && mc.gui.screen() instanceof SettingsScreen s
					&& "Language (言語)".equals(s.optionName(Configs.Generic.LANGUAGE)), TIMEOUT);
			check(Tr.english(), "English にしても英語にならない");
			check("Display name (unverified only)".equals(context.computeOnClient(mc -> settings(mc).optionName(Configs.Generic.USER))),
					"設定の名前が英語にならない: " + context.computeOnClient(mc -> settings(mc).optionName(Configs.Generic.USER)));

			// どのタブも、ボタンの字・アイコンの名前・タブの名前に日本語が残らない
			List<String> problems = new ArrayList<>();
			int checked = 0;

			for (MenuTab tab : TABS) {
				open(context, tab);
				List<String> texts = context.computeOnClient(mc -> texts(mc.gui.screen()));
				checked += texts.size();

				for (String t : texts) {
					if (JAPANESE.matcher(t).find()) {
						problems.add(tab + ": " + t);
					}
				}

				context.takeScreenshot("redtact-live-lang-en-" + tab.name().toLowerCase(java.util.Locale.ROOT));
			}

			LOGGER.info("[lang] English: 6 つのタブで {} 個の字を確かめ、日本語が残っていたのは {} 個", checked, problems.size());
			check(problems.isEmpty(), "英語の画面に日本語が残っている:\n" + String.join("\n", problems));
		} finally {
			context.runOnClient(mc -> {
				Configs.Generic.LANGUAGE.setOptionListValue(UiLanguage.JA);
				Configs.saveToFile();
			});
			context.setScreen(() -> null);
			LiveClientGameTestShots.restore(context);
		}

		LOGGER.info("language switch ok");
	}

	/** タブを開いて描かれるまで待ち、マウスを端に寄せる (撮る前)。 */
	private static void open(ClientGameTestContext context, MenuTab tab) {
		context.runOnClient(mc -> Menu.open(tab));
		context.waitFor(mc -> mc.gui.screen() instanceof MenuScreen m && m.tab() == tab, TIMEOUT);

		if (tab == MenuTab.LIBRARY) {
			context.waitFor(mc -> mc.gui.screen() instanceof LibraryScreen l && l.pathField() != null, TIMEOUT);
		}

		context.waitTicks(5);
		LiveClientGameTestShots.neutralCursor(context);
	}

	/**
	 * 画面のタブの名前・ボタンの字・アイコンのボタンの名前 (ツールチップの 1 行目)。字のボタンのツールチップは回路の名前などが入るので見ない。
	 */
	private static List<String> texts(Screen s) {
		List<String> out = new ArrayList<>();

		if (s instanceof MenuScreen m) {
			out.addAll(m.sectionLabels());
			out.addAll(m.subTabLabels());
		}

		for (GuiEventListener l : s.children()) {
			collect(l, out);
		}

		return out;
	}

	private static void collect(GuiEventListener l, List<String> out) {
		if (l instanceof RButton b) {
			if (b.icon() != null) {
				String tip = b.tooltipText();
				out.add(tip == null ? b.getMessage().getString() : tip.split("\n", 2)[0]);
			} else {
				out.add(b.getMessage().getString());
			}
		} else if (l instanceof CardList<?> list) {
			for (CardList.Card<?> c : list.cards()) {
				for (GuiEventListener w : c.children()) {
					collect(w, out);
				}
			}
		} else if (l instanceof ContainerEventHandler c) {
			for (GuiEventListener w : c.children()) {
				collect(w, out);
			}
		}
	}

	private static SettingsScreen settings(Minecraft mc) {
		return (SettingsScreen) mc.gui.screen();
	}

	private static void click(ClientGameTestContext context, java.util.function.Function<Minecraft, RButton> button) {
		double[] p = context.computeOnClient(mc -> {
			RButton b = button.apply(mc);
			check(b != null && b.active, "押せないボタン");
			return new double[] {b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
		context.waitTicks(2);
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}
}
