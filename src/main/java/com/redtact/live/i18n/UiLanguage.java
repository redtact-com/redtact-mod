package com.redtact.live.i18n;

import com.redtact.live.config.ConfigChoice;

/** MOD の画面の言語 (#79。設定の「言語」)。自動はマイクラの言語が日本語なら日本語、ほかは英語。 */
public enum UiLanguage implements ConfigChoice.Choice {
	AUTO("auto"),
	JA("ja"),
	EN("en");

	private final String storageName;

	UiLanguage(String storageName) {
		this.storageName = storageName;
	}

	@Override
	public String storageName() {
		return storageName;
	}

	/** 画面に出す名前。日本語と English はその言語で書く (どちらの言語の画面でも見つけられるように)。 */
	@Override
	public String label() {
		return switch (this) {
			case AUTO -> Tr.t("自動 (ゲームに合わせる)");
			case JA -> Tr.raw("日本語");
			case EN -> "English";
		};
	}
}
