package com.redtact.live.gui.cards;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.gui.kit.Style;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * 保管庫タブで、押すとプレビューに選べるファイルの行 (#70)。ボタンの無いところを押すと選び、選んでいる行は面を明るくして
 * 左端に銅の帯を出す。検索の結果では入っているフォルダ ({@link #location()}) も出す。
 */
public abstract class SelectableCard extends MenuCard {
	private @Nullable Predicate<Path> isSelected;
	private @Nullable Consumer<Path> onSelect;
	private @Nullable String location;

	/** この行のファイル (絶対パス)。 */
	public abstract Path file();

	/** 行を押すとプレビューに選ぶ。 */
	public SelectableCard selectable(Predicate<Path> isSelected, Consumer<Path> onSelect) {
		this.isSelected = isSelected;
		this.onSelect = onSelect;
		return this;
	}

	/** 入っているフォルダを出す (検索の結果)。 */
	public SelectableCard location(String location) {
		this.location = location;
		return this;
	}

	/** 出している、入っているフォルダ (検索の結果でなければ null)。 */
	public @Nullable String location() {
		return location;
	}

	public boolean selected() {
		return isSelected != null && isSelected.test(file());
	}

	@Override
	protected int face(boolean hovered) {
		return selected() ? Style.lighten(Style.PANEL, 0.1f) : super.face(hovered);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true; // ボタン
		}

		if (onSelect == null || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}

		onSelect.accept(file());
		return true;
	}

	/** 選んでいれば左端に銅の帯 (中身の前に描く)。 */
	protected void extractSelection(GuiGraphicsExtractor g) {
		if (selected()) {
			selectedMark(g);
		}
	}
}
