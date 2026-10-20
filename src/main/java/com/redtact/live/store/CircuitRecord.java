package com.redtact.live.store;

import java.util.ArrayList;
import java.util.List;

import com.redtact.live.selection.Box;

/** {@code circuits/<circuitId>/circuit.json}。 */
public final class CircuitRecord {
	public String circuitId;
	public String name;
	public String createdAt;
	public String updatedAt;
	/** 範囲のあるワールド (singleplayer:&lt;セーブ名&gt; / server:&lt;アドレス&gt;)。 */
	public String world;
	/** 例: minecraft:overworld */
	public String dimension;
	public Region region;
	public List<SessionRecord> sessions = new ArrayList<>();
	/** ここまでのコメントは読んだ (メニューの「コメント」で見たとき)。id は時刻順に並ぶ。 */
	public String lastReadCommentId;

	public static final class Region {
		public int[] min;
		public int[] max;

		public static Region of(Box box) {
			Region r = new Region();
			r.min = new int[] {box.minX(), box.minY(), box.minZ()};
			r.max = new int[] {box.maxX(), box.maxY(), box.maxZ()};
			return r;
		}

		public Box toBox() {
			if (min == null || max == null || min.length != 3 || max.length != 3) {
				return null;
			}

			return Box.of(min[0], min[1], min[2], max[0], max[1], max[2]);
		}
	}

	public static final class SessionRecord {
		public String sessionId;
		public String title;
		public String shareUrl;
		public String serverUrl;
		public String startedAt;
		/** null なら開催中 (少なくともこちらは終了を知らない)。 */
		public String endedAt;
		/** host_closed / expired / not_found / unauthorized など */
		public String endReason;
		/** 再接続用。終了したら消す。 */
		public String hostToken;
		public int lastVersion;
	}

	public Box box() {
		return region == null ? null : region.toBox();
	}

	/** 終了を記録していない最後のセッション。 */
	public SessionRecord openSession() {
		if (sessions == null) {
			return null;
		}

		for (int i = sessions.size() - 1; i >= 0; i--) {
			SessionRecord s = sessions.get(i);

			if (s.endedAt == null) {
				return s;
			}
		}

		return null;
	}

	public SessionRecord session(String sessionId) {
		if (sessions == null) {
			return null;
		}

		for (SessionRecord s : sessions) {
			if (sessionId.equals(s.sessionId)) {
				return s;
			}
		}

		return null;
	}
}
