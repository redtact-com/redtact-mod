package com.redtact.live.compat;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
/*import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}

/**
 * MOD のボタンの元 (#112)。1.21.11 からはゲームのボタンの描く口が「中身を描く口」とカーソルの形に分かれているが、1.21.10 までは
 * 描く口を丸ごと上書きする形なので、ここで同じ分け方にする (中身を描いてから、ゲームと同じくカーソルの形を決める)。
 */
public abstract class CompatButton extends AbstractButton {
	protected CompatButton(int x, int y, int width, int height, Component message) {
		super(x, y, width, height, message);
	}

	//? if <1.21.11 {
	/*@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractContents(g, mouseX, mouseY, a);

		if (isHovered()) {
			g.requestCursor(isActive() ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
		}
	}

	protected abstract void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a);
	*///?}
}
