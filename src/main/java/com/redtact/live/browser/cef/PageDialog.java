package com.redtact.live.browser.cef;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefJSDialogCallback;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//? if >=26.1 {
import net.minecraft.client.input.PreeditEvent;
//?}
import net.minecraft.network.chat.Component;

/**
 * ページの JS のダイアログ (alert・confirm・prompt・離脱の確認) を redtact タブの上に出す窓 (#65)。
 *
 * <p>画面外に描く Chromium は、受け口 ({@link CefHooks}) が無いとダイアログを出さずに confirm を false・離脱の確認を
 * 「とどまる」にしてしまう (redtact の編集中に移れない)。受け口で受けたものをこの窓で出し、押したほうを Chromium へ返す。
 * Enter は OK (離れる)・Esc はキャンセル。返すのは 1 回だけ ({@link #finish})。
 */
final class PageDialog {
	enum Kind {
		ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD
	}

	private static final int MAX_W = 320;
	private static final int PAD = 10;
	private static final int LINE_H = 10;
	private static final int MAX_LINES = 12;

	final CefBrowser browser;
	final Kind kind;
	/** ページのホスト (分からなければ null。題では「このページ」)。 */
	private final @Nullable String host;
	private final String message;
	private final String defaultText;
	private final boolean reload;
	private final CefJSDialogCallback callback;
	private final AtomicBoolean done = new AtomicBoolean();
	/** メニューを閉じるときの離脱の確認か (#67。文言を「メニューを閉じますか？」に)。 */
	private boolean closing;
	private volatile boolean accepted;

	private @Nullable RButton ok;
	private @Nullable RButton cancel;
	private @Nullable RTextField field;
	private String fieldText;
	private List<String> lines = List.of();
	private int x;
	private int y;
	private int w;
	private int h;

	PageDialog(CefBrowser browser, Kind kind, @Nullable String url, @Nullable String message, @Nullable String defaultText, boolean reload,
			CefJSDialogCallback callback) {
		this.browser = browser;
		this.kind = kind;
		this.host = host(url);
		this.message = message == null ? "" : message;
		this.defaultText = defaultText == null ? "" : defaultText;
		this.fieldText = this.defaultText;
		this.reload = reload;
		this.callback = callback;
	}

