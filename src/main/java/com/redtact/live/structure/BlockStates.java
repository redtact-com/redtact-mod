package com.redtact.live.structure;

import java.util.Map;
import java.util.TreeMap;

import com.redtact.live.i18n.Tr;
import com.redtact.live.litematic.BlockStateEntry;
import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * ブロック状態の文字列 ({@code 名前[キー=値,...]}) とパレットのタグ (#70)。タグは 26.x の前の {@code {Name, Properties}} と、
 * 後の {@code {id, properties}} (BlockStateFieldNamesFix、版 {@value #ID_FIELD_VERSION}) のどちらも読む。
 */
public final class BlockStates {
	/** パレットのタグの欄の名前が Name/Properties から id/properties に変わった版。 */
	public static final int ID_FIELD_VERSION = 5006;

	private BlockStates() {
	}

	/**
	 * 文字列を分ける。名前空間が無ければ {@code minecraft:} を付ける。
	 *
	 * @throws IllegalArgumentException 形が違う
	 */
	public static BlockStateEntry parse(String text) {
		String s = text.strip();
		int open = s.indexOf('[');
		String name = open < 0 ? s : s.substring(0, open);
		Map<String, String> props = new TreeMap<>();

		if (open >= 0) {
			if (!s.endsWith("]")) {
				throw new IllegalArgumentException(Tr.f("閉じる ] がありません: {0}", text));
			}

			String body = s.substring(open + 1, s.length() - 1);

			for (String pair : body.split(",")) {
				if (pair.isBlank()) {
					continue;
				}

				int eq = pair.indexOf('=');

				if (eq <= 0) {
					throw new IllegalArgumentException(Tr.f("キー=値 の形ではありません: {0}", text));
				}

				props.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
			}
		}

		return BlockStateEntry.of(withNamespace(name.strip()), props);
	}

	/** パレットのタグを読む。名前が無ければ null。 */
	public static @Nullable BlockStateEntry fromTag(CompoundTag tag) {
		String name = tag.getString("id").or(() -> tag.getString("Name")).orElse("");

		if (name.isBlank()) {
			return null;
		}

		Map<String, String> props = new TreeMap<>();
		CompoundTag p = tag.getCompound("properties").or(() -> tag.getCompound("Properties")).orElse(null);

		if (p != null) {
			for (String key : p.keySet()) {
				String v = value(p.get(key));

				if (v != null) {
					props.put(key, v);
				}
			}
		}

		return BlockStateEntry.of(withNamespace(name), props);
	}

	/** その版のパレットのタグの形にする ({@value #ID_FIELD_VERSION} より前は Name/Properties)。 */
	public static CompoundTag toTag(BlockStateEntry e, int dataVersion) {
		boolean ids = dataVersion >= ID_FIELD_VERSION;
		CompoundTag tag = new CompoundTag();
		tag.putString(ids ? "id" : "Name", e.name());

		if (!e.properties().isEmpty()) {
			CompoundTag p = new CompoundTag();
			e.properties().forEach(p::putString);
			tag.put(ids ? "properties" : "Properties", p);
		}

		return tag;
	}

	private static @Nullable String value(@Nullable Tag t) {
		if (t instanceof StringTag(String v)) {
			return v;
		}

		if (t instanceof NumericTag n) {
			return n.asNumber().map(Number::toString).orElse(null);
		}

		return null;
	}

	private static String withNamespace(String name) {
		return name.indexOf(':') < 0 ? "minecraft:" + name : name;
	}
}
