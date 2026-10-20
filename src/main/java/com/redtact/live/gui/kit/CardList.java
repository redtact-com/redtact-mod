package com.redtact.live.gui.kit;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/**
 * カードを縦に並べる一覧 (バニラの ContainerObjectSelectionList)。背景・区切り・スクロールバーを redtact 風に描き、
 * 行の高さは行ごとに決める ({@link Card#height()})。上から詰めて並べる。
 */
public class CardList<E extends CardList.Card<E>> extends ContainerObjectSelectionList<E> {
	/** 行と行の間 (カードの黒い縁が重ならないように)。 */
	public static final int GAP = 3;
	private static final int SCROLLBAR_W = 4;

	public CardList(Minecraft mc, int x, int y, int width, int height) {
		super(mc, width, height, y, 24);
		this.setX(x);
		this.centerListVertically = false;
	}

	/** 行を入れ替える (スクロールの位置は保つ)。 */
	public void setCards(List<E> cards) {
		double scroll = scrollAmount();
		clearEntries();

		for (E c : cards) {
			addEntry(c, c.height() + GAP);
		}

		setScrollAmount(scroll);
	}

	public List<E> cards() {
		return new ArrayList<>(children());
	}

	/** その行が一覧の上端に来るまで送る (送れるところまで)。 */
	public void scrollToTop(E card) {
		setScrollAmount(scrollAmount() + (card.getY() - getY() - 2));
	}

	@Override
	public int getRowWidth() {
		return getWidth() - SCROLLBAR_W - 3;
	}

	@Override
	public int getRowLeft() {
		return getX();
	}

	@Override
	protected int scrollBarX() {
		return getRight() - SCROLLBAR_W;
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor g) {
		// 地は画面の板に任せる
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor g) {
	}

	@Override
	protected void extractScrollbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (!scrollable()) {
			return;
		}

		int x = scrollBarX();
		int top = getY();
		g.fill(x, top, x + SCROLLBAR_W, getBottom(), 0x59000000);
		int h = scrollerHeight();
		int y = scrollBarY();
		g.fill(x, y, x + SCROLLBAR_W, y + h, 0xFF6B4F38);
		g.fill(x, y, x + SCROLLBAR_W, y + 1, 0xFF8A6746);
		g.fill(x, y + h - 1, x + SCROLLBAR_W, y + h, 0xFF2A1F17);
	}

	/**
	 * 1 枚のカード。{@link #children()} に押せる部品を入れ、{@link #extractCard} で中身を描く (部品の位置もそこで決める)。
	 */
	public abstract static class Card<E extends Card<E>> extends ContainerObjectSelectionList.Entry<E> {
		private final List<AbstractWidget> widgets = new ArrayList<>();

		/** カードの高さ (行の間は除く)。 */
		public abstract int height();

		/** 枠付きの板を描くか (見出しの行などは false)。 */
		protected boolean framed() {
			return true;
		}

		protected <W extends AbstractWidget> W add(W widget) {
			widgets.add(widget);
			return widget;
		}

		public List<AbstractWidget> widgets() {
			return widgets;
		}

		protected int cardX() {
			return getX();
		}

		protected int cardY() {
			return getY();
		}

		protected int cardW() {
			return getWidth();
		}

		protected int cardH() {
			return height();
		}

		/** 板の面の色 (選んでいるカードを明るくするときに変える)。 */
		protected int face(boolean hovered) {
			return hovered ? Style.lighten(Style.PANEL, 0.04f) : Style.PANEL;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
			if (framed()) {
				Draw.box(g, cardX(), cardY(), cardW(), cardH(), face(hovered), Style.PANEL_L, Style.PANEL_D);
			}

			extractCard(g, mouseX, mouseY, hovered, a);

			for (AbstractWidget w : widgets) {
				w.extractRenderState(g, mouseX, mouseY, a);
			}
		}

		/** 中身を描く。部品の位置 ({@code setPosition}) もここで決める。部品そのものはこのあとに描かれる。 */
		protected abstract void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a);

		/** 右端から部品を並べる (右から左へ)。並べ終えた左端を返す。 */
		protected int layoutRight(int right, int y, AbstractWidget... ws) {
			int x = right;

			for (AbstractWidget w : ws) {
				if (w instanceof RTextField f) {
					// 入力欄は外側の箱ごと動かす (中の EditBox は箱の内側にある)
					x -= f.frameW();
					f.moveFrame(x, y);
				} else {
					x -= w.getWidth();
					w.setPosition(x, y);
				}

				x -= 3;
			}

			return x;
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return widgets;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return widgets;
		}
	}
}
