package com.redtact.live.gui;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.redtact.live.RedtactLive;
import com.redtact.live.auth.MinecraftJoin;
import com.redtact.live.branch.BranchManager;
import com.redtact.live.branch.BranchText;
import com.redtact.live.config.Configs;
import com.redtact.live.copy.LitematicaSupport;
import com.redtact.live.gui.cards.BranchCard;
import com.redtact.live.gui.cards.CircuitCard;
import com.redtact.live.gui.cards.HeaderCard;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.RToggle;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.gui.rows.BranchRow;
import com.redtact.live.gui.rows.CircuitRow;
import com.redtact.live.gui.rows.HeaderRow;
import com.redtact.live.gui.rows.SessionRow;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.selection.Box;
import com.redtact.live.selection.NameQuery;
import com.redtact.live.selection.SavedRanges;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.StoredComment;
import com.redtact.live.store.Times;
import com.redtact.live.tool.SelectionTool;
import com.redtact.live.ui.EventLog;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

/**
 * メニューの「セッション」: 状態 (Web のセッション画面の見出しと同じバッジ)・push・終了・共有リンク・名前を入れて開始・保存済みの回路から再開。
 * 分岐 (#13): 分岐の準備 (分岐の名前・この配置で分岐を開始・やめる)、子のセッションの分岐元、親のセッションの分岐した子の一覧。
 * 状態の板は、ふだんは題・バッジ・要点の 1 行だけで、範囲の座標・ホスト・同期の設定・共有リンク・サーバ・最近の出来事は「詳細」で開く。
 * 開催中は名前と開始の行を隠し、開始前は操作 (同期・終了・共有リンク) の行を隠す (#34)。
 * 開催中は 2 段目の右に「保管庫へ」「redtact に投稿」(最後に同期した版を保管庫に入れる・そのまま投稿の画面へ。#73)。
 * 保存済みの範囲 (#39): 一覧の上の検索欄で名前を絞り、カードを押して選ぶ (羽根の中クリックでも選べる)。選んでいれば開始の行は
 * 「開始」(その回路を再開) と「新規作成」(選んだ範囲と選択を外して名前の欄へ)。ほかのワールドの回路は後ろに出し、選べない。
 */
public final class SessionScreen extends MenuScreen {
	private static final int LINE = 11;
	private static String nameDraft = "";
	/** 開始前の「一覧に出す」。既定 off で、開始・再開するたびに off に戻す。 */
	private static boolean listDraft;
	/** 開始前の「複製を許可」。null なら設定の「複製を許可 (新しいセッションの既定)」に従う。開始・再開するたびに null に戻す。 */
	private static @Nullable Boolean copyDraft;
	/** 状態の板の「詳細」を開いているか (ゲームを閉じるまで覚える)。 */
	private static boolean detailsOpen;
	/** 分岐の名前の入力。null なら「<分岐元> の分岐」。準備が変わったら null に戻す。 */
	private static @Nullable String branchNameDraft;
	private static BranchManager.@Nullable Prep branchNameFor;
	/** 一覧の検索 (ゲームを閉じるまで覚える)。 */
	private static String searchDraft = "";

	private @Nullable RTextField nameField;
	private @Nullable RButton startButton;
	private @Nullable RButton newButton;
	private @Nullable RTextField searchField;
	private @Nullable RToggle listButton;
	private @Nullable RToggle copyButton;
	private @Nullable RButton libraryButton;
	private @Nullable RButton postButton;
	private @Nullable RTextField branchNameField;
	private @Nullable RButton branchStartButton;
	private @Nullable RButton branchCancelButton;
	private @Nullable RButton parentOpenButton;
	private @Nullable RButton detailsButton;
	private @Nullable CardList<MenuCard> list;
	private int statusY;
	private int statusH;
	private int listLabelY;
	private View view = view(null, sessions());

	/** 画面を作ったときの様子 (変わったら作り直す)。 */
	private @Nullable String builtFor;
	private boolean builtBusy;
	private boolean builtListed;
	private boolean builtListedPending;
	private boolean builtAllowCopy;
	private boolean builtAllowCopyPending;
	private BranchManager.@Nullable Prep builtPrep;
	private boolean builtParent;
	private boolean builtParentEnded;
	private int builtBranchGeneration = -1;
	private int builtDetailCount = -1;
	private @Nullable String builtPicked;
	private boolean builtPickedChanged;

	public SessionScreen() {
		super(MenuTab.SESSION);
	}

	public static boolean listDraft() {
		return listDraft;
	}

	/** 開始・再開に使ったら off に戻す。 */
	public static boolean takeListDraft() {
		boolean v = listDraft;
		listDraft = false;
		return v;
	}

	/** 次の開始で複製を許すか。 */
	public static boolean copyDraft() {
		return copyDraft != null ? copyDraft : Configs.Generic.DEFAULT_ALLOW_COPY.getBooleanValue();
	}

