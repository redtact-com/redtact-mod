package com.redtact.live.preview;

import java.util.List;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.redtact.live.preview.mesh.CircuitGrid;
import com.redtact.live.state.CircuitState;
import org.jspecify.annotations.Nullable;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 描く用意のできたプレビュー: 見えるブロック ({@link PreviewModel}) と、パレットの文字列をこのゲームのブロック状態にしたもの。
 * 知らないブロック (別の版・MOD のブロック) は描かない。知らない状態だけなら除いて描く ({@link #parse})。
 */
public final class PreviewCircuit {
	private final String sessionId;
	private final int version;
	private final PreviewModel model;
	private final @Nullable BlockState[] states;
	private final int unknown;
	/** 全画面のプレビュー (#102) で形を組み立てるマス目 (パレットの番号を詰めて持つ)。 */
	private final CircuitGrid grid;

	private PreviewCircuit(String sessionId, int version, PreviewModel model, @Nullable BlockState[] states, int unknown, CircuitGrid grid) {
		this.sessionId = sessionId;
		this.version = version;
		this.model = model;
		this.states = states;
		this.unknown = unknown;
		this.grid = grid;
	}

	/** 通信のスレッドから呼んでよい (登録済みのブロックを引くだけ)。 */
	public static PreviewCircuit resolve(String sessionId, int version, CircuitState state) {
		List<String> palette = state.palette();
		@Nullable BlockState[] states = new BlockState[palette.size()];
		boolean[] air = new boolean[palette.size()];
		boolean[] occluding = new boolean[palette.size()];
		int unknown = 0;

		for (int p = 0; p < palette.size(); p++) {
			String s = palette.get(p);

			if (CircuitState.isAir(s)) {
				air[p] = true;
				continue;
			}

			BlockState b = parse(s);

			if (b == null) {
				air[p] = true; // 知らないブロックは空気として扱う (描かない・隣も隠さない)
				unknown++;
				continue;
			}

			states[p] = b;
			occluding[p] = b.isSolidRender();
		}

		PreviewModel model = PreviewModel.build(state, p -> air[p], p -> occluding[p]);
		return new PreviewCircuit(sessionId, version, model, states, unknown, new CircuitGrid(state.sizeX(), state.sizeY(), state.sizeZ(), states, state.blocks()));
	}

	/**
	 * 状態の文字列をこのゲームのブロックにする。ブロックは知っているが知らない状態やおかしな値がある (別の版のファイル・統合版から直したもの。
	 * #70) ときは、その状態だけ除いて既定の値にする。知らないブロックなら null。
	 */
	static @Nullable BlockState parse(String s) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
		} catch (CommandSyntaxException | RuntimeException e) {
			return lenient(s);
		}
	}

	private static @Nullable BlockState lenient(String s) {
		int open = s.indexOf('[');
		Identifier id = Identifier.tryParse(open < 0 ? s : s.substring(0, open));

		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			return null;
		}

		Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);

		if (block == null) {
			return null;
		}

		BlockState state = block.defaultBlockState();

		if (open >= 0 && s.endsWith("]")) {
			for (String pair : s.substring(open + 1, s.length() - 1).split(",")) {
				int eq = pair.indexOf('=');
				Property<?> property = eq <= 0 ? null : block.getStateDefinition().getProperty(pair.substring(0, eq).strip());

				if (property != null) {
					state = with(state, property, pair.substring(eq + 1).strip());
				}
			}
		}

		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}

	public String sessionId() {
		return sessionId;
	}

	public int version() {
		return version;
	}

	public PreviewModel model() {
		return model;
	}

	public @Nullable BlockState state(int paletteId) {
		return states[paletteId];
	}

	/** 知らないブロックの種類の数。 */
	public int unknown() {
		return unknown;
	}

	/** 全画面のプレビュー (#102) で形を組み立てるマス目 (全部のブロック。空気・知らないブロックは null)。 */
	public CircuitGrid grid() {
		return grid;
	}
}
