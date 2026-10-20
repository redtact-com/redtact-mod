package com.redtact.live.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.redtact.live.config.ConfigBoolean;
import com.redtact.live.config.ConfigChoice;
import com.redtact.live.config.ConfigHotkey;
import com.redtact.live.config.ConfigInteger;
import com.redtact.live.config.ConfigOption;
import com.redtact.live.config.ConfigString;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.cards.HeaderCard;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.ConfirmButton;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RNumberField;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.RToggle;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.input.KeyDisplay;
import com.redtact.live.input.KeyNames;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * メニューの「設定」: 全般の項目とホットキー。変えたらその場で効き、設定ファイル ({@code config/redtact-live.json}) に書く。
 *
 * <p>ホットキーは同時押しのまま (R を押しながら L など)。ボタンを押してから、押したい順にキーを押して離すと決まる
 * (Esc でやめる・右クリックで割り当てを外す)。
 */
public final class SettingsScreen extends MenuScreen {
	/** 段の名前 (日本語の原文。見出しの ↺ の名前とテストで使う)。 */
	public static final String GENERAL = Tr.ja("全般");
	public static final String HOTKEYS = Tr.ja("ホットキー");
	private @Nullable CardList<MenuCard> list;
	private @Nullable HotkeyCard capturing;
	private final List<Integer> captured = new ArrayList<>();
	private final Set<Integer> held = new HashSet<>();

	public SettingsScreen() {
		super(MenuTab.SETTINGS);
	}

	@Override
	protected void initContent() {
		capturing = null;
		list = addRenderableWidget(new CardList<>(minecraft, MARGIN, top(), contentWidth(), Math.max(30, height - top() - 6)));
		List<MenuCard> cards = new ArrayList<>();
		cards.add(new SectionCard(Tr.f("全般 (config/{0})", Configs.FILE_NAME), GENERAL, generalOptions()));

		for (ConfigOption o : generalOptions()) {
			cards.add(card(o));
		}

		cards.add(new SectionCard(Tr.t("ホットキー (ゲーム中だけ反応。同時押しは押す順に。右クリックで割り当てを外す)"), HOTKEYS, Configs.Hotkeys.HOTKEY_LIST));

		for (ConfigHotkey h : Configs.Hotkeys.HOTKEY_LIST) {
			cards.add(new HotkeyCard(h));
		}

		list.setCards(cards);
	}

	/** 画面に出す全般の項目 (中継サーバと redtact の URL は設定ファイルでだけ変える。#51)。 */
	private static List<ConfigOption> generalOptions() {
		return Configs.Generic.OPTIONS.stream().filter(o -> !Configs.Generic.FILE_ONLY.contains(o)).toList();
	}

	/** 段の全部を既定に戻す (#77。見出しの ↺ を 2 回押したとき)。作り直しても見ていた所のまま (ホットキーで押しても上へ飛ばない)。 */
	private void resetSection(List<? extends ConfigOption> options) {
		stopCapture(false);
		options.forEach(ConfigOption::resetToDefault);
		save();
		double scroll = scrollAmount();
		rebuildWidgets();

		if (list != null) {
			list.setScrollAmount(scroll);
		}
	}

	/** 一覧を送った量 (テスト用)。 */
	public double scrollAmount() {
		return list == null ? 0 : list.scrollAmount();
	}

	private MenuCard card(ConfigOption o) {
		return switch (o) {
			case ConfigBoolean b -> new BoolCard(b);
			case ConfigInteger i -> new IntCard(i);
			case ConfigString s -> new StringCard(s);
			case ConfigChoice<?> c -> new ChoiceCard(c);
			default -> new HeaderCard(o.name());
		};
	}

	/** 設定の名前と説明は言語ファイルの鍵から、いまの言語で (#79。マイクラの言語ではなく MOD の「言語」に従う)。 */
	private static String label(String group, ConfigOption o) {
		return Tr.key("redtact-live.config." + group + ".name." + o.name());
	}

	private static String comment(String group, ConfigOption o) {
		return Tr.key("redtact-live.config." + group + ".comment." + o.name());
	}

	private static void save() {
		Configs.saveToFile();
	}

	@Override
	public void removed() {
		super.removed();
		save();
	}

	// ---- ホットキーの入力

