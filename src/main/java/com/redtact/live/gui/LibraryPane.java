package com.redtact.live.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.redtact.live.RedtactLive;
import com.redtact.live.gui.cards.FolderCard;
import com.redtact.live.gui.cards.HeaderCard;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.cards.PickCard;
import com.redtact.live.gui.kit.CardList;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.FileSelection;
import com.redtact.live.library.RedtactUpload;
import com.redtact.live.library.SchematicsBrowser;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * redtact タブの左半分の保管庫 (#60)。保管庫タブと同じフォルダ ({@link LibraryNav}) を細い行で出す。上の行で上へ・パス欄
 * (Enter で移る)、その下に選んだ数と「外す」。ファイルの行は押して離すと選ぶ・外す (複数可。redtact の上限 20 まで)。
 * 押したまま動かすとドラッグになり、選んだ行からなら選んだもの全部、ほかの行ならその 1 つを運ぶ
 * (右のページへ落とすのは画面の側。{@link #dragStart})。
 */
public final class LibraryPane {
	/** ドラッグを始めるまでに動かす距離 (GUI の座標)。 */
	private static final double DRAG_START = 4;
	private static final int ROW_GAP = 3;
	private final Font font;
	private int x;
	private int y;
	private int w;
	private int h;
	private @Nullable RButton up;
	private @Nullable RButton clear;
	private @Nullable RTextField pathField;
	private @Nullable CardList<MenuCard> list;
	private @Nullable Path shownDir;
	private int shownNav = -1;
	private int shownRevision = -1;
	private long shownStamp;
	private int infoY;
	private int ticks;
	private PickCard.@Nullable PressedAt pressed;

	public LibraryPane(Font font) {
		this.font = font;
	}

	private static FileSelection selection() {
		return RedtactLive.get().fileSelection();
	}

	private static SchematicsBrowser schematics() {
		return RedtactLive.get().schematics();
	}

	/** 部品を作って画面に足す (画面の init のたび)。 */
	public void init(Minecraft mc, int x, int y, int w, int h, Consumer<AbstractWidget> add) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		int left = x + 4;
		int right = x + w - 4;
		int rowY = y + 4;
		up = RButton.icon(left, rowY, Icon.UP, Tr.t("上へ"), RButton.Variant.SECONDARY, b -> {
			if (LibraryNav.up()) {
				refresh();
			}
		}).tooltip(Tr.t("一つ上のフォルダへ"));
		add.accept(up);
		int fieldX = left + up.getWidth() + 3;
		pathField = new RTextField(font, fieldX, rowY, Math.max(30, right - fieldX), Component.literal(Tr.t("フォルダ"))).hint("(" + schematics().rootName() + ")");
		pathField.setMaxLength(512);
		pathField.setTooltip(Tooltip.create(Component.literal(Tr.f("{0} からの場所 (例 redtact・builds/2026)。Enter で移る", schematics().rootName()))));
		add.accept(pathField);
		infoY = rowY + RButton.H + ROW_GAP;
		clear = RButton.icon(0, infoY, Icon.CROSS, Tr.t("外す"), RButton.Variant.SECONDARY, b -> selection().clear()).tooltip(Tr.t("選んだファイルをすべて外す"));
		clear.setX(right - clear.getWidth());
		add.accept(clear);
		int listY = infoY + RButton.H + ROW_GAP;
		list = new CardList<>(mc, left, listY, Math.max(20, right - left), Math.max(20, y + h - 4 - listY));
		add.accept(list);
		shownDir = null;
		refresh();
	}

	/** 一覧を読み直す。 */
	public void refresh() {
		Path dir = LibraryNav.current();
		boolean moved = !dir.equals(shownDir);
		shownDir = dir;
		shownNav = LibraryNav.revision();
		shownRevision = RedtactLive.get().library().revision();
		shownStamp = stamp(dir);
		pressed = null;

		if (up != null) {
			up.disabledReason(schematics().parent(dir) == null ? Tr.f("{0} のいちばん上です", schematics().rootName()) : null);
		}

		if (pathField != null && (moved || !pathField.isFocused())) {
			pathField.setValue(schematics().relative(dir));
			pathField.moveCursorToEnd(false);
		}

		List<MenuCard> cards = new ArrayList<>();
		SchematicsBrowser.Listing l = Files.isDirectory(dir) ? schematics().list(dir) : null;

		if (l == null || l.error() != null) {
			cards.add(new HeaderCard(LibraryNav.inLibrary() ? Tr.t("保管庫はまだ空です")
					: Tr.f("開けません: {0}", l == null ? Tr.t("フォルダがありません") : l.error())));
		} else {
			l.folders().forEach(f -> cards.add(new FolderCard(f, true, this::open)));
			l.files().forEach(f -> cards.add(new PickCard(f, () -> selection().contains(f.path()), p -> pressed = p)));

			if (l.isEmpty()) {
				cards.add(new HeaderCard(Tr.t("回路のファイルがありません")));
			}

			if (l.omitted() > 0) {
				cards.add(new HeaderCard(Tr.n("ほか {0} 件は出していません", l.omitted())));
			}
		}

		if (list != null) {
			list.setCards(cards);

			if (moved) {
				list.setScrollAmount(0);
			}
		}
	}

	private void open(Path dir) {
		if (LibraryNav.go(dir)) {
			refresh();
		}
	}

	/** パス欄のフォルダへ移る (Enter)。 */
	public void goToField() {
		if (pathField != null && LibraryNav.go(pathField.getValue())) {
			pathField.setFocused(false);
			refresh();
		}
	}

	public boolean pathFocused() {
		return pathField != null && pathField.isFocused();
	}

	public void tick() {
		ticks++;

		if (LibraryNav.revision() != shownNav || !LibraryNav.current().equals(shownDir) || RedtactLive.get().library().revision() != shownRevision) {
			refresh();
		} else if (ticks % 20 == 0 && shownDir != null && stamp(shownDir) != shownStamp) {
			refresh();
		}

		if (clear != null) {
			clear.disabledReason(selection().isEmpty() ? Tr.t("選んでいるファイルはありません") : null);
		}
	}

	/** 中身の範囲か (GUI の座標)。 */
	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/**
	 * 押したファイルの行から {@value #DRAG_START} 以上動いたら、運ぶファイル (選んだ行からなら選んだもの全部、ほかの行ならその 1 つ)。
	 * まだ動いていない・押していなければ null。redtact に上げられないファイルなら理由を知らせて空。
	 */
	public @Nullable List<Path> dragStart(double mx, double my) {
		PickCard.PressedAt p = pressed;

		if (p == null || Math.hypot(mx - p.x(), my - p.y()) < DRAG_START) {
			return null;
		}

		pressed = null;
		PickCard c = p.card();

		if (c.selected()) {
			return selection().existing();
		}

		if (c.unavailableReason() != null) {
			Messages.error(c.unavailableReason());
			return List.of();
		}

		return List.of(c.item().path());
	}

	/** 離した (ドラッグにならなかった)。押した行を選ぶ・外す。押した行があれば true。 */
	public boolean release() {
		PickCard.PressedAt p = pressed;
		pressed = null;

		if (p == null) {
			return false;
		}

		PickCard c = p.card();

		if (!c.selected() && c.unavailableReason() != null) {
			Messages.error(c.unavailableReason());
			return true;
		}

		if (!selection().toggle(c.item().path())) {
			Messages.error(Tr.n("redtact に一度に入れられるのは {0} 件までです", RedtactUpload.MAX_FILES));
		}

		return true;
	}

	/** 板と、選んだ数の行 (部品の下に描く)。 */
	public void extract(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.fill(x, y, x + w, y + h, Style.PANEL);
		g.fill(x + w - 1, y, x + w, y + h, Style.OUTLINE);
		int n = selection().size();
		String text = n == 0 ? Tr.t("押して選ぶ・右のページへドラッグ") : Tr.f("選択 {0}/{1}・右のページへドラッグ", n, RedtactUpload.MAX_FILES);
		int right = clear != null ? clear.getX() - 4 : x + w - 4;
		Draw.clippedText(g, font, text, x + 4, infoY + (RButton.H - 8) / 2, Math.max(0, right - x - 4), n == 0 ? Style.MUT : Style.LAMP, mouseX, mouseY);
	}

	private static long stamp(Path dir) {
		try {
			return Files.getLastModifiedTime(dir).toMillis();
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	// ---- テスト用

	/** 一覧のファイルの行。 */
	public List<PickCard> pickCards() {
		List<PickCard> out = new ArrayList<>();

		if (list != null) {
			for (MenuCard c : list.cards()) {
				if (c instanceof PickCard p) {
					out.add(p);
				}
			}
		}

		return out;
	}

	/** 一覧のフォルダの行。 */
	public List<FolderCard> folderCards() {
		List<FolderCard> out = new ArrayList<>();

		if (list != null) {
			for (MenuCard c : list.cards()) {
				if (c instanceof FolderCard f) {
					out.add(f);
				}
			}
		}

		return out;
	}

	public @Nullable RTextField pathField() {
		return pathField;
	}

	public @Nullable RButton upButton() {
		return up;
	}

	public @Nullable RButton clearButton() {
		return clear;
	}

	public int width() {
		return w;
	}
}
