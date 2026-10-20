package com.redtact.live.structure.bedrock;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redtact.live.litematic.BlockStateEntry;
import org.jspecify.annotations.Nullable;

/**
 * 統合版のブロック名と状態を Java 版 (1.21) のブロック状態にする (#70)。mc-nbt-converter の Go 版 (7f17b26) の
 * {@code pkg/mapping/mapping.go} の MapBlock を写したもの (MIT。{@code assets/redtact-live/bedrock/NOTICE.md})。
 * 表は同じ JSON を {@link #DIR} から読む。
 *
 * <p>名前は 4 段で引く: aliases (古い名前を直す) → overrides → chunker (どちらも flatten → names の順) → fallbacks
 * (どれにも無ければ同じ名前のまま)。状態は state-rules.json の順 (common.keyAliases → 名前 → common.ops →
 * 名前の合う最初の rule → common.dropKeys) で直す。表は初めて使うときに 1 度だけ読み、あとは変えない (どのスレッドからも使える)。
 */
final class BlockMapper {
	static final String DIR = "/assets/redtact-live/bedrock/";

	private static volatile @Nullable BlockMapper instance;

	/** names と flatten。flatten は状態のキー → 値 → Java 版の名前で、キーは JSON の並びで見る (Go の FlattenRules と同じ)。 */
	private record Table(Map<String, String> names, Map<String, Map<String, Map<String, String>>> flatten) {
	}

	private record Rule(String match, List<Op> ops) {
	}

	private final Map<String, String> aliases;
	private final Table overrides;
	private final Table chunker;
	private final String defaultBlock;
	private final boolean identityFallback;
	private final boolean stripOnFallback;
	private final Map<String, String> keyAliases;
	private final List<Op> commonOps;
	private final List<String> dropKeys;
	private final List<Rule> rules;
	private final Set<String> redstoneConnectables;

	private BlockMapper(JsonObject aliases, JsonObject overrides, JsonObject chunker, JsonObject fallbacks, JsonObject stateRules) {
		this.aliases = strings(aliases.getAsJsonObject("bedrockAliases"));
		this.overrides = table(overrides);
		this.chunker = table(chunker);
		this.defaultBlock = fallbacks.get("defaultBlock").getAsString();
		this.identityFallback = fallbacks.get("useIdentityFallback").getAsBoolean();
		this.stripOnFallback = fallbacks.get("stripPropertiesOnFallback").getAsBoolean();
		JsonObject common = stateRules.getAsJsonObject("common");
		this.keyAliases = strings(common.getAsJsonObject("keyAliases"));
		this.commonOps = ops(common.getAsJsonArray("ops"));
		this.dropKeys = new ArrayList<>();
		common.getAsJsonArray("dropKeys").forEach(e -> dropKeys.add(e.getAsString()));
		this.rules = new ArrayList<>();
		stateRules.getAsJsonArray("rules").forEach(e -> rules.add(new Rule(e.getAsJsonObject().get("match").getAsString(),
				ops(e.getAsJsonObject().getAsJsonArray("ops")))));
		this.redstoneConnectables = new HashSet<>();
		chunker.getAsJsonArray("redstoneConnectables").forEach(e -> redstoneConnectables.add(e.getAsString()));
	}

	/** 表 (初めてのときに読む。読めなければ投げ、次はまた読みにいく)。 */
	static BlockMapper get() {
		BlockMapper m = instance;

		if (m == null) {
			synchronized (BlockMapper.class) {
				m = instance;

				if (m == null) {
					m = new BlockMapper(json("aliases.json"), json("overrides.json"), json("chunker-mappings.json"), json("fallbacks.json"),
							json("state-rules.json"));
					instance = m;
				}
			}
		}

		return m;
	}

	/** 赤石の線がつながるブロック (chunker-mappings.json の redstoneConnectables)。 */
	Set<String> redstoneConnectables() {
		return redstoneConnectables;
	}

