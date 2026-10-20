package com.redtact.live.preview;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import com.redtact.live.state.CircuitState;
import org.jspecify.annotations.Nullable;

/**
 * 「開催中」タブのプレビューの取得と置き場 (#34)。GET /api/sessions/{id}/preview を専用のスレッドで取り、
 * 最近見たセッションのぶんだけ持つ。版が進んだら取り直す (取り直している間は前の版を見せる)。
 * 一覧のカードのサムネ (#56) も同じものを使う。サムネは版が進んでも {@link #THUMB_INTERVAL_MS} に 1 回までしか取り直さない。
 */
public final class PreviewStore {
	/** これより大きい範囲は取りにいかない (一覧の snapshot で分かる)。 */
	public static final long MAX_VOLUME = 4L << 20;
	/** 失敗したあと、同じ版を取り直すまでの間。 */
	private static final long RETRY_MS = 10_000;
	/** 一覧のカードのサムネを取り直す間 (同じセッションの版が進んでも、これより短い間では取り直さない)。 */
	public static final long THUMB_INTERVAL_MS = 30_000;
	/** 見えているカード (4 列 × 4 段) と詳細のぶん。 */
	private static final int MAX_ENTRIES = 24;

	public enum Status {
		LOADING, READY, ERROR
	}

	/**
	 * @param version 取れた (取ろうとした) 版
	 * @param circuit 描くもの。LOADING で前の版があればそれ
	 * @param message ERROR のときの説明
	 */
	public record Entry(String sessionId, int version, Status status, @Nullable PreviewCircuit circuit, @Nullable String message, long at) {
	}

	private final LiveApi api;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-preview").factory());
	private final Map<String, PreviewStore.Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, PreviewStore.Entry> eldest) {
			return size() > MAX_ENTRIES;
		}
	};
	private final Set<String> inFlight = new HashSet<>();

	public PreviewStore(LiveApi api) {
		this.api = api;
	}

	public synchronized @Nullable Entry get(String sessionId) {
		return entries.get(sessionId);
	}

	/**
	 * いまの版 (一覧の snapshot.version) より古ければ取りにいく。取得中・失敗したばかりなら何もしない。
	 */
	public synchronized void request(String sessionId, int wantVersion) {
		request(sessionId, wantVersion, 0);
	}

	/**
	 * {@link #request(String, int)} と同じだが、前に取れてから minIntervalMs 経つまでは版が進んでいても取り直さない
	 * (一覧のサムネ用。詳細のプレビューは 0 ですぐ取り直す)。
	 */
	public synchronized void request(String sessionId, int wantVersion, long minIntervalMs) {
		if (inFlight.contains(sessionId)) {
			return;
		}

		Entry e = entries.get(sessionId);
		long now = System.currentTimeMillis();

		if (!shouldFetch(e, wantVersion, now, minIntervalMs)) {
			return;
		}

		inFlight.add(sessionId);
		entries.put(sessionId, new Entry(sessionId, e == null ? 0 : e.version(), Status.LOADING, e == null ? null : e.circuit(), null, now));
		executor.execute(() -> fetch(sessionId, wantVersion));
	}

	/**
	 * 取りにいくか (取得中かどうかは別に見る)。まだ無い・版が古い (前に取れてから minIntervalMs 経った)・
	 * 失敗してから {@link #RETRY_MS} 経ったなら取りにいく。
	 */
	static boolean shouldFetch(@Nullable Entry e, int wantVersion, long now, long minIntervalMs) {
		return e == null || shouldFetch(e.version(), e.status(), e.circuit() != null, e.at(), wantVersion, now, minIntervalMs);
	}

	/**
	 * @param version    持っている (取ろうとした) 版
	 * @param hasPicture 描けるもの (前の版でも) を持っているか
	 * @param at         その状態になった時刻
	 */
	static boolean shouldFetch(int version, Status status, boolean hasPicture, long at, int wantVersion, long now, long minIntervalMs) {
		boolean failedRecently = status == Status.ERROR && now - at < RETRY_MS;

		if (version >= wantVersion) {
			return status != Status.READY && !failedRecently;
		}

		// 版が古い: 前の版の絵があるうちは、間を空けてから取り直す
		if (hasPicture && now - at < minIntervalMs) {
			return false;
		}

		return !failedRecently;
	}

	private void fetch(String sessionId, int wantVersion) {
		Entry result;

		try {
			LiveApi.Preview p = api.getPreview(sessionId);
			CircuitState state = CircuitState.fromJson(p.state());
			result = new Entry(sessionId, p.version(), Status.READY, PreviewCircuit.resolve(sessionId, p.version(), state), null, System.currentTimeMillis());
		} catch (ApiException e) {
			result = failed(sessionId, wantVersion, e.status() == 0 ? e.describe() : e.getMessage());
		} catch (RuntimeException e) {
			result = failed(sessionId, wantVersion, Tr.f("プレビューを作れません: {0}", e));
		}

		synchronized (this) {
			inFlight.remove(sessionId);
			entries.put(sessionId, result);
		}
	}

	private synchronized Entry failed(String sessionId, int wantVersion, String message) {
		Entry prev = entries.get(sessionId);
		return new Entry(sessionId, wantVersion, Status.ERROR, prev == null ? null : prev.circuit(), message, System.currentTimeMillis());
	}

	public void shutdown() {
		executor.shutdownNow();
	}
}
