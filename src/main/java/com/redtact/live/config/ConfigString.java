package com.redtact.live.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** 文字列の設定。 */
public final class ConfigString extends ConfigOption {
	private final String defaultValue;
	private volatile String value;

	public ConfigString(String name, String defaultValue) {
		super(name);
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String getStringValue() {
		return value;
	}

	public void setValueFromString(String value) {
		this.value = value == null ? "" : value;
	}

	public String getDefaultStringValue() {
		return defaultValue;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean fromJson(JsonElement element) {
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			value = element.getAsString();
			return true;
		}

		return false;
	}

	@Override
	public boolean isDefault() {
		return value.equals(defaultValue);
	}

	@Override
	public void resetToDefault() {
		value = defaultValue;
	}
}
