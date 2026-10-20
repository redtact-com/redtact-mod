package com.redtact.live.gui;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.branch.BranchManager;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.comment.CommentRules;
import com.redtact.live.comment.CommentWatch;
import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.copy.CopyLinks;
import com.redtact.live.gui.cards.CommentCard;
import com.redtact.live.gui.cards.LiveCard;
import com.redtact.live.gui.cards.LiveGridRow;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.CommentComposer;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.HostFace;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.LiveGrid;
import com.redtact.live.gui.kit.PreviewView;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.RToggle;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.gui.rows.CommentRow;
import com.redtact.live.gui.rows.LiveRow;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.live.LiveDirectory;
import com.redtact.live.live.LiveQuery;
import com.redtact.live.live.LiveText;
import com.redtact.live.net.Api;
import com.redtact.live.preview.PreviewCircuit;
import com.redtact.live.preview.PreviewStore;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.StoredComment;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * メニューの「開催中」: 一覧に出ているセッション (GET /api/sessions)。開いたときと 15 秒ごとに取り直す。
 * 一覧の上の段の検索欄・並べ替え・「フォロー中」で、名前・題で探し、新しい順・視聴中・閲覧数で並べ、redtact でフォローしている人に絞る
 * (#90。探す・並べ替え・絞り込みは中継サーバがする。問い合わせはゲームを閉じるまで覚える)。
 * redtact の回路一覧と同じく、カードのグリッド (サムネ・題・ホストの顔) を並べ、押すと詳細に切り替わる (#56。#34 の左右の配置から変えた)。
 * 詳細は左に立体のプレビュー (止まった絵で、右上のボタンで 15° ずつ回す。#93) と「読み込む」「分岐する」「ブラウザで開く」、
 * 右にコメント (上に入力欄・下に新しい順。#74)。
 * コメントとセッションの様子は {@link CommentWatch} が 5 秒ごとに取る。
 * 自分のセッションの詳細には「保管庫へ」「redtact に投稿」も出す (#73。他人のセッションには出さない)。
 * 一覧の上の欄に共有リンクかセッション ID を貼ると、一覧に出ていないセッションも読み込め、「開く」で詳細も開ける (#74)。
 */
public final class LiveListScreen extends MenuScreen {
	public static final long REFRESH_MS = 15_000;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	/** リンク欄の入力 (タブを作り直しても残す)。 */
	private static String linkDraft = "";
	/** 詳細で開いているセッション (null なら一覧。タブを作り直しても残す)。 */
	private static @Nullable String selectedId;
	/** 詳細をリンク欄の「開く」で開いたか (一覧のカードからなら false)。 */
	private static boolean openedByLink;
	/** 詳細を拡大しているか (#77。ヘッダの ▲ の左のトグル。プレビューを全幅で上に大きく・コメントをその下に。タブを作り直しても残す)。 */
	private static boolean expanded;
	/** 拡大のトグルの名前 (ツールチップの先頭・テスト)。 */
	public static final String EXPAND_TOGGLE = Tr.ja("拡大");
	/** 検索欄の打ち終わりを待つ時間 (#90。最後のキーからこれだけたったら探す。Enter ならすぐ)。 */
	public static final long SEARCH_DELAY_MS = 400;
	/**
	 * 一覧の問い合わせ (#90。探す字・並べ替え・フォロー中)。タブを作り直しても残し、ゲームを閉じるまで覚える (設定ファイルには残さない)。
	 * 15 秒ごとの取り直しもこの問い合わせで取る。
	 */
	private static LiveQuery query = LiveQuery.DEFAULT;
	/** 検索欄の入力 (そのまま。問い合わせの探す字は打ち終わってから変える)。 */
	private static String searchDraft = "";
	/** 検索欄を最後に書き換えた時刻。 */
	private static long searchEditedAt;

	private List<LiveRow> rows = List.of();
	private long shownGeneration = -1;
	/** いまの一覧を取った問い合わせ (変わったら一覧を上から見せる)。 */
	private LiveQuery shownQuery = LiveQuery.DEFAULT;
	private long shownWatchGeneration = -1;
	private long lastRequest;
	// 一覧
	private @Nullable RTextField searchField;
	private @Nullable RButton sortButton;
	private @Nullable RToggle followingToggle;
	/** 一覧が空でフォロー中の連携が無いとき (NOT_LINKED) だけ、空の板に出す「redtact の設定を開く」(#90)。 */
	private @Nullable RButton settingsButton;
	/** 一覧の上の段の件数と時刻の字の左端 (フォロー中のトグルの右)。 */
	private int infoX;
	/** 一覧が空のときの板の字 (折り返したもの) と板の高さ。 */
	private List<FormattedCharSequence> emptyLines = List.of();
	private int emptyH;
	private @Nullable RTextField linkField;
	private @Nullable RButton loadButton;
	private @Nullable RButton branchButton;
	private @Nullable RButton openButton;
	private @Nullable CardList<MenuCard> grid;
	private int linkY;
	private int bodyY;
	// 詳細
	private @Nullable RButton backButton;
	private @Nullable PreviewView preview;
	private @Nullable RButton rowLoad;
	private @Nullable RButton rowBranch;
	private @Nullable RButton rowOpen;
	private @Nullable RButton rowLibrary;
	private @Nullable RButton rowPost;
	private @Nullable CommentComposer composer;
	private @Nullable CardList<MenuCard> commentList;
	private List<CommentRow> commentRows = List.of();
	/** いまの一覧に描いたコメント (同じなら描き直さない。CommentWatch は増えなければ同じリストを返す)。 */
	private @Nullable List<Api.Comment> shownComments;
	private int paneY;
	/** 詳細の板 (プレビュー・補足・操作) の高さ。左右に分けているときは下まで、拡大しているときは上の部分。 */
	private int panelH;
	/** コメントの欄の上端 (左右に分けているときは板と同じ・拡大しているときは板の下)。 */
	private int commentsTop;
	private int infoY;
	/** 補足の行の右端。 */
	private int infoRight;
	/** 拡大した詳細 (#77) の縮小のボタン (プレビューの下の操作の行の右端)。 */
	private @Nullable RButton shrinkButton;
	/** コメントの欄の見出し (「コメント N 件」と状態。拡大しているときは操作の行の「←」と操作のボタンの間で、題が先) の左端・上端・右端。 */
	private int headX;
	private int headY;
	private int headRight;
	/** コメントの入力欄の上端。 */
	private int composerY;
	/** 詳細の左の板 (プレビューと操作) の幅と、右のコメントの欄の左端と幅。 */
	private int leftW;
	private int commentX;
	private int commentW;

	public LiveListScreen() {
		super(MenuTab.LIVE_LIST);
	}

	/** 中身の上端 (メニューの帯の下。live の子タブの段を隠していればその分だけ上。#51)。 */
	private int contentTop() {
		return top();
	}

	private static LiveDirectory directory() {
		return RedtactLive.get().liveDirectory();
	}

	private static CommentWatch watch() {
		return RedtactLive.get().commentWatch();
	}

	@Override
	protected void initContent() {
		if (linkField != null) {
			linkDraft = linkField.getValue();
		}

		rows = buildRows(directory().state());
		shownGeneration = directory().state().generation();
		shownQuery = directory().state().query();
		searchField = null;
		sortButton = null;
		followingToggle = null;
		settingsButton = null;
		linkField = null;
		loadButton = null;
		branchButton = null;
		openButton = null;
		grid = null;
		backButton = null;
		preview = null;
		rowLoad = null;
		rowBranch = null;
		rowOpen = null;
		rowLibrary = null;
		rowPost = null;
		composer = null;
		commentList = null;
		shownComments = null;
		shrinkButton = null;

		if (selectedId != null && watchEnded(selectedId)) {
			selectedId = null; // 詳細のセッションが終わっていたら一覧に戻す
		}

		if (selectedId == null) {
			watch().stop();
			shownWatchGeneration = watch().state().generation();
			initList();
		} else {
			watch().watch(selectedId);
			// 部品を作る前の世代を覚える (作っている間に届いた結果は、次の tick で描き直す)
			shownWatchGeneration = watch().state().generation();
			initDetail();
		}

		if (lastRequest == 0) {
			request(); // タブを開いたら取る
		}
	}

	private static boolean watchEnded(String sessionId) {
		CommentWatch.State w = watch().state();
		return sessionId.equals(w.sessionId()) && w.ended();
	}

	@Override
	public void removed() {
		super.removed();
		watch().stop(); // 画面を閉じた・タブを移ったら取りにいかない
	}

	/** 詳細では、ヘッダの ▲ の左に拡大のトグル (#77。拡大している間は帯ごと隠れるので、操作の行の右端の縮小か Esc で戻す)。 */
	@Override
	protected List<HeaderToggle> headerToggles() {
		if (selectedId == null) {
			return List.of();
		}

		return List.of(new HeaderToggle(Icon.EXPAND, Tr.t(EXPAND_TOGGLE), false,
				Tr.t("帯と題の段を隠してプレビューを全幅で大きくし、コメントをその下に出す (Esc で戻す)"), () -> setExpanded(true)));
	}

	/** 拡大している間は、ヘッダと子タブの段を隠す (#77。詳細を見ているときだけ)。 */
	@Override
	protected boolean barsHidden() {
		return expanded && selectedId != null && !watchEnded(selectedId);
	}

	private void setExpanded(boolean on) {
		expanded = on;
		rebuildWidgets();
	}

	/** 詳細を拡大しているか (#77。テスト用)。 */
	public static boolean expanded() {
		return expanded;
	}

	/** 拡大をやめるボタン (拡大している間だけ。プレビューの右上。テスト用)。 */
	public @Nullable RButton shrinkButton() {
		return shrinkButton;
	}

	/** 一覧: 更新・検索欄・並べ替え・フォロー中 (#90) とその後ろに件数と時刻、リンク欄、カードのグリッド。 */
	private void initList() {
		int x = MARGIN;
		int y = contentTop();
		addRenderableWidget(RButton.icon(x, y, Icon.RELOAD, Tr.t("更新"), RButton.Variant.SECONDARY, b -> request())
				.tooltip(Tr.t("一覧を取り直す (15 秒ごとにも取り直す)。ホストが「一覧に出す」を選んで開いたセッションだけが出る")));

		// 検索欄・並べ替え・フォロー中 (#90)。狭い画面では検索欄から縮める (後ろの件数と時刻の字は切り、重ねると全文)
		x += RButton.H + 4;
		int sortW = sortWidth();
		String followName = Tr.t("フォロー中");
		int toggleW = RToggle.widthFor(font, followName);
		int searchW = LiveGrid.searchWidth(width - MARGIN - x - 4 - sortW - 4 - toggleW);
		searchField = addRenderableWidget(new RTextField(font, x, y, searchW, Component.literal(Tr.t("検索"))).hint(Tr.t("名前・題で探す")));
		searchField.setMaxLength(LiveQuery.MAX_Q);
		searchField.setValue(searchDraft);
		searchField.setTooltip(Tooltip.create(Component.literal(
				Tr.t("ホストのマイクラの名前・redtact の名前・題で探す (大文字小文字は区別しない)。打ち終わると探す (Enter ですぐ)"))));
		searchField.setResponder(text -> {
			if (!text.equals(searchDraft)) {
				searchDraft = text;
				searchEditedAt = System.currentTimeMillis();
			}
		});
		x += searchField.frameW() + 4;
		sortButton = addRenderableWidget(new RButton(x, y, sortW, RButton.H, Component.literal(Tr.t(query.sort().label())), RButton.Variant.SECONDARY,
				b -> changeSort(b.rightClick() ? query.sort().previous() : query.sort().next())).acceptRightClick().tooltip(sortTooltip()));
		x += sortW + 4;
		followingToggle = addRenderableWidget(new RToggle(x, y, toggleW, followName, query.following(), b -> setFollowing(!query.following()))
				.tooltip(Tr.t("redtact でフォローしている人のセッションだけを出す。このマイクラアカウントを redtact の設定 (アカウント → マイクラアカウント) で"
						+ "連携しておく (連携は非公開でもよい)。出るのは、フォローしている人のうちマイクラアカウントを公開にしている人だけ")));
		infoX = x + toggleW + 6;

		// 共有リンクかセッション ID を貼って読み込む・分岐する・開く
		boolean litematica = CircuitLoader.litematicaLoaded();
		linkY = y + RButton.H + 6;
		x = MARGIN + font.width(Tr.t("リンク/ID")) + 5;
		// 欄はボタンと Litematica の印の残りいっぱい (ボタンをアイコンにして広げた。#77)
		String loadLabel = litematica ? Tr.t("読み込む") : Tr.t("保存");
		int buttonsW = RButton.widthFor(font, loadLabel) + 4 + RButton.H + 4 + RButton.H;
		int badgeW = Draw.badgeWidth(font, Tr.t("Litematica: あり")) + 6;
		int fieldW = Math.max(120, Math.min(360, width - MARGIN - x - 4 - buttonsW - badgeW));
		linkField = addRenderableWidget(new RTextField(font, x, linkY, fieldW, Component.literal(Tr.t("リンク/ID"))));
		linkField.setMaxLength(512);
		linkField.setValue(linkDraft);
		linkField.hint(Tr.t("https://…/s/xxxxxxxxxx か ID"));
		x += linkField.frameW() + 4;
		loadButton = addRenderableWidget(RButton.of(font, x, linkY, loadLabel, RButton.Variant.PRIMARY, b -> loadFromField())
				.tooltip(litematica ? Tr.t("共有リンク (…/s/<ID>) かセッション ID の回路を、Litematica に読み込んで足元に置く")
						: Tr.f("共有リンク (…/s/<ID>) かセッション ID の回路を、schematics/{0}/ に .litematic で保存する", CircuitLoader.FOLDER)));
		x += loadButton.getWidth() + 4;
		branchButton = addRenderableWidget(RButton.icon(x, linkY, Icon.BRANCH, Tr.t("分岐する"), RButton.Variant.SECONDARY, b -> branchFromField())
				.tooltip(Tr.t("共有リンクかセッション ID の回路を足元に配置として出し、貼り付けてから「セッション」で分岐を開始する"))
				.disabledReason(BranchManager.unavailableReason()));
		x += branchButton.getWidth() + 4;
		openButton = addRenderableWidget(RButton.icon(x, linkY, Icon.FORWARD, Tr.t("開く"), RButton.Variant.SECONDARY, b -> openFromField())
				.tooltip(Tr.t("共有リンクかセッション ID のセッションの詳細 (回路のプレビューとコメント) を開く。一覧に出ていないセッションも開ける")));

		bodyY = linkY + RButton.H + 6;
		int bodyH = Math.max(40, height - 6 - bodyY);
		// 一覧が空でフォロー中の連携が無いときの「redtact の設定を開く」は、空の一覧より先に置く (先にクリックを受ける)
		settingsButton = addRenderableWidget(RButton.of(font, 0, 0, Tr.t("redtact の設定を開く"), RButton.Variant.PRIMARY, b -> openRedtactSettings())
				.tooltip(Tr.t("redtact の設定のアカウントを開く (マイクラアカウントの連携はそこの「マイクラアカウント」で)")));
		settingsButton.visible = false;
		grid = addRenderableWidget(new CardList<>(minecraft, MARGIN, bodyY, contentWidth(), bodyH));
		grid.setCards(gridRows());
		layoutEmpty();
		requestThumbs();
	}

	/** 並べ替えのボタンの幅 (どの並べ替えの名前も入る幅。押して名前が変わっても後ろのトグルが動かない)。 */
	private int sortWidth() {
		int w = 0;

		for (LiveQuery.Sort s : LiveQuery.Sort.values()) {
			w = Math.max(w, RButton.widthFor(font, Tr.t(s.label())));
		}

		return w;
	}

	/** 並べ替えのボタンのツールチップ (それぞれの並べ替えの説明)。 */
	private static String sortTooltip() {
		StringBuilder sb = new StringBuilder(Tr.t("並べ替え (左クリックで次・右クリックで前)"));

		for (LiveQuery.Sort s : LiveQuery.Sort.values()) {
			sb.append('\n').append(Tr.t(s.description()));
		}

		return sb.toString();
	}

	/** 並べ替えを変えて、すぐ取り直す (#90)。打ちかけの探す字も一緒に送る。 */
	private void changeSort(LiveQuery.Sort sort) {
		setQuery(query.withQ(searchDraft).withSort(sort));

		if (sortButton != null) {
			sortButton.label(Tr.t(sort.label()));
		}
	}

	/** フォロー中を切り替えて、すぐ取り直す (#90)。打ちかけの探す字も一緒に送る。 */
	private void setFollowing(boolean on) {
		setQuery(query.withQ(searchDraft).withFollowing(on));

		if (followingToggle != null) {
			followingToggle.on(on);
		}
	}

	/** 問い合わせを変えて取り直す (#90)。 */
	private void setQuery(LiveQuery q) {
		query = q;
		request();
	}

	/** redtact の設定のアカウント (マイクラアカウントの連携) を開く (ゲーム内の redtact か、無ければ外のブラウザ)。 */
	private void openRedtactSettings() {
		String url = RedtactLinks.accountSettings(BrowserSupport.home());
		minecraft.execute(() -> BrowserSupport.open(url)); // クリックを配っている最中に画面を切り替えない
	}

	/** 一覧が空のときの板の字と高さ、「redtact の設定を開く」の場所と出すかを決める (一覧の結果が変わるたびに)。 */
	private void layoutEmpty() {
		LiveDirectory.State s = directory().state();
		emptyLines = font.split(Component.literal(emptyText()), Math.max(40, contentWidth() - 24));
		LiveDirectory.Following f = s.following();
		boolean link = rows.isEmpty() && s.query().following() && f != null && f.status() == LiveDirectory.FollowStatus.NOT_LINKED;
		int h = Math.max(60, emptyLines.size() * 10 + 20 + (link ? RButton.H + 6 : 0));
		emptyH = grid == null ? h : Math.min(h, grid.getHeight());

		if (settingsButton != null) {
			settingsButton.visible = link;
			settingsButton.setPosition(MARGIN + contentWidth() / 2 - settingsButton.getWidth() / 2, bodyY + emptyH - 8 - RButton.H);
		}
	}

	/** 一覧が空のときの板の説明 (#90。フォロー中・探す字で絞ったときはその説明。テストからも読む)。 */
	public String emptyText() {
		LiveDirectory.State s = directory().state();
		return Objects.requireNonNullElse(LiveText.emptyText(s.query(), s.following()), placeholder(null));
	}

	/**
	 * 詳細: 「← 一覧」・題、左に立体のプレビュー・補足・操作、右にコメント (#74)。拡大しているとき (#77) は帯と題の段を隠し、
	 * 板を全幅にしてプレビューを上いっぱいに、その下に操作の行 (「←」・題とコメントの件数・操作・縮小) とコメントの欄。
	 */
	private void initDetail() {
		boolean litematica = CircuitLoader.litematicaLoaded();
		RButton back = RButton.icon(MARGIN, contentTop(), Icon.BACK, Tr.t("一覧に戻る"), RButton.Variant.SECONDARY,
				b -> minecraft.execute(this::backToList)).tooltip(Tr.t("開催中の一覧に戻る"));
		RButton load = RButton.of(font, 0, 0, litematica ? Tr.t("読み込む") : Tr.t("保存"), RButton.Variant.PRIMARY, b -> loadSelected());
		RButton branch = RButton.icon(0, 0, Icon.BRANCH, Tr.t("分岐する"), RButton.Variant.SECONDARY, b -> branchSelected());
		RButton open = RButton.icon(0, 0, Icon.EXTERNAL, Tr.t("ブラウザで開く"), RButton.Variant.SECONDARY, b -> openSelected());
		List<RButton> buttons = new ArrayList<>(List.of(load, branch, open));
		LiveRow r = selectedRow();
		RButton library = null;
		RButton post = null;

		if (r != null && r.mine()) {
			// 自分のセッションだけ: 最後に同期した版を保管庫へ・そのまま投稿の画面へ (#73)
			library = LiveLibraryButtons.library(font, 0, 0);
			post = LiveLibraryButtons.post(font, 0, 0);
			buttons.add(library);
			buttons.add(post);
		}

		if (barsHidden()) {
			initExpandedDetail(back, buttons);
		} else {
			initSplitDetail(back, buttons);
		}

		backButton = back;
		rowLoad = load;
		rowBranch = branch;
		rowOpen = open;
		rowLibrary = library;
		rowPost = post;

		// コメントの欄: 「コメント N 件」の行 (拡大しているときは操作の行)・入力欄と「送信」・一覧 (新しい順)
		composer = new CommentComposer(font, commentX, composerY, commentW, (sessionId, c) -> {
			watch().addPosted(sessionId, c); // 送った先をいま見ていなければ足さない
			watch().refresh();
		});
		addRenderableWidget(composer.field());
		addRenderableWidget(composer.button());
		int listY = composerY + RButton.H + 5;
		commentList = addRenderableWidget(new CardList<>(minecraft, commentX, listY, commentW, Math.max(30, height - 6 - listY)));
		applyComments();
		applyDetail();
	}

	/** 左右に分けた詳細 (#74): 題の段の下に、左の板 (プレビュー・補足・操作) と右のコメントの欄。 */
	private void initSplitDetail(RButton back, List<RButton> buttons) {
		addRenderableWidget(back);
		paneY = contentTop() + RButton.H + 6;
		panelH = Math.max(60, height - 6 - paneY);
		int[] w = LiveGrid.detailWidths(contentWidth());
		leftW = w[0];
		commentW = w[1];
		commentX = MARGIN + leftW + LiveGrid.DETAIL_GAP;
		commentsTop = paneY;
		headX = commentX;
		headY = commentsTop + 2;
		headRight = commentX + commentW;
		composerY = commentsTop + 13;
		// 操作のボタンは左の板に入らなければ段を分ける (左右に分けたので狭い。右のコメントの欄に重ねない。#74)
		int[] rowOf = LiveGrid.buttonRows(buttons.stream().mapToInt(RButton::getWidth).toArray(), leftW - 10, 3);
		int bottomY = paneY + panelH - 5 - RButton.H;
		int topY = bottomY - rowOf[rowOf.length - 1] * (RButton.H + 3);
		infoY = topY - 13;
		infoRight = MARGIN + leftW - 6;
		preview = addRenderableWidget(new PreviewView(MARGIN + 5, paneY + 5, leftW - 10, Math.max(30, infoY - 4 - (paneY + 5)))
				.onFullscreen(this::openFullscreen));
		preview.controls().forEach(this::addRenderableWidget); // 右上の回す・全画面のボタン (プレビューの上に描く)
		int bx = MARGIN + 5;

		for (int i = 0; i < buttons.size(); i++) {
			if (i > 0 && rowOf[i] != rowOf[i - 1]) {
				bx = MARGIN + 5;
			}

			RButton b = buttons.get(i);
			b.setPosition(bx, topY + rowOf[i] * (RButton.H + 3));
			addRenderableWidget(b);
			bx += b.getWidth() + 3;
		}
	}

	/**
	 * 拡大した詳細 (#77): 帯と題の段を隠し、全幅の板にプレビューを上いっぱい。その下に操作の行 (左に「←」と題・コメントの件数、
	 * 右に操作と縮小) と、コメントの入力欄・一覧 (1 件ぶん)。プレビューには何も重ねない。回路は既定の斜めの向きでは高さで
	 * 大きさが決まるので、題の段を足さずに操作の行をコメントの見出しと兼ねる。
	 */
	private void initExpandedDetail(RButton back, List<RButton> buttons) {
		paneY = contentTop();
		int[] h = LiveGrid.expandedHeights(Math.max(60, height - 6 - paneY));
		leftW = contentWidth();
		commentW = contentWidth();
		commentX = MARGIN;
		panelH = h[0];
		commentsTop = paneY + panelH + LiveGrid.DETAIL_GAP;
		preview = addRenderableWidget(new PreviewView(MARGIN + 5, paneY + 5, leftW - 10, Math.max(30, panelH - 10)).onFullscreen(this::openFullscreen));
		// 操作の行: 左に「←」、右端に縮小、その左にプレビューの回す・全画面のボタン (プレビューに重ねない)、その左に操作のボタン
		back.setPosition(MARGIN, commentsTop);
		addRenderableWidget(back);
		RButton shrink = RButton.icon(MARGIN + contentWidth() - RButton.H, commentsTop, Icon.SHRINK, Tr.t("拡大をやめる"), RButton.Variant.SECONDARY,
				b -> minecraft.execute(() -> setExpanded(false))).tooltip(Tr.t("帯と題の段を戻し、左にプレビュー・右にコメントに戻す (Esc でも)"));
		int total = buttons.stream().mapToInt(RButton::getWidth).sum() + 3 * (buttons.size() - 1);
		int controlsRight = shrink.getX() - 6;
		preview.placeControlsOutside(controlsRight, commentsTop);
		preview.controls().forEach(this::addRenderableWidget);
		int bx = controlsRight - preview.outsideWidth() - 6 - total;
		headX = back.getX() + back.getWidth() + 6;
		headY = commentsTop + 4;
		headRight = bx - 6;

		for (RButton b : buttons) {
			b.setPosition(bx, commentsTop);
			addRenderableWidget(b);
			bx += b.getWidth() + 3;
		}

		shrinkButton = addRenderableWidget(shrink);
		composerY = commentsTop + RButton.H + 3;
	}

	/** 拡大した詳細の題の後ろの補足: ホスト・自分・接続・視聴中と閲覧数・開始からの時間・版と大きさ (切れたらマウスを重ねて全文)。 */
	private String overlayInfo(LiveRow r) {
		Api.Session s = r.session();
		String host = r.hostLabel();
		String who = r.mine() ? Tr.f("{0} (自分)", host) : host;
		String state = s.hostOnline ? Tr.t("ホスト接続中") : Tr.t("ホスト切断中");
		String viewers = r.audienceText();
		return s.copyAllowed() ? Tr.f("・{0}・{1}・{2}・開始 {3}・{4}", who, state, viewers, r.ago(), paneInfo(r))
				: Tr.f("・{0}・複製不可・{1}・{2}・開始 {3}・{4}", who, state, viewers, r.ago(), paneInfo(r));
	}

	/** 一覧のカードを段に分ける (列の数は一覧の幅で決める)。 */
	private List<MenuCard> gridRows() {
		List<MenuCard> out = new ArrayList<>();

		if (grid == null || rows.isEmpty()) {
			return out;
		}

		int rowW = grid.getRowWidth();
		int cols = LiveGrid.columns(rowW);
		int cardW = LiveGrid.cardWidth(rowW, cols);

		for (int i = 0; i < rows.size(); i += cols) {
			List<LiveCard> cells = new ArrayList<>(cols);

			for (LiveRow r : rows.subList(i, Math.min(rows.size(), i + cols))) {
				cells.add(new LiveCard(r, this::openRow));
			}

			out.add(new LiveGridRow(cells, cols, cardW));
		}

		return out;
	}

	/** 詳細のボタンの押せる・押せない、プレビュー、入力欄を、選んでいるセッションに合わせる。 */
	private void applyDetail() {
		LiveRow r = selectedRow();
		boolean litematica = CircuitLoader.litematicaLoaded();
		String none = loadingText();

		if (rowLoad != null) {
			rowLoad.tooltip(litematica ? Tr.t("最新の版を Litematica に読み込んで足元に置く。同じセッションを読み直すと、前の配置の位置のまま新しい版にする")
					: Tr.f("schematics/{0}/ に .litematic を保存する (Litematica を入れると、その場に配置まで作る)", CircuitLoader.FOLDER))
					.disabledReason(r == null ? none : r.loadBlockedReason());
		}

		if (rowBranch != null) {
			rowBranch.tooltip(Tr.t("この回路を足元に配置として出す。貼り付けてから「セッション」の「この配置で分岐を開始」で開く"))
					.disabledReason(r == null ? none : r.branchBlockedReason());
		}

		if (rowOpen != null) {
			rowOpen.tooltip(r == null ? null : r.url()).disabledReason(r == null ? none : r.url() == null ? Tr.t("共有リンクがありません") : null);
		}

		if (preview != null) {
			preview.show(selectedId, r == null ? none : placeholder(r));
		}

		if (composer != null) {
			composer.target(selectedId, watchEnded(Objects.requireNonNullElse(selectedId, "")) ? Tr.t("このセッションは終了しています") : null);
		}

		requestPreview();
	}

	/** 詳細のセッションがまだ取れていないときの説明。 */
	private static String loadingText() {
		CommentWatch.State w = watch().state();
		return w.error() != null ? Tr.f("取得できません: {0}", w.error()) : Tr.t("読み込み中…");
	}

	/** 詳細のコメントの一覧を、取れたコメントに合わせる (新しい順)。 */
	private void applyComments() {
		if (commentList == null) {
			return;
		}

		CommentWatch.State w = watch().state();
		List<Api.Comment> comments = selectedId != null && selectedId.equals(w.sessionId()) ? w.comments() : List.of();

		if (comments == shownComments) {
			return; // 増えていない (5 秒ごとの取得のたびに、最大 2,000 件を折り返し直さない)
		}

		shownComments = comments;
		int textW = commentList.getRowWidth() - 12;
		String myUuid = myUuid();
		String myName = RedtactLive.get().config().userName();
		List<CommentRow> out = new ArrayList<>(comments.size());

		for (int i = comments.size() - 1; i >= 0; i--) {
			Api.Comment c = comments.get(i);
			StoredComment sc = StoredComment.of(selectedId, c);
			out.add(new CommentRow(sc, false, true, CommentRules.isMine(c, myUuid, myName), CommentsScreen.wrap(font, c.text, textW)));
		}

		commentRows = out;
		List<MenuCard> cards = new ArrayList<>(out.size());

		for (CommentRow row : out) {
			cards.add(new CommentCard(row));
		}

		commentList.setCards(cards);
	}

	/** ゲームのアカウントの UUID (ハイフン付き)。 */
	private static @Nullable String myUuid() {
		String signedIn = RedtactLive.get().commentSender().signIn().uuid();

		if (signedIn != null) {
			return signedIn;
		}

		UUID id = Minecraft.getInstance().getUser().getProfileId();
		return id == null ? null : id.toString();
	}

	/** 詳細のプレビューを取りにいかないときの説明 (取りにいくなら null)。 */
	static @Nullable String placeholder(@Nullable LiveRow r) {
		if (r == null) {
			return Tr.t("一覧に出ているセッションはありません。ホストが「一覧に出す」を選んで開いたセッションだけが出ます");
		}

		Api.Snapshot snap = r.session().snapshot;

		if (snap == null || snap.size == null || snap.size.length != 3) {
			return Tr.t("まだ回路が同期されていません");
		}

		if ((long) snap.size[0] * snap.size[1] * snap.size[2] > PreviewStore.MAX_VOLUME) {
			return Tr.f("範囲が大きすぎるので描きません ({0}x{1}x{2})", snap.size[0], snap.size[1], snap.size[2]);
		}

		return null;
	}

	/**
	 * 詳細のプレビューを取る。版はコメントと一緒に 5 秒ごとに分かるが、取り直すのは前に取れてから一覧の間隔
	 * ({@link #REFRESH_MS}) が経ってから (大きな回路を何度も取らないため。#74 の前は一覧の更新で同じ間隔だった)。
	 */
	private void requestPreview() {
		LiveRow r = selectedRow();

		if (r != null && placeholder(r) == null) {
			RedtactLive.get().previews().request(r.session().sessionId, r.session().snapshot.version, REFRESH_MS);
		}
	}

	/** 一覧で見えているカードのサムネを取る (版が進んでも {@link PreviewStore#THUMB_INTERVAL_MS} に 1 回まで)。 */
	private void requestThumbs() {
		for (LiveCard c : visibleCards()) {
			LiveRow r = c.row();

			if (r.thumbBlockedReason() == null) {
				RedtactLive.get().previews().request(r.session().sessionId, r.session().snapshot.version, PreviewStore.THUMB_INTERVAL_MS);
			}
		}
	}

	/** 一覧の見えている範囲にかかるカード (一覧でなければ空。テストからも使う)。 */
	public List<LiveCard> visibleCards() {
		List<LiveCard> out = new ArrayList<>();

		if (grid == null) {
			return out;
		}

		for (MenuCard m : grid.cards()) {
			if (m instanceof LiveGridRow row && row.getY() < grid.getBottom() && row.getY() + row.height() > grid.getY()) {
				row.layout();
				out.addAll(row.cells());
			}
		}

		return out;
	}

	private void openRow(LiveRow r) {
		// 一覧がクリックを配っている最中に部品を作り直さないよう、次のフレームで開く
		String id = r.session().sessionId;
		openedByLink = false;
		minecraft.execute(() -> select(id));
	}

	/** そのセッションの詳細を開く (一覧のカードを押したとき・リンク欄の「開く」と同じ。テストからも呼ぶ)。 */
	public void select(String sessionId) {
		if (sessionId.equals(selectedId)) {
			return;
		}

		selectedId = sessionId;
		rebuildWidgets();
	}

	/** 一覧に戻る (「← 一覧」と同じ。テストからも呼ぶ)。 */
	public void backToList() {
		if (selectedId == null) {
			return;
		}

		selectedId = null;
		rebuildWidgets();
	}

	/**
	 * 詳細のセッションを全画面のプレビューで開く (#102。Esc でこの詳細に戻る)。開いている間も、コメントと一緒に版を 5 秒ごとに
	 * 確かめ、進んでいたら取り直す (詳細と同じく、前に取れてから {@link #REFRESH_MS} は空ける)。
	 */
	private void openFullscreen() {
		LiveRow r = selectedRow();

		if (r == null || minecraft == null) {
			return;
		}

		String id = r.session().sessionId;
		int[] version = {r.session().snapshot == null ? 0 : r.session().snapshot.version};
		float yaw = preview == null ? PreviewView.DEFAULT_YAW : preview.yaw();
		float pitch = preview == null ? PreviewView.DEFAULT_PITCH : preview.pitch();
		minecraft.gui.setScreen(new PreviewScreen(this, r.title(), yaw, pitch, () -> fullscreenCircuit(id), () -> {
			// この画面を閉じると見るのをやめるので、全画面の間はここで見る (戻ると詳細が同じセッションを見続ける)
			watch().watch(id);
			watch().tick();
			CommentWatch.State w = watch().state();

			if (id.equals(w.sessionId()) && w.session() != null && w.session().snapshot != null) {
				version[0] = Math.max(version[0], w.session().snapshot.version);
			}

			RedtactLive.get().previews().request(id, version[0], REFRESH_MS);
		}));
	}

	private static @Nullable PreviewCircuit fullscreenCircuit(String sessionId) {
		PreviewStore.Entry e = RedtactLive.get().previews().get(sessionId);
		return e == null ? null : e.circuit();
	}

	/** 詳細で開いているセッションの行。コメントと一緒に取ったセッション (5 秒ごと) があればそれ、無ければ一覧の行。 */
	private @Nullable LiveRow selectedRow() {
		if (selectedId == null) {
			return null;
		}

		CommentWatch.State w = watch().state();

		if (selectedId.equals(w.sessionId()) && w.session() != null) {
			return row(w.session(), RedtactLive.get().sessions().active(), Instant.now());
		}

		return rowFor(selectedId);
	}

	private @Nullable LiveRow rowFor(String sessionId) {
		for (LiveRow r : rows) {
			if (r.session().sessionId.equals(sessionId)) {
				return r;
			}
		}

		return null;
	}

	/** 詳細の「読み込む」と同じ。 */
	private void loadSelected() {
		LiveRow r = selectedRow();

		if (r != null && r.loadBlockedReason() == null) {
			RedtactLive.get().circuitLoader().loadSession(r.session().sessionId, r.session().title);
		}
	}

	/** 詳細の「分岐する」と同じ。 */
	private void branchSelected() {
		LiveRow r = selectedRow();

		if (r != null && r.branchBlockedReason() == null) {
			Api.Session s = r.session();
			RedtactLive.get().branches().prepare(s.sessionId, s.title, s.host);
		}
	}

	private void openSelected() {
		LiveRow r = selectedRow();

		if (r != null && r.url() != null) {
			ConfirmLinkScreen.confirmLinkNow(this, URI.create(r.url()));
		}
	}

	/** 「読み込む」と同じ (テストからも呼ぶ)。 */
	public void loadFromField() {
		String text = linkField == null ? linkDraft : linkField.getValue();
		RedtactLive.get().circuitLoader().load(text);
	}

	/** 「分岐する」(リンク欄) と同じ (テストからも呼ぶ)。 */
	public void branchFromField() {
		String text = linkField == null ? linkDraft : linkField.getValue();
		RedtactLive.get().branches().prepare(text);
	}

	/** 「開く」(リンク欄) と同じ: 一覧に出ていないセッションも詳細で開く (#74。テストからも呼ぶ)。 */
	public void openFromField() {
		String text = linkField == null ? linkDraft : linkField.getValue();
		var id = CopyLinks.sessionId(text);

		if (id.isEmpty()) {
			Messages.error(Tr.t("共有リンク (…/s/<ID>) か 10 文字のセッション ID を入れてください"));
			return;
		}

		linkDraft = text;
		openedByLink = true;
		minecraft.execute(() -> select(id.get()));
	}

	/** いまの問い合わせで取り直す (取得中なら何もしない)。 */
	private void request() {
		lastRequest = System.currentTimeMillis();
		directory().refresh(query);
	}

	@Override
	public void tick() {
		super.tick();
		long now = System.currentTimeMillis();
		String q = LiveQuery.normalize(searchDraft);

		if (!q.equals(query.q()) && now - searchEditedAt >= SEARCH_DELAY_MS) {
			setQuery(query.withQ(q)); // 検索欄を打ち終わった (#90)
		} else if (now - lastRequest >= REFRESH_MS) {
			request();
		}

		if (selectedId != null) {
			watch().tick();
			CommentWatch.State w = watch().state();
			boolean watching = selectedId.equals(w.sessionId());

			if (watching && w.ended()) {
				// 詳細のセッションが終わった (猶予切れ・ホストが終了)。リンクで開いた ID が違っていたときも
				Messages.info(w.session() != null ? Tr.f("セッション「{0}」は終了しました", Objects.requireNonNullElse(w.session().title, selectedId))
						: Tr.f("セッション「{0}」は見つからないか、終了しています", selectedId));
				selectedId = null;
				rebuildWidgets();
				return;
			}

			if (watching && w.unsupported() && directory().state().fetchedAt() != null && rowFor(selectedId) == null) {
				// 古い中継サーバ (コメントの入口が無い): セッションの様子は一覧でしか分からないので、前と同じく一覧に無ければ戻る。
				// リンクで開いた一覧に無いセッションは、ここで理由を出して戻る
				if (openedByLink) {
					Messages.error(Objects.requireNonNullElse(w.error(), Tr.t("このセッションは開けません")));
				}

				selectedId = null;
				rebuildWidgets();
				return;
			}

			if (w.generation() != shownWatchGeneration) {
				shownWatchGeneration = w.generation();
				LiveRow r = selectedRow();

				if (rowLibrary == null && r != null && r.mine()) {
					rebuildWidgets(); // リンクで開いた自分のセッションが取れた: 「保管庫へ」「redtact に投稿」を出す (#73)
					return;
				}

				applyComments();
				applyDetail();
			}
		}

		LiveDirectory.State s = directory().state();

		if (s.generation() != shownGeneration) {
			shownGeneration = s.generation();
			rows = buildRows(s);

			if (grid != null) {
				grid.setCards(gridRows());

				if (!s.query().equals(shownQuery)) {
					grid.setScrollAmount(0); // 探し直した・並べ替えた: 上から見せる
				}

				layoutEmpty();
			}

			shownQuery = s.query();

			if (preview != null) {
				applyDetail();
			}
		} else if (preview != null) {
			requestPreview(); // 失敗したあとの取り直し・間を空けた取り直し
		}

		LiveLibraryButtons.refresh(rowLibrary);
		LiveLibraryButtons.refresh(rowPost);

		requestThumbs(); // スクロールで見えたカード・間を空けた取り直し
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (composer != null && composer.keyPressed(event)) {
			return true;
		}

		if (searchField != null && searchField.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			setQuery(query.withQ(searchDraft)); // 打ち終わりを待たずに探す (#90)
			return true;
		}

		if (event.key() == InputConstants.KEY_ESCAPE && barsHidden()) {
			setExpanded(false); // 拡大している間の Esc はメニューを閉じずに拡大をやめる (#77)
			return true;
		}

		return super.keyPressed(event);
	}

	/** 表示する行 (テストからも使う)。 */
	public static List<LiveRow> buildRows(LiveDirectory.State state) {
		SessionManager.Active a = RedtactLive.get().sessions().active();
		Instant now = Instant.now();
		List<LiveRow> out = new ArrayList<>(state.sessions().size());

		for (Api.Session s : state.sessions()) {
			out.add(row(s, a, now));
		}

		return out;
	}

	private static LiveRow row(Api.Session s, SessionManager.@Nullable Active a, Instant now) {
		boolean mine = a != null && a.sessionId.equals(s.sessionId);
		String url = s.shareUrl == null || s.shareUrl.isBlank() ? null : s.shareUrl;
		return new LiveRow(s, mine, url, LiveText.ago(s.createdAt, now));
	}

	/** 詳細の補足の行: 版・大きさ・ブロック数 (自分のセッションならそう書く)。 */
	static String paneInfo(@Nullable LiveRow r) {
		if (r == null) {
			return "";
		}

		Api.Snapshot snap = r.session().snapshot;
		String info;

		if (snap == null) {
			info = Tr.t("未同期");
		} else {
			String size = snap.size == null || snap.size.length != 3 ? "?" : snap.size[0] + "x" + snap.size[1] + "x" + snap.size[2];
			info = Tr.n("v{1}・{2}・{0} ブロック", snap.blockCount, snap.version, size);
		}

		return r.mine() ? Tr.f("自分のセッション・{0}", info) : info;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (selectedId == null) {
			extractList(g, mouseX, mouseY);
		} else {
			extractDetail(g, mouseX, mouseY);
		}

		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private void extractList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		LiveDirectory.State s = directory().state();
		String info;
		int color = Style.MUT;

		if (s.fetchedAt() == null && s.error() == null) {
			info = Tr.t("読み込み中…");
		} else if (s.error() != null) {
			info = s.fetchedAt() == null ? Tr.f("取得できません: {0}", s.error())
					: Tr.f("取得できません: {0} (前回 {1} の結果を表示)", s.error(), TIME.format(s.fetchedAt()));
			color = Style.RED_TX;
		} else if (s.searchUnsupported()) {
			// #90 より前の中継サーバ: 探す字と並べ替えを無視して全部を返している
			info = Tr.t("この中継サーバは検索と並べ替えに対応していません (中継サーバの更新待ち)");
			color = Style.LAMP;
		} else if (rows.isEmpty() && !s.query().narrowed()) {
			info = Tr.f("一覧に出ているセッションはありません ({0} 時点)", TIME.format(s.fetchedAt()));
		} else {
			// 絞ったときは 0 件でも件数で (理由は空の板に出す)
			info = s.loading() ? Tr.n("{0} 件・{1} 時点 (15 秒ごとに更新)・更新中…", rows.size(), TIME.format(s.fetchedAt()))
					: Tr.n("{0} 件・{1} 時点 (15 秒ごとに更新)", rows.size(), TIME.format(s.fetchedAt()));
		}

		if (infoX < width - MARGIN) {
			Draw.clippedText(g, font, info, infoX, contentTop() + 4, width - MARGIN - infoX, color, mouseX, mouseY);
		}
		g.text(font, Tr.t("リンク/ID"), MARGIN, linkY + 4, Style.MUT, false);

		if (openButton != null) {
			int bx = openButton.getX() + openButton.getWidth() + 6;
			boolean lm = CircuitLoader.litematicaLoaded();
			String t = lm ? Tr.t("Litematica: あり") : Tr.t("Litematica: なし");

			if (bx + Draw.badgeWidth(font, t) <= width - MARGIN) {
				Draw.badge(g, font, bx, linkY + 2, t, lm ? Draw.Tone.OK : Draw.Tone.WARN);
			}
		}

		if (grid != null && rows.isEmpty()) {
			// 一覧が空: 板に説明を出す (フォロー中・探す字で絞ったときはその説明。連携が無ければ下に「redtact の設定を開く」。#90)
			panel(g, MARGIN, bodyY, contentWidth(), emptyH);
			int textH = emptyH - (settingsButton != null && settingsButton.visible ? RButton.H + 6 : 0);
			int top = bodyY + textH / 2 - emptyLines.size() * 10 / 2;

			for (int i = 0; i < emptyLines.size(); i++) {
				FormattedCharSequence line = emptyLines.get(i);
				g.text(font, line, MARGIN + contentWidth() / 2 - font.width(line) / 2, top + i * 10 + 1, Style.MUT, false);
			}
		}
	}

	private void extractDetail(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (backButton == null) {
			return;
		}

		LiveRow r = selectedRow();
		// 板 (プレビュー・補足・操作の地)。左右に分けているときは左だけ、拡大しているときは全幅の上の部分
		panel(g, MARGIN, paneY, leftW, panelH);
		extractComments(g, mouseX, mouseY);

		if (barsHidden()) {
			return; // 題と補足はプレビューの下の操作の行に描く (extractComments)
		}

		if (r == null) {
			// 一覧に無いセッションをリンクで開いた直後 (まだ取れていない)
			Draw.clippedText(g, font, selectedId, backButton.getX() + backButton.getWidth() + 6, contentTop() + 4, width - MARGIN - 80, Style.MUT, mouseX, mouseY);
			return;
		}

		Api.Session s = r.session();
		// 題の行: 「← 一覧」・題・印 … 右にホストの接続と視聴中・閲覧数 (#90)
		String state = s.hostOnline ? Tr.t("ホスト接続中") : Tr.t("ホスト切断中");
		String viewers = r.audienceText();
		int right = width - MARGIN;
		int vw = font.width(viewers);
		g.text(font, viewers, right - vw, contentTop() + 4, Style.MUT, false);
		int lampX = right - vw - 6 - Draw.lampBadgeWidth(font, state);
		Draw.lampBadge(g, font, lampX, contentTop() + 2, state, s.hostOnline ? Draw.Tone.OK : Draw.Tone.MUTE, s.hostOnline);
		int tx = backButton.getX() + backButton.getWidth() + 6;
		int max = lampX - 6;
		int end = tx + Draw.clippedText(g, font, r.title(), tx, contentTop() + 4, Math.max(0, max - tx), Style.TXT, mouseX, mouseY);

		if (r.mine()) {
			end = badgeAfter(g, end, max, Tr.t("自分"), Draw.Tone.OK);
		}

		if (!s.copyAllowed()) {
			badgeAfter(g, end, max, Tr.t("複製不可"), Draw.Tone.RED);
		}

		// 補足: ホストの顔と名前 (redtact の公開プロフィールがあれば、重ねると redtact の名前。#90)・開始からの時間・版と大きさ
		int ix = MARGIN + 6;
		HostFace.draw(g, s.hostVerified ? s.hostUuid : null, s.host, ix + 1, infoY, 8);
		int limit = infoRight;
		ix += 13 + LiveCard.drawHost(g, font, r, ix, ix + 13, infoY + 1, Math.max(0, Math.min(font.width(r.hostLabel()), limit - ix - 13)), mouseX, mouseY);
		String text = CircuitLoader.litematicaLoaded() ? Tr.f("・開始 {0}・{1}", r.ago(), paneInfo(r))
				: Tr.f("・開始 {0}・{1}・Litematica が無いので保存だけ", r.ago(), paneInfo(r));
		Draw.clippedText(g, font, text, ix, infoY + 1, Math.max(0, limit - ix), Style.MUT, mouseX, mouseY);
	}

	/** コメントの欄: 「コメント N 件」と状態 (送信中・送れなかった理由・取得の失敗。拡大しているときは操作の行で題の後ろ)、一覧が空なら説明。 */
	private void extractComments(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		CommentWatch.State w = watch().state();
		boolean watching = selectedId != null && selectedId.equals(w.sessionId());
		int y = headY;
		int right = headRight;
		int x = headX;

		if (barsHidden() && x < right) {
			// 拡大: 操作の行に題 (と補足) を先に、幅の半分まで (全文はマウスを重ねると出る)
			LiveRow r = selectedRow();
			String title = r == null ? (selectedId == null ? "" : selectedId) : r.title() + overlayInfo(r);
			x += Draw.clippedText(g, font, title, x, y, Math.min(font.width(title), (right - x) / 2), r == null ? Style.MUT : Style.TXT, mouseX, mouseY) + 8;
		}

		String head = watching && w.loaded() ? Tr.n("コメント {0} 件・新しい順", w.comments().size()) : Tr.t("コメント");
		x += Draw.clippedText(g, font, head, x, y, Math.max(0, right - x), barsHidden() ? Style.MUT : Style.TXT, mouseX, mouseY) + 6;
		String status = composer == null ? null : composer.status();
		boolean error = composer != null && composer.statusIsError();

		if (status == null && watching && w.error() != null) {
			status = Tr.f("取得できません: {0}", w.error());
			error = true;
		}

		if (status != null && x < right) {
			Draw.clippedText(g, font, status, x, y, right - x, error ? Style.RED_TX : Style.MUT, mouseX, mouseY);
		}

		if (commentList != null && commentRows.isEmpty()) {
			String empty = !watching || !w.loaded() ? (w.error() != null ? Tr.t("コメントを取得できません") : Tr.t("読み込み中…")) : Tr.t("まだコメントはありません");
			int cy = commentList.getY() + 8;
			Draw.clippedText(g, font, empty, commentX + 4, cy, commentW - 8, Style.MUT, mouseX, mouseY);
		}
	}

	/** 題の右に印を置く (入らなければ置かない)。次の x を返す。 */
	private int badgeAfter(GuiGraphicsExtractor g, int x, int maxRight, String text, Draw.Tone tone) {
		int w = Draw.badgeWidth(font, text);

		if (x + 4 + w > maxRight) {
			return x;
		}

		Draw.badge(g, font, x + 4, contentTop() + 2, text, tone);
		return x + 4 + w;
	}

	// ---- テスト用

	public List<LiveRow> rows() {
		return rows;
	}

	/** いまの一覧の問い合わせ (#90。打ちかけの探す字はまだ入っていない)。 */
	public static LiveQuery query() {
		return query;
	}

	/** 一覧の上の検索欄 (#90)。 */
	public @Nullable RTextField searchField() {
		return searchField;
	}

	/** 並べ替えのボタン (#90。押すと次・右クリックで前)。 */
	public @Nullable RButton sortButton() {
		return sortButton;
	}

	/** 「フォロー中」のトグル (#90)。 */
	public @Nullable RToggle followingToggle() {
		return followingToggle;
	}

	/** 一覧が空でフォロー中の連携が無いときの「redtact の設定を開く」(#90。ふだんは隠している)。 */
	public @Nullable RButton settingsButton() {
		return settingsButton;
	}

	/** 一覧の上の段の件数と時刻の字の左端 (#90。狭い画面の確かめ)。 */
	public int infoX() {
		return infoX;
	}

	public @Nullable RTextField linkField() {
		return linkField;
	}

	public @Nullable RButton loadButton() {
		return loadButton;
	}

	public @Nullable RButton branchButton() {
		return branchButton;
	}

	/** リンク欄の「開く」(#74)。 */
	public @Nullable RButton openButton() {
		return openButton;
	}

	/** 詳細で開いているセッション (一覧なら null)。 */
	public @Nullable String selectedId() {
		return selectedId;
	}

	/** 詳細を開いているか。 */
	public boolean detail() {
		return preview != null;
	}

	/** 詳細の「← 一覧」。 */
	public @Nullable RButton backButton() {
		return backButton;
	}

	/** 詳細の「読み込む」。 */
	public @Nullable RButton rowLoadButton() {
		return rowLoad;
	}

	/** 詳細の「分岐する」。 */
	public @Nullable RButton rowBranchButton() {
		return rowBranch;
	}

	/** 詳細の「ブラウザで開く」。 */
	public @Nullable RButton rowOpenButton() {
		return rowOpen;
	}

	/** 詳細の「保管庫へ」(自分のセッションだけ。#73)。 */
	public @Nullable RButton rowLibraryButton() {
		return rowLibrary;
	}

	/** 詳細の「redtact に投稿」(自分のセッションだけ。#73)。 */
	public @Nullable RButton rowPostButton() {
		return rowPost;
	}

	public @Nullable PreviewView preview() {
		return preview;
	}

	/** 詳細のコメントの入力欄と「送信」(#74)。 */
	public @Nullable CommentComposer composer() {
		return composer;
	}

	/** 詳細のコメントの行 (新しい順。#74)。 */
	public List<CommentRow> commentRows() {
		return commentRows;
	}

	/** 詳細のコメントの一覧 (#74)。 */
	public @Nullable CardList<MenuCard> commentList() {
		return commentList;
	}

	/** 一覧のグリッドの列の数 (一覧でなければ 0)。 */
	public int gridColumns() {
		if (grid != null) {
			for (MenuCard m : grid.cards()) {
				if (m instanceof LiveGridRow row) {
					return row.columns();
				}
			}
		}

		return 0;
	}

	/** そのセッションのカードの段が一覧の上に来るまで送る (テストで押す前に)。 */
	public void scrollToCard(String sessionId) {
		if (grid == null) {
			return;
		}

		for (MenuCard m : grid.cards()) {
			if (m instanceof LiveGridRow row && row.cells().stream().anyMatch(c -> c.row().session().sessionId.equals(sessionId))) {
				grid.scrollToTop(m);
				return;
			}
		}
	}

	/** そのセッションのカード (一覧に無い・詳細を開いているなら null)。場所はいまのスクロールで決め直す。 */
	public @Nullable LiveCard cardFor(String sessionId) {
		if (grid == null) {
			return null;
		}

		for (MenuCard m : grid.cards()) {
			if (m instanceof LiveGridRow row) {
				row.layout();

				for (LiveCard c : row.cells()) {
					if (c.row().session().sessionId.equals(sessionId)) {
						return c;
					}
				}
			}
		}

		return null;
	}
}
