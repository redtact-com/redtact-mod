package com.redtact.live.config;

import com.google.gson.JsonElement;

/**
 * 設定の 1 項目。設定ファイル ({@code config/redtact-live.json}) のキーは {@link #name()}。
 * 画面の表示名と説明は言語ファイルの {@code redtact-live.config.<group>.name.<name>} と
 * {@code redtact-live.config.<group>.comment.<name>} (malilib の頃と同じキー)。MC に依存しない。
 */
public abstract class ConfigOption {
	private final String name;

	protected ConfigOption(String name) {
		this.name = name;
	}

	/** 設定ファイルのキー。 */
	public String name() {
		return name;
	}

	/** 設定ファイルに書く値。 */
	public abstract JsonElement toJson();

	/**
	 * 設定ファイルの値を読む。形が合わなければ既定のまま (読めない値で設定を壊さない)。
	 *
	 * @return 読めたら true
	 */
	public abstract boolean fromJson(JsonElement element);

	public abstract boolean isDefault();

	public abstract void resetToDefault();
}
