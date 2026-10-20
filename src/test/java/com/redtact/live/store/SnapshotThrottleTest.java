package com.redtact.live.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SnapshotThrottleTest {
	static final long FIVE_MIN = 300_000;

	@Test
	void manualAlwaysSaves() {
		SnapshotThrottle<Integer> t = new SnapshotThrottle<>();
		assertTrue(t.offer(1, true, 0, FIVE_MIN));
		assertTrue(t.offer(2, true, 10, FIVE_MIN));
		assertFalse(t.hasUnsaved());
	}

	@Test
	void firstAutoPushSavesWhenNothingSavedYet() {
		SnapshotThrottle<Integer> t = new SnapshotThrottle<>();
		assertTrue(t.offer(1, false, 1_000, FIVE_MIN));
	}

	/** 自動の push を短い間隔で何度か起こしても、保存は 1 つ。最新だけ覚えておく。 */
	@Test
	void autoPushesWithinIntervalAreNotSavedButLatestIsKept() {
		SnapshotThrottle<Integer> t = new SnapshotThrottle<>();
		assertTrue(t.offer(1, true, 0, FIVE_MIN)); // open 直後の手動 push
		assertFalse(t.offer(2, false, 3_000, FIVE_MIN));
		assertFalse(t.offer(3, false, 13_000, FIVE_MIN));
		assertFalse(t.offer(4, false, 299_999, FIVE_MIN));
		assertTrue(t.hasUnsaved());
		assertEquals(4, t.drainUnsaved(300_500));
		assertNull(t.drainUnsaved(300_600), "2 回目は空");
	}

	@Test
	void autoPushAfterIntervalSavesAndDropsOlderUnsaved() {
		SnapshotThrottle<Integer> t = new SnapshotThrottle<>();
		t.offer(1, true, 0, FIVE_MIN);
		assertFalse(t.offer(2, false, 60_000, FIVE_MIN));
		assertTrue(t.offer(3, false, 300_000, FIVE_MIN));
		assertFalse(t.hasUnsaved(), "保存した版より古い未保存は要らない");
		assertFalse(t.offer(4, false, 300_001, FIVE_MIN), "保存した時刻から数え直す");
	}

	@Test
	void manualSaveSupersedesUnsaved() {
		SnapshotThrottle<Integer> t = new SnapshotThrottle<>();
		t.offer(1, true, 0, FIVE_MIN);
		t.offer(2, false, 1_000, FIVE_MIN);
		assertTrue(t.offer(3, true, 2_000, FIVE_MIN));
		assertNull(t.drainUnsaved(3_000));
	}

	/** 終了時に保存したら、それも前回の保存として数える。 */
	@Test
	void drainCountsAsSave() {
		SnapshotThrottle<Integer> t = new SnapshotThrottle<>();
		t.offer(1, true, 0, FIVE_MIN);
		t.offer(2, false, 1_000, FIVE_MIN);
		assertEquals(2, t.drainUnsaved(2_000));
		assertFalse(t.offer(3, false, 3_000, FIVE_MIN));
	}

	@Test
	void zeroIntervalSavesEveryPush() {
		SnapshotThrottle<Integer> t = new SnapshotThrottle<>();
		assertTrue(t.offer(1, false, 0, 0));
		assertTrue(t.offer(2, false, 0, 0));
	}
}
