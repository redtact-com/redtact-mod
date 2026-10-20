package com.redtact.live.gui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * redtact 風の入力欄 (沈んだ暗い地・黒い縁。フォーカス中は銅の縁。redtact の .el-input / .el-field)。
 * 中身はバニラの EditBox (枠なし) で、外側の箱 ({@link #frameX()} など) を自分で描く。
 */
public class RTextField extends EditBox {
	private static final int PAD_X = 4;
	private int frameX;
	private int frameY;
	private int frameW;
	private int frameH;

	public RTextField(Font font, int x, int y, int width, int height, Component narration) {
		super(font, x + PAD_X, y + (height - 8) / 2, width - PAD_X * 2, 9, narration);
		setBordered(false);
		setTextColor(Style.TXT);
		setTextColorUneditable(Style.MUT);
		setTextShadow(false);
		this.frameX = x;
		this.frameY = y;
		this.frameW = width;
		this.frameH = height;
	}

	public RTextField(Font font, int x, int y, int width, Component narration) {
		this(font, x, y, width, RButton.H, narration);
	}

	/** 外側の箱ごと動かす。 */
	public void moveFrame(int x, int y) {
		this.frameX = x;
		this.frameY = y;
		setX(x + PAD_X);
		setY(y + (frameH - 8) / 2);
	}

	public int frameX() {
		return frameX;
	}

	public int frameY() {
		return frameY;
	}

	public int frameW() {
		return frameW;
	}

	public int frameH() {
		return frameH;
	}

	public RTextField hint(String hint) {
		setHint(Component.literal(hint).withColor(Style.fade(Style.MUT, 0.8f)));
		return this;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return this.active && this.visible && mouseX >= frameX && mouseY >= frameY && mouseX < frameX + frameW && mouseY < frameY + frameH;
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (!this.isVisible()) {
			return;
		}

		int x = frameX;
		int y = frameY;
		int w = frameW;
		int h = frameH;
		g.fill(x, y, x + w, y + h, isFocused() ? Style.COP : Style.OUTLINE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Style.FIELD);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, Style.FIELD_SHADE);
		g.fill(x + 1, y + 2, x + 2, y + h - 1, Style.FIELD_SHADE);
		super.extractWidgetRenderState(g, mouseX, mouseY, a);
	}
}