	/** 開始・再開に使ったら既定 (設定) に戻す。 */
	public static boolean takeCopyDraft() {
		boolean v = copyDraft();
		copyDraft = null;
		return v;
	}

	private static SessionManager sessions() {
		return RedtactLive.get().sessions();
	}

	private static BranchManager branches() {
		return RedtactLive.get().branches();
	}

	@Override
	protected void initContent() {
		if (nameField != null) {
			nameDraft = nameField.getValue();
		}

		BranchManager.Prep prep = branches().prep();

		if (branchNameField != null && builtPrep != null && builtPrep == prep) {
			branchNameDraft = branchNameField.getValue();
		}

		if (prep != branchNameFor) {
			branchNameFor = prep;
			branchNameDraft = null;
		}

		SessionManager s = sessions();
		SessionManager.Active a = s.active();
		view = view(a, s);
		builtFor = a == null ? null : a.sessionId;
		builtBusy = s.isBusy();
		builtListed = a != null && a.listed();
		builtListedPending = a != null && a.listedPending();
		builtAllowCopy = a != null && a.allowCopy();
		builtAllowCopyPending = a != null && a.allowCopyPending();
		builtPrep = prep;
		builtParent = a != null && a.parent() != null;
		builtParentEnded = builtParent && a.parent().ended;
		builtBranchGeneration = a == null ? -1 : a.branchGeneration();
		builtPicked = saved().pickedId();
		builtPickedChanged = saved().pickedChanged();

		statusY = top();
		builtDetailCount = view.details().size();
		statusH = 6 + 2 * LINE + (detailsOpen ? builtDetailCount * LINE + 2 : 0) + 2;
		// 状態の板の 2 行目の右端: 詳細を開く・閉じる
		detailsButton = addRenderableWidget(RButton.of(font, 0, statusY + 4 + LINE - 3, detailsOpen ? Tr.t("閉じる ▲") : Tr.t("詳細 ▼"), RButton.Variant.GHOST,
				b -> toggleDetails()).tooltip(detailsOpen ? null : Tr.t("範囲の座標・ホスト・同期の設定・共有リンク・サーバ・最近の出来事を出す")));
		detailsButton.setX(MARGIN + contentWidth() - 5 - detailsButton.getWidth());
		int y = statusY + statusH + 5;
		parentOpenButton = null;
		libraryButton = null;
		postButton = null;
		nameField = null;
		startButton = null;
		newButton = null;

		if (a != null) {
			y = initLiveRows(a, y);
		} else {
			y = initStartRow(y);
		}

		y = initBranchPrepRow(prep, a, y);

		// 一覧: 分岐した子 (開催中なら) と保存済みの範囲。見出しの右に検索欄
		listLabelY = y;
		searchField = addRenderableWidget(new RTextField(font, MARGIN + contentWidth() - 130, y - 1, 130, Component.literal(Tr.t("検索"))));
		searchField.setMaxLength(64);
		searchField.setValue(searchDraft);
		searchField.hint(Tr.t("名前で探す"));
		searchField.setResponder(text -> {
			if (!text.equals(searchDraft)) {
				searchDraft = text;

				if (list != null) {
					list.setCards(cards(rows()));
				}
			}
		});
		y += RButton.H + 3;
		int listBottom = this.height - 6;
		list = addRenderableWidget(new CardList<>(minecraft, MARGIN, y, contentWidth(), Math.max(30, listBottom - y)));
		list.setCards(cards(rows()));
	}

	/** 開催中: 同期・終了・共有リンクをコピー・ブラウザで開く (・分岐元を開く)、その下に一覧に出す・複製を許可・保管庫へ・redtact に投稿。 */
	private int initLiveRows(SessionManager.Active a, int y) {
		int x = MARGIN;

		RButton push = addRenderableWidget(RButton.of(font, x, y, Tr.t("今すぐ同期"), RButton.Variant.PRIMARY, b -> sessions().push())
				.tooltip(Tr.t("範囲の回路をいま送る (自動同期を待たない)")));
		x += push.getWidth() + 4;
		RButton close = addRenderableWidget(RButton.icon(x, y, Icon.POWER, Tr.t("終了"), RButton.Variant.DANGER, b -> sessions().close())
				.tooltip(Tr.t("セッションを閉じる (閲覧者の画面も終わる)")));
		x += close.getWidth() + 4;
		RButton copy = addRenderableWidget(RButton.icon(x, y, Icon.COPY, Tr.t("リンクをコピー"), RButton.Variant.SECONDARY, b -> sessions().copyShareLink())
				.tooltip(Tr.f("共有リンクをコピーする: {0}", a.shareUrl)));
		x += copy.getWidth() + 4;
		RButton open = addRenderableWidget(RButton.icon(x, y, Icon.EXTERNAL, Tr.t("ブラウザで開く"), RButton.Variant.SECONDARY, b -> {
			SessionManager.Active act = sessions().active();

			if (act != null) {
				ConfirmLinkScreen.confirmLinkNow(this, URI.create(act.shareUrl));
			}
		}).tooltip(a.shareUrl));
		x += open.getWidth() + 4;

		// 分岐して開いたセッションなら、分岐元をブラウザで開く
		if (a.parent() != null && a.parent().shareUrl != null) {
			String url = a.parent().shareUrl;
			parentOpenButton = addRenderableWidget(RButton.icon(x, y, Icon.BRANCH, Tr.t("分岐元を開く"), RButton.Variant.SECONDARY,
					b -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(url))).tooltip(Tr.f("分岐元「{0}」をブラウザで開く", a.parent().title)));
		}

