package com.redtact.live.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.RedtactLive;
import com.redtact.live.copy.LitematicaSupport;
import com.redtact.live.gui.cards.FileCard;
import com.redtact.live.gui.cards.FolderCard;
import com.redtact.live.gui.cards.HeaderCard;
import com.redtact.live.gui.cards.LibraryCard;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.cards.SelectableCard;
import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.PreviewView;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.Library;
import com.redtact.live.library.LibraryActions;
import com.redtact.live.library.LibraryEntry;
import com.redtact.live.library.LibrarySearch;
import com.redtact.live.library.SchematicsBrowser;
import com.redtact.live.preview.FilePreviewStore;
import com.redtact.live.preview.PreviewStore;
import com.redtact.live.structure.StructureFiles;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * メニューの「保管庫」(#51)。ヘッダの下の段 (サブヘッダ。ヘッダの右端の ▲ で隠せる) に、schematics の下のフォルダを行き来する
 * 「上へ」・パス欄・「開く」(#60) と、検索欄・「フォルダを開く」を置く (#70)。中身は左に一覧、右に選んだファイルの立体のプレビュー
 * (#70。中身の幅が狭ければ一覧の下)。保管庫 ({@code schematics/redtact/}、#46) では redtact で取ってきた回路の行 (配置・redtact・削除)、
 * ほかのフォルダでは回路のファイルの行 (配置・redtact に投稿) を出す。子フォルダは行を押すと中へ入り、ファイルの行を押すと右に立体で出す
 * (↑↓ でも選べる)。プレビューはヘッダの右端 (段の ▲ の左) の「3D」のトグルで隠せ、隠すと一覧が全幅になる。
 * 検索欄に字を入れると、いまのフォルダとその下から名前 (保管庫の回路は題も) で探した結果 (場所付き) に変わる。
 * 開いたときは最後に見ていたフォルダ・検索の字・選んだファイル (ゲームを閉じるまで覚える)。
 */
public final class LibraryScreen extends MenuScreen {
	private static final String OTHER_FORMATS = Tr.ja(".litematic・.schem・.schematic・.nbt・.mcstructure・.mcworld");
	/** 検索欄の字が止まってから探すまで。 */
	private static final long SEARCH_DELAY_MS = 250;
	/** 中身の幅がこれより狭ければ、プレビューを一覧の下に置く。 */
	private static final int SPLIT_MIN_WIDTH = 380;
	/** ヘッダの右端の、プレビューを出す・隠すトグルの名前 (テストからも探す)。 */
	public static final String PREVIEW_TOGGLE = Tr.ja("立体のプレビュー");
	/** プレビューの下の補足の行の数。 */
	private static final int INFO_LINES = 3;
	private @Nullable CardList<MenuCard> list;
	private @Nullable RTextField pathField;
	private @Nullable RTextField searchField;
	private @Nullable RButton up;
	private @Nullable RButton go;
	private @Nullable RButton openFolder;
	private @Nullable PreviewView preview;
	private @Nullable Path shownDir;
	private int shownRevision = -1;
	private int shownNav = -1;
	private long shownStamp;
	/** 一覧を作ったときの検索の字 (空なら検索していない)。 */
	private String shownQuery = "";
	private LibrarySearch.@Nullable Result shownResult;
	private boolean shownBusy;
	/** 検索欄の字を変えた時刻 (止まってから探す)。 */
	private long queryEditedAt;
	private boolean showPrefix;
	private int prefixX;
	private int paneX;
	private int paneY;
	private int paneW;
	private int paneH;
	private int infoY;
	/** プレビューの下に補足の行を出すか (入らなければ出さない)。 */
	private boolean showInfo;
	/** プレビューの下の題と、それを決めたファイルと保管庫の版 (索引を毎フレーム読まない)。 */
	private @Nullable Path titleFor;
	private int titleRevision = -1;
	private @Nullable LibraryEntry titleEntry;
	private int ticks;

	public LibraryScreen() {
		super(MenuTab.LIBRARY);
	}

	private static Library library() {
		return RedtactLive.get().library();
	}

	private static SchematicsBrowser schematics() {
		return RedtactLive.get().schematics();
	}

	private static LibrarySearch.Service search() {
		return RedtactLive.get().librarySearch();
	}

	private static FilePreviewStore filePreviews() {
		return RedtactLive.get().filePreviews();
	}

	/** 保管庫も、ヘッダの下に場所と検索の段を置く (#70)。 */
	@Override
	protected boolean subBarAvailable() {
		return true;
	}

	@Override
	protected String subBarName() {
		return Tr.t("場所と検索の段");
	}

	/** 段の ▲ の左に、右の立体のプレビューを出す・隠すトグル (#70)。 */
	@Override
	protected List<HeaderToggle> headerToggles() {
		boolean shown = LibraryNav.previewShown();
		return List.of(new HeaderToggle(Icon.PREVIEW_3D, Tr.t(PREVIEW_TOGGLE), shown,
				shown ? Tr.t("右の立体のプレビューを隠して、一覧を広くする") : Tr.t("右に、選んだファイルの立体のプレビューを出す"), () -> {
					LibraryNav.setPreviewShown(!shown);
					rebuildWidgets();
				}));
	}

	@Override
	protected void initContent() {
		up = null;
		go = null;
		openFolder = null;
		pathField = null;
		searchField = null;

		if (hasSubBar()) {
			initBar();
		}

		int y = top();
		int h = Math.max(60, height - 6 - y);
		int cw = contentWidth();
		int listW;
		int listH;
		preview = null;

		if (!LibraryNav.previewShown()) {
			// プレビューを隠している: 一覧だけを全面に
			list = addRenderableWidget(new CardList<>(minecraft, MARGIN, y, cw, h));
			paneW = 0;
			paneH = 0;
			showInfo = false;
			shownDir = null;
			refresh();
			return;
		}

		if (cw >= SPLIT_MIN_WIDTH) {
			listW = Math.max(200, Math.min(cw - 150, cw * 11 / 20));
			listH = h;
			paneX = MARGIN + listW + 6;
			paneY = y;
			paneW = cw - listW - 6;
			paneH = h;
		} else {
			listW = cw;
			listH = Math.max(40, h * 11 / 20);
			paneX = MARGIN;
			paneY = y + listH + 6;
			paneW = cw;
			paneH = Math.max(40, h - listH - 6);
		}

		list = addRenderableWidget(new CardList<>(minecraft, MARGIN, y, listW, listH));
		// 補足の行は入るときだけ (狭い画面では立体を優先する)
		int previewH = paneH - 8 - (INFO_LINES * 10 + 2) - 4;
		showInfo = previewH >= 40;
		preview = addRenderableWidget(new PreviewView(paneX + 4, paneY + 4, paneW - 8, Math.max(20, showInfo ? previewH : paneH - 8))
				.tooLargeHint(Tr.t("Litematica で配置すると見られます")).onFullscreen(this::openFullscreen));
		preview.controls().forEach(this::addRenderableWidget); // 右上の回す・全画面のボタン (プレビューの上に描く)
		filePreviews().warmUp();
		infoY = preview.getY() + preview.getHeight() + 4;
		shownDir = null;
		refresh();
	}

	/** ヘッダの下の段: 上へ・schematics/・パス欄・開く ｜ 検索欄・フォルダを開く (ボタンはアイコン。欄を広くするため。#77)。 */
	private void initBar() {
		int y = SUB_Y + (SUB_H - RButton.H) / 2;
		int left = MARGIN;
		int right = width - MARGIN;
		up = addRenderableWidget(RButton.icon(left, y, Icon.UP, Tr.t("上へ"), RButton.Variant.SECONDARY, b -> {
			if (LibraryNav.up()) {
				refresh();
			}
		}).tooltip(Tr.t("一つ上のフォルダへ")));
		openFolder = RButton.icon(0, y, Icon.FOLDER, Tr.t("フォルダを開く"), RButton.Variant.SECONDARY, b -> LibraryActions.openFolder(folderToOpen()))
				.tooltip(Tr.t("いま見ているフォルダを OS のファイルの画面で開く"));
		openFolder.setX(right - openFolder.getWidth());
		go = RButton.icon(0, y, Icon.FORWARD, Tr.t("開く"), RButton.Variant.SECONDARY, b -> goToField()).tooltip(Tr.t("パス欄のフォルダへ移る (Enter でも)"));
		String prefix = schematics().rootName() + "/";
		int fixed = up.getWidth() + 4 + 3 + go.getWidth() + 10 + 4 + openFolder.getWidth();
		int prefixW = font.width(prefix) + 2;
		int flex = right - left - fixed - prefixW;
		showPrefix = flex >= 120;

		if (!showPrefix) {
			prefixW = 0;
			flex = right - left - fixed;
		}

		int pathW = Math.max(40, flex / 2);
		int searchW = Math.max(40, flex - pathW);
		prefixX = left + up.getWidth() + 4;
		int x = prefixX + prefixW;
		pathField = addRenderableWidget(new RTextField(font, x, y, pathW, Component.literal(Tr.t("フォルダ"))).hint(Tr.t("(いちばん上)")));
		pathField.setMaxLength(512);
		pathField.setTooltip(Tooltip.create(Component.literal(Tr.f("{0} からの場所 (例 redtact・builds/2026)。Enter で移る", schematics().rootName()))));
		x += pathW + 3;
		go.setX(x);
		addRenderableWidget(go);
		x += go.getWidth() + 10;
		searchField = addRenderableWidget(new RTextField(font, x, y, searchW, Component.literal(Tr.t("検索"))).hint(Tr.t("検索 (この下も)")));
		searchField.setMaxLength(100);
		searchField.setValue(LibraryNav.query());
		searchField.setTooltip(Tooltip.create(Component.literal(Tr.t(
				"いまのフォルダとその下から名前で探す (保管庫の回路は redtact の題でも)。空白で区切ると、すべての語を含むものだけ"))));
		searchField.setResponder(text -> {
			if (!text.equals(LibraryNav.query())) {
				LibraryNav.setQuery(text);
				queryEditedAt = System.currentTimeMillis();
			}
		});
		addRenderableWidget(openFolder);
	}

	/** 「フォルダを開く」で開くところ (保管庫がまだ無ければ作る)。 */
	private static Path folderToOpen() {
		Path dir = LibraryNav.current();

		if (LibraryNav.inLibrary()) {
			try {
				Files.createDirectories(dir);
			} catch (IOException | RuntimeException ignored) {
				// 開くときに知らせる
			}
		}

		return dir;
	}

	private void goToField() {
		if (pathField != null && LibraryNav.go(pathField.getValue())) {
			setFocused(null);
			refresh();
		}
	}

	private void open(Path dir) {
		if (LibraryNav.go(dir)) {
			refresh();
		}
	}

	/** 検索の結果のフォルダを開く (検索はやめる。エクスプローラーと同じ)。 */
	private void openFromSearch(Path dir) {
		clearQuery();
		open(dir);
	}

	private void clearQuery() {
		LibraryNav.setQuery("");
		search().clear();

		if (searchField != null && !searchField.getValue().isEmpty()) {
			searchField.setValue("");
		}
	}

	/** 検索の字 (前後の空白は除く)。 */
	private static String query() {
		return LibraryNav.query().strip();
	}

	/** 一覧を読み直す。 */
	public void refresh() {
		Path dir = LibraryNav.current();
		String q = query();
		boolean moved = !dir.equals(shownDir) || !q.equals(shownQuery);
		shownDir = dir;
		shownNav = LibraryNav.revision();
		shownRevision = library().revision();
		shownStamp = stamp(dir);
		shownQuery = q;

		if (up != null) {
			up.disabledReason(schematics().parent(dir) == null ? Tr.f("{0} のいちばん上です", schematics().rootName()) : null);
		}

		if (pathField != null && (moved || !pathField.isFocused())) {
			pathField.setValue(schematics().relative(dir));
			pathField.moveCursorToEnd(false);
		}

		List<MenuCard> cards;

		if (q.isEmpty()) {
			shownResult = null;
			shownBusy = false;
			cards = LibraryNav.inLibrary() ? libraryCards(dir) : folderCards(dir);
		} else {
			search().request(dir, q, searchStamp());
			shownResult = search().latest(dir, q);
			shownBusy = search().busy();
			cards = searchCards(dir, q, shownResult, shownBusy);
		}

		if (list != null) {
			list.setCards(cards);

			if (moved) {
				list.setScrollAmount(0);
			}
		}

		applyPreview();
	}

	/** 中身が変わった合図 (保管庫の索引・行き来・いまのフォルダの更新日時)。変わったら探し直す。 */
	private long searchStamp() {
		return (long) library().revision() * 31 + LibraryNav.revision() * 7L + shownStamp;
	}

	/** 保管庫 ({@code schematics/redtact/}): 子フォルダ・redtact で取ってきた回路・索引に無いファイル。 */
	private List<MenuCard> libraryCards(Path dir) {
		Library lib = library();
		List<LibraryEntry> entries = lib.list();
		SchematicsBrowser.Listing listing = Files.isDirectory(dir) ? schematics().list(dir) : null;
		List<MenuCard> cards = new ArrayList<>();
		String head = LitematicaSupport.available()
				? Tr.n("保管庫 {1} ({0} 件)・Litematica の読み込み画面にも出ます", entries.size(), schematics().display(dir))
				: Tr.n("保管庫 {1} ({0} 件)・Litematica を入れると配置まで作れます", entries.size(), schematics().display(dir));
		cards.add(new HeaderCard(head));

		if (listing != null) {
			listing.folders().forEach(f -> cards.add(new FolderCard(f, false, this::open)));
		}

		Set<String> indexed = new HashSet<>();

		for (LibraryEntry e : entries) {
			indexed.add(e.storedName.toLowerCase(Locale.ROOT));
			cards.add(libraryCard(e));
		}

		List<SchematicsBrowser.Item> others = listing == null ? List.of()
				: listing.files().stream().filter(f -> !indexed.contains(f.name().toLowerCase(Locale.ROOT))).toList();

		if (!others.isEmpty()) {
			cards.add(new HeaderCard(Tr.n("索引に無いファイル ({0} 件。自分で置いたもの)", others.size())));
			others.forEach(f -> cards.add(fileCard(f, false)));
		}

		if (entries.isEmpty() && others.isEmpty()) {
			cards.add(new HeaderCard(Tr.t("まだありません。redtact タブで回路の「ダウンロード」を押すとここに入ります")));
		}

		return cards;
	}

	/** ほかのフォルダ: 子フォルダと回路のファイル (配置・redtact に投稿)。 */
	private List<MenuCard> folderCards(Path dir) {
		SchematicsBrowser.Listing l = schematics().list(dir);
		List<MenuCard> cards = new ArrayList<>();

		if (l.error() != null) {
			cards.add(new HeaderCard(Tr.f("{0} を開けません: {1}", schematics().display(dir), l.error())));
			return cards;
		}

		cards.add(new HeaderCard(l.omitted() > 0
				? Tr.f("{0} (フォルダ {1}・ファイル {2})・ほか {3} 件は出していません", schematics().display(dir), l.folders().size(), l.files().size(), l.omitted())
				: Tr.f("{0} (フォルダ {1}・ファイル {2})", schematics().display(dir), l.folders().size(), l.files().size())));
		l.folders().forEach(f -> cards.add(new FolderCard(f, false, this::open)));
		l.files().forEach(f -> cards.add(fileCard(f, true)));

		if (l.isEmpty()) {
			cards.add(new HeaderCard(Tr.f("回路のファイルがありません ({0} を出します)", Tr.t(OTHER_FORMATS))));
		}

		return cards;
	}

	/** 検索の結果: 見出し・フォルダ・ファイル (どれも入っているフォルダ付き)。 */
	private List<MenuCard> searchCards(Path dir, String q, LibrarySearch.@Nullable Result r, boolean busy) {
		List<MenuCard> cards = new ArrayList<>();
		String where = schematics().display(dir);

		if (r == null) {
			cards.add(new HeaderCard(Tr.f("「{0}」を{1} とその下から探しています…", q, where)));
			return cards;
		}

		if (r.error() != null) {
			cards.add(new HeaderCard(Tr.f("「{0}」を探せません: {1}", q, r.error())));
			return cards;
		}

		String found = r.count() == 0 ? Tr.f("「{0}」{1} とその下: 見つかりません", q, where) : Tr.n("「{1}」{2} とその下: {0} 件", r.count(), q, where);
		cards.add(new HeaderCard(found + (r.truncated() ? Tr.t("・多いので途中で止めました") : "") + (busy ? Tr.t("・探し直しています…") : "")));
		Path libDir = LibraryNav.libraryDir();

		for (LibrarySearch.Hit h : r.folders()) {
			cards.add(new FolderCard(h.item(), false, this::openFromSearch).location(h.location()));
		}

		for (LibrarySearch.Hit h : r.files()) {
			if (h.entry() != null) {
				cards.add(libraryCard(h.entry()).location(h.location()));
			} else {
				cards.add(fileCard(h.item(), !libDir.equals(h.item().path().getParent())).location(h.location()));
			}
		}

		return cards;
	}

	private SelectableCard libraryCard(LibraryEntry e) {
		// 次の tick で読み直す (押している最中に一覧を作り直さない)
		return new LibraryCard(library(), e, () -> shownRevision = -1).selectable(LibraryScreen::isSelected, this::select);
	}

	private SelectableCard fileCard(SchematicsBrowser.Item item, boolean postable) {
		return new FileCard(item, postable).selectable(LibraryScreen::isSelected, this::select);
	}

	private static boolean isSelected(Path file) {
		return file.equals(LibraryNav.selected());
	}

	/** プレビューにそのファイルを選ぶ (行を押したとき。テストからも呼ぶ)。 */
	public void select(Path file) {
		LibraryNav.select(file);
		applyPreview();
	}

	/** 右のプレビューを、選んでいるファイルに合わせる (読んでいなければ読む)。 */
	private void applyPreview() {
		if (preview == null) {
			return;
		}

		Path sel = LibraryNav.selected();

		if (sel == null) {
			preview.show(null, () -> null, Tr.t("ファイルの行を押すと、ここに立体で出します (↑↓ でも選べます)"));
			return;
		}

		String unsupported = StructureFiles.unsupportedReason(sel.getFileName() == null ? "" : sel.getFileName().toString());

		if (unsupported != null) {
			preview.show(sel, () -> null, unsupported);
			return;
		}

		filePreviews().request(sel);
		preview.show(sel, () -> content(sel), null);
	}

	/** 選んだファイルを全画面のプレビューで開く (#102。Esc で保管庫に戻る)。開いている間もファイルが書き換わったら読み直す。 */
	private void openFullscreen() {
		Path sel = LibraryNav.selected();

		if (sel == null || minecraft == null) {
			return;
		}

		String name = sel.getFileName() == null ? sel.toString() : sel.getFileName().toString();
		LibraryEntry entry = entryOf(sel, name);
		String title = entry != null && !entry.label.isBlank() ? entry.label : name;
		float yaw = preview == null ? PreviewView.DEFAULT_YAW : preview.yaw();
		float pitch = preview == null ? PreviewView.DEFAULT_PITCH : preview.pitch();
		minecraft.gui.setScreen(new PreviewScreen(this, title, yaw, pitch, () -> {
			FilePreviewStore.Entry e = filePreviews().get(sel);
			return e == null ? null : e.circuit();
		}, () -> filePreviews().request(sel)));
	}

	private static PreviewView.@Nullable Content content(Path file) {
		FilePreviewStore.Entry e = filePreviews().get(file);
		return e == null ? null : new PreviewView.Content(e.status(), e.circuit(), e.message());
	}

	/** 一覧の中の、選べる行 (ファイルと保管庫の回路) を上から。 */
	private List<SelectableCard> selectableCards() {
		return cardsOf(SelectableCard.class);
	}

	/** ↑↓ で選ぶ行を動かす (選んでいなければ最初か最後の行)。 */
	private void moveSelection(int step) {
		List<SelectableCard> cards = selectableCards();

		if (cards.isEmpty() || list == null) {
			return;
		}

		int at = -1;

		for (int i = 0; i < cards.size(); i++) {
			if (cards.get(i).file().equals(LibraryNav.selected())) {
				at = i;
				break;
			}
		}

		int next = at < 0 ? (step > 0 ? 0 : cards.size() - 1) : Math.max(0, Math.min(cards.size() - 1, at + step));
		SelectableCard c = cards.get(next);
		select(c.file());
		int top = list.getY();
		int bottom = list.getBottom();

		if (c.getY() < top) {
			list.setScrollAmount(list.scrollAmount() - (top - c.getY()) - 2);
		} else if (c.getY() + c.height() > bottom) {
			list.setScrollAmount(list.scrollAmount() + (c.getY() + c.height() - bottom) + 2);
		}
	}

	/** フォルダの更新日時 (ファイルが増えた・消えたら変わる)。無ければ -1。 */
	private static long stamp(Path dir) {
		try {
			return Files.getLastModifiedTime(dir).toMillis();
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	@Override
	public void tick() {
		ticks++;
		String q = query();
		boolean queryChanged = !q.equals(shownQuery);
		// 字を打っている間は探さない (止まってから {@value #SEARCH_DELAY_MS} ms。Enter ならすぐ)
		boolean settled = !queryChanged || System.currentTimeMillis() - queryEditedAt >= SEARCH_DELAY_MS;

		if (queryChanged && q.isEmpty()) {
			search().clear();
			refresh(); // 検索欄を空にした
		} else if (settled && (queryChanged || library().revision() != shownRevision || LibraryNav.revision() != shownNav
				|| !LibraryNav.current().equals(shownDir))) {
			refresh();
		} else if (settled && ticks % 20 == 0 && shownDir != null && stamp(shownDir) != shownStamp) {
			refresh(); // 外 (Litematica の保存・エクスプローラー) でファイルが増えた・消えた
		} else if (!queryChanged && !q.isEmpty() && (search().latest(LibraryNav.current(), q) != shownResult || search().busy() != shownBusy)) {
			refresh(); // 検索の結果が届いた
		} else if (preview != null && ticks % 20 == 0 && LibraryNav.selected() != null) {
			filePreviews().request(LibraryNav.selected()); // 選んだファイルが書き換わったら読み直す
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean enter = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER;

		if (pathField != null && pathField.isFocused() && enter) {
			goToField();
			return true;
		}

		if (searchField != null && searchField.isFocused() && enter) {
			queryEditedAt = 0; // 待たずに探す
			return true;
		}

		GuiEventListener focused = getFocused();

		if ((focused == null || focused == list) && (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)) {
			moveSelection(event.key() == InputConstants.KEY_DOWN ? 1 : -1);
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (pathField != null && showPrefix) {
			g.text(font, schematics().rootName() + "/", prefixX, pathField.frameY() + (pathField.frameH() - 8) / 2, Style.MUT, false);
		}

		// 保存の途中の数はヘッダの空きに出す (redtact タブと同じ ↓ と数。#69)
		int inFlight = RedtactLive.get().downloads().inFlight();

		if (inFlight > 0) {
			int w = Draw.downloadsBadgeWidth(font, inFlight);

			if (headerFreeRight() - w >= headerFreeLeft()) {
				Draw.downloadsBadge(g, font, headerFreeRight() - w, (BAR_H - Draw.BADGE_H) / 2, inFlight, mouseX, mouseY);
			}
		}

		if (preview != null) {
			panel(g, paneX, paneY, paneW, paneH);
			extractInfo(g, mouseX, mouseY);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	/** プレビューの下の補足: 題 (保管庫の回路は redtact の題)・形式と大きさとブロックの数・場所と注意。 */
	private void extractInfo(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Path sel = LibraryNav.selected();

		if (sel == null || !showInfo) {
			return;
		}

		int x = paneX + 5;
		int w = paneW - 10;
		String name = sel.getFileName() == null ? sel.toString() : sel.getFileName().toString();
		LibraryEntry entry = entryOf(sel, name);
		String title = entry != null && !entry.label.isBlank() ? entry.label : name;
		Draw.clippedText(g, font, title, x, infoY, w, Style.TXT, mouseX, mouseY);
		FilePreviewStore.Entry e = filePreviews().get(sel);
		FilePreviewStore.Info info = e == null ? null : e.info();
		String where = schematics().contains(sel) && sel.getParent() != null ? schematics().display(sel.getParent()) : "";

		if (info != null) {
			String size = Tr.n("{1}×{2}×{3}・ブロック {0}", info.nonAir(), info.sizeX(), info.sizeY(), info.sizeZ());
			int bw = Draw.badgeWidth(font, info.format());
			Draw.badge(g, font, x, infoY + 10 + (8 - Draw.BADGE_H) / 2 + 1, info.format(), Draw.Tone.OK);
			Draw.clippedText(g, font, size, x + bw + 4, infoY + 10, w - bw - 4, Style.STONE_TX, mouseX, mouseY);
			String third = entry != null ? Tr.f("redtact では {0}・{1}", entry.fileName, where) : where;

			if (!info.notes().isEmpty()) {
				String sep = Tr.t("・");
				third = String.join(sep, info.notes()) + sep + third;
			}

			Draw.clippedText(g, font, third, x, infoY + 20, w, info.notes().isEmpty() ? Style.MUT : Style.LAMP, mouseX, mouseY);
		} else {
			boolean loading = e == null || e.status() == PreviewStore.Status.LOADING;
			Draw.clippedText(g, font, loading ? Tr.t("読み込み中…") : name, x, infoY + 10, w, Style.MUT, mouseX, mouseY);
			Draw.clippedText(g, font, where, x, infoY + 20, w, Style.MUT, mouseX, mouseY);
		}
	}

	/** 保管庫の回路なら索引の行 (選んだファイルか保管庫が変わったときだけ読む)。 */
	private @Nullable LibraryEntry entryOf(Path sel, String name) {
		if (!sel.equals(titleFor) || library().revision() != titleRevision) {
			titleFor = sel;
			titleRevision = library().revision();
			titleEntry = sel.getParent() != null && sel.getParent().equals(LibraryNav.libraryDir()) ? library().find(name) : null;
		}

		return titleEntry;
	}

	// ---- テスト用

	private <T extends MenuCard> List<T> cardsOf(Class<T> type) {
		List<T> out = new ArrayList<>();

		if (list != null) {
			for (MenuCard c : list.cards()) {
				if (type.isInstance(c)) {
					out.add(type.cast(c));
				}
			}
		}

		return out;
	}

	/** 一覧の保管庫の行。 */
	public List<LibraryCard> libraryCards() {
		return cardsOf(LibraryCard.class);
	}

	/** 一覧のファイル (保管庫の外) の行。 */
	public List<FileCard> fileCards() {
		return cardsOf(FileCard.class);
	}

	/** 一覧のフォルダの行。 */
	public List<FolderCard> folderCards() {
		return cardsOf(FolderCard.class);
	}

	/** 一覧の見出し。 */
	public List<String> headers() {
		return cardsOf(HeaderCard.class).stream().map(HeaderCard::text).toList();
	}

	public @Nullable RTextField pathField() {
		return pathField;
	}

	/** 検索欄 (段を隠していれば null)。 */
	public @Nullable RTextField searchField() {
		return searchField;
	}

	public @Nullable RButton upButton() {
		return up;
	}

	public @Nullable RButton goButton() {
		return go;
	}

	public @Nullable RButton openFolderButton() {
		return openFolder;
	}

	public @Nullable Path shownDir() {
		return shownDir;
	}

	/** 右のプレビュー (隠していれば null)。 */
	public @Nullable PreviewView preview() {
		return preview;
	}

	/** 一覧 (幅と位置を見る)。 */
	public @Nullable CardList<MenuCard> list() {
		return list;
	}

	/** いま出しているのが検索の結果か (字があり、結果が届いている)。 */
	public boolean showingSearch() {
		return !shownQuery.isEmpty() && shownResult != null && Objects.equals(shownResult.query(), shownQuery);
	}
}
