package com.redtact.live.gui.kit;

import com.redtact.live.compat.CompatButton;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/**
 * redtact 風のボタン。黒い縁 1 px と左上・右下の縁で立体に見せる (redtact の .el-mbtn / .el-optpill と同じ形)。
 * 押せないときは薄く描き、{@link #disabledReason(String)} の理由をツールチップに出す。
 * 名前が幅に入らず「…」で切ったときは、ツールチップの先頭に名前の全文を出す (マウスを重ねるかキーボードで選んだとき。#41)。
 * アイコンのボタン ({@link #icon}) は名前を描かず、いつもツールチップの先頭に出す (#69)。
 */
public class RButton extends CompatButton {
	/** ボタンの高さ (ふつう)。 */
	public static final int H = 16;

	public enum Variant {
		/** 銅の面 (主な操作)。 */
		PRIMARY,
		/** 石の面 (ふつうの操作)。 */
		SECONDARY,
		/** 赤の面 (終了・通報)。 */
		DANGER,
		/** 面なし・字だけ (目立たせない操作)。 */
		GHOST
	}

	/** 押したときの動き。右クリックを受けるボタンでは {@link #rightClick()} で見分ける。 */
	@FunctionalInterface
	public interface Action {
		void onPress(RButton button);
	}

	private Variant variant;
	private Action action;
	private boolean acceptRightClick;
	private boolean lastRightClick;
	private @Nullable String disabledReason;
	private @Nullable String normalTooltip;
	/** 切って描いた名前の全文 (切っていなければ null。アイコンのボタンではいつも名前)。 */
	private @Nullable String clippedLabel;
	private @Nullable String tooltipText;
	/** 名前の代わりに描くドット絵 (字のボタンなら null。#69)。 */
	private @Nullable Icon icon;
	/** 字を縮めたときの本当の名前 (ツールチップの先頭にいつも出す。#77。null なら字のまま)。 */
	private @Nullable String fullName;

	public RButton(int x, int y, int width, int height, Component label, Variant variant, Action action) {
		super(x, y, width, height, label);
		this.variant = variant;
		this.action = action;
	}

	/** 字の幅に合わせた幅のボタン。 */
	public static RButton of(Font font, int x, int y, String label, Variant variant, Action action) {
		return new RButton(x, y, font.width(label) + 12, H, Component.literal(label), variant, action);
	}

	/**
	 * アイコンの正方形のボタン (#69)。名前 (label) は描かず、ツールチップの先頭に出す (説明・押せない理由はその下)。
	 * 名前は読み上げとテストにも使う。
	 */
	public static RButton icon(int x, int y, Icon icon, String label, Variant variant, Action action) {
		RButton b = new RButton(x, y, H, H, Component.literal(label), variant, action);
		b.icon = icon;
		b.clippedLabel = label;
		b.updateTooltip();
		return b;
	}

	/** 名前の代わりに描くドット絵 (字のボタンなら null。テスト用)。 */
	public @Nullable Icon icon() {
		return icon;
	}

	/** アイコンのボタンにする (サブクラスの作り方。名前はツールチップの先頭に出す。#77)。 */
	protected void useIcon(Icon icon) {
		this.icon = icon;
		this.clippedLabel = getMessage().getString();
		setWidth(H);
		updateTooltip();
	}

	/**
	 * 字は縮めて描き (例「投稿」)、本当の名前 (例「redtact に投稿」) はマウスを重ねる・Tab で選ぶとツールチップの先頭に出す (#77。
	 * 並びの字の幅を空けるため。アイコンのボタンの名前と同じ出し方)。
	 */
	public RButton fullName(String name) {
		this.fullName = name;
		this.clippedLabel = name;
		updateTooltip();
		return this;
	}

	public static int widthFor(Font font, String label) {
		return font.width(label) + 12;
	}

	public RButton tooltip(@Nullable String text) {
		this.normalTooltip = text;
		updateTooltip();
		return this;
	}

	/** 押せないようにして、理由をツールチップに出す。null なら押せる。 */
	public RButton disabledReason(@Nullable String reason) {
		this.disabledReason = reason;
		this.active = reason == null;
		updateTooltip();
		return this;
	}

