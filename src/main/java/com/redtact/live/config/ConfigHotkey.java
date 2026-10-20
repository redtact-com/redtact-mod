package com.redtact.live.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redtact.live.input.Keybind;

/**
 * ホットキーの設定。設定ファイルには malilib の頃と同じく {@code {"keys": "R,L"}} と書く
 * (malilib が書いた細かい設定 (settings) は読み飛ばす)。
 */
public final class ConfigHotkey extends ConfigOption {
	private final Keybind keybind;

	public ConfigHotkey(String name, String defaultKeys) {
		super(name);
		this.keybind = new Keybind(defaultKeys);
	}

	public Keybind getKeybind() {
		return keybind;
	}

	@Override
	public JsonElement toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("keys", keybind.getStringValue());
		return o;
	}

	@Override
	public boolean fromJson(JsonElement element) {
		if (element == null) {
			return false;
		}

		if (element.isJsonObject()) {
			JsonElement keys = element.getAsJsonObject().get("keys");
			return keys != null && keys.isJsonPrimitive() && keybind.setValueFromString(keys.getAsString());
		}

		// 古い形 (文字列だけ) も読む
		return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && keybind.setValueFromString(element.getAsString());
	}

	@Override
	public boolean isDefault() {
		return keybind.isDefault();
	}

	@Override
	public void resetToDefault() {
		keybind.resetToDefault();
	}
}
