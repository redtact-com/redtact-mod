package com.redtact.live.browser;

import java.net.URI;
import java.util.function.Consumer;

import com.mojang.blaze3d.Blaze3D;
import com.redtact.live.browser.cef.McefBridge;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.RedtactScreen;
import com.redtact.live.i18n.Tr;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * ゲーム内の redtact (#46) への入口。MCEF Modern (任意の依存) のクラスは {@code browser.cef} の中だけが使い、
 * それは {@link #available()} が true のときにだけ呼ぶ (呼ばなければ読み込まれない)。
 *
 * <p>Chromium は redtact タブを初めて開くときに用意する (MCEF Modern が取ってくる。Windows で約 140MB)。
 * 用意が済むまでは redtact タブに入口 ({@link RedtactScreen}) と用意の様子を出し、済んだらそのタブにブラウザを出す
 * (redtact タブを見ている間だけ。#51)。
 */
public final class BrowserSupport {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	public static final String MCEF_ID = "mcef-modern";
	public static final String MCEF_PAGE = "https://modrinth.com/mod/mcef-modern";
	private static final boolean AVAILABLE = FabricLoader.getInstance().isModLoaded(MCEF_ID);
	/** 最後に見ていたページ (開き直したらそこから)。 */
	private static volatile @Nullable String lastUrl;
	/** 用意が済んだら redtact タブにブラウザを出すか。 */
	private static volatile boolean pendingOpen;
	/** そのとき開くページ (null ならいまのページか、最後に見ていたページかトップ)。 */
	private static volatile @Nullable String pendingUrl;
	/** MCEF Modern を使い始めたか (使っていなければ tick で触らない)。 */
	private static volatile boolean used;
	/** 外のブラウザで開くところ (テストでは差し替える)。 */
	private static volatile Consumer<String> externalOpener = BrowserSupport::openWithSystem;

	public enum State {
		/** MCEF Modern が入っていない。 */
		MISSING,
		/** まだ用意していない (初めて開くときに用意する)。 */
		NOT_STARTED,
		/** 用意している (Chromium を取ってくる・展開する・起動する)。 */
		PREPARING,
		READY,
		FAILED
	}

	/** いまの様子と、画面に出す説明。 */
	public record Status(State state, String text) {
	}

	private BrowserSupport() {
	}

	public static boolean available() {
		return AVAILABLE;
	}

	public static Status status() {
		if (!AVAILABLE) {
			return new Status(State.MISSING, Tr.t("MCEF Modern を入れると、ここで redtact を開けます (いまは外のブラウザで開きます)"));
		}

		try {
			return McefBridge.status();
		} catch (LinkageError | RuntimeException e) {
			return new Status(State.FAILED, Tr.f("MCEF Modern を使えません: {0}", e));
		}
	}

	/** 設定の redtact のトップ。 */
	public static URI home() {
		return RedtactLinks.home(Configs.Generic.REDTACT_URL.getStringValue());
	}

	public static @Nullable String lastUrl() {
		return lastUrl;
	}

	/** 最後に見ていたページか、無ければトップ。 */
	public static String lastOrHome() {
		String u = lastUrl;
		return u != null ? u : home().toString();
	}

	public static void rememberUrl(@Nullable String url) {
		if (url != null && RedtactLinks.allowedInGame(url, home()) && url.startsWith("http") && !url.contains("cloudflarestorage.com")) {
			lastUrl = url;
		}
	}

	/**
	 * メニューを閉じる (#67)。ゲーム内の redtact のブラウザがあれば、ページの離脱の確認を通してから閉じる
	 * (編集中なら redtact タブで確認の窓。キャンセルなら閉じない)。無ければすぐ閉じる。
	 */
	public static void closeMenu(Runnable close) {
		if (AVAILABLE && used) {
			try {
				McefBridge.requestMenuClose(close);
				return;
			} catch (LinkageError | RuntimeException e) {
				LOGGER.warn("[redtact-live] ゲーム内の redtact に閉じてよいかを聞けないので、そのまま閉じる", e);
			}
		}

		close.run();
	}

	/** 用意を始める (済んでいれば何もしない)。 */
	public static void prepare() {
		if (AVAILABLE) {
			used = true;

			try {
				McefBridge.initialize();
			} catch (LinkageError | RuntimeException e) {
				LOGGER.warn("[redtact-live] MCEF Modern を起動できません", e);
				Messages.error(Tr.f("MCEF Modern を起動できません: {0}", e.getMessage()));
			}
		}
	}

	/**
	 * redtact タブでゲーム内の redtact を開く (クライアントスレッドから)。url が null ならいまのページのまま
	 * (閉じていれば最後に見ていたページかトップ)。MCEF Modern が無ければ外のブラウザで開く。
	 * 用意がまだなら redtact タブの入口を出して用意を始め、済んだらそのタブにブラウザを出す。
	 */
	public static void open(@Nullable String url) {
		Status s = status();

		switch (s.state()) {
			case MISSING -> openExternal(url != null ? url : lastOrHome());
			case NOT_STARTED, PREPARING -> {
				pendingOpen = true;

				if (url != null) {
					pendingUrl = url;
				}

				prepare();

				if (!(Minecraft.getInstance().gui.screen() instanceof RedtactScreen)) {
					Menu.open(MenuTab.REDTACT); // 入口で用意の様子を見せる
				}
			}
			case READY -> {
				pendingOpen = false;
				pendingUrl = null;
				Menu.openRedtact(url);
			}
			case FAILED -> {
				// ゲーム内では開けないので外のブラウザで (入口の「redtact を開く (外のブラウザ)」と同じ)
				openExternal(url != null ? url : lastOrHome());
				Messages.info(Tr.t("ゲーム内の redtact を用意できなかったので、外のブラウザで開きました"));
			}
		}
	}

	/**
	 * redtact タブの画面: 用意が済んでいればゲーム内のブラウザ (url があればそのページへ。null ならいまのページのまま)、
	 * まだなら入口 ({@link RedtactScreen})。
	 */
	public static Screen tabScreen(@Nullable String url) {
		if (AVAILABLE && status().state() == State.READY) {
			try {
				return McefBridge.createScreen(url);
			} catch (LinkageError | RuntimeException e) {
				LOGGER.warn("[redtact-live] ゲーム内の redtact を出せません", e);
				Messages.error(Tr.f("ゲーム内の redtact を出せません: {0}", e.getMessage()));
			}
		}

		return new RedtactScreen();
	}

	/** redtact タブの入口を開いたとき: 用意を始め、済んだらこのタブにブラウザを出す (失敗していれば何もしない)。 */
	public static void prepareForTab() {
		if (!AVAILABLE || status().state() == State.FAILED) {
			return;
		}

		pendingOpen = true;
		prepare();
	}

	/** クライアントの tick ごとに呼ぶ。用意が済んで、まだ redtact タブ (入口) を見ていればブラウザに切り替える。 */
	public static void tick(Minecraft mc, boolean onRedtactTab) {
		if (AVAILABLE && used) {
			McefBridge.tick();
		}

		if (!pendingOpen || !AVAILABLE) {
			return;
		}

		if (!onRedtactTab) {
			pendingOpen = false;
			pendingUrl = null;
			return;
		}

		State st = status().state();

		if (st == State.READY) {
			String url = pendingUrl;
			pendingOpen = false;
			pendingUrl = null;
			Menu.openRedtact(url);
		} else if (st == State.FAILED) {
			pendingOpen = false;
			pendingUrl = null;
		}
	}

	/** 外のブラウザで開く。 */
	public static void openExternal(String url) {
		externalOpener.accept(url);
	}

	/** 外のブラウザで開くところを差し替える (テスト用。null で元に戻す)。 */
	public static void setExternalOpener(@Nullable Consumer<String> opener) {
		externalOpener = opener == null ? BrowserSupport::openWithSystem : opener;
	}

	private static void openWithSystem(String url) {
		try {
			//? if >=26.3 {
			Blaze3D.openUri(URI.create(url));
			//?} else
			/*net.minecraft.util.Util.getPlatform().openUri(URI.create(url));*/
		} catch (RuntimeException e) {
			Messages.error(Tr.f("ブラウザを開けません: {0}", e.getMessage()));
		}
	}
}
