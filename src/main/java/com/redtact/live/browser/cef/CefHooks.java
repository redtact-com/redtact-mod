package com.redtact.live.browser.cef;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.FileSelection;
import com.redtact.live.library.LibraryDownloads;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.library.RedtactUpload;
import com.redtact.live.ui.Messages;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefJSDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefJSDialogHandler;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

/**
 * 埋め込みのブラウザの受け口 (移動・ポップアップ・ダウンロード)。MCEF Modern はブラウザをすべて 1 つの CefClient で作るので、
 * 受け口はその CefClient に 1 回だけ付け、この MOD が作ったブラウザ ({@link #track}) のときだけ働く
 * (ほかの MOD のブラウザには、受け口が無いときと同じ既定の動きを返す)。
 *
 * <ul>
 * <li>移動: redtact とファイルの置き場 (R2) 以外は止めて外のブラウザで開く。ログインの画面は止めて知らせる (埋め込みでは通らない)
 * <li>ポップアップ (target=_blank・window.open): 窓は作らない。redtact ならこの画面で開き、ほかは外のブラウザ
 * <li>ダウンロード: 一時ファイルに書かせ、取り終わったら保管庫に入れる ({@link LibraryDownloads})
 * <li>ファイル選択 (ページの「ファイルを追加」など): redtact タブの横の保管庫でファイルを選んでいれば、OS の画面を出さずにそれを渡す (#60)
 * <li>ファイルを開く移動 (ドロップ欄の外に落としたファイル): 止めて、選んでいたファイルを戻す (#60)
 * <li>JS のダイアログ (alert・confirm・prompt・離脱の確認): redtact タブの上に窓で出す ({@link PageDialog}。#65)。
 * 受け口が無いと、画面外に描く Chromium は出さずにキャンセル扱いにする (編集中のページから移れない)
 * <li>Pointer Lock の代役の頼み (隠した iframe の移動): 止めて、redtact タブがマウスを閉じ込める・解く ({@link PointerLockBridge}。#88)
 * </ul>
 * どれも CEF のスレッドから呼ばれる。
 */
