package com.redtact.live.gui;

import java.util.Objects;

import com.redtact.live.RedtactLive;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.Library;
import com.redtact.live.library.LiveLibrary;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.Font;

/** live の開催中の自分の回路の「保管庫へ」(アイコン。#77)「redtact に投稿」(#73)。セッションタブと開催中タブの詳細 (自分のセッションだけ) に出す。 */
final class LiveLibraryButtons {
	private LiveLibraryButtons() {
	}

	static RButton library(Font font, int x, int y) {
		return refresh(RButton.icon(x, y, Icon.DOWNLOAD, Tr.t("保管庫へ"), RButton.Variant.SECONDARY, b -> LiveLibrary.save(false))
				.tooltip(Tr.f("最後に同期した版を .litematic にして保管庫 (schematics/{0}/) に入れる。保管庫タブから配置・redtact に投稿できる", Library.FOLDER)));
	}

	static RButton post(Font font, int x, int y) {
		return refresh(RButton.of(font, x, y, Tr.t("redtact に投稿"), RButton.Variant.PRIMARY, b -> LiveLibrary.save(true))
				.tooltip(Tr.t("最後に同期した版を保管庫に入れ、redtact タブの左に保管庫・右に投稿の画面を開く。左で選ばれたファイルを右のファイルの欄へドラッグして投稿する"
						+ " (MCEF Modern が無ければ外のブラウザで投稿の画面とフォルダを開く)")));
	}

	/** 押せる・押せないをいまの様子に合わせる (変わったときだけ。ツールチップを毎 tick 作り直さない)。 */
	static RButton refresh(@Nullable RButton b) {
		if (b != null) {
			String reason = LiveLibrary.unavailableReason(RedtactLive.get().sessions());

			if (!Objects.equals(reason, b.disabledReason())) {
				b.disabledReason(reason);
			}
		}

		return b;
	}
}
