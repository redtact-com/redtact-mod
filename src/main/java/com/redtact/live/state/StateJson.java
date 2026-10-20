package com.redtact.live.state;

import java.util.List;

/** protocol.md の CircuitState / CircuitDiff の JSON の形 (Gson 用)。null のフィールドは書き出さない。 */
public final class StateJson {
	private StateJson() {
	}

	public static final class State {
		/** サーバ→閲覧者 (と GET) のときだけ付く。 */
		public Integer version;
		public int[] size;
		public List<String> palette;
		/** uint16 リトルエンディアン × 体積 の base64。 */
		public String blocks;
		public List<BlockEntity> blockEntities;
	}

	public static final class Diff {
		public int baseVersion;
		public Integer version;
		public List<String> palette;
		/** [添字, この差分の palette の添字, ...] */
		public int[] changes;
		public List<BlockEntity> blockEntities;
		public int[] removedBlockEntities;
	}

	public static final class BlockEntity {
		public int i;
		/** 非圧縮のバイナリ NBT の base64。 */
		public String nbt;

		public BlockEntity() {
		}

		public BlockEntity(int i, String nbt) {
			this.i = i;
			this.nbt = nbt;
		}
	}
}