	/** Go の MapBlock。states の値は {@link LittleEndianNbt} の形 (字にするのは {@link #stateValue})。 */
	BlockStateEntry map(String bedrockName, Map<String, ?> states) {
		Map<String, String> props = new HashMap<>();
		states.forEach((k, v) -> props.put(k, stateValue(v)));

		// 1. 名前空間付きのキーを直す
		keyAliases.forEach((ns, local) -> {
			String v = props.get(ns);

			if (v != null) {
				props.put(local, v);
				props.remove(ns);
			}
		});

		// 2. Java 版の名前 (4 段)。rule は aliases を当てた統合版の名前で選ぶ
		String matchName = aliases.getOrDefault(bedrockName, bedrockName);
		String name = resolveName(bedrockName, props);
		// 3. 共通の手順
		name = applyOps(commonOps, props, name);

		// 4. 名前の合う最初の rule
		for (Rule rule : rules) {
			if (wildcardMatch(rule.match(), matchName)) {
				name = applyOps(rule.ops(), props, name);
				break;
			}
		}

		// 5. 要らないキーを落とす
		props.keySet().removeIf(k -> {
			for (String pattern : dropKeys) {
				if (wildcardMatch(pattern, k)) {
					return true;
				}
			}

			return false;
		});

		return BlockStateEntry.of(name, props);
	}

	/** Go の resolveJavaName。flatten で当たった状態のキーは props から消す。 */
	private String resolveName(String bedrockName, Map<String, String> props) {
		String name = aliases.getOrDefault(bedrockName, bedrockName);

		for (Table t : List.of(overrides, chunker)) {
			String flat = lookupFlatten(t.flatten(), name, props);

			if (flat != null) {
				return flat;
			}

			String named = t.names().get(name);

			if (named != null) {
				return named;
			}
		}

		if (identityFallback) {
			if (stripOnFallback) {
				props.clear();
			}

			return "minecraft:" + (name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name);
		}

		return defaultBlock;
	}

	private static @Nullable String lookupFlatten(Map<String, Map<String, Map<String, String>>> flatten, String name, Map<String, String> props) {
		Map<String, Map<String, String>> byKey = flatten.get(name);

		if (byKey == null) {
			return null;
		}

		for (Map.Entry<String, Map<String, String>> e : byKey.entrySet()) {
			String val = props.get(e.getKey());
			String resolved = val == null ? null : e.getValue().get(val);

			if (resolved != null) {
				props.remove(e.getKey());
				return resolved;
			}
		}

		return null;
	}

	private static String applyOps(List<Op> ops, Map<String, String> props, String name) {
		for (Op op : ops) {
			name = op.apply(props, name);
		}

		return name;
	}

	/** {@code *} がどこにでも何字でも当たる (ほかの記号は無い)。Go の wildcardMatch と同じ。 */
	static boolean wildcardMatch(String pattern, String name) {
		if (pattern.indexOf('*') < 0) {
			return pattern.equals(name);
		}

		String[] parts = pattern.split("\\*", -1);
		int idx = 0;

		for (int i = 0; i < parts.length; i++) {
			String seg = parts[i];

			if (seg.isEmpty()) {
				continue;
			}

			if (i == 0) {
				if (!name.startsWith(seg)) {
					return false;
				}

				idx = seg.length();
			} else if (i == parts.length - 1) {
				return name.substring(idx).endsWith(seg);
			} else {
				int found = name.indexOf(seg, idx);

				if (found < 0) {
					return false;
				}

				idx = found + seg.length();
			}
		}

		return true;
	}

	/**
	 * 状態の値を字にする (Go の parseString。gophertunnel は Byte を符号なしの byte、Float を float32 で読み、
	 * 数でも字でもない値は {@code %v} で書く)。
	 */
	static String stateValue(Object v) {
		return switch (v) {
			case String s -> s;
			case Byte b -> Integer.toString(b & 0xFF);
			case Short s -> s.toString();
			case Integer i -> i.toString();
			case Long l -> l.toString();
			case Float f -> goFloat(f, true);
			case Double d -> goFloat(d, false);
			default -> goValue(v);
		};
	}

	/** Go の {@code %v} (配列は {@code [a b]}、Compound はキー順の {@code map[k:v]})。 */
	private static String goValue(Object v) {
		StringJoiner sj = new StringJoiner(" ", "[", "]");

		switch (v) {
			case byte[] a -> {
				for (byte b : a) {
					sj.add(Integer.toString(b & 0xFF));
				}
			}
			case int[] a -> {
				for (int i : a) {
					sj.add(Integer.toString(i));
				}
			}
			case LittleEndianNbt.IntList l -> {
				return goValue(l.values());
			}
			case long[] a -> {
				for (long l : a) {
					sj.add(Long.toString(l));
				}
			}
			case List<?> l -> {
				for (Object e : l) {
					sj.add(stateValue(e));
				}
			}
			case Map<?, ?> m -> {
				StringJoiner mj = new StringJoiner(" ", "map[", "]");
				new TreeMap<>(m).forEach((k, e) -> mj.add(k + ":" + stateValue(e)));
				return mj.toString();
			}
			default -> {
				return String.valueOf(v);
			}
		}

		return sj.toString();
	}

