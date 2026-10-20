package com.redtact.live.gui;

import java.util.ArrayList;
import java.util.List;

import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.gui.kit.ClipText;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * メニューの画面の土台。redtact のヘッダと同じく、上の帯にロゴ (REDTACT) とメインタブ (階のボタン) を並べ、
 * 後ろに暗い幕を敷く (ワールドは少し見える)。ゲームは止めない。
 *
 * <p>メインタブは redtact・live・保管庫・設定 (#51)。live の画面ではヘッダの下に子タブの段 (セッション・開催中・範囲・コメント。
 * redtact の .el-tabbar と同じく、いまの子タブは板の地と銅の下線) を出す。redtact のブラウザはこの段に戻る・進むなどの帯を置く。
 * live とコメントの子タブには未読のコメントの数を出す。
 * 子タブの段 (サブヘッダ) はヘッダの右端のトグル (▲ で隠す・▼ で出す) で隠せる。メインタブごとに別々に覚える ({@link Menu#subBarHidden})。
 * 保管庫は場所と検索の段をここに置く (#70)。トグルの左には、その画面だけのトグル ({@link #headerToggles()}。保管庫の立体のプレビューなど) を並べる。
 */
public abstract class MenuScreen extends Screen {
	/** ヘッダの帯の高さ (redtact のページを広くするため低め。#51)。 */
	public static final int BAR_H = 20;
	/** 子タブの段の上端と高さ (この下に 1 px の線)。 */
	public static final int SUB_Y = BAR_H + 1;
	public static final int SUB_H = 18;
	/** 左右の余白。 */
	public static final int MARGIN = 8;
	private static final int TAB_H = 16;
	/** 帯と中身の間。 */
	private static final int GAP = 5;
	private static final String LOGO = "REDTACT";
	private final MenuTab tab;
	private final List<TabButton> tabs = new ArrayList<>();
	private final List<SubTabButton> subTabs = new ArrayList<>();
	/** 子タブの段を隠す・出すトグル (段がある画面だけ)。 */
	private @Nullable SubBarToggle subBarToggle;
	/** その画面だけのヘッダの右端のトグル (段のトグルの左に右から並べる)。 */
	private final List<HeaderIconToggle> headerButtons = new ArrayList<>();
	/** ヘッダの右端に並べた部品の左端 (無ければ右の余白の端)。 */
	private int controlsLeft;
	private boolean showLogo;
	/** メインタブの右端 (ヘッダの空きの左端)。 */
	private int tabsRight;

	protected MenuScreen(MenuTab tab) {
		super(Component.literal("redtact"));
		this.tab = tab;
	}

	public MenuTab tab() {
		return tab;
	}

	/** この画面に子タブの段があるか (隠しているかは別)。live は子タブ、redtact のブラウザは戻る・進むなどの帯をこの段に置く。 */
	protected boolean subBarAvailable() {
		return tab.section().hasSubTabs();
	}

	/**
	 * ヘッダの右端 (段のトグルの左) に置く、その画面だけのトグル (#70)。右から並べる。画面を組むたびに呼ぶ。
	 *
	 * @param name    名前 (ツールチップの先頭・読み上げ・テスト)
	 * @param on      押し込んだ面で描く (出している・効いている)
	 * @param tooltip 押すと何が起きるか
	 */
	public record HeaderToggle(Icon icon, String name, boolean on, String tooltip, Runnable toggle) {
	}

	protected List<HeaderToggle> headerToggles() {
		return List.of();
	}

	/** 子タブの段の呼び名 (ヘッダの右端のトグルの説明に出す)。保管庫は場所と検索の段 (#70)。 */
	protected String subBarName() {
		return tab.section() == MenuSection.REDTACT ? Tr.t("戻る・進むなどの帯") : Tr.t("子タブの段");
	}

	/** 子タブの段を出しているか (ヘッダの右端のトグルで隠せる)。 */
	protected final boolean hasSubBar() {
		return subBarAvailable() && !Menu.subBarHidden(tab.section());
	}

	/**
	 * 帯 (ヘッダと子タブの段) を隠すか (#77。開催中の詳細の拡大)。隠すとタブもトグルも作らず、中身が画面の上端から始まる。
	 * 戻る手段 (ボタンや Esc) はその画面が持つ。
	 */
	protected boolean barsHidden() {
		return false;
	}

	/** 帯 (ヘッダと子タブの段) の下端。この下から中身。 */
	protected final int barBottom() {
		if (barsHidden()) {
			return 0;
		}

		return hasSubBar() ? SUB_Y + SUB_H + 1 : BAR_H + 1;
	}

	/** 中身の上端 (テストからも読む)。 */
	public final int top() {
		return barBottom() + GAP;
	}

	@Override
	protected final void init() {
		tabs.clear();
		subTabs.clear();
		headerButtons.clear();
		subBarToggle = null;

		if (!barsHidden()) {
			addTabs();
		}

		initContent();
	}

	/**
	 * メニューを閉じる (Esc)。ゲーム内の redtact で編集中なら、redtact タブで確認してから閉じる (#67。
	 * {@link BrowserSupport#closeMenu})。
	 */
	@Override
	public void onClose() {
		BrowserSupport.closeMenu(super::onClose);
	}

	/** 中身の部品を作る (タブのあと)。画面の大きさが変わるたびに呼ばれる。 */
	protected abstract void initContent();

	private void addTabs() {
		for (MenuSection s : MenuSection.values()) {
			tabs.add(addRenderableWidget(new TabButton((BAR_H - TAB_H) / 2, s, s == tab.section())));
		}

		if (tab.section().hasSubTabs() && hasSubBar()) {
			for (MenuTab t : tab.section().pages()) {
				subTabs.add(addRenderableWidget(new SubTabButton(SUB_Y, t, t == tab)));
			}
		}

		if (subBarAvailable()) {
			boolean shown = hasSubBar();
			String what = subBarName();
			subBarToggle = addRenderableWidget(new SubBarToggle((BAR_H - TAB_H) / 2, shown, shown ? Tr.f("{0}を隠して広くする", what) : Tr.f("{0}を出す", what), () -> {
				Menu.toggleSubBar(tab.section());
				rebuildWidgets();
			}));
		}

		for (HeaderToggle t : headerToggles()) {
			headerButtons.add(addRenderableWidget(new HeaderIconToggle((BAR_H - TAB_H) / 2, t)));
		}

		layoutTabs();
	}

	/**
	 * タブの幅と位置を決める (未読の数で幅が変わるので、描くたびにも呼ぶ)。ロゴは入りきるときだけ出す。
	 */
	private void layoutTabs() {
		int total = 0;

		for (TabButton b : tabs) {
			b.badge = badge(b.target);
			total += b.preferredWidth(font) + 3;
		}

		int right = width - MARGIN;

		if (subBarToggle != null) {
			right -= subBarToggle.getWidth();
			subBarToggle.setX(right);
			right -= 3;
		}

		for (HeaderIconToggle b : headerButtons) {
			right -= b.getWidth();
			b.setX(right);
			right -= 3;
		}

		if (subBarToggle != null || !headerButtons.isEmpty()) {
			controlsLeft = right + 3;
			right = controlsLeft - 6;
		} else {
			controlsLeft = width - MARGIN;
		}

		int logoW = logoWidth();
		showLogo = MARGIN + logoW + 10 + total - 3 <= right;
		int x = showLogo ? MARGIN + logoW + 10 : MARGIN;

		for (TabButton b : tabs) {
			int w = b.preferredWidth(font);
			b.setX(x);
			b.setWidth(w);
			x += w + 3;
		}

		tabsRight = x - 3;

		x = MARGIN;

		for (SubTabButton b : subTabs) {
			b.badge = badge(b.target);
			int w = b.preferredWidth(font);
			b.setX(x);
			b.setWidth(w);
			x += w + 2;
		}
	}

	/** メインタブに出す数 (live は未読のコメント)。無ければ null。 */
	private static @Nullable String badge(MenuSection s) {
		return s == MenuSection.LIVE ? unreadText() : null;
	}

	/** 子タブに出す数 (コメントは未読)。無ければ null。 */
	private static @Nullable String badge(MenuTab t) {
		return t == MenuTab.COMMENTS ? unreadText() : null;
	}

	private static @Nullable String unreadText() {
		RedtactLive mod = RedtactLive.get();
		int n = mod == null ? 0 : mod.sessions().unread();
		return n > 0 ? String.valueOf(n) : null;
	}

	private int logoWidth() {
		return 18 + this.font.width(LOGO);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, this.width, this.height, Style.BACKDROP);

		if (barsHidden()) {
			return;
		}

		layoutTabs();
		g.fill(0, 0, this.width, BAR_H, Style.HUD_BAR);
		g.fill(0, BAR_H, this.width, BAR_H + 1, Style.OUTLINE);

		if (hasSubBar()) {
			g.fill(0, SUB_Y, this.width, SUB_Y + SUB_H, Style.SUB_BAR);
			g.fill(0, SUB_Y + SUB_H, this.width, SUB_Y + SUB_H + 1, Style.OUTLINE);
		}

		if (showLogo) {
			g.item(new ItemStack(Items.REDSTONE_TORCH), MARGIN - 2, (BAR_H - 16) / 2);
			g.text(this.font, LOGO, MARGIN + 16, (BAR_H - 8) / 2, Style.TXT, false);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** ヘッダの空き (メインタブとトグルの間) の左端と右端。そこに小さな知らせを出せる。 */
	protected int headerFreeLeft() {
		return tabsRight + 6;
	}

	protected int headerFreeRight() {
		return controlsLeft - 6;
	}

	/** 中身の幅。 */
	protected int contentWidth() {
		return this.width - MARGIN * 2;
	}

	/** 板 (中身の地)。 */
	protected void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		Draw.panel(g, x, y, w, h);
	}

	// ---- テスト用

	/** メインタブのボタン。 */
	public @Nullable AbstractWidget sectionButton(MenuSection s) {
		for (TabButton b : tabs) {
			if (b.target == s) {
				return b;
			}
		}

		return null;
	}

	/** 子タブの段を隠す・出すトグル (段が無い画面では null)。名前は ▲ (隠す) か ▼ (出す)。 */
	public @Nullable AbstractWidget subBarToggle() {
		return subBarToggle;
	}

	/** その画面だけのヘッダの右端のトグル (その名前のものが無ければ null。名前は日本語の原文でもよい)。 */
	public @Nullable AbstractWidget headerToggle(String name) {
		for (HeaderIconToggle b : headerButtons) {
			if (b.getMessage().getString().equals(Tr.t(name))) {
				return b;
			}
		}

		return null;
	}

	/** 子タブのボタン (子タブの段が無い・隠している画面では null)。 */
	public @Nullable AbstractWidget subTabButton(MenuTab t) {
		for (SubTabButton b : subTabs) {
			if (b.target == t) {
				return b;
			}
		}

		return null;
	}

	/** メインタブの名前と数 (「live 2」など)。 */
	public List<String> sectionLabels() {
		return tabs.stream().map(b -> b.target.label() + (b.badge == null ? "" : " " + b.badge)).toList();
	}

	/** 子タブの名前と数。 */
	public List<String> subTabLabels() {
		return subTabs.stream().map(b -> b.target.label() + (b.badge == null ? "" : " " + b.badge)).toList();
	}

	/** メインタブ (redtact の階のボタン: ランプ + 名前 (+ 数)。いまの階はランプが点く)。 */
	private static final class TabButton extends AbstractButton {
		private final MenuSection target;
		private final boolean on;
		private @Nullable String badge;

		TabButton(int y, MenuSection target, boolean on) {
			super(0, y, 0, TAB_H, Component.literal(target.label()));
			this.target = target;
			this.on = on;
		}

		int preferredWidth(Font font) {
			return font.width(target.label()) + 22 + (badge == null ? 0 : Draw.badgeWidth(font, badge) + 3);
		}

		@Override
		public void onPress(InputWithModifiers input) {
			if (!on) {
				Menu.open(target);
			}
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			Font font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY() - (isHovered() && !on ? 1 : 0);
			int face = on ? Style.FLOOR_ON : isHovered() ? Style.lighten(Style.FLOOR, 0.06f) : Style.FLOOR;
			Draw.box(g, x, y, getWidth(), getHeight(), face, Style.PANEL_L, 0xFF15100C);
			Draw.lamp(g, x + 5, y + (TAB_H - 8) / 2, 8, on);
			g.text(font, getMessage(), x + 17, y + (TAB_H - 8) / 2, on ? Style.FLOOR_ON_TX : Style.MUT, false);

			if (badge != null) {
				Draw.badge(g, font, x + 17 + font.width(getMessage()) + 3, y + (TAB_H - Draw.BADGE_H) / 2, badge, Draw.Tone.WARN);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}

	/**
	 * 子タブの段を隠す・出すトグル。ヘッダのタブと同じ地に、自前で描いた三角 (▲ = 隠す・▼ = 出す。マイクラの字の三角は小さいので)。
	 */
	private static final class SubBarToggle extends AbstractButton {
		private final boolean shown;
		private final Runnable toggle;

		SubBarToggle(int y, boolean shown, String tooltip, Runnable toggle) {
			super(0, y, TAB_H + 2, TAB_H, Component.literal(shown ? "▲" : "▼"));
			this.shown = shown;
			this.toggle = toggle;
			setTooltip(Tooltip.create(Component.literal(tooltip)));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			toggle.run();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			int x = getX();
			int y = getY();
			boolean hover = isHoveredOrFocused();
			Draw.box(g, x, y, getWidth(), getHeight(), hover ? Style.lighten(Style.FLOOR, 0.06f) : Style.FLOOR, Style.PANEL_L, 0xFF15100C);
			int color = hover ? Style.FLOOR_ON_TX : Style.MUT;
			int cx = x + getWidth() / 2;
			int top = y + (getHeight() - 4) / 2;

			// 幅 1・3・5・7 の 4 段 (▲ は上が細く、▼ は下が細い)
			for (int i = 0; i < 4; i++) {
				int half = shown ? i : 3 - i;
				g.fill(cx - half, top + i, cx + half + 1, top + i + 1, color);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}

	/**
	 * その画面だけのヘッダの右端のトグル (#70)。段のトグルと同じ地にドット絵 ({@link Icon})。効いているときは
	 * いまのメインタブと同じ押し込んだ面と明るい字の色。名前はツールチップの先頭に出す。
	 */
	private static final class HeaderIconToggle extends AbstractButton {
		private final HeaderToggle spec;

		HeaderIconToggle(int y, HeaderToggle spec) {
			super(0, y, TAB_H + 2, TAB_H, Component.literal(spec.name()));
			this.spec = spec;
			setTooltip(Tooltip.create(Component.literal(ClipText.buttonTooltip(spec.name(), null, spec.tooltip()))));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			spec.toggle().run();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			int x = getX();
			int y = getY();
			boolean hover = isHoveredOrFocused();
			int face = spec.on() ? Style.FLOOR_ON : Style.FLOOR;
			Draw.box(g, x, y, getWidth(), getHeight(), hover ? Style.lighten(face, 0.06f) : face, Style.PANEL_L, 0xFF15100C);
			int color = spec.on() || hover ? Style.FLOOR_ON_TX : Style.MUT;
			Icon icon = spec.icon();
			icon.draw(g, x + (getWidth() - icon.width()) / 2, y + (getHeight() - icon.height()) / 2, color);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}

	/** 子タブ (redtact の .el-tabbtn: 字だけ。いまの子タブは板の地・左上の明るい縁・銅の下線と銅の字)。 */
	private static final class SubTabButton extends AbstractButton {
		private final MenuTab target;
		private final boolean on;
		private @Nullable String badge;

		SubTabButton(int y, MenuTab target, boolean on) {
			super(0, y, 0, SUB_H, Component.literal(target.label()));
			this.target = target;
			this.on = on;
		}

		int preferredWidth(Font font) {
			return font.width(target.label()) + 16 + (badge == null ? 0 : Draw.badgeWidth(font, badge) + 3);
		}

		@Override
		public void onPress(InputWithModifiers input) {
			if (!on) {
				Menu.open(target);
			}
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			Font font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			int w = getWidth();
			int h = getHeight();
			boolean hover = !on && isHoveredOrFocused();

			if (on) {
				g.fill(x, y, x + w, y + h, Style.PANEL);
				g.fill(x, y, x + w, y + 1, Style.PANEL_L);
				g.fill(x, y, x + 1, y + h, Style.PANEL_L);
				g.fill(x + w - 1, y + 1, x + w, y + h, Style.PANEL_D);
				// 下線は段の下の線に重ねて、中身とつながって見せる
				g.fill(x, y + h - 1, x + w, y + h + 1, Style.COP);
			} else if (hover) {
				g.fill(x, y, x + w, y + h, 0x20FFFFFF);
			}

			int color = on ? Style.COP_TX : hover ? Style.TXT : Style.MUT;
			g.text(font, getMessage(), x + 8, y + (h - 8) / 2, color, false);

			if (badge != null) {
				Draw.badge(g, font, x + 8 + font.width(getMessage()) + 3, y + (h - Draw.BADGE_H) / 2, badge, Draw.Tone.WARN);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}
}
