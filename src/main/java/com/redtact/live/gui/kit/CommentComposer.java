package com.redtact.live.gui.kit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.comment.CommentSender;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * コメントの入力欄と「送信」(#74)。Enter でも送る。送っている間は押せず、送れなかった理由はトーストと {@link #status()} に出す。
 *
 * <p>書きかけ・送っている最中・送れなかった理由はセッションごとに覚える (タブを移っても・画面の大きさが変わって部品を作り直しても残す)。
 * 画面は部品 ({@link #field()}・{@link #button()}) を足し、{@link #target} で書く先を決める。
 */
public final class CommentComposer {
	public static final String SEND = Tr.ja("送信");
	/** 書きかけ (セッション ID ごと)。 */
	private static final Map<String, String> DRAFTS = new HashMap<>();
	/** 送っている最中のセッション。 */
	private static final Set<String> SENDING = new HashSet<>();
	/** 最後に送れなかった理由 (書き直すか、送れたら消す)。 */
	private static final Map<String, String> ERRORS = new HashMap<>();
	/** いま画面にある入力欄 (送った結果をここに返す。部品を作り直すと入れ替わる)。 */
	private static @Nullable CommentComposer current;

	private final RTextField field;
	private final RButton button;
	private final BiConsumer<String, Api.Comment> onSent;
	private @Nullable String sessionId;
	private @Nullable String blocked;

	/**
	 * @param onSent 送れたときに、送った先のセッション ID とコメントで呼ぶ (ゲームのスレッド。部品を作り直したあとや、
	 *               別のセッションに移ったあとでも呼ぶので、画面の部品に触らない)
	 */
	public CommentComposer(Font font, int x, int y, int width, BiConsumer<String, Api.Comment> onSent) {
		this.onSent = onSent;
		this.button = RButton.of(font, 0, y, Tr.t(SEND), RButton.Variant.PRIMARY, b -> send());
		int fieldW = Math.max(40, width - button.getWidth() - 3);
		this.field = new RTextField(font, x, y, fieldW, Component.literal(Tr.t("コメント")));
		// EditBox の上限は UTF-16 で数える (中継サーバは 1,000 文字 = コードポイントまで受けるので、ふつうの字は同じ。
		// サロゲートペアの絵文字だけ 500 個までになるが、マイクラの字では描けないものが多いので合わせない)
		field.setMaxLength(CommentSender.MAX_CHARS);
		button.setX(x + fieldW + 3);
		field.setResponder(text -> {
			if (sessionId != null) {
				if (text.isEmpty()) {
					DRAFTS.remove(sessionId);
				} else {
					DRAFTS.put(sessionId, text);
				}

				ERRORS.remove(sessionId);
			}

			update();
		});
		current = this;
		target(null, null);
	}

	public RTextField field() {
		return field;
	}

	public RButton button() {
		return button;
	}

	/**
	 * 書く先。sessionId が null か blockedReason があれば書けない (理由を欄の薄い字とボタンのツールチップに出す)。
	 */
	public void target(@Nullable String sessionId, @Nullable String blockedReason) {
		String id = blockedReason == null ? sessionId : null;
		String reason = id == null ? (blockedReason != null ? blockedReason : Tr.t("書き込めるセッションがありません")) : null;

		if (java.util.Objects.equals(id, this.sessionId) && java.util.Objects.equals(reason, this.blocked)) {
			return;
		}

		this.sessionId = null; // 書きかけを入れ替える間は覚えない
		field.setValue(id == null ? "" : DRAFTS.getOrDefault(id, ""));
		this.sessionId = id;
		this.blocked = reason;
		field.setEditable(id != null);
		field.hint(id != null ? Tr.t("コメントを書く (Enter で送信)") : reason);
		update();
	}

	/** 書く先のセッション (書けなければ null)。 */
	public @Nullable String sessionId() {
		return sessionId;
	}

	/** 欄にフォーカスがあるか。 */
	public boolean isFocused() {
		return field.isFocused();
	}

	/** Enter (テンキーも) で送る。送ったら (送ろうとしたら) true。 */
	public boolean keyPressed(KeyEvent event) {
		if (field.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			send();
			return true;
		}

		return false;
	}

	/** 送る (「送信」・Enter と同じ。テストからも呼ぶ)。送り始めたら true。 */
	public boolean send() {
		String id = sessionId;
		String text = field.getValue();

		if (id == null || SENDING.contains(id) || text.isBlank()) {
			return false;
		}

		SENDING.add(id);
		ERRORS.remove(id);
		update();
		RedtactLive.get().commentSender().send(id, text, r -> {
			SENDING.remove(id);

			if (r.ok()) {
				// 送った字のままなら消す (送っている間に書き足していれば残す)
				if (text.equals(DRAFTS.get(id))) {
					DRAFTS.remove(id);
				}

				onSent.accept(id, r.comment());
			} else {
				ERRORS.put(id, r.error());
				Messages.error(r.error());
			}

			CommentComposer c = current;

			if (c != null && id.equals(c.sessionId)) {
				if (r.ok() && c.field.getValue().equals(text)) {
					c.field.setValue("");
				}

				c.update();
			}
		});
		return true;
	}

	/** 欄の下などに出す状態 (送っている最中・送れなかった理由。無ければ null)。 */
	public @Nullable String status() {
		if (sessionId == null) {
			return null;
		}

		return SENDING.contains(sessionId) ? Tr.t("送信中…") : ERRORS.get(sessionId);
	}

	/** {@link #status()} が送れなかった理由か (赤で出す)。 */
	public boolean statusIsError() {
		return sessionId != null && !SENDING.contains(sessionId) && ERRORS.containsKey(sessionId);
	}

	private void update() {
		String reason = sessionId == null ? blocked
				: SENDING.contains(sessionId) ? Tr.t("送信中…")
				: field.getValue().isBlank() ? Tr.t("コメントを書いてから押す") : null;
		button.tooltip(Tr.f("コメントを送る (Enter でも送れる)。{0} 文字まで・2 秒に 1 件まで", CommentSender.MAX_CHARS)).disabledReason(reason);
	}
}
