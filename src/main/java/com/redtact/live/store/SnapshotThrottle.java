package com.redtact.live.store;

/**
 * ローカルの litematic を保存するかの判定 (MC に依存しない。時刻は呼ぶ側が渡す)。
 *
 * <p>手動の push は毎回保存する。自動の push は、前回の保存から intervalMs 以上たったときだけ保存し、
 * 保存しなかった最新の版は覚えておく ({@link #drainUnsaved} で終了・切断のときに保存する)。
 *
 * @param <T> 保存するもの (版と中身)
 */
public final class SnapshotThrottle<T> {
	private long lastSavedAt = Long.MIN_VALUE;
	private T unsaved;

	/**
	 * push が 1 回成功した。
	 *
	 * @param always 手動の push なら true (毎回保存)
	 * @return いま保存するなら true。false なら snapshot を「保存していない最新」として覚える
	 */
	public synchronized boolean offer(T snapshot, boolean always, long now, long intervalMs) {
		if (always || lastSavedAt == Long.MIN_VALUE || now - lastSavedAt >= intervalMs) {
			lastSavedAt = now;
			unsaved = null; // これより古い未保存の版は要らない
			return true;
		}

		unsaved = snapshot;
		return false;
	}

	/** 保存していない最新の版を取り出す (無ければ null)。取り出したら保存したものとして扱う。 */
	public synchronized T drainUnsaved(long now) {
		T t = unsaved;

		if (t != null) {
			unsaved = null;
			lastSavedAt = now;
		}

		return t;
	}

	public synchronized boolean hasUnsaved() {
		return unsaved != null;
	}
}
