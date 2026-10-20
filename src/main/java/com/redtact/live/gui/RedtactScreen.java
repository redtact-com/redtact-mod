package com.redtact.live.gui;

import java.util.ArrayList;
import java.util.List;

import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.Library;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;

/**
 * メニューの「redtact」で、ゲーム内の redtact (#46) をまだ出せないときの入口 (#51): MCEF Modern が無い・Chromium の用意の途中・失敗。
 * MCEF Modern があれば、この画面を開いたときに用意を始め、済んだらこのタブにブラウザを出す ({@link BrowserSupport#tick})。
 * 無ければ (用意に失敗したときも)「redtact を開く」は外のブラウザで開く。
 */
public final class RedtactScreen extends MenuScreen {
	private static final int LINE = 10;
	private @Nullable RButton openButton;
	private List<String> lines = List.of();
	private BrowserSupport.@Nullable State builtState;
	private int panelH;
	private int textY;
	private int statusY;

	public RedtactScreen() {
		super(MenuTab.REDTACT);
	}

	@Override
	protected void initContent() {
		boolean inGame = BrowserSupport.available();

		if (inGame) {
			BrowserSupport.prepareForTab(); // 用意を始め、済んだらこのタブに出す
		}

		BrowserSupport.State st = BrowserSupport.status().state();
		builtState = st;
		boolean external = !inGame || st == BrowserSupport.State.FAILED;
		int w = contentWidth();
		int x = MARGIN + 6;
		int y = top() + 5 + 14 + 5; // 見出しの帯の下
		textY = y;
		String folder = "schematics/" + Library.FOLDER + "/";
		lines = wrap(inGame ? Tr.f("レッドストーン回路の共有サイト redtact を、このタブに出します (MCEF Modern の Chromium)。回路の「ダウンロード」を押すと保管庫 ({0}) に入り、"
				+ "保管庫タブから Litematica の配置を作れます。ログインは外のブラウザで済ませると、ゲーム内でもログインした状態になります", folder)
				: Tr.f("MCEF Modern (任意の MOD) を入れると、このタブに redtact が出ます。いまは外のブラウザで開きます。ダウンロードしたファイルを {0} に置けば "
						+ "Litematica で読めます", folder), w - 12);
		y += lines.size() * LINE + 4;
		openButton = addRenderableWidget(RButton.of(font, x, y, external ? Tr.t("redtact を開く (外のブラウザ)") : Tr.t("redtact を開く"), RButton.Variant.PRIMARY, b -> {
			if (external) {
				BrowserSupport.openExternal(BrowserSupport.lastOrHome());
			} else {
				BrowserSupport.open(null);
			}
		}).tooltip(external ? Tr.t("外のブラウザで redtact を開く") : Tr.t("ゲーム内で redtact を開く")));

		if (!external && st != BrowserSupport.State.READY) {
			openButton.disabledReason(Tr.t("準備中です (済んだらこのタブに出ます)"));
		}

		int bx = x + openButton.getWidth() + 4;

		if (inGame) {
			addRenderableWidget(RButton.of(font, bx, y, Tr.t("外のブラウザで開く"), RButton.Variant.SECONDARY,
					b -> BrowserSupport.openExternal(BrowserSupport.lastOrHome())).tooltip(Tr.t("外のブラウザで redtact を開く")));
		} else {
			addRenderableWidget(RButton.of(font, bx, y, Tr.t("MCEF Modern を入手"), RButton.Variant.SECONDARY,
					b -> BrowserSupport.openExternal(BrowserSupport.MCEF_PAGE)).tooltip(Tr.t("Modrinth の MCEF Modern のページを開く (Fabric・MC 26.3 版)")));
		}

		y += RButton.H + 6;
		statusY = y;
		y += LINE + 4;
		panelH = y - top();
	}

	private List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();

		for (FormattedText line : font.getSplitter().splitLines(text, Math.max(40, width), net.minecraft.network.chat.Style.EMPTY)) {
			out.add(line.getString());
		}

		return out;
	}

	public @Nullable RButton openButton() {
		return openButton;
	}

	public String openButtonLabel() {
		return openButton == null ? "" : openButton.getMessage().getString();
	}

	@Override
	public void tick() {
		// 用意が失敗したら外のブラウザのボタンに、済んだら押せるように作り直す (済んだときはふつう tick でブラウザに切り替わる)
		if (BrowserSupport.status().state() != builtState) {
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int w = contentWidth();
		panel(g, MARGIN, top(), w, panelH);
		Draw.sectionLabel(g, font, MARGIN + 5, top() + 5, Tr.t("redtact をゲーム内で開く"), w - 10, mouseX, mouseY);
		int y = textY;

		for (String line : lines) {
			g.text(font, line, MARGIN + 6, y, Style.TXT, false);
			y += LINE;
		}

		BrowserSupport.Status s = BrowserSupport.status();
		int color = switch (s.state()) {
			case READY -> Style.OK;
			case PREPARING -> Style.LAMP;
			case FAILED -> Style.RED_TX;
			default -> Style.MUT;
		};
		int inFlight = RedtactLive.get().downloads().inFlight();
		// MCEF Modern が無いときの説明は上の文と同じなので、開く先だけ
		String home = BrowserSupport.home().toString();
		String text;

		if (s.state() == BrowserSupport.State.MISSING) {
			text = inFlight > 0 ? Tr.n("保存中 {0} 件・開く先 {1}", inFlight, home) : Tr.f("開く先 {0}", home);
		} else {
			text = inFlight > 0 ? Tr.n("{1}・保存中 {0} 件・開く先 {2}", inFlight, s.text(), home) : Tr.f("{0}・開く先 {1}", s.text(), home);
		}

		Draw.clippedText(g, font, text, MARGIN + 6, statusY, w - 12, color, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, a);
	}
}