	private static @Nullable String host(@Nullable String url) {
		try {
			String h = url == null ? null : URI.create(url).getHost();
			return h == null || h.isBlank() ? null : h;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** メニューを閉じるときの確認にする (窓を組む前に。#67)。 */
	void markClosing() {
		closing = true;
	}

	boolean closing() {
		return closing;
	}

	/** OK (離れる・閉じる) で答えたか。 */
	boolean accepted() {
		return accepted;
	}

	String title() {
		if (closing) {
			return Tr.t("メニューを閉じますか？");
		}

		String from = host != null ? host : Tr.t("このページ");
		return switch (kind) {
			case ALERT -> Tr.f("{0} からのお知らせ", from);
			case CONFIRM -> Tr.f("{0} の確認", from);
			case PROMPT -> Tr.f("{0} の入力", from);
			case BEFORE_UNLOAD -> reload ? Tr.t("このページを再読み込みしますか？") : Tr.t("このページを離れますか？");
		};
	}

	/** 本文。離脱の確認はページの文を出さない (Chromium の決まり。ページは文を選べない)。 */
	String message() {
		if (closing) {
			return Tr.t("このページで行った変更が保存されない可能性があります。");
		}

		return kind == Kind.BEFORE_UNLOAD ? Tr.t("行った変更が保存されない可能性があります。") : message;
	}

	/** Chromium へ返す (1 回だけ)。accepted は OK・離れる。 */
	void finish(boolean accepted) {
		if (done.compareAndSet(false, true)) {
			this.accepted = accepted;
			callback.Continue(accepted, accepted && field != null ? field.getValue() : accepted ? fieldText : "");
		}
	}

	/** 返さずに捨てる (ページが移った・Chromium がダイアログを取り消した)。 */
	void drop() {
		done.set(true);
	}

	boolean done() {
		return done.get();
	}

	// ---- 配置 (画面を組むたび)

	/** 窓と部品を置く。field を作ったら返す (呼ぶ側がフォーカスを渡す)。 */
	@Nullable RTextField layout(Font font, int screenW, int screenH) {
		if (field != null) {
			fieldText = field.getValue(); // 組み直しで打った字を失わない
		}

		w = Math.min(MAX_W, screenW - 2 * PAD);
		int inner = w - 2 * PAD;
		lines = wrap(font, message(), inner);
		int fieldH = kind == Kind.PROMPT ? RButton.H + 6 : 0;
		h = PAD + LINE_H + 6 + lines.size() * LINE_H + 8 + fieldH + RButton.H + PAD;
		x = (screenW - w) / 2;
		y = Math.max(PAD, (screenH - h) / 2);
		int by = y + h - PAD - RButton.H;
		int right = x + w - PAD;

		String okLabel = switch (kind) {
			case BEFORE_UNLOAD -> closing ? Tr.t("閉じる") : reload ? Tr.t("再読み込み") : Tr.t("離れる");
			default -> "OK";
		};
		ok = RButton.of(font, 0, by, okLabel, kind == Kind.BEFORE_UNLOAD ? RButton.Variant.DANGER : RButton.Variant.PRIMARY, b -> finish(true));
		right -= ok.getWidth();
		ok.setX(right);
		cancel = null;

		if (kind != Kind.ALERT) {
			cancel = RButton.of(font, 0, by, Tr.t("キャンセル"), RButton.Variant.SECONDARY, b -> finish(false));
			right -= cancel.getWidth() + 6;
			cancel.setX(right);
		}

		field = null;

		if (kind == Kind.PROMPT) {
			field = new RTextField(font, x + PAD, by - fieldH, inner, RButton.H, Component.literal(title()));
			field.setMaxLength(1000);
			field.setValue(fieldText);
		}

		return field;
	}

	private static List<String> wrap(Font font, String text, int width) {
		List<String> out = new ArrayList<>();
		font.getSplitter().splitLines(text, Math.max(1, width), net.minecraft.network.chat.Style.EMPTY, false,
				(style, start, end) -> out.add(text.substring(start, end).stripTrailing()));

		if (out.size() > MAX_LINES) {
			List<String> cut = new ArrayList<>(out.subList(0, MAX_LINES));
			cut.set(MAX_LINES - 1, Draw.clip(font, cut.get(MAX_LINES - 1) + "…", width));
			return cut;
		}

		return out;
	}

	// ---- 描画 (ページと帯の上に重ねる)

	void extract(GuiGraphicsExtractor g, Font font, int screenW, int screenH, int mouseX, int mouseY, float a) {
		g.fill(0, 0, screenW, screenH, Style.BACKDROP);
		Draw.panel(g, x, y, w, h);
		g.text(font, Draw.clip(font, title(), w - 2 * PAD), x + PAD, y + PAD, Style.COP_TX, false);
		int ty = y + PAD + LINE_H + 6;

		for (String line : lines) {
			g.text(font, line, x + PAD, ty, Style.TXT, false);
			ty += LINE_H;
		}

		if (field != null) {
			field.extractRenderState(g, mouseX, mouseY, a);
		}

		if (cancel != null) {
			cancel.extractRenderState(g, mouseX, mouseY, a);
		}

		if (ok != null) {
			ok.extractRenderState(g, mouseX, mouseY, a);
		}
	}

	// ---- 入力 (窓が出ている間は、ページ・帯・タブへは何も渡さない)

	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (field != null && field.isMouseOver(event.x(), event.y())) {
			field.mouseClicked(event, doubleClick);
		} else if (cancel != null && cancel.isMouseOver(event.x(), event.y())) {
			cancel.mouseClicked(event, doubleClick);
		} else if (ok != null && ok.isMouseOver(event.x(), event.y())) {
			ok.mouseClicked(event, doubleClick);
		}

		return true;
	}

	boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			finish(false);
		} else if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			finish(true);
		} else if (field != null) {
			field.keyPressed(event);
		}

		return true;
	}

	boolean charTyped(CharacterEvent event) {
		if (field != null) {
			field.charTyped(event);
		}

		return true;
	}

	//? if >=26.1 {
	boolean preeditUpdated(@Nullable PreeditEvent event) {
		return field != null && field.preeditUpdated(event);
	}
	//?}

	@Nullable RTextField field() {
		return field;
	}

	@Nullable RButton okButton() {
		return ok;
	}

	@Nullable RButton cancelButton() {
		return cancel;
	}
}
