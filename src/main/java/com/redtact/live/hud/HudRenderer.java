package com.redtact.live.hud;

import java.util.ArrayList;
import java.util.List;

import com.redtact.live.RedtactLive;
import com.redtact.live.config.Configs;
import com.redtact.live.config.HudCorner;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.selection.Box;
import com.redtact.live.session.SessionManager;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * 画面の隅の状態表示。redtact 風の小さな板に、ランプ (ホスト接続中なら点く) とセッション・版・閲覧者・未読・自動同期を出す。
 * メニューなどの画面を開いている間は出さない (チャットは除く)。
 */
public final class HudRenderer {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(RedtactLive.MOD_ID, "status");
	private static final int BOTTOM_OFFSET = 40;
	private static final int PAD = 4;
	private static final int LINE = 10;

	/** 1 行 (文字と色)。 */
	public record Line(String text, int color) {
	}

	private HudRenderer() {
	}

	public static void register() {
		HudElementRegistry.addLast(ID, HudRenderer::extract);
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();

		if (!Configs.Generic.HUD_ENABLED.getBooleanValue() || mc.player == null || mc.gui.hud.isHidden()
				|| (mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen))) {
			return;
		}

		RedtactLive mod = RedtactLive.get();
		List<Line> lines = styledLines(mod);

		if (lines.isEmpty()) {
			return;
		}

		Font font = mc.font;
		int textW = 0;

		for (Line l : lines) {
			textW = Math.max(textW, font.width(l.text()));
		}

		int w = PAD + 10 + textW + PAD;
		int h = PAD + lines.size() * LINE + PAD - 2;
		HudCorner corner = Configs.Generic.HUD_ALIGNMENT.getOptionListValue();
		// 下の角はホットバー・体力・満腹度の上に出す。上の角は右上だとトーストと重なるので既定は右下
		int x = corner.right() ? g.guiWidth() - 4 - w : 4;
		int y = corner.bottom() ? g.guiHeight() - BOTTOM_OFFSET - h : 4;
		Draw.box(g, x, y, w, h, 0xD0241B15, Style.PANEL_L, Style.PANEL_D);
		SessionManager.Active a = mod.sessions().active();
		Draw.lamp(g, x + PAD, y + PAD, 8, a != null && a.online());
		int ty = y + PAD;

		for (Line l : lines) {
			g.text(font, l.text(), x + PAD + 11, ty, l.color(), false);
			ty += LINE;
		}
	}

	/** 表示する行 (テストからも使う)。セッションも選択も無ければ空。 */
	public static List<String> lines(RedtactLive mod) {
		List<String> out = new ArrayList<>();

		for (Line l : styledLines(mod)) {
			out.add(l.text());
		}

		return out;
	}

	static List<Line> styledLines(RedtactLive mod) {
		List<Line> lines = new ArrayList<>(3);
		SessionManager sessions = mod.sessions();
		SessionManager.Active a = sessions.active();
		boolean auto = Configs.Generic.AUTO_SYNC.getBooleanValue();

		if (a != null) {
			lines.add(new Line(Tr.f("開催中「{0}」v{1}", a.circuitName, a.lastVersion()) + (a.listed() ? Tr.t("・公開中") : "")
					+ (a.online() ? "" : Tr.t("・再接続待ち")), a.online() ? Style.TXT : Style.LAMP));
			int unread = sessions.unread();
			StringBuilder sb = new StringBuilder();
			sb.append(Tr.n("閲覧者 {0}・未読 {1}・自動 {2}", a.viewers(), unread, auto ? "on" : "off"));

			if (mod.autoSync().debouncer().isDirty()) {
				sb.append(auto ? Tr.t(" (送信待ち)") : Tr.t(" (未送信あり)"));
			}

			lines.add(new Line(sb.toString(), unread > 0 ? Style.LAMP : Style.MUT));
		} else {
			Box box = mod.selection().box();

			com.redtact.live.store.CircuitRecord picked = mod.savedRanges().picked();

			if (picked != null) {
				// 選んでいる保存済みの範囲 (#39)
				lines.add(new Line(Tr.f("redtact: 範囲「{0}」{1}{2} (セッションなし)", picked.name, box != null ? " " + box.sizeText() : "",
						mod.savedRanges().pickedChanged() ? Tr.t(" (変更あり)") : ""), Style.MUT));
			} else if (box != null) {
				lines.add(new Line(Tr.f("redtact: 範囲 {0} (セッションなし)", box.sizeText()), Style.MUT));
			}
		}

		return lines;
	}
}
