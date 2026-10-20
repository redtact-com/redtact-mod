package com.redtact.live.gui.cards;

import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.Style;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** メニューの一覧のカード (種類の違うカードを 1 つの一覧に混ぜられるよう、共通の型にする)。 */
public abstract class MenuCard extends CardList.Card<MenuCard> {
	/** 2 行のカードの高さ。 */
	public static final int TWO_LINES = 26;

	protected static Font font() {
		return Minecraft.getInstance().font;
	}

	/** 2 行のカードのボタンの y。 */
	protected int buttonY() {
		return cardY() + (cardH() - RButton.H) / 2;
	}

	/** 1 行目 (題) を描く (切ったらマウスを重ねたときに全文を出す)。続けてバッジを並べられるよう、右端の x を返す。 */
	protected int title(GuiGraphicsExtractor g, int x, String text, int maxRight, int mouseX, int mouseY) {
		return x + Draw.clippedText(g, font(), text, x, cardY() + 4, Math.max(0, maxRight - x), Style.TXT, mouseX, mouseY);
	}

	/** 2 行目 (補足) を描く (切ったらマウスを重ねたときに全文を出す)。 */
	protected void info(GuiGraphicsExtractor g, int x, String text, int maxRight, int mouseX, int mouseY) {
		Draw.clippedText(g, font(), text, x, cardY() + 15, Math.max(0, maxRight - x), Style.MUT, mouseX, mouseY);
	}

	/** ファイルの大きさの表示 (B・KB・MB)。 */
	public static String sizeText(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}

		return bytes < 1024 * 1024 ? String.format("%.1f KB", bytes / 1024.0) : String.format("%.1f MB", bytes / 1024.0 / 1024.0);
	}

	/** 選んでいる印 (左端の銅の帯)。 */
	protected void selectedMark(GuiGraphicsExtractor g) {
		g.fill(cardX() + 1, cardY() + 1, cardX() + 3, cardY() + cardH() - 1, Style.COP);
	}

	/** 1 行目の題の右にバッジを置く (入らなければ置かない)。次の x を返す。 */
	protected int badgeAfter(GuiGraphicsExtractor g, int x, int maxRight, String text, Draw.Tone tone) {
		int w = Draw.badgeWidth(font(), text);

		if (x + 4 + w > maxRight) {
			return x;
		}

		Draw.badge(g, font(), x + 4, cardY() + 2, text, tone);
		return x + 4 + w;
	}
}