		// 一覧に出す・複製を許可 (開催中はその場で切り替える)
		y += RButton.H + 4;
		x = MARGIN;
		listButton = addRenderableWidget(RToggle.of(font, x, y, Tr.t("一覧に出す"), builtListed, b -> toggleListed())
				.tooltip(Tr.t("開催中の一覧 (Web のホームとメニューの live の「開催中」) に出す")));
		listButton.pending(builtListedPending);
		x += listButton.getWidth() + 4;
		copyButton = addRenderableWidget(RToggle.of(font, x, y, Tr.t("複製を許可"), builtAllowCopy, b -> toggleAllowCopy())
				.tooltip(Tr.t("閲覧者が回路を .litematic で取ったり、Litematica に読み込んだり、分岐したりできるようにする")));
		copyButton.pending(builtAllowCopyPending);
		// 開催中の回路を保管庫へ・redtact に投稿 (#73)
		x += copyButton.getWidth() + 8;
		libraryButton = LiveLibraryButtons.library(font, x, y);
		postButton = LiveLibraryButtons.post(font, x, y);

		if (x + libraryButton.getWidth() + 4 + postButton.getWidth() > MARGIN + contentWidth()) {
			// 狭いときは次の段へ
			y += RButton.H + 4;
			x = MARGIN;
		}

