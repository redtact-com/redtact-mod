package com.redtact.live.gui.cards;

import java.nio.file.Path;
import java.time.Instant;


import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.Library;
import com.redtact.live.library.LibraryActions;
import com.redtact.live.library.SchematicsBrowser;
import com.redtact.live.store.Times;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 保管庫の外 (schematics の下のほかのフォルダ) の回路のファイルの 1 行 (#60): 名前・形式・ファイル名・大きさ・更新日時と
 * 「配置」「投稿」(名前「redtact に投稿」はツールチップ。#77)。保管庫 ({@code schematics/redtact/}) の索引に無いファイルは「配置」だけ (redtact から取ってきたものなので)。
 * 保管庫タブでは行を押すとプレビューに選ぶ (#70。{@link #selectable})。検索の結果では 2 行目に入っているフォルダを出す。
 */
public final class FileCard extends SelectableCard {
	private final SchematicsBrowser.Item item;
	private final RButton place;
	private final @Nullable RButton post;

	public FileCard(SchematicsBrowser.Item item, boolean postable) {
		this.item = item;
		this.place = add(RButton.of(font(), 0, 0, Tr.t("配置"), RButton.Variant.SECONDARY, b -> LibraryActions.place(item.path()))
				.tooltip(Tr.t("Litematica の配置を足元に作る (同じファイルの配置があれば足元へ置き直す)")));
		this.place.disabledReason(LibraryActions.placeUnavailableReason(item.path()));

		if (postable) {
			// 字は「投稿」だけにしてファイル名の幅を空け、「redtact に投稿」はツールチップの先頭に出す (#77)
			this.post = add(RButton.of(font(), 0, 0, Tr.t(LibraryCard.POST_LABEL), RButton.Variant.PRIMARY, b -> LibraryActions.post(item.path()))
					.fullName(Tr.t(LibraryCard.POST_NAME))
					.tooltip(Tr.t("redtact タブの左に保管庫を出して投稿の画面を開く。このファイルを右のページのファイルの欄へドラッグして入れる"
							+ " (MCEF Modern が無ければ外のブラウザで投稿の画面とこのフォルダを開く)")));
			this.post.disabledReason(LibraryActions.postUnavailableReason(item.path()));
		} else {
			this.post = null;
		}
	}

	public SchematicsBrowser.Item item() {
		return item;
	}

	@Override
	public Path file() {
		return item.path().toAbsolutePath().normalize();
	}

	public RButton placeButton() {
		return place;
	}

	public @Nullable RButton postButton() {
		return post;
	}

	@Override
	public int height() {
		return TWO_LINES;
	}

	@Override
	protected void extractCard(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
		extractSelection(g);
		int left = post == null ? layoutRight(cardX() + cardW() - 5, buttonY(), place) : layoutRight(cardX() + cardW() - 5, buttonY(), post, place);
		int x = cardX() + 5;
		String ext = item.extension();
		String stem = ext.isEmpty() ? item.name() : item.name().substring(0, item.name().length() - ext.length() - 1);
		int end = title(g, x, stem, left - 4, mouseX, mouseY);
		badgeAfter(g, end, left - 4, ext.isEmpty() ? "?" : ext, Library.isPlaceable(ext) ? Draw.Tone.OK : Draw.Tone.PLAIN);
		String where = location() != null ? location() : item.name();
		String sep = Tr.t("・");
		info(g, x, where + sep + sizeText(item.bytes()) + sep + Times.local(Instant.ofEpochMilli(item.modifiedMillis())), left - 4, mouseX, mouseY);
	}
}
