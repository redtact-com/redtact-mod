package com.redtact.live.gui.kit;

import java.util.function.IntConsumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * redtact 風の整数のスライダー (#102。全画面のプレビューの高さ)。へこんだ溝に銅のつまみ。押した・ドラッグした位置の値にし、
 * ←→↑↓ で 1 ずつ動かす。値が変わったら onChange に渡す。
 */
public final class RSlider extends AbstractWidget {
	private static final int THUMB_W = 6;
	private final IntConsumer onChange;
	private int min;
	private int max;
	private int value;

	public RSlider(int x, int y, int width, int min, int max, int value, Component name, IntConsumer onChange) {
		super(x, y, width, RButton.H, name);
		this.onChange = onChange;
		this.min = min;
		this.max = Math.max(min, max);
		this.value = clamp(value);
	}

	public int value() {
		return value;
	}

	/** 範囲を変える (値は範囲に収める。onChange は呼ばない)。 */
	public void setRange(int min, int max) {
		this.min = min;
		this.max = Math.max(min, max);
		this.value = clamp(value);
	}

	/** 値を変える (onChange は呼ばない)。 */
	public void setValue(int v) {
		value = clamp(v);
	}

	private void change(int v) {
		int next = clamp(v);

		if (next != value) {
			value = next;
			onChange.accept(next);
		}
	}

	private int clamp(int v) {
		return Math.max(min, Math.min(max, v));
	}

	private void setFromMouse(double mouseX) {
		int span = Math.max(1, getWidth() - THUMB_W);
		double t = (mouseX - getX() - THUMB_W / 2.0) / span;
		change(min + (int) Math.round(Math.max(0, Math.min(1, t)) * (max - min)));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		Draw.box(g, x, y + h / 2 - 2, w, 4, Style.FIELD, Style.PANEL_D, Style.PANEL_L);
		int span = Math.max(1, w - THUMB_W);
		int tx = x + (max == min ? 0 : (int) Math.round((value - min) / (double) (max - min) * span));
		boolean hot = isHoveredOrFocused() && active;
		Draw.box(g, tx, y + 2, THUMB_W, h - 4, hot ? Style.COP_L : Style.COP, Style.COP_L, Style.COP_D);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		setFromMouse(event.x());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		setFromMouse(event.x());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active || !visible) {
			return false;
		}

		int k = event.key();

		if (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_DOWN) {
			change(value - 1);
			return true;
		}

		if (k == InputConstants.KEY_RIGHT || k == InputConstants.KEY_UP) {
			change(value + 1);
			return true;
		}

		return false;
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
		// つまみを動かすたびに音を鳴らさない
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.empty().append(getMessage()).append(": " + value));
	}
}
