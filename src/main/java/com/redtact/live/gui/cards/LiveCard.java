package com.redtact.live.gui.cards;

import java.util.function.Consumer;

import com.redtact.live.RedtactLive;
import com.redtact.live.gui.kit.ClipText;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.HostFace;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.LiveGrid;
import com.redtact.live.gui.kit.PreviewView;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.gui.rows.LiveRow;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.preview.CircuitPreviewRenderState;
import com.redtact.live.preview.PreviewCircuit;
import com.redtact.live.preview.PreviewStore;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 開催中のセッションの 1 枚 (#56。redtact の回路カードと同じ形): 上に回路のサムネ (16:9)、その左上に LIVE (ホスト接続中は赤・
 * 切断中は灰の「切断」)、右上に視聴中 (目) と閲覧数 (人。#90) の数、左下に印 (自分・複製不可)、右下に大きさ。未同期・大きすぎる回路はサムネの代わりに字。
 * 下に題 (2 行まで)、足にホストの顔と名前 (redtact の公開プロフィールがあれば、重ねると redtact の名前。#90)・
 * 開始からの時間。押すと詳細を開く。グリッドの 1 段 ({@link LiveGridRow}) が並べて描く。
 */
public final class LiveCard {
	/** これより多く見えるブロックがある回路はサムネを描かない (見えるカードを並べ直すたびに描き直すため。詳細では描く)。 */
	public static final int THUMB_MAX_VISIBLE = 12_000;
	/** サムネの地 (redtact の .el-thumb)。 */
	private static final int THUMB_BG = 0xFF1A1411;
	/** サムネの上に載せる印の地 (redtact のエディションの印と同じ濃い単色)。 */
	private static final int ON_THUMB = 0xE6140C04;
	private static final int OFFLINE = 0xFF5A4E46;

	private final LiveRow row;
	private final Consumer<LiveRow> onOpen;
	private int x;
	private int y;
	private int w;
	private int h;

	public LiveCard(LiveRow row, Consumer<LiveRow> onOpen) {
		this.row = row;
		this.onOpen = onOpen;
	}

	public LiveRow row() {
		return row;
	}

	/** 最後に置いた場所 (テストで押すため)。 */
	public int getX() {
		return x;
	}

	public int getY() {
		return y;
	}

	public int getWidth() {
		return w;
	}

	public int getHeight() {
		return h;
	}

	void place(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
	}

	boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	void open() {
		onOpen.accept(row);
	}

	/** いま描けるサムネの回路 (描けなければ null)。 */
	public @Nullable PreviewCircuit thumbCircuit() {
		if (row.thumbBlockedReason() != null) {
			return null;
		}

		PreviewStore.Entry e = entry();
		PreviewCircuit c = e == null ? null : e.circuit();
		return c == null || c.model().tooLarge() || c.model().drawCount() == 0 || c.model().visibleCount() > THUMB_MAX_VISIBLE ? null : c;
	}

	/** サムネの代わりに出す字 (描けていれば null)。 */
	public @Nullable String thumbMessage() {
		String blocked = row.thumbBlockedReason();

		if (blocked != null) {
			return blocked;
		}

		PreviewStore.Entry e = entry();
		PreviewCircuit c = e == null ? null : e.circuit();

		if (c == null) {
			return e == null || e.status() == PreviewStore.Status.LOADING ? Tr.t("読み込み中…") : Tr.t("絵を取れません");
		}

		if (c.model().tooLarge() || c.model().visibleCount() > THUMB_MAX_VISIBLE) {
			return Tr.t("ブロックが多いので絵はありません (詳細で見られます)");
		}

		return c.model().drawCount() == 0 ? Tr.t("空気だけの範囲です") : null;
	}

	private PreviewStore.@Nullable Entry entry() {
		return RedtactLive.get().previews().get(row.session().sessionId);
	}

	void extract(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered) {
		Font font = Minecraft.getInstance().font;
		Api.Session s = row.session();
		int face = hovered ? Style.lighten(Style.PANEL, 0.06f) : Style.PANEL;
		Draw.box(g, x, y, w, h, face, Style.PANEL_L, Style.PANEL_D);

		// サムネ
		int tx = x + LiveGrid.THUMB_INSET;
		int ty = y + LiveGrid.THUMB_INSET;
		int tw = LiveGrid.thumbWidth(w);
		int th = LiveGrid.thumbHeight(w);
		g.fill(tx, ty, tx + tw, ty + th, THUMB_BG);
		PreviewCircuit c = thumbCircuit();

		if (c != null) {
			float yaw = PreviewView.DEFAULT_YAW;
			float pitch = PreviewView.DEFAULT_PITCH;
			float scale = c.model().fitScale(yaw, pitch, tw - 8, th - 8);
			// バニラは描画状態の種類ごとにテクスチャが 1 枚だが、Fabric API (PictureInPictureRendererPool) が同じフレームの
			// 2 枚目からは別の renderer (別のテクスチャ) を使うので、カードごとに別の絵になる。並びが変わらなければ前の絵を使い回す
			g.guiRenderState.addPicturesInPictureState(new CircuitPreviewRenderState(c, yaw, pitch, tx, ty, tx + tw, ty + th, scale, g.scissorStack.peek()));
		} else {
			String msg = thumbMessage();
			centered(g, font, msg == null ? "" : msg, tx + tw / 2, ty + th / 2, tw - 12, Style.MUT);
		}

		// サムネの左上: LIVE / 切断
		String live = s.hostOnline ? "LIVE" : Tr.t("切断");
		int lw = font.width(live) + 6;
		g.fill(tx + 3, ty + 3, tx + 3 + lw, ty + 3 + Draw.BADGE_H, Style.OUTLINE);
		g.fill(tx + 4, ty + 4, tx + 2 + lw, ty + 2 + Draw.BADGE_H, s.hostOnline ? Style.RED : OFFLINE);
		g.text(font, live, tx + 6, ty + 5, s.hostOnline ? Style.ON_RED : Style.TXT, false);

		// サムネの右上: 視聴中 (目) と閲覧数 (人。古い中継サーバでは閲覧数は出さない。#90)。重ねると何の数かを出す
		String viewers = String.valueOf(s.viewerCount);
		String views = s.views == null ? null : String.valueOf(s.views);
		int vw = 3 + 7 + 3 + font.width(viewers) + 3 + (views == null ? 0 : 5 + Icon.PERSON.width() + 2 + font.width(views));
		int vx = tx + tw - 3 - vw;
		g.fill(vx, ty + 3, vx + vw, ty + 3 + Draw.BADGE_H, ON_THUMB);
		Draw.eye(g, vx + 3, ty + 6, Style.TXT);
		int countX = vx + 3 + 7 + 3;
		g.text(font, viewers, countX, ty + 5, Style.TXT, false);

		if (views != null) {
			int px = countX + font.width(viewers) + 5;
			Icon.PERSON.draw(g, px, ty + 5, Style.TXT);
			g.text(font, views, px + Icon.PERSON.width() + 2, ty + 5, Style.TXT, false);
		}

		Draw.hoverTooltip(g, font, row.audienceTooltip(), vx, ty + 3, vw, Draw.BADGE_H, mouseX, mouseY);

		// サムネの右下: 大きさ
		String size = row.sizeText();
		int bottom = ty + th - 3 - Draw.BADGE_H;
		int sizeX = tx + tw - 3;

		if (size != null) {
			int sw = font.width(size) + 6;
			sizeX -= sw;
			g.fill(sizeX, bottom, sizeX + sw, bottom + Draw.BADGE_H, ON_THUMB);
			g.text(font, size, sizeX + 3, bottom + 2, Style.TXT, false);
		}

		// サムネの左下: 印 (入るぶんだけ)
		int bx = tx + 3;
		int bmax = sizeX - 3;
		bx = badge(g, font, bx, bottom, bmax, row.mine() ? Tr.t("自分") : null, Draw.Tone.OK);

		if (!s.copyAllowed()) {
			badge(g, font, bx, bottom, bmax, Tr.t("複製不可"), Draw.Tone.RED);
		}

		// 題 (2 行まで)
		int cx = x + 5;
		int cw = w - 10;
		Draw.clippedLines(g, font, row.title(), cx, ty + th + 4, cw, 2, Style.TXT, mouseX, mouseY);

		// 足: ホストの顔と名前・開始からの時間
		int fy = y + h - 3 - 10;
		HostFace.draw(g, s.hostVerified ? s.hostUuid : null, s.host, cx + 1, fy, 8);
		String ago = row.ago();
		int agoW = font.width(ago);
		g.text(font, ago, x + w - 5 - agoW, fy + 1, Style.MUT, false);
		int nx = cx + 1 + 8 + 4;
		drawHost(g, font, row, cx, nx, fy + 1, Math.max(0, x + w - 5 - agoW - 4 - nx), mouseX, mouseY);

		if (hovered) {
			// 押せることを示す銅の縁
			g.fill(x, y, x + w, y + 1, Style.COP);
			g.fill(x, y + h - 1, x + w, y + h, Style.COP);
			g.fill(x, y, x + 1, y + h, Style.COP);
			g.fill(x + w - 1, y, x + w, y + h, Style.COP);
		}
	}

	/**
	 * ホストの名前を maxW に切って (x, y) に描く。redtact の公開プロフィールがあるホスト (#90) は、顔 (faceX から) と名前に重ねると
	 * 名前と redtact の名前を出す (無ければ切ったときだけ全文)。描いた名前の幅を返す (詳細の補足でも使う)。
	 */
	public static int drawHost(GuiGraphicsExtractor g, Font font, LiveRow row, int faceX, int x, int y, int maxW, int mouseX, int mouseY) {
		String host = row.hostLabel();
		String tip = row.hostTooltip();

		if (tip == null) {
			return Draw.clippedText(g, font, host, x, y, maxW, Style.COP_TX, mouseX, mouseY);
		}

		String shown = Draw.clip(font, host, maxW);
		g.text(font, shown, x, y, Style.COP_TX, false);
		int w = font.width(shown);
		Draw.hoverTooltip(g, font, tip, faceX, y - 2, x - faceX + w, 11, mouseX, mouseY);
		return w;
	}

	/** 印をサムネの上に置く (入らなければ置かない)。次の x を返す。 */
	private static int badge(GuiGraphicsExtractor g, Font font, int x, int y, int maxRight, @Nullable String text, Draw.Tone tone) {
		if (text == null) {
			return x;
		}

		int bw = Draw.badgeWidth(font, text);

		if (x + bw > maxRight) {
			return x;
		}

		Draw.badge(g, font, x, y, text, tone);
		return x + bw + 2;
	}

	/** 折り返して真ん中に置く (サムネの代わりの字)。 */
	private static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int cy, int maxWidth, int color) {
		var lines = font.split(Component.literal(text), Math.max(20, maxWidth));
		int top = cy - lines.size() * 10 / 2;

		for (int i = 0; i < lines.size(); i++) {
			var line = lines.get(i);
			g.text(font, line, cx - font.width(line) / 2, top + i * 10 + 1, color, false);
		}
	}

	/** マウスがこのカードの上か (はさみの外は除く)。 */
	boolean hovered(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		return ClipText.inside(x, y, w, h, mouseX, mouseY) && g.containsPointInScissor(mouseX, mouseY);
	}
}
