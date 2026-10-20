package com.redtact.live.gui.cards;

import java.net.URI;

import com.redtact.live.RedtactLive;
import com.redtact.live.branch.BranchText;
import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.gui.rows.BranchRow;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;

/** このセッションから分岐した子: 題・ホスト・分岐した版と「重ねて読み込む」「ブラウザで開く」。左に水色の帯。 */
public final class BranchCard extends MenuCard {
	private final BranchRow row;
	private final RButton overlay;
	private final RButton open;
	private final String overlayLabel;

	public BranchCard(BranchRow row) {
		this.row = row;
		Api.Branch b = row.branch();
		boolean hasUrl = b.shareUrl != null && !b.shareUrl.isBlank();
		this.open = add(RButton.icon(0, 0, Icon.EXTERNAL, Tr.t("ブラウザで開く"), RButton.Variant.SECONDARY, btn -> {
			if (hasUrl) {
				ConfirmLinkScreen.confirmLinkNow(Minecraft.getInstance().gui.screen(), URI.create(b.shareUrl));
			}
		}).tooltip(hasUrl ? b.shareUrl : null).disabledReason(hasUrl ? null : Tr.t("共有リンクがありません")));
		// 子の回路を、自分のセッションの範囲の最小の角に重ねる (Litematica が無ければ保存だけ)
		boolean litematica = CircuitLoader.litematicaLoaded();
		this.overlayLabel = litematica ? Tr.t("重ねて読み込む") : Tr.t(".litematic を保存");
		this.overlay = add(RButton.of(font(), 0, 0, overlayLabel, RButton.Variant.PRIMARY, btn -> {
			if (row.overlayBlockedReason() == null) {
				RedtactLive.get().branches().overlay(b);
			}
		}).tooltip(litematica ? Tr.t("子の最新の版を、自分の範囲の最小の角に配置として重ねる。違いは Litematica の検証 (Schematic Verifier) で見る")
				: Tr.f("schematics/{0}/ に .litematic を保存する", CircuitLoader.FOLDER)).disabledReason(row.overlayBlockedReason()));
	}

	public BranchRow row() {
		return row;
	}

	public RButton overlayButton() {
		return overlay;
	}

	public String overlayLabel() {
		return overlayLabel;
	}

	public boolean overlayEnabled() {
		return overlay.active;
	}

	public RButton openButton() {
		return open;
	}

	@Override
	public int height() {
		return TWO_LINES;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		int left = layoutRight(cardX() + cardW() - 5, buttonY(), open, overlay);
		g.fill(cardX() + 1, cardY() + 1, cardX() + 3, cardY() + cardH() - 1, Style.CYAN);
		Api.Branch b = row.branch();
		int x = cardX() + 8;
		int end = title(g, x, b.title, left - 4, mouseX, mouseY);
		end = badgeAfter(g, end, left - 4, Tr.t("分岐"), Draw.Tone.CYAN);

		if (!b.copyAllowed()) {
			badgeAfter(g, end, left - 4, Tr.t("複製不可"), Draw.Tone.RED);
		}

		info(g, x, Tr.f("{0}・v{1} から分岐", BranchText.host(b.host, b.hostVerified), b.fromVersion), left - 4, mouseX, mouseY);
	}
}