		libraryButton.setPosition(x, y);
		addRenderableWidget(libraryButton);
		x += libraryButton.getWidth() + 4;
		postButton.setPosition(x, y);
		addRenderableWidget(postButton);
		return y + RButton.H + 5;
	}

	/**
	 * 開始前: 名前・開始 (保存済みの範囲を選んでいれば、開始・新規作成)・一覧に出す・複製を許可。
	 */
	private int initStartRow(int y) {
		SavedRanges saved = saved();
		CircuitRecord picked = saved.picked();
		int x;

		if (picked != null) {
			x = MARGIN;
			boolean changed = saved.pickedChanged();
			startButton = addRenderableWidget(RButton.of(font, x, y, Tr.t("開始"), RButton.Variant.PRIMARY, b -> start())
					.tooltip(changed ? Tr.f("「{0}」を再開する (角を動かしたので、保存済みの範囲をいまの選択で更新する)", picked.name)
							: Tr.f("「{0}」を再開する (保存済みの範囲で)", picked.name))
					.disabledReason(builtBusy ? Tr.t("処理中です") : null));
			x += startButton.getWidth() + 4;
			newButton = addRenderableWidget(RButton.icon(x, y, Icon.PLUS, Tr.t("新規作成"), RButton.Variant.SECONDARY, b -> newRange())
					.tooltip(Tr.t("選んだ範囲と選択を外して、新しい名前と範囲で始める")));
			x += newButton.getWidth() + 8;
		} else {
			x = MARGIN + font.width(Tr.t("名前")) + 5;
			nameField = addRenderableWidget(new RTextField(font, x, y, 110, Component.literal(Tr.t("名前"))));
			nameField.setMaxLength(64);
			nameField.setValue(nameDraft);
			nameField.hint(Tr.t("回路の名前"));
			x += nameField.frameW() + 4;
			startButton = addRenderableWidget(RButton.of(font, x, y, Tr.t("開始"), RButton.Variant.PRIMARY, b -> start())
					.tooltip(Tr.t("この名前でセッションを開く (同じ名前の回路は再開)")).disabledReason(builtBusy ? Tr.t("処理中です") : null));
			x += startButton.getWidth() + 8;
		}

		listButton = addRenderableWidget(RToggle.of(font, x, y, Tr.t("一覧に出す"), listDraft, b -> toggleListed())
				.tooltip(Tr.t("開催中の一覧 (Web のホームとメニューの live の「開催中」) に出す。既定は出さない (開始するたびに OFF から)")));
		x += listButton.getWidth() + 4;
		copyButton = addRenderableWidget(RToggle.of(font, x, y, Tr.t("複製を許可"), copyDraft(), b -> toggleAllowCopy())
				.tooltip(Tr.t("閲覧者が回路を .litematic で取ったり、Litematica に読み込んだり、分岐したりできるようにする。既定は設定の「複製を許可 (新しいセッションの既定)」")));
		return y + RButton.H + 5;
	}

	/** 分岐の準備: 名前・この配置で分岐を開始・やめる (準備しているときだけ)。 */
	private int initBranchPrepRow(BranchManager.@Nullable Prep prep, SessionManager.@Nullable Active a, int y) {
		int x;
		branchNameField = null;
		branchStartButton = null;
		branchCancelButton = null;

		if (prep != null) {
			x = MARGIN + font.width(Tr.t("分岐の名前")) + 5;
			branchNameField = addRenderableWidget(new RTextField(font, x, y, 130, Component.literal(Tr.t("分岐の名前"))));
			branchNameField.setMaxLength(BranchText.MAX_NAME);
			branchNameField.setValue(branchNameDraft != null ? branchNameDraft : prep.defaultName());
			x += branchNameField.frameW() + 4;
			branchStartButton = addRenderableWidget(RButton.of(font, x, y, Tr.t("この配置で分岐を開始"), RButton.Variant.PRIMARY, b -> startBranch())
					.tooltip(Tr.f("Litematica の配置の範囲 (回転していれば囲む箱) で、分岐元「{0}」v{1} から分岐したセッションを開く。先に Litematica で貼り付けておく",
							prep.title(), prep.version()))
					.disabledReason(a != null ? Tr.t("開催中のセッションを終了してから分岐できます") : builtBusy ? Tr.t("処理中です") : null));
			x += branchStartButton.getWidth() + 4;
			branchCancelButton = addRenderableWidget(RButton.icon(x, y, Icon.CROSS, Tr.t("やめる"), RButton.Variant.SECONDARY, b -> {
				branches().cancel();
				rebuildWidgets();
			}).tooltip(Tr.t("分岐の準備をやめる (Litematica の配置は残す)")));
			y += RButton.H + 5;
		}

		return y;
	}

	/** 「詳細 ▼」「閉じる ▲」と同じ (テストからも呼ぶ)。 */
	public void toggleDetails() {
		detailsOpen = !detailsOpen;
		rebuildWidgets();
	}

	public static boolean detailsOpen() {
		return detailsOpen;
	}

	private static SavedRanges saved() {
		return RedtactLive.get().savedRanges();
	}

	/** いまのワールドの識別子 (ワールドに居なければ null)。 */
	private static @Nullable String world() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : SelectionTool.worldKey(mc);
	}

	/** 一覧の行: 分岐した子 (開催中なら) と、保存済みの範囲 (検索で絞る。このワールドの範囲を先に、ほかのワールドは後ろ)。 */
	static List<SessionRow> rows() {
		SessionManager s = sessions();
		SessionManager.Active a = s.active();
		List<SessionRow> rows = new ArrayList<>();

		if (a != null && !a.branches().isEmpty()) {
			for (Api.Branch b : a.branches()) {
				rows.add(new BranchRow(b));
			}

			rows.add(new HeaderRow(Tr.t("保存済みの範囲 (再開・コメントの閲覧)")));
		}

		NameQuery q = NameQuery.of(searchDraft);
		String world = world();
		String picked = saved().pickedId();
		List<CircuitRecord> others = new ArrayList<>();

		for (CircuitRecord r : saved().all()) {
			if (!q.matches(r.name)) {
				continue;
			}

			if (world != null && !SavedRanges.inWorld(r, world)) {
				others.add(r);
				continue;
			}

			rows.add(circuitRow(s, a, r, r.circuitId.equals(picked), null));
		}

		if (!others.isEmpty()) {
			rows.add(new HeaderRow(Tr.t("ほかのワールドの回路 (選べない・コメントは見られる)")));

			for (CircuitRecord r : others) {
				rows.add(circuitRow(s, a, r, false, r.world));
			}
		}

		return rows;
	}

	private static CircuitRow circuitRow(SessionManager s, SessionManager.@Nullable Active a, CircuitRecord r, boolean picked, @Nullable String otherWorld) {
		List<StoredComment> comments = s.comments(r.circuitId);
		int snapshots;

		try {
			snapshots = s.store().countSnapshots(r.circuitId);
		} catch (java.io.IOException e) {
			snapshots = 0;
		}

		return new CircuitRow(r, snapshots, comments.size(), SessionManager.unreadCount(r, comments), a != null && a.circuitId.equals(r.circuitId),
				picked, otherWorld);
	}

	/** このワールドの保存済みの範囲の数 (検索の前)。 */
	private static int countInWorld() {
		String world = world();
		int n = 0;

		for (CircuitRecord r : saved().all()) {
			if (world == null || SavedRanges.inWorld(r, world)) {
				n++;
			}
		}

		return n;
	}

	/**
	 * 保存済みの範囲をゴミ箱へ移したあと (#77): 一覧だけ取り直す (送った位置のまま。続けて消せる)。
	 * 選んでいた範囲なら選択が外れるので、開始の行は tick で作り直す。
	 */
	private void savedRangesChanged() {
		if (minecraft != null && minecraft.gui.screen() == this && list != null) {
			list.setCards(cards(rows()));
		}
	}

	private List<MenuCard> cards(List<SessionRow> rows) {
		List<MenuCard> out = new ArrayList<>(rows.size());

		for (SessionRow r : rows) {
			out.add(switch (r) {
				case BranchRow b -> new BranchCard(b);
				case CircuitRow c -> new CircuitCard(c, SessionScreen::pickRow, this::savedRangesChanged);
				case HeaderRow h -> new HeaderCard(h.text());
			});
		}

		return out;
	}

	/** カードを押して保存済みの範囲を選ぶ。一覧がクリックを配っている最中に作り直さないよう、次のフレームで選ぶ (tick が作り直す)。 */
	private static void pickRow(CircuitRow row) {
		Minecraft.getInstance().execute(() -> RedtactLive.get().pickSavedRange(row.record()));
	}

	/** 「新規作成」と同じ (テストからも呼ぶ)。選んだ範囲と選択を外して、名前の欄へ。 */
	public void newRange() {
		RedtactLive.get().clearSelection();
		nameDraft = "";
		rebuildWidgets();

		if (nameField != null) {
			setFocused(nameField);
		}

		Messages.overlay(Tr.t("新しい範囲: 角を 2 つ決めて ([ ] キー・羽根の左右クリック・「範囲」タブ) 名前を入れ、「開始」"));
	}

	/** 「この配置で分岐を開始」と同じ (テストからも呼ぶ)。 */
	public void startBranch() {
		String name = branchNameField == null ? "" : branchNameField.getValue();
		branches().start(name, SessionScreen::takeListDraft, SessionScreen::takeCopyDraft);
	}

	/** 「開始」と同じ (テストからも呼ぶ)。保存済みの範囲を選んでいればその回路を再開する。 */
	public void start() {
		CircuitRecord picked = saved().picked();

		if (nameField == null && picked != null) {
			// 選択 (角を動かしていれば動かした範囲) で開く。doOpen が保存済みの範囲を選択で更新する
			sessions().open(picked.name, takeListDraft(), takeCopyDraft());
			return;
		}

		String name = nameField == null ? "" : nameField.getValue();
		nameDraft = "";

		// 作り直しで欄の値が nameDraft に戻らないよう、欄も空にする
		if (nameField != null) {
			nameField.setValue("");
		}

		sessions().open(name, takeListDraft(), takeCopyDraft());
	}

	/** 「一覧に出す」と同じ (テストからも呼ぶ)。 */
	public void toggleListed() {
		SessionManager.Active a = sessions().active();

		if (a == null) {
			listDraft = !listDraft;
		} else if (!a.listedPending()) {
			sessions().setListed(!a.listed());
		}

		rebuildWidgets();
	}

	/** 「複製を許可」と同じ (テストからも呼ぶ)。 */
	public void toggleAllowCopy() {
		SessionManager.Active a = sessions().active();

		if (a == null) {
			copyDraft = !copyDraft();
		} else if (!a.allowCopyPending()) {
			sessions().setAllowCopy(!a.allowCopy());
		}

		rebuildWidgets();
	}

	@Override
	public void tick() {
		super.tick();
		SessionManager s = sessions();
		SessionManager.Active a = s.active();
		String now = a == null ? null : a.sessionId;
		boolean parent = a != null && a.parent() != null;

		// セッションが始まった・終わった・処理が終わった・分岐の準備や分岐元が変わったら、ボタンと一覧を作り直す
		if (!Objects.equals(now, builtFor) || s.isBusy() != builtBusy || branches().prep() != builtPrep
				|| !Objects.equals(saved().pickedId(), builtPicked) || saved().pickedChanged() != builtPickedChanged
				|| parent != builtParent || (parent && a.parent().ended != builtParentEnded)
				|| (a != null && (a.listed() != builtListed || a.listedPending() != builtListedPending
						|| a.allowCopy() != builtAllowCopy || a.allowCopyPending() != builtAllowCopyPending))) {
			rebuildWidgets();
			return;
		}

		if (a != null && a.branchGeneration() != builtBranchGeneration && list != null) {
			// 分岐した子が増えた・減った: 一覧だけ取り直す
			builtBranchGeneration = a.branchGeneration();
			list.setCards(cards(rows()));
		}

		view = view(a, s); // 閲覧者・版・未読・直前の push は毎 tick 取り直す
		LiveLibraryButtons.refresh(libraryButton); // 最初の同期が済んだら押せる
		LiveLibraryButtons.refresh(postButton);

		// 詳細を開いていて行の数が変わったら (最近の出来事が出たなど)、板の高さを合わせる
		if (detailsOpen && view.details().size() != builtDetailCount) {
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int w = contentWidth();
		panel(g, MARGIN, statusY, w, statusH);
		drawStatus(g, MARGIN + 6, statusY + 5, w - 12, mouseX, mouseY);

		// 一覧の見出し (右は検索欄)
		String label = view.branchCount() > 0
				? Tr.n("このセッションから分岐した子 {0} 件 (「重ねて読み込む」で自分の範囲の角に重ねる)", view.branchCount())
				: Tr.n("保存済みの範囲 (このワールド {0} 件)", countInWorld());
		int labelMax = (searchField == null ? MARGIN + w : searchField.frameX() - 6) - MARGIN - 12;
		Draw.sectionLabel(g, font, MARGIN, listLabelY, label, labelMax, mouseX, mouseY);

		if (nameField != null) {
			g.text(font, Tr.t("名前"), MARGIN, nameField.frameY() + 4, Style.MUT, false);
		}

		if (branchNameField != null) {
			g.text(font, Tr.t("分岐の名前"), MARGIN, branchNameField.frameY() + 4, Style.CYAN, false);
		}

		if (list != null && list.children().isEmpty()) {
			String empty = saved().all().isEmpty() ? Tr.t("保存済みの範囲はまだありません (範囲を指定して名前を入れ、「開始」か「範囲」タブの「範囲を保存」)")
					: Tr.f("「{0}」に当たる範囲はありません", searchDraft.trim());
			Draw.clippedText(g, font, empty, MARGIN + 4, list.getY() + 6, w - 8, Style.MUT, mouseX, mouseY);
		}

		super.extractRenderState(g, mouseX, mouseY, a);
	}

	/** 状態の板の中身: 1 行目は題とバッジ、2 行目は要点 (右端に「詳細」)、開いていれば詳細の行。 */
	private void drawStatus(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		int right = x + w;
		Draw.lamp(g, x, y + 1, 8, view.active() && view.online());
		int tx = x + 12;
		String title = view.title();
		int badgesW = 0;

		for (Badge b : view.badges()) {
			badgesW += (b.lamp() == null ? Draw.badgeWidth(font, b.text()) : Draw.lampBadgeWidth(font, b.text())) + 3;
		}

		int titleMax = Math.max(60, right - tx - badgesW - 6);
		int bx = tx + Draw.clippedText(g, font, title, tx, y + 1, titleMax, view.active() ? Style.TXT : Style.MUT, mouseX, mouseY) + 6;

		for (Badge b : view.badges()) {
			int bw = b.lamp() == null ? Draw.badgeWidth(font, b.text()) : Draw.lampBadgeWidth(font, b.text());

			if (bx + bw > right) {
				break;
			}

			bw = b.lamp() == null ? Draw.badge(g, font, bx, y - 1, b.text(), b.tone()) : Draw.lampBadge(g, font, bx, y - 1, b.text(), b.tone(), b.lamp());
			bx += bw + 3;
		}

		// 要点 (「詳細」のボタンにかからないところまで)
		int ly = y + LINE;
		int summaryMax = (detailsButton == null ? right : detailsButton.getX() - 4) - tx;
		Draw.clippedText(g, font, view.summary().text(), tx, ly + 1, summaryMax, view.summary().color(), mouseX, mouseY);

		if (!detailsOpen) {
			return;
		}

		ly += LINE + 2;
		g.fill(tx, ly - 2, right, ly - 1, Style.PANEL_L);

		for (Row r : view.details()) {
			Draw.clippedText(g, font, r.text(), tx, ly + 1, right - tx, r.color(), mouseX, mouseY);
			ly += LINE;
		}
	}

	// ---- 見せる内容 (描くのとテストで同じものを使う)

	/** バッジ。lamp が null でなければランプ付き (点いているか)。 */
	public record Badge(String text, Draw.Tone tone, @Nullable Boolean lamp) {
	}

	/** 板の 1 行 (色付き)。 */
	public record Row(String text, int color) {
	}

	/**
	 * 状態の板の内容: 題とバッジ、要点の 1 行、「詳細」で開く行。
	 */
	public record View(boolean active, boolean online, String title, List<Badge> badges, Row summary, List<Row> details, int branchCount,
			EventLog.@Nullable Entry lastEvent) {
		/** 文字にした全部の行 (0 番目は題とバッジを「・」でつないだもの、1 番目は要点、続けて詳細)。 */
		public List<Row> rows() {
			StringBuilder head = new StringBuilder(title);

			for (Badge b : badges) {
				head.append(Tr.raw("・")).append(b.text());
			}

			List<Row> out = new ArrayList<>(details.size() + 2);
			out.add(new Row(head.toString(), Style.TXT));
			out.add(summary);
			out.addAll(details);
			return out;
		}
	}

	static View view(SessionManager.@Nullable Active a, SessionManager s) {
		List<Badge> badges = new ArrayList<>();
		List<Row> details = new ArrayList<>();
		String title;
		BranchManager.Prep prep = RedtactLive.get().branches().prep();
		boolean auto = Configs.Generic.AUTO_SYNC.getBooleanValue();
		String onOff = auto ? "on" : "off";
		boolean dirty = RedtactLive.get().autoSync().debouncer().isDirty();
		StringBuilder summary = new StringBuilder();

		// 一覧に出す・複製を許可は下の切替ボタン (ランプ) で見えるので、バッジは目立たせたいとき (公開中・複製不可) だけ出す
		int summaryColor = Style.MUT;

		if (a == null) {
			title = Tr.t("セッションなし");
			Box box = RedtactLive.get().selection().box();
			SavedRanges saved = RedtactLive.get().savedRanges();
			CircuitRecord picked = saved.picked();

			if (picked != null) {
				Box pickedBox = picked.box();
				String pickedSize = pickedBox == null ? "-" : pickedBox.sizeText();

				if (saved.pickedChanged()) {
					summary.append(Tr.f("範囲 「{0}」 {1} → {2} (角を動かした・開始で更新)", picked.name, pickedSize, box == null ? Tr.t("未指定") : box.sizeText()));
					summaryColor = Style.LAMP;
				} else {
					summary.append(Tr.f("範囲 「{0}」 {1} (保存済み)", picked.name, pickedSize));
				}
			} else {
				summary.append(box == null ? Tr.t("範囲 未指定 (「範囲」タブか [ ] キー・羽根で指定)") : Tr.f("範囲 {0} (新しい範囲)", box.sizeText()));
			}
		} else {
			title = Tr.f("開催中「{0}」", a.circuitName);
			badges.add(new Badge(a.online() ? Tr.t("接続中") : Tr.t("再接続待ち"), a.online() ? Draw.Tone.OK : Draw.Tone.WARN, a.online()));
			badges.add(new Badge(Tr.n("閲覧者 {0}", a.viewers()), Draw.Tone.PLAIN, null));
			badges.add(new Badge("v" + a.lastVersion(), Draw.Tone.PLAIN, null));

			if (!a.branches().isEmpty()) {
				badges.add(new Badge(Tr.n("分岐 {0}", a.branches().size()), Draw.Tone.CYAN, null));
			}

			int unread = s.unread();

			if (unread > 0) {
				badges.add(new Badge(Tr.n("未読 {0}", unread), Draw.Tone.WARN, null));
			}

			if (a.listed()) {
				badges.add(new Badge(Tr.t("一覧に公開中"), Draw.Tone.RED, null));
			}

			if (!a.allowCopy()) {
				badges.add(new Badge(Tr.t("複製: 不可"), Draw.Tone.RED, null));
			}

			if (a.parent() != null && a.parent().ended) {
				badges.add(new Badge(Tr.t("分岐元が終了"), Draw.Tone.RED, null));
			}

			String size = a.region().sizeText();
			summary.append(dirty ? Tr.f("{0}・自動同期 {1}・未送信あり", size, onOff) : Tr.f("{0}・自動同期 {1}・未送信なし", size, onOff));

			if (a.parent() != null) {
				summary.append(Tr.f("・分岐元「{0}」v{1}", a.parent().title, a.parent().version));
			}
		}

		if (prep != null) {
			summary.append(Tr.f("・分岐の準備中「{0}」v{1}", prep.title(), prep.version()));
		}

		// 詳細 (「詳細 ▼」で開く)
		if (a == null) {
			Box box = RedtactLive.get().selection().box();
			String dim = RedtactLive.get().selection().dimension();
			details.add(new Row(box == null ? Tr.t("範囲: 未指定 (「範囲」タブか [ ] キー・羽根で指定)") : Tr.f("範囲: {0} {1} @ {2}", box, box.sizeText(), dim), Style.MUT));
		} else {
			details.add(new Row(Tr.f("範囲: {0} {1} @ {2}", a.region(), a.region().sizeText(), a.dimension()), Style.MUT));

			if (a.parent() != null) {
				details.add(new Row(BranchText.parentText(a.parent()), a.parent().ended ? Style.RED_TX : Style.CYAN));
			}
		}

		if (prep != null) {
			details.add(new Row(prepText(prep), Style.CYAN));
		}

		details.add(new Row(hostLine(a), Style.MUT));
		SessionManager.PushResult last = s.lastPush();
		String lastText = "-";

		if (last != null) {
			String kind = switch (last.kind()) {
				case DIFF -> Tr.n("差分 {0}", last.changes());
				case FULL -> Tr.t("全体");
				case UNCHANGED -> Tr.t("変化なし");
			};
			lastText = last.trigger() == SessionManager.Trigger.AUTO ? Tr.f("{0} (自動)", kind) : Tr.f("{0} (手動)", kind);
		}

		double quiet = Configs.Generic.AUTO_SYNC_QUIET_MS.getIntegerValue() / 1000.0;
		double maxDelay = Configs.Generic.AUTO_SYNC_MAX_DELAY_MS.getIntegerValue() / 1000.0;
		details.add(new Row(dirty ? Tr.f("自動同期 {0} (静止 {1} 秒・最長 {2} 秒)・未送信 あり・直前の push: {3}", onOff, quiet, maxDelay, lastText)
				: Tr.f("自動同期 {0} (静止 {1} 秒・最長 {2} 秒)・未送信 なし・直前の push: {3}", onOff, quiet, maxDelay, lastText), Style.MUT));

		if (a != null) {
			details.add(new Row(Tr.f("共有リンク: {0}", a.shareUrl), Style.MUT));
		}

		details.add(new Row(Tr.f("サーバ: {0}", Configs.Generic.SERVER_URL.getStringValue()), Style.MUT));
		List<EventLog.Entry> latest = EventLog.latest(1);
		EventLog.Entry event = latest.isEmpty() ? null : latest.getFirst();

		// 最近の出来事 (詳細のいちばん下)
		if (event != null) {
			details.add(new Row(Tr.f("最近: {0}  {1}", Times.local(Times.format(event.time())), event.text()),
					event.level() == EventLog.Level.ERROR ? Style.RED_TX : Style.COP_TX));
		}

		return new View(a != null, a != null && a.online(), title, badges, new Row(summary.toString(), summaryColor), details,
				a == null ? 0 : a.branches().size(), event);
	}

	/** 状態の行 (テストから使う。描くものと同じ内容を文字で)。 */
	public static List<String> statusLines(SessionManager.@Nullable Active a, SessionManager s) {
		View v = view(a, s);
		List<String> out = new ArrayList<>();

		for (Row r : v.rows()) {
			out.add(r.text());
		}

		return out;
	}

	/**
	 * ホスト名の行。開く前は見込み (アクセストークンの形などから)、開催中はサーバの hostVerified に従う。
	 */
	public static String hostLine(SessionManager.@Nullable Active a) {
		if (a != null) {
			return a.hostVerified() ? Tr.f("ホスト: {0} (✔ マイクラで確認済み)", a.hostName()) : Tr.f("ホスト: {0} (確認なし)", a.hostName());
		}

		return MinecraftJoin.looksVerifiable()
				? Tr.f("ホスト名: {0} (マイクラで確認します)", MinecraftJoin.playerName())
				: Tr.f("ホスト名: {0} (確認なし・開発用)", Configs.Generic.USER.getStringValue());
	}

	/** 分岐の準備の行: 分岐元・版と、いまの配置の原点・大きさ (動かせば追従する)。 */
	public static String prepText(BranchManager.Prep prep) {
		LitematicaSupport.PlacementInfo info = LitematicaSupport.placementInfo(prep.sessionId(), null);

		if (info == null) {
			return Tr.f("分岐の準備: 「{0}」v{1}・配置が見つかりません (もう一度「分岐する」を)", prep.title(), prep.version());
		}

		Box b = info.box();
		String transform = info.transform().isEmpty() ? "" : " (" + info.transform() + ")";
		return Tr.f("分岐の準備: 「{0}」v{1}・配置の原点 {2}・大きさ {3}{4}・貼り付けてから「この配置で分岐を開始」", prep.title(), prep.version(),
				info.origin().toShortString(), b.sizeText(), transform);
	}

	// ---- テスト用

	public @Nullable RTextField nameField() {
		return nameField;
	}

	public @Nullable RButton startButton() {
		return startButton;
	}

	public @Nullable RButton newButton() {
		return newButton;
	}

	public @Nullable RTextField searchField() {
		return searchField;
	}

	/** その回路のカード (一覧に無ければ null)。 */
	public @Nullable CircuitCard circuitCard(String circuitId) {
		for (MenuCard c : cards()) {
			if (c instanceof CircuitCard cc && cc.row().record().circuitId.equals(circuitId)) {
				return cc;
			}
		}

		return null;
	}

	public @Nullable RToggle listButton() {
		return listButton;
	}

	public @Nullable RToggle copyButton() {
		return copyButton;
	}

	/** 開催中の「保管庫へ」(#73)。 */
	public @Nullable RButton libraryButton() {
		return libraryButton;
	}

	/** 開催中の「redtact に投稿」(#73)。 */
	public @Nullable RButton postButton() {
		return postButton;
	}

	public @Nullable RTextField branchNameField() {
		return branchNameField;
	}

	public @Nullable RButton branchStartButton() {
		return branchStartButton;
	}

	public @Nullable RButton branchCancelButton() {
		return branchCancelButton;
	}

	public @Nullable RButton parentOpenButton() {
		return parentOpenButton;
	}

	public @Nullable RButton detailsButton() {
		return detailsButton;
	}

	public List<MenuCard> cards() {
		return list == null ? List.of() : list.cards();
	}

	/** その子のカード (一覧に無ければ null)。 */
	public @Nullable BranchCard branchCard(String sessionId) {
		for (MenuCard c : cards()) {
			if (c instanceof BranchCard b && b.row().branch().sessionId.equals(sessionId)) {
				return b;
			}
		}

		return null;
	}
}
