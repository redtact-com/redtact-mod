package com.redtact.live.gui.kit;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 切り替えのボタン: 左のランプが点いていれば ON (redtact の .el-uvar .el-optpill.on の点灯と同じ考え)。
 * 切り替え中 (サーバに送っている間) は {@link #pending(boolean)} で薄くして押せなくする。
 */
public class RToggle extends RButton {
	private boolean on;
	private boolean pending;

	public RToggle(int x, int y, int width, String label, boolean on, Action action) {
		super(x, y, width, H, Component.literal(label), Variant.SECONDARY, action);
		this.on = on;
	}

	public static RToggle of(Font font, int x, int y, String label, boolean on, Action action) {
		return new RToggle(x, y, widthFor(font, label), label, on, action);
	}

	public static int widthFor(Font font, String label) {
		return font.width(label) + 22;
	}

	public boolean on() {
		return on;
	}

	public RToggle on(boolean on) {
		this.on = on;
		return this;
	}

	@Override
	public RToggle tooltip(@Nullable String text) {
		super.tooltip(text);
		return this;
	}

	@Override
	public RToggle disabledReason(@Nullable String reason) {
		super.disabledReason(reason);
		return this;
	}

	public boolean pending() {
		return pending;
	}

	public RToggle pending(boolean pending) {
		this.pending = pending;

		if (pending) {
			disabledReason(Tr.t("切り替え中…"));
		}

		return this;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean hover = this.active && isHoveredOrFocused();
		int face = on ? 0xFF4A3420 : Style.STONE;
		Draw.box(g, x, y, w, h, hover ? Style.lighten(face, 0.1f) : face, on ? 0xFF6A4A2C : Style.STONE_L, Style.STONE_D);
		Draw.lamp(g, x + 4, y + (h - 8) / 2, 8, on);
		Font font = Minecraft.getInstance().font;
		int text = !this.active ? Style.fade(Style.MUT, 0.8f) : on ? Style.FLOOR_ON_TX : Style.STONE_TX;
		g.text(font, fitLabel(font, w - 18), x + 15, y + (h - 8) / 2, text, false);
	}
}
