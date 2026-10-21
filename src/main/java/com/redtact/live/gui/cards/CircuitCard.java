package com.redtact.live.gui.cards;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Consumer;

import com.redtact.live.RedtactLive;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.SessionScreen;
import com.redtact.live.gui.kit.ConfirmButton;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.gui.rows.CircuitRow;
import com.redtact.live.i18n.Tr;
import com.redtact.live.selection.Box;
import com.redtact.live.store.Times;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * 保存済みの回路 (範囲): ランプ (開催中なら点く)・名前・大きさ・履歴・コメントと「再開」・コメント (吹き出し)・削除 (ゴミ箱)。
 * カードを押すとその範囲を選ぶ (#39。選んでいるカードは明るく、左に銅の帯)。ほかのワールドの回路は選べず、再開もできない。
 * 削除は 2 回押して、回路のフォルダごと trash/ へ移す (#77。開催中の回路は消せない)。
 */
public final class CircuitCard extends MenuCard {
	private final CircuitRow row;
	private final Consumer<CircuitRow> onPick;
	private final RButton resume;
	private final RButton comments;
	private final ConfirmButton delete;

	/**
	 * @param onDeleted ゴミ箱へ移したあと (ゲームのスレッド。一覧を読み直す)
	 */
	public CircuitCard(CircuitRow row, Consumer<CircuitRow> onPick, Runnable onDeleted) {
		this.row = row;
		this.onPick = onPick;
		this.delete = add(ConfirmButton.icon(0, 0, Icon.TRASH, Tr.t("削除"), Tr.t("もう一度押すと削除"), RButton.Variant.SECONDARY,
				b -> RedtactLive.get().trashSavedRange(row.record(), onDeleted))
				.tooltip(Tr.t("この範囲を消す (2 回押す)。回路のフォルダごと redtact-live/trash/ へ移す (ローカルの .litematic の履歴とコメント履歴も一緒に。"
						+ "戻すときはフォルダを circuits/ に戻す)")));
		this.delete.disabledReason(row.open() ? Tr.t("開催中のセッションの回路は消せません (終了してから)") : null);
		this.comments = add(RButton.icon(0, 0, Icon.COMMENT, Tr.t("コメント"), RButton.Variant.SECONDARY, b -> Menu.openComments(row.record().circuitId))
				.tooltip(Tr.t("この回路のコメントを見る")));
		this.resume = add(RButton.of(font(), 0, 0, Tr.t("再開"), RButton.Variant.PRIMARY,
				b -> RedtactLive.get().resumeSavedRange(row.record(), SessionScreen::takeListDraft, SessionScreen::takeCopyDraft))
				.tooltip(Tr.t("保存済みの範囲を選んで、その範囲で新しいセッションを開く (いまの選択は保存済みの範囲に置き換わる。上の「一覧に出す」「複製を許可」の選択に従う)")));
		this.resume.disabledReason(RedtactLive.get().sessions().active() != null ? Tr.t("開催中のセッションを終了してから再開できます")
				: row.otherWorld() != null ? Tr.f("別のワールド ({0}) の範囲です", row.otherWorld()) : null);
	}

	public CircuitRow row() {
		return row;
	}

	public RButton resumeButton() {
		return resume;
	}

	public RButton commentsButton() {
		return comments;
	}

	/** 削除 (ゴミ箱。2 回押す。#77)。 */
	public ConfirmButton deleteButton() {
		return delete;
	}

	/** 押して選べるか (このワールドの回路で、開催中でない)。 */
	public boolean pickable() {
		return row.otherWorld() == null && RedtactLive.get().sessions().active() == null;
	}

	@Override
	public int height() {
		return TWO_LINES;
	}

	@Override
	protected int face(boolean hovered) {
		if (row.picked()) {
			return Style.lighten(Style.PANEL, 0.1f);
		}

		return hovered && pickable() ? super.face(true) : Style.PANEL;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 先にボタン (再開・コメント)
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || !pickable()) {
			return false;
		}

		onPick.accept(row);
		return true;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		if (row.picked()) {
			g.fill(cardX() + 1, cardY() + 1, cardX() + 3, cardY() + cardH() - 1, Style.COP);
		}

		int left = layoutRight(cardX() + cardW() - 5, buttonY(), delete, comments, resume);
		int x = cardX() + 5;
		Draw.lamp(g, x, cardY() + 5, 8, row.open());
		x += 12;
		int end = title(g, x, row.record().name, left - 4, mouseX, mouseY);

		if (row.picked()) {
			end = badgeAfter(g, end, left - 4, Tr.t("選択中"), Draw.Tone.OK);
		}

		if (row.unread() > 0) {
			badgeAfter(g, end, left - 4, Tr.n("未読 {0}", row.unread()), Draw.Tone.WARN);
		}

		Box box = row.record().box();
		String size = box == null ? Tr.t("範囲なし") : box.sizeText();
		String where = row.otherWorld() != null ? row.otherWorld() : dimensionText(row.record().dimension);
		String time = Times.local(row.record().updatedAt);
		info(g, x, where == null ? Tr.f("{0}・履歴 {1}・コメント {2}・{3}", size, row.snapshots(), row.comments(), time)
				: Tr.f("{0}・{1}・履歴 {2}・コメント {3}・{4}", size, where, row.snapshots(), row.comments(), time), left - 4, mouseX, mouseY);
	}

	/** オーバーワールド以外ならディメンションを添える (オーバーワールドなら null)。 */
	private static @Nullable String dimensionText(String dim) {
		if (dim == null || dim.equals("minecraft:overworld")) {
			return null;
		}

		return switch (dim) {
			case "minecraft:the_nether" -> Tr.t("ネザー");
			case "minecraft:the_end" -> Tr.t("エンド");
			default -> dim;
		};
	}
}
