package com.redtact.live.browser.cef;

import java.util.concurrent.atomic.AtomicLong;

import com.redtact.live.i18n.Tr;

/**
 * ゲーム内の redtact の描き直しの計測 (#55)。MCEF Modern の描き直し (onPaint: Chromium の絵を写して並べ替える。
 * Chromium のスレッド) と、テクスチャへの転送 (onPaintInternal: ゲームの描画のスレッド) の回数と時間を数える
 * ({@link com.redtact.live.browser.cef.mixin.McefPaintMixin})。1 秒ごとにまとめ、F3 を開いているときに帯へ出す。
 */
public final class BrowserStats {
	private static final AtomicLong PAINTS = new AtomicLong();
	private static final AtomicLong PAINT_NANOS = new AtomicLong();
	private static final AtomicLong UPLOADS = new AtomicLong();
	private static final AtomicLong UPLOAD_NANOS = new AtomicLong();
	private static volatile int lastWidth;
	private static volatile int lastHeight;
	private static final ThreadLocal<Long> PAINT_START = new ThreadLocal<>();
	private static final ThreadLocal<Long> UPLOAD_START = new ThreadLocal<>();
	private static long windowStart = System.nanoTime();
	private static long lastPaints;
	private static long lastPaintNanos;
	private static long lastUploads;
	private static long lastUploadNanos;
	private static volatile Snapshot snapshot = new Snapshot(0, 0, 0, 0, 0, 0);

	/** 1 秒ぶんのまとめ。 */
	public record Snapshot(double paintsPerSec, double paintMs, double uploadsPerSec, double uploadMs, int width, int height) {
		/** 帯に出す短い字。 */
		public String text() {
			return Tr.f("描き直し {0}/秒 (並べ替え {1} ms・転送 {2} ms)・{3}×{4}", String.format("%.0f", paintsPerSec), String.format("%.1f", paintMs),
					String.format("%.1f", uploadMs), width, height);
		}
	}

	private BrowserStats() {
	}

	public static void paintStart(int width, int height) {
		lastWidth = width;
		lastHeight = height;
		PAINT_START.set(System.nanoTime());
	}

	public static void paintEnd() {
		Long t = PAINT_START.get();

		if (t != null) {
			PAINTS.incrementAndGet();
			PAINT_NANOS.addAndGet(System.nanoTime() - t);
			PAINT_START.remove();
		}
	}

	public static void uploadStart() {
		UPLOAD_START.set(System.nanoTime());
	}

	public static void uploadEnd() {
		Long t = UPLOAD_START.get();

		if (t != null) {
			UPLOADS.incrementAndGet();
			UPLOAD_NANOS.addAndGet(System.nanoTime() - t);
			UPLOAD_START.remove();
		}
	}

	/** クライアントの tick ごとに呼ぶ。1 秒たったらまとめる。 */
	public static void tick() {
		long now = System.nanoTime();
		long elapsed = now - windowStart;

		if (elapsed < 1_000_000_000L) {
			return;
		}

		long p = PAINTS.get();
		long pn = PAINT_NANOS.get();
		long u = UPLOADS.get();
		long un = UPLOAD_NANOS.get();
		double sec = elapsed / 1e9;
		long dp = p - lastPaints;
		long du = u - lastUploads;
		snapshot = new Snapshot(dp / sec, dp == 0 ? 0 : (pn - lastPaintNanos) / 1e6 / dp, du / sec, du == 0 ? 0 : (un - lastUploadNanos) / 1e6 / du, lastWidth,
				lastHeight);
		lastPaints = p;
		lastPaintNanos = pn;
		lastUploads = u;
		lastUploadNanos = un;
		windowStart = now;
	}

	public static Snapshot snapshot() {
		return snapshot;
	}

	/** これまでの描き直しの回数 (テスト用)。 */
	public static long paints() {
		return PAINTS.get();
	}

	/** これまでの転送の回数と合計の時間 (テスト用)。 */
	public static long uploads() {
		return UPLOADS.get();
	}

	public static long paintNanos() {
		return PAINT_NANOS.get();
	}

	public static long uploadNanos() {
		return UPLOAD_NANOS.get();
	}
}