	private void startCapture(HotkeyCard card) {
		capturing = card;
		captured.clear();
		held.clear();
		card.refresh();
	}

	private void stopCapture(boolean commit) {
		HotkeyCard card = capturing;
		capturing = null;

		if (card == null) {
			return;
		}

		if (commit && !captured.isEmpty()) {
			card.hotkey.getKeybind().setKeys(captured);
			save();
		}

		captured.clear();
		held.clear();
		card.refresh();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (capturing != null) {
			if (event.isEscape()) {
				stopCapture(false);
			} else {
				press(event.key());
			}

			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (capturing != null) {
			release(event.key());
			return true;
		}

		return super.keyReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (capturing != null) {
			press(KeyNames.mouseCode(event.button()));
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (capturing != null) {
			release(KeyNames.mouseCode(event.button()));
			return true;
		}

		return super.mouseReleased(event);
	}

	private void press(int code) {
		if (KeyNames.name(code) == null) {
			return; // 設定ファイルに書けないキー
		}

		if (!captured.contains(code)) {
			captured.add(code);
		}

		held.add(code);
		capturing.refresh();
	}

	/** 押したキーをすべて離したら決まる。 */
	private void release(int code) {
		if (!held.remove(code)) {
			return; // 入力を始める前から押していたキー (ボタンを押したクリックなど)
		}

		if (held.isEmpty()) {
			stopCapture(true);
		}
	}

	/** そのホットキーの入力を始める (テスト用。「設定」の行のボタンを押したのと同じ)。 */
	public void startCaptureFor(ConfigHotkey hotkey) {
		if (list == null) {
			return;
		}

		for (MenuCard c : list.cards()) {
			if (c instanceof HotkeyCard h && h.hotkey == hotkey) {
				startCapture(h);
				return;
			}
		}
	}

	/** ホットキーの見出しが一覧の上端に来るまで送る (テスト用)。 */
	public void scrollToHotkeys() {
		if (list == null) {
			return;
		}

		for (MenuCard c : list.cards()) {
			if (c instanceof SectionCard h && h.section.equals(HOTKEYS)) {
				list.scrollToTop(c);
				return;
			}
		}
	}

	/** 段の全部を既定に戻すボタン (#77。section は {@link #GENERAL} か {@link #HOTKEYS}。テスト用)。 */
	public @Nullable ConfirmButton sectionResetButton(String section) {
		if (list == null) {
			return null;
		}

		for (MenuCard c : list.cards()) {
			if (c instanceof SectionCard h && h.section.equals(section)) {
				return h.reset;
			}
		}

		return null;
	}

	/** その項目のカード (テスト用)。 */
	public @Nullable MenuCard cardFor(ConfigOption option) {
		if (list == null) {
			return null;
		}

		for (MenuCard c : list.cards()) {
			if (c instanceof OptionCard o && o.option == option) {
				return c;
			}
		}

		return null;
	}

	/** 選択の項目のボタン (言語など。テスト用)。 */
	public @Nullable RButton choiceButton(ConfigChoice<?> option) {
		return cardFor(option) instanceof ChoiceCard c ? c.button : null;
	}

	/** 項目の名前 (いまの言語。テスト用)。 */
	public @Nullable String optionName(ConfigOption option) {
		return cardFor(option) instanceof OptionCard o ? o.name : null;
	}

	/** そのホットキーのボタン (テスト用)。 */
	public @Nullable RButton hotkeyButton(ConfigHotkey hotkey) {
		return cardFor(hotkey) instanceof HotkeyCard h ? h.button : null;
	}

	/** いま入力中のホットキー (テスト用)。 */
	public @Nullable ConfigHotkey capturing() {
		return capturing == null ? null : capturing.hotkey;
	}

	// ---- カード

	/** カードの「既定に戻す」の押せる・押せないと、段の見出しの ↺ を直す (どれかを変えたあと)。 */
	private void refreshAll() {
		if (list == null) {
			return;
		}

		for (MenuCard c : list.cards()) {
			if (c instanceof OptionCard o) {
				o.refresh();
			} else if (c instanceof SectionCard sc) {
				sc.refresh();
			}
		}
	}

	/**
	 * 段の見出し (#77): 左に銅の印と名前、右端に段の全部を既定に戻す ↺ (2 回押す。{@link ConfirmButton})。
	 * 段の全部が既定のままなら押せない。
	 */
	private final class SectionCard extends MenuCard {
		final String text;
		/** 段の名前 ({@link #GENERAL} か {@link #HOTKEYS}。日本語の原文のまま持ち、出すときに訳す)。 */
		final String section;
		final List<? extends ConfigOption> options;
		final ConfirmButton reset;

		SectionCard(String text, String section, List<? extends ConfigOption> options) {
			this.text = text;
			this.section = section;
			this.options = options;
			this.reset = add(ConfirmButton.icon(0, 0, Icon.RESET, Tr.f("「{0}」をすべて既定に戻す", Tr.t(section)),
					Tr.f("もう一度押すと「{0}」をすべて既定に戻す", Tr.t(section)), RButton.Variant.SECONDARY, b -> resetSection(options))
					.tooltip(Tr.t("2 回押す")));
			refresh();
		}

		private @Nullable Boolean shownAllDefault;

		/** 段の全部が既定のままなら押せない (変わったときだけツールチップを作り直す)。 */
		void refresh() {
			boolean allDefault = options.stream().allMatch(ConfigOption::isDefault);

			if (shownAllDefault == null || shownAllDefault != allDefault) {
				shownAllDefault = allDefault;
				reset.disabledReason(allDefault ? Tr.t("すべて既定のままです") : null);
			}
		}

		@Override
		public int height() {
			return RButton.H + 2;
		}

		@Override
		protected boolean framed() {
			return false;
		}

		@Override
		protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
			refresh(); // 項目を変えたら押せるようになる
			int left = layoutRight(cardX() + cardW() - 5, cardY() + 1, reset);
			int ty = cardY() + (height() - 8) / 2;
			g.fill(cardX() + 1, ty, cardX() + 3, ty + 8, Style.COP);
			Draw.clippedText(g, font(), text, cardX() + 7, ty, Math.max(0, left - 4 - (cardX() + 7)), Style.COP_TX, mouseX, mouseY);
		}
	}

	/** 設定の 1 項目: 1 行目に名前、2 行目に説明、右に入力と「既定に戻す」(↺)。 */
	private abstract class OptionCard extends MenuCard {
		final ConfigOption option;
		final String name;
		final String help;
		final RButton reset;

		OptionCard(String group, ConfigOption option) {
			this.option = option;
			this.name = label(group, option);
			this.help = comment(group, option);
			this.reset = add(RButton.icon(0, 0, Icon.RESET, Tr.t("既定に戻す"), RButton.Variant.GHOST, b -> {
				option.resetToDefault();
				save();
				refreshAll();
			}).tooltip(Tr.t("この項目を既定の値に戻す")));
		}

		/** 入力の部品 (右から左へ並べる順。既定のボタンは除く)。 */
		abstract AbstractWidget[] controls();

		/** 値が外から変わったら入力の表示を直す。 */
		void refresh() {
			reset.enabled(!option.isDefault());
		}

		@Override
		public int height() {
			return TWO_LINES;
		}

		@Override
		protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
			List<AbstractWidget> ws = new ArrayList<>();
			ws.add(reset);
			ws.addAll(List.of(controls()));
			int left = layoutRight(cardX() + cardW() - 5, buttonY(), ws.toArray(new AbstractWidget[0]));
			int x = cardX() + 6;
			Draw.clippedText(g, font(), name, x, cardY() + 4, left - x - 4, Style.TXT, mouseX, mouseY);
			Draw.clippedText(g, font(), help, x, cardY() + 15, left - x - 4, Style.MUT, mouseX, mouseY);
		}
	}

