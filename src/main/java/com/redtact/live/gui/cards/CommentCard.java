package com.redtact.live.gui.cards;

import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.gui.rows.CommentRow;
import com.redtact.live.i18n.Tr;
import com.redtact.live.store.Times;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * コメント 1 件 (Web のコメント欄と同じ並び): 時刻・投稿者・印 (ホスト・自分・新着)・本文 (折り返し)。未読だったものは左に黄色の帯。
 * ホスト (#74) の印はセッションを開いた人のコメント、自分の印は他人のセッションに自分が書いたもの。
 */
public final class CommentCard extends MenuCard {
	public static final int LINE = 10;
	private final CommentRow row;

	public CommentCard(CommentRow row) {
		this.row = row;
	}

	public CommentRow row() {
		return row;
	}

	@Override
	public int height() {
		return 15 + row.wrapped().size() * LINE + 3;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		if (row.unread()) {
			g.fill(cardX() + 1, cardY() + 1, cardX() + 3, cardY() + cardH() - 1, Style.LAMP);
		}

		int x = cardX() + 7;
		int y = cardY() + 4;
		int right = cardX() + cardW() - 5;
		String time = Times.local(row.comment().createdAt);
		g.text(font(), time, x, y, Style.MUT, false);
		int ax = x + font().width(time) + 6;
		int end = ax + Draw.clippedText(g, font(), row.comment().author, ax, y, Math.max(0, right - ax), Style.COP_TX, mouseX, mouseY);

		if (row.comment().byHost()) {
			end = badgeAfter(g, end, right, Tr.t("ホスト"), Draw.Tone.OK);
		} else if (row.mine()) {
			end = badgeAfter(g, end, right, Tr.t("自分"), Draw.Tone.CYAN);
		}

		if (row.unread()) {
			end = badgeAfter(g, end, right, Tr.t("新着"), Draw.Tone.WARN);
		}

		if (!row.currentSession() && row.comment().sessionId != null) {
			String sid = "(" + row.comment().sessionId + ")";

			if (end + 6 + font().width(sid) <= right) {
				g.text(font(), sid, end + 6, y, Style.fade(Style.MUT, 0.7f), false);
			}
		}

		int ty = cardY() + 15;

		for (String line : row.wrapped()) {
			g.text(font(), line, x, ty, Style.TXT, false);
			ty += LINE;
		}
	}
}
