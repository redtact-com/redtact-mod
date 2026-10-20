package com.redtact.live.browser.cef;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.Menu;
import com.redtact.live.i18n.Tr;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * MCEF Modern を呼ぶところ。MCEF Modern が入っているときだけ読み込まれる ({@link BrowserSupport} から)。
 * クライアントスレッドから呼ぶ。
 */
public final class McefBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static volatile MCEFApi.@Nullable Initialization init;
	/** 画面を閉じたが、ダウンロードが終わるまで閉じずに置いておくブラウザ。 */
	private static final List<MCEFBrowser> CLOSING = new ArrayList<>();
	/**
	 * redtact タブのブラウザ (#51)。メニューのほかのタブへ移っても閉じずに置き、戻ったら同じページを出す。
	 * メニューを閉じたら ({@link #tick}) 閉じる。
	 */
	private static @Nullable MCEFBrowser tabBrowser;
	/** redtact タブに出しているか。 */
	private static boolean tabShown;
	/** redtact タブのブラウザを作った回数 (テスト用。タブを移っても同じブラウザかを見る)。 */
	private static int tabBrowserSerial;
	/** 答えを待たずに閉じるまでの時間 (Chromium が固まった・知らせが届かないとき。#67)。 */
	private static final long CLOSE_WAIT_MS = 3_000;
	/** メニューを閉じる頼み (ページの離脱の確認の答えを待っている間だけ。#67)。 */
	private static @Nullable CloseRequest closeRequest;

	/** メニューを閉じる頼み。asking はページが止めて確認の窓を出している間 (待ち切れずに閉じない)。 */
	private static final class CloseRequest {
		final MCEFBrowser browser;
		final Runnable close;
		final long since = System.currentTimeMillis();
		boolean asking;

		CloseRequest(MCEFBrowser browser, Runnable close) {
			this.browser = browser;
			this.close = close;
		}
	}

	private McefBridge() {
	}

	/** Chromium の用意を始める (MCEF Modern が取ってくる・展開する・起動する。何度呼んでもよい)。 */
	public static void initialize() {
		if (init == null) {
			init = MCEFApi.initialize();
		}
	}

	public static BrowserSupport.Status status() {
		MCEFApi.Initialization i = init;

		if (i == null) {
			return new BrowserSupport.Status(BrowserSupport.State.NOT_STARTED,
					Tr.t("初めて開くときに Chromium を用意します (MCEF Modern が取ってきます。Windows で約 140MB)"));
		}

		CompletableFuture<MCEFApi> f = i.getFuture();

		if (f.isCompletedExceptionally()) {
			return new BrowserSupport.Status(BrowserSupport.State.FAILED, Tr.t("Chromium を用意できませんでした (ゲームのログに MCEF Modern の失敗が出ています)"));
		}

		if (f.isDone()) {
			return new BrowserSupport.Status(BrowserSupport.State.READY, Tr.t("ゲーム内で redtact を開けます"));
		}

		float pct = i.getPercentage();
		String p = pct >= 0 ? String.format(" %.0f%%", pct) : "";
		String text = switch (i.getStage()) {
			case NOT_STARTED -> Tr.t("Chromium の用意を待っています");
			case DOWNLOADING -> Tr.f("Chromium を取ってきています{0} (初回だけ。Windows で約 140MB)", p);
			case EXTRACTING -> Tr.f("Chromium を展開しています{0}", p);
			case INSTALL -> Tr.f("Chromium を入れています{0}", p);
			case INITIALIZING -> Tr.t("Chromium を起動しています");
			default -> Tr.t("Chromium を用意しています");
		};
		return new BrowserSupport.Status(BrowserSupport.State.PREPARING, text);
	}

	/** redtact タブの画面 (url があればそのページへ。null ならいまのページのまま)。 */
	public static Screen createScreen(@Nullable String url) {
		return new RedtactBrowserScreen(url);
	}

	/**
	 * redtact タブに出すブラウザ。置いてあればそれを使い (url があればそのページへ移る)、無ければ作る
	 * (url が無ければ最後に見ていたページかトップ)。
	 */
	static MCEFBrowser showTabBrowser(@Nullable String url) {
		if (tabBrowser == null) {
			tabBrowser = createBrowser(url != null ? url : BrowserSupport.lastOrHome());
			tabBrowserSerial++;
		} else if (url != null) {
			tabBrowser.getCefBrowser().loadURL(url);
		}

		tabShown = true;
		applyFrameRate(tabBrowser, true);
		return tabBrowser;
	}

	/**
	 * redtact タブを離れた (ほかのタブへ移った・メニューを閉じた)。閉じたかどうかは次の tick で見る。見ていない間は
	 * コマ数を 1 に落とし、描き直しを止める (#55。ページの動きがあっても重くならない)。
	 */
	static void hideTabBrowser() {
		tabShown = false;

		if (tabBrowser != null) {
			BrowserSupport.rememberUrl(tabBrowser.getCefBrowser().getURL());
			tabBrowser.setFocus(false);
			applyFrameRate(tabBrowser, false);
		}
	}

	/** Chromium のコマ数の上限: 見ているときは設定の値、見ていないときは 1 (#55)。同じ値なら何も変わらない。 */
	static void applyFrameRate(MCEFBrowser b, boolean visible) {
		b.getCefBrowser().setWindowlessFrameRate(visible ? Configs.Generic.BROWSER_FRAME_RATE.getOptionListValue().fps() : 1);
	}

	/** redtact タブのブラウザを置いているか (テスト用)。 */
	public static boolean hasTabBrowser() {
		return tabBrowser != null;
	}

	/** redtact タブのブラウザを作った回数 (テスト用)。 */
	public static int tabBrowserSerial() {
		return tabBrowserSerial;
	}

	/**
	 * ブラウザを作り、受け口 ({@link CefHooks}) を付ける。最初のページは作るときに渡す (白紙で作ってすぐ loadURL すると、
	 * Chromium 側のブラウザができる前の読み込みとして捨てられることがある)。最初のページは redtact なので受け口を通らなくてよい。
	 */
	static MCEFBrowser createBrowser(String url) {
		MCEFBrowser b = MCEFApi.getInstance().createBrowser(url, false);
		CefHooks.install(b.getCefBrowser().getClient(), RedtactLive.get().downloads());
		CefHooks.track(b.getCefBrowser());
		return b;
	}

	// ---- メニューを閉じるときの確認 (#67)

	/**
	 * メニューを閉じる。redtact タブのブラウザがあれば、jcef の {@code close(false)} でページの離脱の確認 (beforeunload) を通す。
	 * ページが止めなければ Chromium がそのまま閉じ ({@link #closedByChromium})、止めれば確認の窓が出る
	 * ({@link RedtactBrowserScreen#showDialog}。ほかのタブにいれば redtact タブに切り替える)。答えは {@link #closeAnswered}。
	 * ブラウザが無い・ダウンロードやログインの途中 (閉じると止まる) はすぐ閉じる (今まで通り)。答えを待っている間の 2 回目はすぐ閉じる。
	 */
	public static void requestMenuClose(Runnable close) {
		if (closeRequest != null) {
			LOGGER.info("[redtact-live] メニューを閉じる: 答えを待たずに閉じる (2 回目)");
			finishClose();
			close.run();
			return;
		}

		if (tabBrowser == null || busy()) {
			close.run();
			return;
		}

		BrowserSupport.rememberUrl(tabBrowser.getCefBrowser().getURL()); // 閉じたあとは URL を読めない
		closeRequest = new CloseRequest(tabBrowser, close);
		tabBrowser.getCefBrowser().close(false);
	}

	/** b を閉じてよいかをページに聞いている途中か (離脱の確認の窓を「メニューを閉じますか？」にし、redtact タブで出す)。 */
	static boolean closing(org.cef.browser.CefBrowser b) {
		return closeRequest != null && closeRequest.browser.getCefBrowser() == b;
	}

	/** ページが止めて、確認の窓を出した (答えるまで待つ)。 */
	static void closeAsking(org.cef.browser.CefBrowser b) {
		if (closing(b)) {
			closeRequest.asking = true;
		}
	}

	/**
	 * 確認の窓の答え。閉じる → メニューを閉じる (Chromium はブラウザを閉じている途中。片付けはメニューが閉じた次の tick)。
	 * キャンセル → Chromium も閉じるのをやめているので、そのまま開いておく。
	 */
	static void closeAnswered(org.cef.browser.CefBrowser b, boolean accepted) {
		if (closing(b)) {
			Runnable close = closeRequest.close;
			finishClose();
			LOGGER.info("[redtact-live] メニューを閉じる: ページの確認で {}", accepted ? "閉じる" : "キャンセル");

			if (accepted) {
				close.run();
			}
		}
	}

	/** Chromium がブラウザを閉じた (ページが止めなかった。{@link CefHooks} の onBeforeClose から、クライアントスレッドで)。 */
	static void closedByChromium(org.cef.browser.CefBrowser b) {
		if (closing(b)) {
			Runnable close = closeRequest.close;
			finishClose();
			LOGGER.info("[redtact-live] メニューを閉じる: ページは止めなかった");
			close.run(); // ブラウザの片付けはメニューが閉じた次の tick (close(true) は閉じ済みなら何もしない)
		}
	}

	private static void finishClose() {
		closeRequest = null;
	}

	/** メニューを閉じてよいかをページに聞いている途中か (テスト用)。 */
	public static boolean closePending() {
		return closeRequest != null;
	}

	/** 画面を閉じたとき。ダウンロードやログインの途中なら終わるまで閉じない (閉じると止まる・結果を返せないため)。 */
	static void release(MCEFBrowser b) {
		if (busy()) {
			CLOSING.add(b);
			return;
		}

		close(b);
	}

	private static boolean busy() {
		return RedtactLive.get().downloads().inFlight() > 0 || RedtactLive.get().gameLogin().pending();
	}

	private static void close(MCEFBrowser b) {
		CefHooks.untrack(b.getCefBrowser());
		b.close();
	}

	/** クライアントの tick ごとに呼ぶ。 */
	public static void tick() {
		BrowserStats.tick();

		// 閉じる頼みの答えが来ない (Chromium が固まった・知らせが届かない): 待ち切れずに閉じる。メニューがほかの経路で閉じたら頼みは要らない
		if (closeRequest != null) {
			CloseRequest r = closeRequest;

			if (!Menu.isMenu(Minecraft.getInstance().gui.screen())) {
				finishClose();
			} else if (!r.asking && System.currentTimeMillis() - r.since > CLOSE_WAIT_MS) {
				LOGGER.warn("[redtact-live] メニューを閉じる: ページの答えが {} ms 来ないので閉じる", CLOSE_WAIT_MS);
				finishClose();
				r.close.run();
			}
		}

		// メニューを閉じたら (メニューでない画面になったら) redtact タブのブラウザを閉じる。ほかのタブにいる間は置いておく。
		// ダウンロードやログイン (#50) の途中なら、終わるまで閉じない (release)
		if (tabBrowser != null && !tabShown && !Menu.isMenu(Minecraft.getInstance().gui.screen())) {
			MCEFBrowser b = tabBrowser;
			tabBrowser = null;
			release(b);
		}

		if (!CLOSING.isEmpty() && !busy()) {
			List<MCEFBrowser> list = new ArrayList<>(CLOSING);
			CLOSING.clear();
			list.forEach(McefBridge::close);
		}
	}
}
