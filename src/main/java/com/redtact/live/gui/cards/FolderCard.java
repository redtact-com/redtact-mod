package com.redtact.live.gui.cards;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.SchematicsBrowser;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * フォルダの 1 行 (#60): フォルダの印と名前。カードを押すと中へ入る。保管庫タブでは右に「開く」も置く
 * (redtact タブの横の保管庫では細い行で、ボタンを置かない)。
 */
public final class FolderCard extends MenuCard {
	private final SchematicsBrowser.Item item;
	private final Consumer<Path> open;
	private final boolean compact;
	private final @Nullable RButton openButton;
	private @Nullable String location;

	public FolderCard(SchematicsBrowser.Item item, boolean compact, Consumer<Path> open) {
		this.item = item;
		this.open = open;
		this.compact = compact;
		this.openButton = compact ? null
				: add(RButton.icon(0, 0, Icon.FORWARD, Tr.t("開く"), RButton.Variant.SECONDARY, b -> open.accept(item.path())).tooltip(Tr.t("このフォルダの中を見る")));
	}

	public SchematicsBrowser.Item item() {
		return item;
	}

	public @Nullable RButton openButton() {
		return openButton;
	}

	/** 名前の右に入っているフォルダを出す (検索の結果。#70)。 */
	public FolderCard location(String location) {
		this.location = location;
		return this;
	}

	@Override
	public int height() {
		return compact ? 14 : RButton.H + 4;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		if (event.button() != 1) {
			return false;
		}

		open.accept(item.path());
		return true;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		int right = cardX() + cardW() - 5;

		if (openButton != null) {
			right = layoutRight(right, cardY() + (cardH() - RButton.H) / 2, openButton);
		}

		int x = cardX() + 5;
		int y = cardY() + (cardH() - 8) / 2;
		folderIcon(g, x, y, hovered ? Style.COP_L : Style.COP);
		int max = Math.max(0, right - 4 - (x + 13));
		int w = Draw.clippedText(g, font(), item.name(), x + 13, y, max, hovered ? Style.TXT : Style.STONE_TX, mouseX, mouseY);

		if (location != null && w + 6 < max) {
			Draw.clippedText(g, font(), location, x + 13 + w + 6, y, max - w - 6, Style.MUT, mouseX, mouseY);
		}
	}

	/** フォルダの印 (幅 10・高さ 8)。 */
	static void folderIcon(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x, y, x + 4, y + 1, color);
		g.fill(x, y + 1, x + 10, y + 8, color);
		g.fill(x + 1, y + 3, x + 9, y + 7, Style.fade(color, 0.55f));
	}
}
