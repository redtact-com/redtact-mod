package com.redtact.live.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.redtact.live.RedtactLive;
import com.redtact.live.comment.CommentRules;
import com.redtact.live.gui.cards.CommentCard;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.CommentComposer;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.gui.rows.CommentRow;
import com.redtact.live.i18n.Tr;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.StoredComment;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.FormattedText;

/**
 * メニューの「コメント」: 回路ごとのコメント (いまのセッションと過去分)。新しい順。
 * 開いた時点の未読に印を付け、開いたら既読にする。開いている間に届いたコメントも一覧に足す。
 * 開催中のセッションの回路なら、上の欄から閲覧者に返事を書ける (#74。届いたコメントは「ホスト」の印付きで一覧に入る)。
 */
public final class CommentsScreen extends MenuScreen {
	private static @Nullable String selectedCircuitId;

	private List<CircuitRecord> circuits = List.of();
	private @Nullable CircuitRecord circuit;
	private List<CommentRow> rows = List.of();
	private int unreadAtOpen;
	private @Nullable CardList<MenuCard> list;
	private @Nullable CommentComposer composer;
	private int labelX;
	private int textWidth;
	/** 一覧に読み込んだときの {@link SessionManager#commentsGeneration()}。 */
	private long shownGeneration;
	/**
	 * 開いたときの既読の位置 (readMarkCircuit の回路を出しているあいだ保つ)。新着が届いて読み直しても、
	 * 開いた時点の未読の印を消さないため。
	 */
	private @Nullable String readMarkCircuit;
	private @Nullable String readMarkAtOpen;
	/** 既読にした最後のコメントの ID (同じところまでをくり返し既読にしない)。 */
	private @Nullable String markedUpTo;

	public CommentsScreen(@Nullable String circuitId) {
		super(MenuTab.COMMENTS);

		if (circuitId != null) {
			selectedCircuitId = circuitId;
		}
	}

	private static SessionManager sessions() {
		return RedtactLive.get().sessions();
	}

	@Override
	protected void initContent() {
		int composerY = top() + RButton.H + 6;
		int listY = composerY + RButton.H + 6;
		int listW = contentWidth();
		textWidth = listW - 4 - 3 - 12;
		shownGeneration = sessions().commentsGeneration();
		load();
		int x = MARGIN;
		RButton prev = addRenderableWidget(RButton.icon(x, top(), Icon.BACK, Tr.t("前の回路"), RButton.Variant.SECONDARY, b -> cycle(-1)));
		x += prev.getWidth() + 3;
		RButton next = addRenderableWidget(RButton.icon(x, top(), Icon.FORWARD, Tr.t("次の回路"), RButton.Variant.SECONDARY, b -> cycle(1)));
		prev.enabled(circuits.size() > 1);
		next.enabled(circuits.size() > 1);
		labelX = x + next.getWidth() + 8;
		// 書いたコメントはホストの WebSocket で届き、ほかのコメントと同じく保存してから一覧に入る
		composer = new CommentComposer(font, MARGIN, composerY, listW, (sessionId, c) -> {
		});
		addRenderableWidget(composer.field());
		addRenderableWidget(composer.button());
		applyTarget();
		list = addRenderableWidget(new CardList<>(minecraft, MARGIN, listY, listW, Math.max(30, height - 6 - listY)));
		list.setCards(cards());
	}

	/** 書く先: 出している回路が開催中のセッションの回路なら、そのセッション。 */
	private void applyTarget() {
		if (composer == null) {
			return;
		}

		SessionManager.Active a = sessions().active();
		composer.target(a != null && circuit != null && a.circuitId.equals(circuit.circuitId) ? a.sessionId : null,
				CommentRules.blockedReason(a == null ? null : a.circuitId, a == null ? null : a.circuitName, circuit == null ? null : circuit.circuitId));
	}

	private List<MenuCard> cards() {
		List<MenuCard> cards = new ArrayList<>(rows.size());

		for (CommentRow r : rows) {
			cards.add(new CommentCard(r));
		}

		return cards;
	}

	/** 回路を選び、コメントを読み込み、既読にする。 */
	private void load() {
		SessionManager s = sessions();
		circuits = s.listCircuits();
		SessionManager.Active a = s.active();
		circuit = null;
		String want = selectedCircuitId != null ? selectedCircuitId : a != null ? a.circuitId : null;

		for (CircuitRecord r : circuits) {
			if (r.circuitId.equals(want)) {
				circuit = r;
			}
		}

		if (circuit == null && !circuits.isEmpty()) {
			circuit = circuits.getFirst(); // 最後に更新した回路
		}

		if (circuit == null) {
			rows = List.of();
			return;
		}

		selectedCircuitId = circuit.circuitId;

		if (!circuit.circuitId.equals(readMarkCircuit)) {
			readMarkCircuit = circuit.circuitId;
			readMarkAtOpen = circuit.lastReadCommentId;
			markedUpTo = circuit.lastReadCommentId;
		}

		loadComments(circuit);
	}

