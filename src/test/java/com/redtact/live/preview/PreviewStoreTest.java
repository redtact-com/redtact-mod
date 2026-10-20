package com.redtact.live.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** プレビューを取りにいくかの判定 (#34・一覧のサムネの間引きは #56)。 */
class PreviewStoreTest {
	private static final long NOW = 1_000_000;
	private static final long THUMB = PreviewStore.THUMB_INTERVAL_MS;

	@Test
	void fetchesWhenMissing() {
		assertTrue(PreviewStore.shouldFetch(null, 3, NOW, THUMB));
	}

	@Test
	void keepsReadyVersion() {
		assertFalse(PreviewStore.shouldFetch(3, PreviewStore.Status.READY, true, NOW - 100_000, 3, NOW, 0));
		assertFalse(PreviewStore.shouldFetch(4, PreviewStore.Status.READY, true, NOW - 100_000, 3, NOW, 0), "サーバの版の方が新しい");
	}

	@Test
	void detailRefetchesNewVersionAtOnce() {
		assertTrue(PreviewStore.shouldFetch(3, PreviewStore.Status.READY, true, NOW - 1, 4, NOW, 0));
	}

	@Test
	void thumbnailWaitsBeforeRefetchingNewVersion() {
		assertFalse(PreviewStore.shouldFetch(3, PreviewStore.Status.READY, true, NOW - (THUMB - 1), 4, NOW, THUMB), "前に取れてから間がない");
		assertTrue(PreviewStore.shouldFetch(3, PreviewStore.Status.READY, true, NOW - THUMB, 4, NOW, THUMB), "間が空いたら取り直す");
		assertTrue(PreviewStore.shouldFetch(0, PreviewStore.Status.LOADING, false, NOW, 4, NOW, THUMB), "絵が無ければ待たない");
	}

	@Test
	void retriesFailureAfterAWhile() {
		assertFalse(PreviewStore.shouldFetch(4, PreviewStore.Status.ERROR, false, NOW - 1_000, 4, NOW, 0));
		assertTrue(PreviewStore.shouldFetch(4, PreviewStore.Status.ERROR, false, NOW - 10_000, 4, NOW, 0));
		assertFalse(PreviewStore.shouldFetch(4, PreviewStore.Status.ERROR, false, NOW - 1_000, 5, NOW, 0), "版が進んでも失敗したばかりなら待つ");
	}
}
