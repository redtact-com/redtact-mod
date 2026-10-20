package com.redtact.live.sync;

/**
 * 自動同期のデバウンス (MC に依存しない。時刻は呼ぶ側が渡す)。
 *
 * <p>変化が quietMs 止まったら、または最初の変化から maxDelayMs たったら送る。
 * 送るときに {@link #take} で未送信の変化を取り出し、失敗したら {@link #restore} で戻す。
 * 全メソッドは任意のスレッドから呼んでよい。
 */
public final class Debouncer {
	private long quietMs;
	private long maxDelayMs;
	/** 未送信の最初の変化の時刻。-1 なら変化なし。 */
	private long firstChangeAt = -1;
	private long lastChangeAt = -1;
	/** これより前には送らない (429・失敗の後の待ち)。 */
	private long notBefore;

	/** 取り出した未送信の変化 (失敗したら restore で戻す)。 */
	public record Pending(long firstChangeAt, long lastChangeAt) {
	}

	public Debouncer(long quietMs, long maxDelayMs) {
		setTimings(quietMs, maxDelayMs);
	}

	public synchronized void setTimings(long quietMs, long maxDelayMs) {
		if (quietMs < 0 || maxDelayMs < quietMs) {
			throw new IllegalArgumentException("need 0 <= quiet <= maxDelay: " + quietMs + ", " + maxDelayMs);
		}

		this.quietMs = quietMs;
		this.maxDelayMs = maxDelayMs;
	}

	public synchronized void markChanged(long now) {
		if (firstChangeAt < 0) {
			firstChangeAt = now;
		}

		lastChangeAt = Math.max(lastChangeAt, now);
	}

	public synchronized boolean isDirty() {
		return firstChangeAt >= 0;
	}

	/** いま送るべきか。 */
	public synchronized boolean due(long now) {
		if (firstChangeAt < 0 || now < notBefore) {
			return false;
		}

		return now - lastChangeAt >= quietMs || now - firstChangeAt >= maxDelayMs;
	}

	/** 未送信の変化を取り出して空にする (送る直前、ワールドを読む前に呼ぶ)。変化が無ければ null。 */
	public synchronized Pending take() {
		if (firstChangeAt < 0) {
			return null;
		}

		Pending p = new Pending(firstChangeAt, lastChangeAt);
		firstChangeAt = -1;
		lastChangeAt = -1;
		return p;
	}

	/** 送れなかった変化を戻し、retryDelayMs は送らない。その後に来た変化とまとめる。 */
	public synchronized void restore(Pending pending, long now, long retryDelayMs) {
		if (pending != null) {
			firstChangeAt = firstChangeAt < 0 ? pending.firstChangeAt() : Math.min(firstChangeAt, pending.firstChangeAt());
			lastChangeAt = Math.max(lastChangeAt, pending.lastChangeAt());
		}

		postpone(now, retryDelayMs);
	}

	/** delayMs のあいだ送らない (変化は残す)。 */
	public synchronized void postpone(long now, long delayMs) {
		notBefore = Math.max(notBefore, now + delayMs);
	}

	public synchronized void clear() {
		firstChangeAt = -1;
		lastChangeAt = -1;
		notBefore = 0;
	}

	/** 表示用: 最後の変化から何 ms たったか (変化なしなら -1)。 */
	public synchronized long sinceLastChange(long now) {
		return firstChangeAt < 0 ? -1 : now - lastChangeAt;
	}
}