	/** Go の {@code %g} / {@code %v} (戻せるいちばん短い字。指数が -4 より小さいか 6 以上なら {@code 1e+06} の形)。 */
	static String goFloat(double v, boolean float32) {
		if (Double.isNaN(v)) {
			return "NaN";
		}

		if (Double.isInfinite(v)) {
			return v > 0 ? "+Inf" : "-Inf";
		}

		if (v == 0) {
			return 1 / v < 0 ? "-0" : "0";
		}

		BigDecimal d = new BigDecimal(float32 ? Float.toString((float) v) : Double.toString(v)).stripTrailingZeros();

		// Java の toString は最低 2 桁 (Double.MIN_VALUE は 4.9E-324)。1 桁で戻せるなら Go と同じく 1 桁 (5e-324)
		if (d.precision() == 2) {
			BigDecimal one = new BigDecimal(v).round(new MathContext(1, RoundingMode.HALF_EVEN));

			if (float32 ? Float.parseFloat(one.toString()) == (float) v : Double.parseDouble(one.toString()) == v) {
				d = one.stripTrailingZeros();
			}
		}

		String digits = d.unscaledValue().abs().toString();
		int exp = digits.length() - 1 - d.scale();
		StringBuilder sb = new StringBuilder(v < 0 ? "-" : "");

		if (exp < -4 || exp >= 6) {
			sb.append(digits.charAt(0));

			if (digits.length() > 1) {
				sb.append('.').append(digits, 1, digits.length());
			}

			int a = Math.abs(exp);
			return sb.append('e').append(exp < 0 ? '-' : '+').append(a < 10 ? "0" : "").append(a).toString();
		}

		if (exp < 0) {
			return sb.append("0.").append("0".repeat(-exp - 1)).append(digits).toString();
		}

		if (exp + 1 >= digits.length()) {
			return sb.append(digits).append("0".repeat(exp + 1 - digits.length())).toString();
		}

		return sb.append(digits, 0, exp + 1).append('.').append(digits, exp + 1, digits.length()).toString();
	}

	// ---- state-rules.json の手順 (Go の stateOp) ----

	/** 1 つの手順。props を直し、(変わったかもしれない) Java 版の名前を返す。 */
	private sealed interface Op permits Rename, MapOp, MapBool, SetAll, SetDefault, Drop, SetName, WallVariant {
		String apply(Map<String, String> props, String name);
	}

	private record Rename(String from, String to) implements Op {
		@Override
		public String apply(Map<String, String> props, String name) {
			String v = props.get(from);

			if (v != null) {
				props.put(to, v);
				props.remove(from);
			}

			return name;
		}
	}

	/** map の値。字なら to に入れ、Compound なら中のキーをそのまま入れる。 */
	private record MapValue(@Nullable String str, @Nullable Map<String, String> obj) {
	}

	private record MapOp(String from, String to, String def, boolean keepUnmapped, Map<String, MapValue> values) implements Op {
		@Override
		public String apply(Map<String, String> props, String name) {
			String raw = props.get(from);

			if (raw == null) {
				return name;
			}

			MapValue hit = values.get(raw);

			if (hit != null) {
				props.remove(from);

				if (hit.obj() != null) {
					props.putAll(hit.obj());
				} else if (!to.isEmpty()) {
					props.put(to, hit.str());
				}
			} else if (keepUnmapped) {
				if (!to.isEmpty() && !to.equals(from)) {
					props.put(to, raw);
					props.remove(from);
				}
			} else if (!def.isEmpty() && !to.isEmpty()) {
				props.remove(from);
				props.put(to, def);
			}

			return name;
		}
	}

	private record MapBool(String from, String to, boolean invert) implements Op {
		@Override
		public String apply(Map<String, String> props, String name) {
			String v = props.get(from);

			if (v != null) {
				boolean b = (v.equals("1") || v.equals("true")) != invert;
				props.remove(from);
				props.put(to, b ? "true" : "false");
			}

			return name;
		}
	}

	private record SetAll(Map<String, String> values) implements Op {
		@Override
		public String apply(Map<String, String> props, String name) {
			props.putAll(values);
			return name;
		}
	}

	private record SetDefault(Map<String, String> values) implements Op {
		@Override
		public String apply(Map<String, String> props, String name) {
			values.forEach(props::putIfAbsent);
			return name;
		}
	}