final class CefHooks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final Set<CefBrowser> OURS = ConcurrentHashMap.newKeySet();
	private static boolean installed;

	private CefHooks() {
	}

	static synchronized void install(CefClient client, LibraryDownloads downloads) {
		if (installed) {
			return;
		}

		installed = true;
		Requests requests = new Requests();
		Popups popups = new Popups();
		Downloads dl = new Downloads(downloads);
		Loads loads = new Loads();
		Dialogs dialogs = new Dialogs();
		JsDialogs jsDialogs = new JsDialogs();
		client.addRequestHandler(requests);
		client.addLoadHandler(loads);
		client.addLifeSpanHandler(popups);
		client.addDownloadHandler(dl);
		client.addDialogHandler(dialogs);
		client.addJSDialogHandler(jsDialogs);
		// CefClient の受け口は 1 つずつで、先に付いていれば置き換わらない (ほかの MOD が付けていた)
		warnIfNotOurs(client, "requestHandler_", requests, Tr.ja("外のサイトへの移動を止められません"));
		warnIfNotOurs(client, "lifeSpanHandler_", popups, Tr.ja("ポップアップを止められません"));
		warnIfNotOurs(client, "downloadHandler_", dl, Tr.ja("ダウンロードを保管庫に入れられません"));
		warnIfNotOurs(client, "dialogHandler_", dialogs, Tr.ja("ページの「ファイルを追加」に保管庫で選んだファイルを入れられません"));
		warnIfNotOurs(client, "jsDialogHandler_", jsDialogs, Tr.ja("ページの確認 (編集中に離れるときなど) を出せません"));
		// 読み込みの失敗の知らせは無くても動くので、ほかの MOD が使っていても知らせない

	}

	static void track(CefBrowser b) {
		OURS.add(b);
	}

	static void untrack(CefBrowser b) {
		OURS.remove(b);
	}

	private static boolean ours(@Nullable CefBrowser b) {
		return b != null && OURS.contains(b);
	}

	/** この MOD のブラウザでまだ開いているか。 */
	static boolean tracked(@Nullable CefBrowser b) {
		return ours(b);
	}

	/** effect は日本語の原文 ({@link Tr#ja})。ログにはそのまま、知らせには訳して出す。 */
	private static void warnIfNotOurs(CefClient client, String field, Object ours, String effect) {
		try {
			Field f = CefClient.class.getDeclaredField(field);
			f.setAccessible(true);

			if (f.get(client) != ours) {
				LOGGER.warn("[redtact-live] ほかの MOD が MCEF の受け口 ({}) を先に使っているので、{}", field, effect);
				Messages.error(Tr.f("ほかの MOD が MCEF Modern の受け口を使っているので、{0}", Tr.t(effect)));
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// 確かめられないだけ (jcef の版が違う)
		}
	}

	/** 受け口の中の失敗。CEF のスレッドに例外を投げ返さず、記録して既定の答え (result) を返す。 */
	private static boolean failed(String what, Throwable e, boolean result) {
		LOGGER.error("[redtact-live] ゲーム内の redtact の受け口 ({}) で失敗", what, e);
		return result;
	}

	/** ゲーム内で開かない URL。ログインなら知らせ、ほかは外のブラウザで開く。 */
	private static void leave(String url) {
		if (RedtactLinks.isSignIn(url)) {
			Messages.notice(Tr.t("ゲーム内ではログインできません"), Tr.t("未ログインのまま回路を探して保存できます。ログインが要る操作は「外のブラウザ」で"));
			return;
		}

		String host = hostOf(url);
		Minecraft.getInstance().execute(() -> BrowserSupport.openExternal(url));
		Messages.info(host.isEmpty() ? Tr.t("外のブラウザで開きました") : Tr.f("外のブラウザで開きました: {0}", host));
	}

	private static boolean isFileUrl(@Nullable String url) {
		return url != null && url.regionMatches(true, 0, "file:", 0, 5);
	}

	/**
	 * ページのファイルの欄の外に落としたファイルを Chromium が開こうとした (この移動か新しいタブ)。開かず (外のブラウザにも出さず)、
	 * 落とす前の選択を戻す (#60)。
	 */
	private static void fileDroppedOutside() {
		LOGGER.info("[redtact-live] ページのファイルの欄の外に落としたファイルを開くのを止めました");
		Minecraft.getInstance().execute(RedtactBrowserScreen::dropMissed);
	}

	private static String hostOf(String url) {
		try {
			String h = URI.create(url).getHost();
			return h == null ? "" : h;
		} catch (RuntimeException e) {
			return "";
		}
	}

	private static final class Requests extends CefRequestHandlerAdapter {
		@Override
		public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture, boolean isRedirect) {
			try {
				// ゲーム内のログインの頼み (#50)。ページの受け口が隠した iframe をここへ移す (GameLoginBridge)
				if (ours(browser) && GameLoginBridge.isRequest(request.getURL())) {
					GameLoginBridge.handle(browser, request.getURL());
					return true;
				}

				// Pointer Lock の代役の頼み (#88)。ログインと同じく隠した iframe をここへ移す (PointerLockBridge)
				if (ours(browser) && PointerLockBridge.isRequest(request.getURL())) {
					PointerLockBridge.handle(browser, request.getURL());
					return true;
				}

				// ドロップ欄の外に落としたファイル (#60): Chromium がファイルそのものを開こうとする。止めて、選んでいたファイルを戻す
				if (ours(browser) && isFileUrl(request.getURL())) {
					fileDroppedOutside();
					return true;
				}

				if (!ours(browser) || frame == null || !frame.isMain()) {
					return false;
				}

				String url = request.getURL();
				LOGGER.debug("[redtact-live] 移動: {}", RedtactLinks.redact(url));

				if (RedtactLinks.isSignIn(url) || !RedtactLinks.allowedInGame(url, BrowserSupport.home())) {
					leave(url);
					return true;
				}

				return false;
			} catch (RuntimeException | LinkageError e) {
				return failed(Tr.raw("移動"), e, true);
			}
		}

		@Override
		public void onRenderProcessTerminated(CefBrowser browser, CefRequestHandler.TerminationStatus status, int errorCode, String errorString) {
			if (ours(browser)) {
				LOGGER.warn("[redtact-live] ゲーム内の redtact の描画プロセスが止まりました: {} {} {}", status, errorCode, errorString);
				Messages.error(Tr.f("redtact の画面が止まりました ({0})。「更新」で読み直してください", status));
			}
		}

		@Override
		public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String targetUrl, boolean userGesture) {
			try {
				if (!ours(browser)) {
					return false;
				}

				Popups.openHere(browser, targetUrl);
				return true;
			} catch (RuntimeException | LinkageError e) {
				return failed(Tr.raw("別のタブ"), e, true);
			}
		}
	}

	/** 読み込みの失敗を知らせる (止めたページの取り消しは除く)。 */
	private static final class Loads extends CefLoadHandlerAdapter {
		@Override
		public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
			if (!ours(browser) || frame == null || !frame.isMain() || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) {
				return;
			}

			LOGGER.warn("[redtact-live] ゲーム内の redtact を読み込めません: {} {} {}", errorCode, errorText, RedtactLinks.redact(failedUrl));
			Messages.error(Tr.f("読み込めません ({0}): {1}", errorText, hostOf(failedUrl)));
		}
	}

	private static final class Popups extends CefLifeSpanHandlerAdapter {
		@Override
		public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName) {
			try {
				if (!ours(browser)) {
					return false;
				}

				// 窓は作らない (オフスクリーンの描画では別の窓が出てしまう)
				openHere(browser, targetUrl);
				return true;
			} catch (RuntimeException | LinkageError e) {
				return failed(Tr.raw("ポップアップ"), e, true);
			}
		}

		/** Chromium がブラウザを閉じた。メニューを閉じるときにページが止めなかった (#67) なら、メニューを閉じる。 */
		@Override
		public void onBeforeClose(CefBrowser browser) {
			if (ours(browser)) {
				Minecraft.getInstance().execute(() -> McefBridge.closedByChromium(browser));
			}
		}

		static void openHere(CefBrowser browser, @Nullable String url) {
			if (url == null || url.isBlank() || url.startsWith("about:")) {
				return;
			}

			// 欄の外に落としたファイルは新しいタブで開こうとする (#60)
			if (isFileUrl(url)) {
				fileDroppedOutside();
				return;
			}

			LOGGER.info("[redtact-live] ゲーム内の redtact の新しいタブ: {}", RedtactLinks.redact(url));

			if (RedtactLinks.isSignIn(url) || !RedtactLinks.allowedInGame(url, BrowserSupport.home())) {
				leave(url);
			} else {
				// 受け口の中 (ポップアップを作るかどうかの問い合わせの最中) では移らないので、ゲームのスレッドで後から読む
				Minecraft.getInstance().execute(() -> browser.loadURL(url));
			}
		}
	}

	private static final class Downloads extends CefDownloadHandlerAdapter {
		private final LibraryDownloads downloads;

		Downloads(LibraryDownloads downloads) {
			this.downloads = downloads;
		}

		@Override
		public boolean onBeforeDownload(CefBrowser browser, CefDownloadItem item, String suggestedName, CefBeforeDownloadCallback callback) {
			try {
				if (!ours(browser)) {
					return false;
				}

				LOGGER.debug("[redtact-live] ダウンロードの開始: {} {}", item.getId(), RedtactLinks.redact(item.getURL()));
				Path target = downloads.begin(item.getId(), item.getURL(), suggestedName, browser.getURL(), item.getTotalBytes());

				if (target == null) {
					// false = 既定の扱い (オフスクリーンのブラウザでは取り消し)
					return false;
				}

				callback.Continue(target.toAbsolutePath().toString(), false);
				return true;
			} catch (RuntimeException | LinkageError e) {
				return failed(Tr.raw("ダウンロード"), e, false);
			}
		}

		@Override
		public void onDownloadUpdated(CefBrowser browser, CefDownloadItem item, CefDownloadItemCallback callback) {
			// ブラウザを閉じた後も知らせは来るので、ブラウザではなく番号で見る (LibraryDownloads が知らない番号は無視される)
			try {
				int id = item.getId();

				if (item.isComplete()) {
					downloads.complete(id);
				} else if (item.isCanceled() || !item.isInProgress()) {
					// 取り消し・中断 (Chromium の状態は進行中・完了・取り消し・中断の 4 つ)
					downloads.canceled(id);
				} else if (!downloads.progress(id, item.getReceivedBytes())) {
					callback.cancel();
				}
			} catch (RuntimeException | LinkageError e) {
				failed(Tr.raw("ダウンロードの途中"), e, false);
			}
		}
	}

	/**
	 * ページのファイル選択 (#60)。redtact タブの横に保管庫を出していて、そこでファイルを選んでいれば、OS のファイル選択を出さずに
	 * それを渡す (欄の accept に合うものだけ。画像の欄などには渡さない)。渡したものは選択から外す。選んでいない・合わなければ
	 * いつもの扱い (false)。redtact のページのときだけ。
	 */
	/** JS のダイアログ。この MOD のブラウザなら窓で出して true (あとで返す)、ほかは false (既定の動き)。 */
	private static final class JsDialogs implements CefJSDialogHandler {
		@Override
		public boolean onJSDialog(CefBrowser browser, String originUrl, CefJSDialogHandler.JSDialogType type, String message, String defaultPrompt,
				CefJSDialogCallback callback, BoolRef suppressMessage) {
			try {
				if (!ours(browser)) {
					return false;
				}

				PageDialog.Kind kind = switch (type) {
					case JSDIALOGTYPE_ALERT -> PageDialog.Kind.ALERT;
					case JSDIALOGTYPE_CONFIRM -> PageDialog.Kind.CONFIRM;
					case JSDIALOGTYPE_PROMPT -> PageDialog.Kind.PROMPT;
				};
				LOGGER.info("[redtact-live] ページのダイアログ ({}): {}", kind, RedtactLinks.redact(originUrl));
				PageDialog d = new PageDialog(browser, kind, originUrl, message, defaultPrompt, false, callback);
				Minecraft.getInstance().execute(() -> RedtactBrowserScreen.showDialog(d));
				return true;
			} catch (RuntimeException | LinkageError e) {
				return failed(Tr.raw("JS のダイアログ"), e, false);
			}
		}

		@Override
		public boolean onBeforeUnloadDialog(CefBrowser browser, String message, boolean isReload, CefJSDialogCallback callback) {
			try {
				if (!ours(browser)) {
					return false;
				}

				LOGGER.info("[redtact-live] ページの離脱の確認 (再読み込み {})", isReload);
				PageDialog d = new PageDialog(browser, PageDialog.Kind.BEFORE_UNLOAD, browser.getURL(), message, null, isReload, callback);
				Minecraft.getInstance().execute(() -> RedtactBrowserScreen.showDialog(d));
				return true;
			} catch (RuntimeException | LinkageError e) {
				return failed(Tr.raw("離脱の確認"), e, false);
			}
		}

		@Override
		public void onResetDialogState(CefBrowser browser) {
			if (ours(browser)) {
				Minecraft.getInstance().execute(() -> RedtactBrowserScreen.dropDialog(browser));
			}
		}

		@Override
		public void onDialogClosed(CefBrowser browser) {
			// 窓は返したときに閉じている
		}
	}

	private static final class Dialogs implements CefDialogHandler {
		@Override
		public boolean onFileDialog(CefBrowser browser, CefDialogHandler.FileDialogMode mode, String title, String defaultFilePath,
				Vector<String> acceptFilters, Vector<String> acceptExtensions, Vector<String> acceptDescriptions, CefFileDialogCallback callback) {
			try {
				if (!ours(browser) || !LibraryNav.paneShown() || !GameLoginBridge.shouldInject(browser.getURL())) {
					return false;
				}

				boolean multiple = mode == CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE;

				if (!multiple && mode != CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN) {
					return false; // フォルダを選ぶ・保存
				}

				List<String> filters = strings(acceptFilters);
				List<String> exts = strings(acceptExtensions);
				FileSelection selection = RedtactLive.get().fileSelection();
				List<Path> picked = new ArrayList<>();

				for (Path p : selection.existing()) {
					if (RedtactUpload.accepts(filters, exts, p.getFileName().toString()) && (multiple || picked.isEmpty())) {
						picked.add(p);
					}
				}

				LOGGER.info("[redtact-live] ページのファイル選択 ({}・accept {} {}): 保管庫で選んだ {} 件を渡す", mode, filters, exts, picked.size());

				if (picked.isEmpty()) {
					return false;
				}

				Vector<String> paths = new Vector<>();
				picked.forEach(p -> paths.add(p.toAbsolutePath().toString()));
				callback.Continue(paths);
				Minecraft.getInstance().execute(() -> {
					picked.forEach(selection::toggle); // 渡したものを外す (もう一度押すとまた入らないように)
					Messages.info(Tr.n("保管庫で選んだ {0} 件をページに入れました", picked.size()));
				});
				return true;
			} catch (RuntimeException | LinkageError e) {
				return failed(Tr.raw("ファイルの選択"), e, false);
			}
		}

		private static List<String> strings(@Nullable Vector<String> v) {
			List<String> out = new ArrayList<>();

			if (v != null) {
				for (String s : v) {
					if (s != null) {
						out.add(s);
					}
				}
			}

			return out;
		}
	}
}
