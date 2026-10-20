package com.redtact.live.selection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.LocalStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 保存済みの範囲 (#39)。保存済みの回路 ({@code circuit.json} の名前・ワールド・ディメンション・範囲) をそのまま使い、別の保存形式は持たない。
 *
 * <ul>
 * <li>一覧は {@link LocalStore} を書き換えたときだけ読み直す ({@link LocalStore#generation()})</li>
 * <li>「選んだ範囲」: セッションタブのカード・羽根の中クリック・「範囲を保存」で付く。選ぶと選択 (pos1/pos2) をその範囲にする。
 * セッションタブの「開始」はその回路の再開になる</li>
 * <li>外れるのは「新規作成」「選択を解除」・ワールドを抜けたとき・別の回路でセッションを開いたとき。
 * 選んだあとに角を動かしても外さない (開始すると保存済みの範囲をいまの選択で更新する。{@link #pickedChanged()})</li>
 * </ul>
 *
 * クライアントスレッドだけで触る。
 */
public final class SavedRanges {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");

	private final LocalStore store;
	private final Selection selection;
	private List<CircuitRecord> cache = List.of();
	private int loadedGeneration = Integer.MIN_VALUE;
	private @Nullable String pickedId;

	public SavedRanges(LocalStore store, Selection selection) {
		this.store = store;
		this.selection = selection;
	}

	/** 保存済みの回路 (新しい順)。書き換わっていれば読み直す。 */
	public List<CircuitRecord> all() {
		int g = store.generation();

		if (g != loadedGeneration) {
			loadedGeneration = g; // 読めなくても毎フレーム読み直さない

			try {
				cache = List.copyOf(store.listCircuits());
			} catch (IOException e) {
				LOGGER.warn("[redtact-live] 保存済みの範囲を読めません: {}", e.toString());
			}
		}

		return cache;
	}

	/** そのワールドの範囲か (ワールドの無い古い記録は、どのワールドでも選べる)。 */
	public static boolean inWorld(CircuitRecord r, String world) {
		return r.world == null || r.world.equals(world);
	}

	/** このワールド・ディメンションの、範囲のある保存済みの回路 (ワールドに枠を出し、中クリックで選ぶもの)。 */
	public List<CircuitRecord> visible(String world, String dimension) {
		List<CircuitRecord> out = new ArrayList<>();

		for (CircuitRecord r : all()) {
			if (r.box() != null && inWorld(r, world) && dimension.equals(r.dimension)) {
				out.add(r);
			}
		}

		return out;
	}

	public @Nullable String pickedId() {
		return pickedId;
	}

	/** 選んでいる回路 (一覧から消えていれば null)。 */
	public @Nullable CircuitRecord picked() {
		if (pickedId == null) {
			return null;
		}

		for (CircuitRecord r : all()) {
			if (pickedId.equals(r.circuitId)) {
				return r;
			}
		}

		return null;
	}

	/** 選ぶ。選択をその範囲にする (範囲が無ければ印だけ)。 */
	public void pick(CircuitRecord r) {
		pickedId = r.circuitId;
		Box box = r.box();

		if (box != null && r.dimension != null) {
			selection.set(box, r.dimension);
		}
	}

	/** 印だけを付ける (選択は変えない。いまの選択を保存したとき)。 */
	public void mark(String circuitId) {
		pickedId = circuitId;
	}

	public void clearPick() {
		pickedId = null;
	}

	/** 選んでから角を動かしたか (選択が保存済みの範囲と違う)。選んでいなければ false。 */
	public boolean pickedChanged() {
		CircuitRecord r = picked();

		if (r == null) {
			return false;
		}

		Box box = selection.box();
		return box == null || !box.equals(r.box()) || !Objects.equals(selection.dimension(), r.dimension);
	}

	/** 毎 tick。別の回路でセッションを開いたら、選んだ範囲を外す。 */
	public void tick(@Nullable String activeCircuitId) {
		if (pickedId != null && activeCircuitId != null && !pickedId.equals(activeCircuitId)) {
			pickedId = null;
		}
	}
}
