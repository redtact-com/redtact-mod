package com.redtact.live.gui.cards;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.library.RedtactUpload;
import com.redtact.live.library.SchematicsBrowser;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * redtact タブの横の保管庫のファイルの 1 行 (#60): 選んだ印・名前・形式と大きさ。押して離すと選ぶ・外す、押したまま動かすと
 * ドラッグ (どちらかは離すまで分からないので、押したことだけを知らせる)。redtact に上げられないファイルは薄く出す。
 */
public final class PickCard extends MenuCard {
	public static final int HEIGHT = 14;
	private final SchematicsBrowser.Item item;
	private final BooleanSupplier selected;
	private final Consumer<PressedAt> onPress;
	private final @Nullable String unavailable;

	/** 押した行と位置 (GUI の座標)。 */
	public record PressedAt(PickCard card, double x, double y) {
	}

	public PickCard(SchematicsBrowser.Item item, BooleanSupplier selected, Consumer<PressedAt> onPress) {
		this.item = item;
		this.selected = selected;
		this.onPress = onPress;
		this.unavailable = RedtactUpload.unavailableReason(item.name(), item.bytes());
	}

	public SchematicsBrowser.Item item() {
		return item;
	}

	/** redtact に上げられない理由 (上げられるなら null)。 */
	public @Nullable String unavailableReason() {
		return unavailable;
	}

	public boolean selected() {
		return selected.getAsBoolean();
	}

	@Override
	public int height() {
		return HEIGHT;
	}

	@Override
	protected int face(boolean hovered) {
		if (selected()) {
			return Style.lighten(Style.PANEL, 0.1f);
		}

		return super.face(hovered);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}

		onPress.accept(new PressedAt(this, event.x(), event.y()));
		return true;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		boolean on = selected();

		if (on) {
			g.fill(cardX() + 1, cardY() + 1, cardX() + 3, cardY() + cardH() - 1, Style.COP);
		}

		int x = cardX() + 5;
		int y = cardY() + (cardH() - 8) / 2;
		checkbox(g, x, y, on, unavailable == null);
		x += 12;
		String right = item.extension() + " " + sizeText(item.bytes());
		int rw = font().width(right);
		int rx = cardX() + cardW() - 5 - rw;
		g.text(font(), right, rx, y, Style.fade(Style.MUT, 0.9f), false);
		int color = unavailable != null ? Style.fade(Style.MUT, 0.7f) : on ? Style.TXT : Style.STONE_TX;
		Draw.clippedText(g, font(), item.name(), x, y, Math.max(0, rx - 4 - x), color, mouseX, mouseY);
	}

	/** 選んだ印 (8×8 の箱。選んでいれば銅で埋めてチェック)。 */
	private static void checkbox(GuiGraphicsExtractor g, int x, int y, boolean on, boolean enabled) {
		int edge = enabled ? Style.STONE_L : Style.STONE;
		g.fill(x, y, x + 8, y + 8, edge);
		g.fill(x + 1, y + 1, x + 7, y + 7, on ? Style.COP : Style.FIELD);

		if (on) {
			// ✓ (左下へ 2 段・右上へ 4 段)
			int c = Style.ON_COP;
			g.fill(x + 2, y + 4, x + 3, y + 5, c);
			g.fill(x + 3, y + 5, x + 4, y + 6, c);
			g.fill(x + 4, y + 4, x + 5, y + 5, c);
			g.fill(x + 5, y + 3, x + 6, y + 4, c);
			g.fill(x + 6, y + 2, x + 7, y + 3, c);
		}
	}
}
