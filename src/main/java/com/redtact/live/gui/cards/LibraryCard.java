package com.redtact.live.gui.cards;

import java.nio.file.Path;

import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.gui.kit.ConfirmButton;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.Library;
import com.redtact.live.library.LibraryActions;
import com.redtact.live.library.LibraryEntry;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.store.Times;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 保管庫の 1 件: 題・形式・ファイル名・大きさ・保存した日時と「配置」・redtact (回路のページ。アイコン)・削除 (ゴミ箱。#46・#77)。
 * 自分で作った回路の行 (live から入れた行も。#60・#73) は、回路のページの代わりに投稿 (字は「投稿」、名前「redtact に投稿」はツールチップ。#77)。
 * 削除は 2 回押す (1 回目で赤くなり、3 秒で戻る。{@link ConfirmButton})。保管庫タブでは行を押すとプレビューに選ぶ (#70)。
 */
public final class LibraryCard extends SelectableCard {
	/** 投稿のボタンの字と名前 (#77。字は短く、名前はツールチップの先頭。テストからも読む)。 */
	public static final String POST_LABEL = Tr.ja("投稿");
	public static final String POST_NAME = Tr.ja("redtact に投稿");
	private final LibraryEntry entry;
	private final Path file;
	private final boolean exists;
	private final RButton place;
	private final RButton page;
	private final ConfirmButton delete;

	public LibraryCard(Library library, LibraryEntry entry, Runnable changed) {
		this.entry = entry;
		this.file = fileOf(library, entry);
		this.exists = library.exists(entry);
		boolean created = entry.created();
		this.place = add(RButton.of(font(), 0, 0, Tr.t("配置"), created ? RButton.Variant.SECONDARY : RButton.Variant.PRIMARY,
				b -> LibraryActions.place(library, entry)).tooltip(Tr.t("Litematica の配置を足元に作る (同じファイルの配置があれば足元へ置き直す)")));
		this.place.disabledReason(LibraryActions.placeUnavailableReason(library, entry));

		if (created) {
			// 自分の回路: 投稿の画面へ (redtact タブの左に保管庫、このファイルを選んだ状態で。#60)。字は「投稿」だけにしてファイル名の幅を空け、
			// 「redtact に投稿」はツールチップの先頭に出す (#77)
			this.page = add(RButton.of(font(), 0, 0, Tr.t(POST_LABEL), RButton.Variant.PRIMARY, b -> LibraryActions.post(library.file(entry)))
					.fullName(Tr.t(POST_NAME))
					.tooltip(Tr.t("redtact タブの左に保管庫を出して投稿の画面を開く。このファイルを右のページのファイルの欄へドラッグして入れる"
							+ " (MCEF Modern が無ければ外のブラウザで投稿の画面とこのフォルダを開く)")));
			this.page.disabledReason(exists ? LibraryActions.postUnavailableReason(library.file(entry)) : Tr.t("ファイルが見つかりません (消されたか、名前が変わりました)"));
		} else {
			this.page = add(RButton.icon(0, 0, Icon.PAGE, Tr.t("redtact の回路のページ"), RButton.Variant.SECONDARY,
					b -> BrowserSupport.open(RedtactLinks.circuitPage(BrowserSupport.home(), entry.circuitId)))
					.tooltip(Tr.t("この回路のページを redtact タブで開く (MCEF Modern が無ければ外のブラウザ)")));
			this.page.disabledReason(entry.circuitId == null || !entry.circuitId.matches("[A-Za-z0-9_-]{1,64}") ? Tr.t("どの回路のファイルか分かりません") : null);
		}
		this.delete = add(ConfirmButton.icon(0, 0, Icon.TRASH, Tr.t("削除"), Tr.t("もう一度押すと削除"), RButton.Variant.SECONDARY, b -> {
			LibraryActions.delete(library, entry);
			changed.run();
		}).tooltip(Tr.t("ファイルごと消す (2 回押す)")));
	}

	public LibraryEntry entry() {
		return entry;
	}

	private static Path fileOf(Library library, LibraryEntry entry) {
		try {
			return library.file(entry).toAbsolutePath().normalize();
		} catch (IllegalArgumentException e) {
			return library.dir().toAbsolutePath().normalize(); // 索引の名前が変 (選んでも「ファイルがありません」)
		}
	}

	/** 保管庫の中のファイル。 */
	@Override
	public Path file() {
		return file;
	}

	public RButton placeButton() {
		return place;
	}

	/** 「redtact」(回路のページ)。live から入れた行 (#73) では「redtact に投稿」。 */
	public RButton pageButton() {
		return page;
	}

	public ConfirmButton deleteButton() {
		return delete;
	}

	@Override
	public int height() {
		return TWO_LINES;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		extractSelection(g);
		int left = layoutRight(cardX() + cardW() - 5, buttonY(), delete, page, place);
		int x = cardX() + 5;
		int end = title(g, x, entry.label.isBlank() ? entry.storedName : entry.label, left - 4, mouseX, mouseY);
		end = badgeAfter(g, end, left - 4, entry.formatLabel(), entry.placeable() ? Draw.Tone.OK : Draw.Tone.PLAIN);

		if (entry.created()) {
			end = badgeAfter(g, end, left - 4, "live", Draw.Tone.CYAN);
		}

		if (!exists) {
			badgeAfter(g, end, left - 4, Tr.t("ファイルなし"), Draw.Tone.WARN);
		}

		String name = entry.fileName.isBlank() || entry.fileName.equals(entry.storedName) ? entry.storedName
				: Tr.f("{0} (redtact では {1})", entry.storedName, entry.fileName);
		String sep = Tr.t("・");
		info(g, x, (location() == null ? "" : location() + sep) + name + sep + sizeText(entry.bytes) + sep + Times.local(entry.savedAt), left - 4, mouseX, mouseY);
	}
}
