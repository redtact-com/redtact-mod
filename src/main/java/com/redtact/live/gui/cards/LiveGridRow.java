package com.redtact.live.gui.cards;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.gui.kit.LiveGrid;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * 開催中のカードのグリッドの 1 段 (#56)。縦の一覧 ({@link com.redtact.live.gui.kit.CardList}) の 1 行に、
 * {@link LiveCard} を左から cols 枚まで並べる (スクロール・はさみ・スクロールバーは一覧のものを使う)。
 */
public final class LiveGridRow extends MenuCard {
	private final List<LiveCard> cells;
	private final int cols;
	private final int cardW;
	private final int cardH;

	public LiveGridRow(List<LiveCard> cells, int cols, int cardW) {
		this.cells = List.copyOf(cells);
		this.cols = cols;
		this.cardW = cardW;
		this.cardH = LiveGrid.cardHeight(cardW);
	}

	public List<LiveCard> cells() {
		return cells;
	}

	@Override
	public int height() {
		return cardH;
	}

	@Override
	protected boolean framed() {
		return false;
	}

	/** カードの場所を決める (一覧が段を動かしたあと、描く前とテストから)。 */
	public void layout() {
		for (int i = 0; i < cells.size(); i++) {
			cells.get(i).place(getX() + i * (cardW + LiveGrid.GAP), getY(), cardW, cardH);
		}
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		layout();

		for (LiveCard c : cells) {
			c.extract(g, mouseX, mouseY, hovered && c.hovered(g, mouseX, mouseY));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}

		layout();

		for (LiveCard c : cells) {
			if (c.contains(event.x(), event.y())) {
				c.open();
				return true;
			}
		}

		return false;
	}

	/** 列の数 (テスト用)。 */
	public int columns() {
		return cols;
	}
}