	private record Drop(List<String> keys) implements Op {
		@Override
		public String apply(Map<String, String> props, String name) {
			keys.forEach(props::remove);
			return name;
		}
	}

	private record SetName(String to) implements Op {
		@Override
		public String apply(Map<String, String> props, String name) {
			return to;
		}
	}

	/** 松明など: 向きが横なら壁に付いた方 (flip なら向きを逆に)、上か無いなら立った方。 */
	private record WallVariant(String from, String wall, String standing, boolean flip) implements Op {
		private static final Map<String, String> FLIP = Map.of("north", "south", "south", "north", "east", "west", "west", "east");

		@Override
		public String apply(Map<String, String> props, String name) {
			String dir = props.remove(from);

			if (dir != null && !dir.equals("top") && !dir.equals("unknown") && !dir.isEmpty()) {
				props.put("facing", flip ? FLIP.getOrDefault(dir, dir) : dir);
				return wall;
			}

			return standing;
		}
	}

	// ---- JSON を読む ----

	private static JsonObject json(String file) {
		try (InputStream in = BlockMapper.class.getResourceAsStream(DIR + file)) {
			if (in == null) {
				throw new IllegalStateException("missing resource " + DIR + file);
			}

			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(r).getAsJsonObject();
			}
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + DIR + file, e);
		}
	}

	private static Table table(JsonObject o) {
		Map<String, Map<String, Map<String, String>>> flatten = new HashMap<>();

		for (Map.Entry<String, JsonElement> block : o.getAsJsonObject("flatten").entrySet()) {
			Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
			block.getValue().getAsJsonObject().entrySet().forEach(e -> byKey.put(e.getKey(), strings(e.getValue().getAsJsonObject())));
			flatten.put(block.getKey(), byKey);
		}

		return new Table(strings(o.getAsJsonObject("names")), flatten);
	}

	private static Map<String, String> strings(JsonObject o) {
		Map<String, String> out = new LinkedHashMap<>();
		o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		return out;
	}

	private static List<Op> ops(JsonArray a) {
		List<Op> out = new ArrayList<>();

		for (JsonElement e : a) {
			Op op = op(e.getAsJsonObject());

			if (op != null) {
				out.add(op);
			}
		}

		return out;
	}

	/** 1 つの手順。キーが複数あれば Go の applyOp の switch と同じ順で 1 つだけ使う (どれも無ければ何もしない)。 */
	private static @Nullable Op op(JsonObject o) {
		if (has(o, "rename")) {
			JsonObject r = o.getAsJsonObject("rename");
			return new Rename(str(r, "from"), str(r, "to"));
		}

		if (has(o, "map")) {
			JsonObject m = o.getAsJsonObject("map");
			Map<String, MapValue> values = new HashMap<>();

			if (has(m, "values")) {
				m.getAsJsonObject("values").entrySet().forEach(e -> values.put(e.getKey(), e.getValue().isJsonPrimitive()
						? new MapValue(e.getValue().getAsString(), null)
						: new MapValue(null, e.getValue().isJsonObject() ? strings(e.getValue().getAsJsonObject()) : Map.of())));
			}

			return new MapOp(str(m, "from"), str(m, "to"), str(m, "default"), has(m, "keepUnmapped") && m.get("keepUnmapped").getAsBoolean(),
					values);
		}

		if (has(o, "mapBool")) {
			JsonObject m = o.getAsJsonObject("mapBool");
			return new MapBool(str(m, "from"), str(m, "to"), has(m, "invert") && m.get("invert").getAsBoolean());
		}

		if (has(o, "set")) {
			return new SetAll(strings(o.getAsJsonObject("set")));
		}

		if (has(o, "setDefault")) {
			return new SetDefault(strings(o.getAsJsonObject("setDefault")));
		}

		if (has(o, "drop")) {
			List<String> keys = new ArrayList<>();
			o.getAsJsonArray("drop").forEach(e -> keys.add(e.getAsString()));
			return new Drop(keys);
		}

		if (!str(o, "setName").isEmpty()) {
			return new SetName(str(o, "setName"));
		}

		if (has(o, "wallVariant")) {
			JsonObject w = o.getAsJsonObject("wallVariant");
			return new WallVariant(str(w, "from"), str(w, "wall"), str(w, "standing"), has(w, "flip") && w.get("flip").getAsBoolean());
		}

		return null;
	}

	private static boolean has(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull();
	}

	/** 字のキー (無ければ空。Go の string の欄と同じ)。 */
	private static String str(JsonObject o, String key) {
		return has(o, key) ? o.get(key).getAsString() : "";
	}
}