	/** その回路のコメントを読み直す (新しい順)。開いた時点の未読に印を付け、新しいコメントがあれば既読にする。 */
	private void loadComments(CircuitRecord c0) {
		SessionManager s = sessions();
		SessionManager.Active a = s.active();
		List<StoredComment> comments = new ArrayList<>(s.comments(c0.circuitId));
		comments.sort(Comparator.comparing((StoredComment c) -> c.id).reversed());
		String currentSession = a != null && a.circuitId.equals(c0.circuitId) ? a.sessionId : null;
		List<CommentRow> out = new ArrayList<>(comments.size());
		unreadAtOpen = 0;

		for (StoredComment c : comments) {
			boolean unread = SessionManager.isUnread(readMarkAtOpen, c);

			if (unread) {
				unreadAtOpen++;
			}

			out.add(new CommentRow(c, unread, c.sessionId != null && c.sessionId.equals(currentSession), wrap(font, c.text, textWidth)));
		}

		rows = out;
		String newest = comments.isEmpty() ? null : comments.getFirst().id;

		if (newest != null && (markedUpTo == null || newest.compareTo(markedUpTo) > 0)) {
			markedUpTo = newest;
			s.markRead(c0.circuitId);
		}
	}

	/** 本文を幅で折り返す。 */
	static List<String> wrap(Font font, @Nullable String text, int width) {
		List<String> wrapped = new ArrayList<>();

		for (FormattedText line : font.getSplitter().splitLines(text == null ? "" : text, Math.max(40, width), net.minecraft.network.chat.Style.EMPTY)) {
			wrapped.add(line.getString());
		}

		return wrapped;
	}

	private void cycle(int step) {
		if (circuits.isEmpty() || circuit == null) {
			return;
		}

		int i = Math.floorMod(circuits.indexOf(circuit) + step, circuits.size());
		selectedCircuitId = circuits.get(i).circuitId;
		rebuildWidgets();
	}

	@Override
	public void tick() {
		super.tick();
		long gen = sessions().commentsGeneration();

		if (gen != shownGeneration) {
			// 開いている間に届いた (自分が書いたものも)。入力欄はそのまま、一覧だけ読み直す
			shownGeneration = gen;

			if (circuit != null) {
				loadComments(circuit);
			} else {
				load(); // 回路がまだ無かった (開いてからセッションを始めた)
			}

			if (list != null) {
				list.setCards(cards());
			}
		}

		applyTarget(); // セッションを開いた・終えた
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (composer != null && composer.keyPressed(event)) {
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int right = width - MARGIN;

		if (circuit == null) {
			Draw.clippedText(g, font, Tr.t("コメントはまだありません (セッションを開くと閲覧者のコメントがここに溜まります)"), labelX, top() + 4, right - labelX, Style.MUT, mouseX, mouseY);
		} else {
			String name = circuit.name + " (" + (circuits.indexOf(circuit) + 1) + "/" + circuits.size() + ")";
			int x = labelX + Draw.clippedText(g, font, name, labelX, top() + 4, Math.max(40, (right - labelX) / 2), Style.TXT, mouseX, mouseY) + 8;
			String info = Tr.n("全 {0} 件・新しい順", rows.size());
			Draw.clippedText(g, font, info, x, top() + 4, Math.max(0, right - x), Style.MUT, mouseX, mouseY);
			x += font.width(info) + 6;

			String fresh = Tr.n("新着 {0}", unreadAtOpen);

			if (unreadAtOpen > 0 && x + Draw.badgeWidth(font, fresh) <= right) {
				Draw.badge(g, font, x, top() + 2, fresh, Draw.Tone.WARN);
				x += Draw.badgeWidth(font, fresh) + 6;
			}

			String status = composer == null ? null : composer.status();

			if (status != null && x < right) {
				Draw.clippedText(g, font, status, x, top() + 4, right - x, composer.statusIsError() ? Style.RED_TX : Style.MUT, mouseX, mouseY);
			}
		}

		super.extractRenderState(g, mouseX, mouseY, a);
	}

	// ---- テスト用

	/** いま出している行。 */
	public List<CommentRow> rows() {
		return rows;
	}

	/** 上の入力欄と「送信」。 */
	public @Nullable CommentComposer composer() {
		return composer;
	}
}
