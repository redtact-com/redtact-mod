package com.redtact.live.browser.cef;

import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefDragData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 画面の外で描く Chromium へ、外 (MOD の保管庫) からのファイルのドラッグ & ドロップを渡す (#60)。OS からのドロップと同じく
 * Chromium がディスクのファイルを読むので、ページの drop には本物の File が届く (大きさに関わらず軽い)。
 *
 * <p>jcef の {@code CefBrowser_N} の drag target の口 ({@code dragTargetDragEnter/Over/Leave/Drop}) は protected なので反射で呼ぶ
 * (jcef 自身は AWT の画面のときにだけ使う)。座標は Chromium の画素 (ページの左上から)。クライアントスレッドから呼ぶ。
 */
final class BrowserDrop {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	/** cef_drag_operations_mask_t の COPY (ページの dropEffect = copy)。 */
	private static final int OP_COPY = 1;
	private static @Nullable Method enter;
	private static @Nullable Method over;
	private static @Nullable Method leave;
	private static @Nullable Method drop;
	private static boolean resolved;

	private BrowserDrop() {
	}

	/** 使えるか (jcef の版が違って口が無ければ false)。 */
	static synchronized boolean available() {
		if (!resolved) {
			resolved = true;

			try {
				Class<?> n = Class.forName("org.cef.browser.CefBrowser_N");
				enter = n.getDeclaredMethod("dragTargetDragEnter", CefDragData.class, Point.class, int.class, int.class);
				over = n.getDeclaredMethod("dragTargetDragOver", Point.class, int.class, int.class);
				leave = n.getDeclaredMethod("dragTargetDragLeave");
				drop = n.getDeclaredMethod("dragTargetDrop", Point.class, int.class);

				for (Method m : List.of(enter, over, leave, drop)) {
					m.setAccessible(true);
				}
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				LOGGER.warn("[redtact-live] Chromium へのドロップの口が見つかりません (jcef の版が違う)", e);
				enter = null;
			}
		}

		return enter != null;
	}

	/** ドラッグがページに入った。渡せたら true。 */
	static boolean enter(CefBrowser browser, List<Path> files, int x, int y) {
		if (!available() || files.isEmpty()) {
			return false;
		}

		CefDragData data = CefDragData.create();

		for (Path f : files) {
			Path abs = f.toAbsolutePath().normalize();
			data.addFile(abs.toString(), abs.getFileName().toString());
		}

		return call(enter, browser, data, new Point(x, y), 0, OP_COPY);
	}

	/** ページの上で動いた。 */
	static void over(CefBrowser browser, int x, int y) {
		call(over, browser, new Point(x, y), 0, OP_COPY);
	}

	/** ページから出た (落とさずにやめた)。 */
	static void leave(CefBrowser browser) {
		call(leave, browser);
	}

	/** ページに落とした。 */
	static void drop(CefBrowser browser, int x, int y) {
		call(drop, browser, new Point(x, y), 0);
	}

	private static boolean call(@Nullable Method m, CefBrowser browser, Object... args) {
		if (m == null) {
			return false;
		}

		try {
			m.invoke(browser, args);
			return true;
		} catch (InvocationTargetException e) {
			LOGGER.warn("[redtact-live] Chromium へのドロップ ({}) に失敗", m.getName(), e.getCause());
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOGGER.warn("[redtact-live] Chromium へのドロップ ({}) を呼べません", m.getName(), e);
		}

		return false;
	}
}
