package com.redtact.live.gui.cards;

import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Style;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 一覧の中の見出し (枠なし。左に銅の印)。 */
public final class HeaderCard extends MenuCard {
	private final String text;

	public HeaderCard(String text) {
		this.text = text;
	}

	public String text() {
		return text;
	}

	@Override
	public int height() {
		return 12;
	}

	@Override
	protected boolean framed() {
		return false;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		g.fill(cardX() + 1, cardY() + 2, cardX() + 3, cardY() + 10, Style.COP);
		Draw.clippedText(g, font(), text, cardX() + 7, cardY() + 2, cardW() - 10, Style.COP_TX, mouseX, mouseY);
	}
}
