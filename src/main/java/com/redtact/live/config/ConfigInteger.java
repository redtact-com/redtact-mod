package com.redtact.live.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** 整数の設定。範囲の外の値は端に丸める。 */
public final class ConfigInteger extends ConfigOption {
	private final int defaultValue;
	private final int min;
	private final int max;
	private volatile int value;

	public ConfigInteger(String name, int defaultValue, int min, int max) {
		super(name);
		this.min = min;
		this.max = max;
		this.defaultValue = clamp(defaultValue);
		this.value = this.defaultValue;
	}

	public int getIntegerValue() {
		return value;
	}

	/** 範囲の外なら端に丸めて入れる。 */
	public void setIntegerValue(int value) {
		this.value = clamp(value);
	}

	public int getDefaultIntegerValue() {
		return defaultValue;
	}

	public int getMinIntegerValue() {
		return min;
	}

	public int getMaxIntegerValue() {
		return max;
	}

	private int clamp(int v) {
		return Math.max(min, Math.min(max, v));
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean fromJson(JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return false;
		}

		try {
			long v = element.getAsLong();
			value = (int) Math.max(min, Math.min(max, v));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
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
