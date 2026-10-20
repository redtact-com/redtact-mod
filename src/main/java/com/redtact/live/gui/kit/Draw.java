package com.redtact.live.gui.kit;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** redtact 風の描き方 (枠付きの板・バッジ・ランプ・字の切り詰め)。 */
public final class Draw {
	/** バッジの高さ。 */
	public static final int BADGE_H = 11;
	private static final SpriteId LAMP_OFF = new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/redstone_lamp"));
	private static final SpriteId LAMP_ON = new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/redstone_lamp_on"));
	/** 最後にツールチップに出した全文 (テスト用)。 */
	private static volatile @Nullable String lastFullText;

	/** バッジの色。 */
	public enum Tone {
		PLAIN(Style.BADGE_PLAIN, Style.TXT),
		OK(Style.BADGE_OK, Style.OK),
		WARN(Style.BADGE_WARN, Style.LAMP),
		RED(Style.BADGE_RED, Style.RED_TX),
		CYAN(Style.BADGE_CYAN, Style.CYAN),
		MUTE(Style.PANEL, Style.MUT);

		final int bg;
		final int fg;

		Tone(int bg, int fg) {
			this.bg = bg;
			this.fg = fg;
		}
	}

	private Draw() {
	}

	/**
	 * 立体の箱 (redtact の .el-card などと同じ形): 黒い縁 1 px・面・左上に明るい縁・右下に暗い縁。
	 */
	public static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int face, int light, int dark) {
		if (w < 4 || h < 4) {
			g.fill(x, y, x + w, y + h, face);
			return;
		}

		g.fill(x, y, x + w, y + h, Style.OUTLINE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
		g.fill(x + 1, y + 2, x + 2, y + h - 1, light);
		g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, dark);
		g.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, dark);
	}

	/** 茶色の板。 */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		box(g, x, y, w, h, Style.PANEL, Style.PANEL_L, Style.PANEL_D);
	}

	/** 板の上の見出しの帯 (左に銅の縦帯。redtact の .el-sect-btn)。 */
	public static void sectionLabel(GuiGraphicsExtractor g, Font font, int x, int y, String text) {
		int w = font.width(text) + 12;
		box(g, x, y, w, 14, 0xFF3A281B, Style.PANEL_L, Style.PANEL_D);
		g.fill(x + 1, y + 1, x + 3, y + 13, Style.COP);
		g.text(font, text, x + 7, y + 3, Style.TXT, false);
	}

	/** 見出しの帯 (字が maxWidth に入らなければ切り、帯にマウスが重なれば全文を出す)。 */
	public static void sectionLabel(GuiGraphicsExtractor g, Font font, int x, int y, String text, int maxWidth, int mouseX, int mouseY) {
		String t = clip(font, text, maxWidth);
		sectionLabel(g, font, x, y, t);
		fullTextTooltip(g, font, text, t, x, y, font.width(t) + 12, 14, mouseX, mouseY);
	}

	public static int badgeWidth(Font font, String text) {
		return font.width(text) + 8;
	}

	/** バッジ (高さ 11)。幅を返す。 */
	public static int badge(GuiGraphicsExtractor g, Font font, int x, int y, String text, Tone tone) {
		int w = badgeWidth(font, text);
		g.fill(x, y, x + w, y + BADGE_H, Style.OUTLINE);
		g.fill(x + 1, y + 1, x + w - 1, y + BADGE_H - 1, tone.bg);
		g.text(font, text, x + 4, y + 2, tone.fg, false);
		return w;
	}

	/** ランプ付きのバッジ (ホストの接続など)。幅を返す。 */
	public static int lampBadge(GuiGraphicsExtractor g, Font font, int x, int y, String text, Tone tone, boolean on) {
		int w = font.width(text) + 8 + 9;
		g.fill(x, y, x + w, y + BADGE_H, Style.OUTLINE);
		g.fill(x + 1, y + 1, x + w - 1, y + BADGE_H - 1, tone.bg);
		lamp(g, x + 3, y + 2, 7, on);
		g.text(font, text, x + 12, y + 2, tone.fg, false);
		return w;
	}

	public static int lampBadgeWidth(Font font, String text) {
		return font.width(text) + 8 + 9;
	}

	/** アイコン付きのバッジ (保存中の数など。#69)。幅を返す。 */
	public static int iconBadge(GuiGraphicsExtractor g, Font font, int x, int y, Icon icon, String text, Tone tone) {
		int w = iconBadgeWidth(font, icon, text);
		g.fill(x, y, x + w, y + BADGE_H, Style.OUTLINE);
		g.fill(x + 1, y + 1, x + w - 1, y + BADGE_H - 1, tone.bg);
		icon.draw(g, x + 3, y + 2, tone.fg);
		g.text(font, text, x + 3 + icon.width() + 2, y + 2, tone.fg, false);
		return w;
	}

	public static int iconBadgeWidth(Font font, Icon icon, String text) {
		return 3 + icon.width() + 2 + font.width(text) + 3;
	}

	/** 保管庫に保存の途中の数のバッジ (↓ と数。#69)。マウスが重なれば「保管庫に保存中」と出す。幅を返す。 */
	public static int downloadsBadge(GuiGraphicsExtractor g, Font font, int x, int y, int count, int mouseX, int mouseY) {
		int w = iconBadge(g, font, x, y, Icon.DOWNLOAD, String.valueOf(count), Tone.WARN);

		if (ClipText.inside(x, y, w, BADGE_H, mouseX, mouseY)) {
			g.setTooltipForNextFrame(font, Tooltip.splitTooltip(Minecraft.getInstance(), Component.literal(Tr.n("保管庫に保存中: {0} 件", count))), mouseX,
					mouseY);
		}

		return w;
	}

	public static int downloadsBadgeWidth(Font font, int count) {
		return iconBadgeWidth(font, Icon.DOWNLOAD, String.valueOf(count));
	}

	/** レッドストーンランプ (ゲームのブロックのテクスチャ)。点いていれば周りをほんのり光らせる。 */
	public static void lamp(GuiGraphicsExtractor g, int x, int y, int size, boolean on) {
		if (on) {
			// redtact の点いたランプと同じく黄色く光らせる (8 px のテクスチャだけでは消えたランプとの差が小さい)
			g.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0x40FFD24D);
			g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xA0FFD24D);
		}

		TextureAtlasSprite sprite = g.getSprite(on ? LAMP_ON : LAMP_OFF);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, size, size);
	}

	/** 幅に入らなければ末尾を「…」にする。 */
	public static String clip(Font font, String s, int maxWidth) {
		if (s == null) {
			return "";
		}

		if (font.width(s) <= maxWidth) {
			return s;
		}

		int ellipsis = font.width("…");
		return maxWidth <= ellipsis ? "" : font.plainSubstrByWidth(s, maxWidth - ellipsis) + "…";
	}

	/**
	 * {@link #clip} で切った字を描く。切ったときは、字にマウスが重なれば全文をツールチップに出す (#41)。描いた字の幅を返す。
	 */
	public static int clippedText(GuiGraphicsExtractor g, Font font, @Nullable String s, int x, int y, int maxWidth, int color, int mouseX, int mouseY) {
		String t = clip(font, s, maxWidth);
		g.text(font, t, x, y, color, false);
		int w = font.width(t);
		fullTextTooltip(g, font, s, t, x, y - 1, w, font.lineHeight + 1, mouseX, mouseY);
		return w;
	}

	/**
	 * 字を maxWidth で折り返し、maxLines 行まで描く。入りきらなければ最後の行の末尾を「…」にし、字にマウスが重なれば
	 * 全文をツールチップに出す (#41 と同じ)。描いた行の数を返す。
	 */
	public static int clippedLines(GuiGraphicsExtractor g, Font font, @Nullable String s, int x, int y, int maxWidth, int maxLines, int color,
			int mouseX, int mouseY) {
		String text = s == null ? "" : s;
		java.util.List<int[]> spans = new java.util.ArrayList<>();
		font.getSplitter().splitLines(text, Math.max(1, maxWidth), net.minecraft.network.chat.Style.EMPTY, false,
				(style, start, end) -> spans.add(new int[] {start, end}));

		if (spans.isEmpty()) {
			return 0;
		}

		int lines = Math.min(maxLines, spans.size());
		boolean clipped = spans.size() > maxLines;
		int widest = 0;

		for (int i = 0; i < lines; i++) {
			String line;

			if (i == lines - 1 && clipped) {
				// 最後の行: 残りを全部つないで切る (「…」で入りきらないことを示す)
				String rest = text.substring(spans.get(i)[0]).strip();
				line = clip(font, rest, maxWidth);

				if (line.equals(rest)) {
					line = clip(font, rest + "…", maxWidth);
				}
			} else {
				line = text.substring(spans.get(i)[0], spans.get(i)[1]);
			}

			g.text(font, line, x, y + i * 10, color, false);
			widest = Math.max(widest, font.width(line));
		}

		if (clipped && ClipText.inside(x, y - 1, widest, lines * 10, mouseX, mouseY) && g.containsPointInScissor(mouseX, mouseY)) {
			g.setTooltipForNextFrame(font, Tooltip.splitTooltip(Minecraft.getInstance(), Component.literal(text)), mouseX, mouseY);
			lastFullText = text;
		}

		return lines;
	}

	/** 目のドット絵 (閲覧者の数の印。7 × 5)。 */
	public static void eye(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x + 2, y, x + 5, y + 1, color);
		g.fill(x + 1, y + 1, x + 6, y + 2, color);
		g.fill(x, y + 2, x + 2, y + 3, color);
		g.fill(x + 5, y + 2, x + 7, y + 3, color);
		g.fill(x + 1, y + 3, x + 6, y + 4, color);
		g.fill(x + 2, y + 4, x + 5, y + 5, color);
		// 瞳
		g.fill(x + 3, y + 1, x + 4, y + 4, Style.OUTLINE);
		g.fill(x + 2, y + 2, x + 5, y + 3, Style.OUTLINE);
	}

	/**
	 * 切った字 (shown) の場所 (x, y, w, h) にマウスが重なれば、全文 (full) をツールチップに出す。切っていなければ何もしない。
	 * 一覧のスクロールで隠れた所 (はさみの外) では出さない。
	 */
	public static void fullTextTooltip(GuiGraphicsExtractor g, Font font, @Nullable String full, String shown, int x, int y, int w, int h, int mouseX,
			int mouseY) {
		if (!ClipText.clipped(full, shown) || !ClipText.inside(x, y, w, h, mouseX, mouseY) || !g.containsPointInScissor(mouseX, mouseY)) {
			return;
		}

		g.setTooltipForNextFrame(font, Tooltip.splitTooltip(Minecraft.getInstance(), Component.literal(full)), mouseX, mouseY);
		lastFullText = full;
	}

	/** (x, y, w, h) にマウスが重なれば text をツールチップに出す (#90。一覧のスクロールで隠れた所では出さない)。 */
	public static void hoverTooltip(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int h, int mouseX, int mouseY) {
		if (!ClipText.inside(x, y, w, h, mouseX, mouseY) || !g.containsPointInScissor(mouseX, mouseY)) {
			return;
		}

		g.setTooltipForNextFrame(font, Tooltip.splitTooltip(Minecraft.getInstance(), Component.literal(text)), mouseX, mouseY);
		lastFullText = text;
	}

	/** 最後に {@link #fullTextTooltip} (と {@link #hoverTooltip}) がツールチップに出した全文 (テスト用)。 */
	public static @Nullable String lastFullText() {
		return lastFullText;
	}
}
