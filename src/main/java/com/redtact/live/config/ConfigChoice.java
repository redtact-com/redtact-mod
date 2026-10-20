package com.redtact.live.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** 決まった選択肢から 1 つを選ぶ設定。設定ファイルには {@link Choice#storageName()} を書く。 */
public final class ConfigChoice<E extends Enum<E> & ConfigChoice.Choice> extends ConfigOption {
	/** 選択肢。 */
	public interface Choice {
		/** 設定ファイルに書く名前 (malilib の頃と同じ)。 */
		String storageName();

		/** 画面に出す名前。 */
		String label();
	}

	private final E defaultValue;
	private final E[] values;
	private volatile E value;

	public ConfigChoice(String name, E defaultValue) {
		super(name);
		this.defaultValue = defaultValue;
		this.values = defaultValue.getDeclaringClass().getEnumConstants();
		this.value = defaultValue;
	}

	public E getOptionListValue() {
		return value;
	}

	public void setOptionListValue(E value) {
		this.value = value;
	}

	/** 次 (step = 1) か前 (step = -1) の選択肢にする。 */
	public void cycle(int step) {
		value = values[Math.floorMod(value.ordinal() + step, values.length)];
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value.storageName());
	}

	@Override
	public boolean fromJson(JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return false;
		}

		String s = element.getAsString();

		for (E e : values) {
			if (e.storageName().equalsIgnoreCase(s)) {
				value = e;
				return true;
			}
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
