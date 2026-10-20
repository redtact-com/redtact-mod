package com.redtact.live.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** on / off の設定。 */
public final class ConfigBoolean extends ConfigOption {
	private final boolean defaultValue;
	private volatile boolean value;

	public ConfigBoolean(String name, boolean defaultValue) {
		super(name);
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public boolean getBooleanValue() {
		return value;
	}

	public void setBooleanValue(boolean value) {
		this.value = value;
	}

	public boolean getDefaultBooleanValue() {
		return defaultValue;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean fromJson(JsonElement element) {
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
			value = element.getAsBoolean();
			return true;
		}

		return false;
	}

	@Override
	public boolean isDefault() {
		return value == defaultValue;
	}

	@Override
	public void resetToDefault() {
		value = defaultValue;
	}
}