	private final class BoolCard extends OptionCard {
		final ConfigBoolean value;
		final RToggle toggle;

		BoolCard(ConfigBoolean value) {
			super("generic", value);
			this.value = value;
			this.toggle = add(RToggle.of(font(), 0, 0, "OFF", false, b -> {
				value.setBooleanValue(!value.getBooleanValue());
				save();
				refresh();
			}));
			toggle.setWidth(RToggle.widthFor(font(), "OFF"));
			toggle.tooltip(help);
			refresh();
		}

		@Override
		AbstractWidget[] controls() {
			return new AbstractWidget[] {toggle};
		}

		@Override
		void refresh() {
			super.refresh();
			boolean on = value.getBooleanValue();
			toggle.on(on).label(on ? "ON" : "OFF");
		}
	}

	private final class IntCard extends OptionCard {
		final ConfigInteger value;
		final RNumberField field;

		IntCard(ConfigInteger value) {
			super("generic", value);
			this.value = value;
			this.field = add(new RNumberField(font(), 0, 0, 70, Component.literal(name)));
			field.setValue(String.valueOf(value.getIntegerValue()));
			field.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(Tr.f("{0} ({1}〜{2})", help, value.getMinIntegerValue(),
					value.getMaxIntegerValue()))));
			field.setResponder(text -> {
				try {
					value.setIntegerValue(Integer.parseInt(text.trim()));
					reset.enabled(!value.isDefault());
				} catch (NumberFormatException ignored) {
					// 入力途中
				}
			});
			refresh();
		}

		@Override
		AbstractWidget[] controls() {
			return new AbstractWidget[] {field};
		}

		@Override
		void refresh() {
			super.refresh();
			String now = String.valueOf(value.getIntegerValue());

			if (!field.getValue().equals(now)) {
				field.setValue(now);
			}
		}
	}

	private final class StringCard extends OptionCard {
		final ConfigString value;
		final RTextField field;

		StringCard(ConfigString value) {
			super("generic", value);
			this.value = value;
			this.field = add(new RTextField(font(), 0, 0, 150, Component.literal(name)));
			field.setMaxLength(256);
			field.setValue(value.getStringValue());
			field.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(help)));
			field.setResponder(text -> {
				value.setValueFromString(text);
				reset.enabled(!value.isDefault());
			});
			refresh();
		}

		@Override
		AbstractWidget[] controls() {
			return new AbstractWidget[] {field};
		}

		@Override
		void refresh() {
			super.refresh();

			if (!field.getValue().equals(value.getStringValue())) {
				field.setValue(value.getStringValue());
			}
		}
	}

	private final class ChoiceCard extends OptionCard {
		final ConfigChoice<?> value;
		final RButton button;

		ChoiceCard(ConfigChoice<?> value) {
			super("generic", value);
			this.value = value;
			this.button = add(new RButton(0, 0, 60, RButton.H, Component.empty(), RButton.Variant.SECONDARY, b -> {
				value.cycle(b.rightClick() ? -1 : 1);
				save();

				if (value == Configs.Generic.LANGUAGE) {
					minecraft.execute(SettingsScreen.this::rebuildWidgets); // 言語を変えたら字を全部作り直す (#79)
					return;
				}

				refresh();
			}).acceptRightClick().tooltip(Tr.f("{0} (左クリックで次・右クリックで前)", help)));
			refresh();
		}

		@Override
		AbstractWidget[] controls() {
			return new AbstractWidget[] {button};
		}

		@Override
		void refresh() {
			super.refresh();
			button.label(value.getOptionListValue().label());
		}
	}

	/** ホットキー: 今の組み合わせのボタン (押すと入力を始める) と「既定」。 */
	private final class HotkeyCard extends OptionCard {
		final ConfigHotkey hotkey;
		final RButton button;

		HotkeyCard(ConfigHotkey hotkey) {
			super("hotkeys", hotkey);
			this.hotkey = hotkey;
			this.button = add(new RButton(0, 0, 110, RButton.H, Component.empty(), RButton.Variant.SECONDARY, b -> {
				if (b.rightClick()) {
					hotkey.getKeybind().setKeys(List.of());
					save();
					refresh();
				} else {
					startCapture(this);
				}
			}).acceptRightClick().tooltip(Tr.f("{0} (押してから、押したい順にキーを押して離す。Esc でやめる・右クリックで外す)", help)));
			refresh();
		}

		@Override
		AbstractWidget[] controls() {
			return new AbstractWidget[] {button};
		}

		@Override
		void refresh() {
			super.refresh();

			if (capturing == this) {
				button.variant(RButton.Variant.PRIMARY).label(captured.isEmpty() ? Tr.t("> キーを押す <") : "> " + KeyDisplay.combo(captured) + " <");
			} else {
				button.variant(RButton.Variant.SECONDARY).label(KeyDisplay.combo(hotkey.getKeybind().getKeys()));
			}
		}
	}
}