	public RButton enabled(boolean enabled) {
		return disabledReason(enabled ? null : "");
	}

	public @Nullable String disabledReason() {
		return disabledReason;
	}

	private void updateTooltip() {
		String text = ClipText.buttonTooltip(clippedLabel, disabledReason, normalTooltip);
		tooltipText = text;
		setTooltip(text == null ? null : Tooltip.create(Component.literal(text)));
	}

	/** いまのツールチップの字 (テスト用)。 */
	public @Nullable String tooltipText() {
		return tooltipText;
	}

	/**
	 * 名前を幅に合わせて切る。切ったか (と全文) が変わったらツールチップを作り直す。描く前に毎回呼ぶ
	 * (ウィジェットはこのあとツールチップを出すので、同じフレームで間に合う)。
	 */
	protected String fitLabel(Font font, int maxWidth) {
		String full = getMessage().getString();
		String label = Draw.clip(font, full, maxWidth);
		String clipped = fullName != null ? fullName : ClipText.clipped(full, label) ? full : null;

		if (!Objects.equals(clipped, clippedLabel)) {
			clippedLabel = clipped;
			updateTooltip();
		}

		return label;
	}

	public RButton acceptRightClick() {
		this.acceptRightClick = true;
		return this;
	}

	/** いまの押下が右クリックか ({@link #acceptRightClick()} のとき)。 */
	public boolean rightClick() {
		return lastRightClick;
	}

	public RButton variant(Variant variant) {
		this.variant = variant;
		return this;
	}

	public Variant variant() {
		return variant;
	}

	public RButton label(String label) {
		setMessage(Component.literal(label));

		if (icon != null) {
			clippedLabel = label; // アイコンのボタンの名前はツールチップに出す
			updateTooltip();
		}

		return this;
	}

	public RButton action(Action action) {
		this.action = action;
		return this;
	}

	@Override
	protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
		return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT || (acceptRightClick && buttonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT);
	}

	@Override
	public void onPress(InputWithModifiers input) {
		lastRightClick = input instanceof MouseButtonEvent e && e.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		action.onPress(this);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean on = this.active;
		boolean hover = on && isHoveredOrFocused();
		int text;

		switch (variant) {
			case PRIMARY -> {
				int face = hover ? Style.lighten(Style.COP, 0.12f) : Style.COP;
				Draw.box(g, x, y, w, h, on ? face : Style.STONE_D, on ? Style.COP_L : Style.STONE, on ? Style.COP_D : Style.PANEL_D);
				text = on ? Style.ON_COP : Style.MUT;
			}
			case DANGER -> {
				int face = hover ? Style.lighten(Style.RED, 0.12f) : Style.RED;
				Draw.box(g, x, y, w, h, on ? face : Style.STONE_D, on ? Style.RED_L : Style.STONE, on ? Style.RED_D : Style.PANEL_D);
				text = on ? Style.ON_RED : Style.MUT;
			}
			case GHOST -> {
				if (hover) {
					g.fill(x, y, x + w, y + h, 0x30FFFFFF);
				}

				text = !on ? Style.fade(Style.MUT, 0.6f) : hover ? Style.TXT : Style.MUT;
			}
			default -> {
				int face = hover ? Style.lighten(Style.STONE, 0.1f) : Style.STONE;
				Draw.box(g, x, y, w, h, on ? face : Style.STONE_D, on ? Style.STONE_L : Style.STONE, on ? Style.STONE_D : Style.PANEL_D);
				text = on ? (hover ? 0xFFFFFFFF : Style.STONE_TX) : Style.fade(Style.MUT, 0.8f);
			}
		}

		if (icon != null) {
			// 字と同じ高さ (上端も字と同じ所) に、横は真ん中に描く
			icon.draw(g, x + (w - icon.width()) / 2, y + (h - 8) / 2, text);
			return;
		}

		Font font = Minecraft.getInstance().font;
		String label = fitLabel(font, w - 6);
		int tx = x + (w - font.width(label)) / 2;
		g.text(font, label, tx, y + (h - 8) / 2, text, false);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
