package com.redtact.live.browser.cef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.redtact.live.compat.GameInput;
import com.redtact.live.RedtactLive;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.gui.LibraryPane;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuScreen;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.input.PointerCapture;
import com.redtact.live.library.FileSelection;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.ui.Messages;
import net.dimaskama.mcef.api.MCEFBrowser;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
//? if >=26.1 {
import net.minecraft.client.gui.components.IMEPreeditOverlay;
//?}
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//? if >=26.1 {
import net.minecraft.client.input.PreeditEvent;
//?}
//? if <26.1 {
/*import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import org.joml.Matrix3x2f;
*///?}

/**
 * メニューの「redtact」タブのゲーム内の redtact (#46・#51)。メインタブのヘッダの下の段に帯 (戻る・進む・更新・トップ・保管庫・
 * いまのページ・保存中・字の大きさ・外のブラウザ) を置き、その下に MCEF Modern の Chromium で redtact を出す。帯のボタンは
 * アイコンの正方形で、名前はツールチップに出す (#69。いまのページの欄を広くするため。https:// も省く)。
 * ダウンロードは保管庫に入る ({@link CefHooks})。ほかのタブへ移ってもブラウザは閉じずに置き、戻ると同じページ
 * ({@link McefBridge#showTabBrowser})。Esc でメニューを閉じる (ブラウザも閉じる)。帯はヘッダの右端のトグルで隠せ、
 * 隠すとページがその分だけ広くなる (保存中の数はヘッダの空きに出す)。
 *
 * <p>Chromium は画面の実際のピクセルの大きさで描かせ (GUI の倍率でぼやけないように)、字が小さくなりすぎないよう
 * 画面の高さに合わせて拡大する (帯の A− A+ で一段ずつ変えられる。ゲームを閉じるまで覚えている)。
 *
 * <p>字の入力 (#61): マイクラ (26.3) は SDL3 で、画面が文字入力を始めないと字 ({@link #charTyped}) が届かない。
 * この画面を開いている間はずっと文字入力を有効にする (閉じる・ほかのタブへ移るとバニラの {@code Gui#setScreen} が止める)。
 * 日本語の変換中の字は Chromium に渡す口が無いので、マイクラ標準の表示でページを最後に押した位置の下に出し、
 * 確定した字だけを Chromium へ送る。キーの押下は Windows のスキャンコードを付けて送る ({@link CefKeys})。
 *
 * <p>帯の「保管庫」で左半分に保管庫 ({@link LibraryPane}) を出せる (#60。ゲームを閉じるまで覚える)。そこで選んだファイルを
 * 右のページへドラッグすると、OS からのドロップと同じくページのファイルの欄に入る ({@link BrowserDrop})。投稿の画面だけでなく
 * 回路の編集でも使える。ページの「ファイルを追加」を押しても、選んだファイルが入る ({@link CefHooks})。
 *
 * <p>Pointer Lock の肩代わり (#88): 画面外に描く Chromium は Pointer Lock を必ず断るので、3D プレビューのフライ (押すとマウスを
 * 閉じ込めて見回す) のために代役を差し込む ({@link PointerLockBridge})。ページが頼んだら、ページを押した直後でカーソルがページの上に
 * あるときだけ、マウスをゲームの窓に閉じ込め (SDL の相対モード。カーソルは消え、位置は閉じ込めたところのまま)、動いた分を毎コマ
 * ページへ送る。閉じ込めている間は文字入力を止める (日本語入力のままでも WASD がページに届くように)。Esc はメニューを閉じずに
 * 閉じ込めだけを解く (ブラウザと同じ。Esc はページへ送らない)。ほかのタブへ移る・メニューを閉じる・ゲームの窓から離れる・ページの
 * ダイアログが出る・ページが入れ替わったときも解き、カーソルを閉じ込めたところへ戻す。
 */
public final class RedtactBrowserScreen extends MenuScreen {
	/** 一段の拡大 (Chromium の拡大の段で 0.5 = 約 1.1 倍)。 */
	private static final double ZOOM_STEP = 0.5;
	/** 変換中の字を出すときに入力欄とみなす高さ (押した位置を真ん中に、この下に出す)。 */
	private static final int PREEDIT_INPUT_H = 12;
	/** 帯のボタンの間と、ボタンといまのページの欄の間 (#69。欄を広くするため詰める)。 */
	private static final int BAR_GAP = 2;
	private static final int URL_GAP = 4;
	/** ページの Pointer Lock の閉じ込めは、この時間内にページを押したときだけ (ブラウザの「ユーザの操作の直後」と同じ 5 秒。#88)。 */
	private static final long POINTER_LOCK_AFTER_PRESS_MS = 5_000;
	/** 閉じ込めている間、ページがまだその閉じ込めを持っているかを確かめる間隔 (tick)。 */
	private static final int POINTER_LOCK_CHECK_TICKS = 10;
	/** 帯のボタンの名前 (日本語の原文。テストが名前でボタンを引くので原文のまま持ち、出すときに訳す。#79)。 */
	public static final String BAR_BACK = Tr.ja("戻る");
	public static final String BAR_FORWARD = Tr.ja("進む");
	public static final String BAR_RELOAD = Tr.ja("更新");
	public static final String BAR_HOME = Tr.ja("トップ");
	public static final String BAR_LIBRARY = Tr.ja("保管庫");
	public static final String BAR_SMALLER = Tr.ja("字を小さく");
	public static final String BAR_BIGGER = Tr.ja("字を大きく");
	public static final String BAR_EXTERNAL = Tr.ja("外のブラウザで開く");
	private static int zoomSteps;
	/**
	 * 読み込みが終わるたびにページに差し込む。target=_blank のリンクと window.open を「この画面での移動」に変える
	 * (移動はゲーム内の決まりを通るので、redtact 以外は外のブラウザ・ログインは止まる)。ポップアップの受け口
	 * (onBeforePopup) は Linux の MCEF では Java に届かないことがあるので、それに頼らない。
	 */
	static final String NO_POPUPS = """
			(function () {
			  if (window.__redtactLiveNoPopups) return;
			  window.__redtactLiveNoPopups = true;
			  document.addEventListener('click', function (e) {
			    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
			    if (!a || e.defaultPrevented) return;
			    var t = (a.getAttribute('target') || '').toLowerCase();
			    if (t && t !== '_self' && t !== '_top' && t !== '_parent') { e.preventDefault(); location.href = a.href; }
			  }, true);
			  window.open = function (u) { if (u) location.href = new URL(u, location.href).href; return null; };
			})();
			""";
	private boolean wasLoading = true;
	private boolean bridgeInjected;
	/** 開くときに移るページ (null ならいまのページのまま)。最初の 1 回だけ使う。 */
	private @Nullable String request;
	private @Nullable MCEFBrowser browser;
	private double scale = 1;
	/** 実際の画素に掛けている割合 (設定の解像度。#55)。 */
	private double resolution = 1;
	private int browserW;
	private int browserH;
	private int ticks;
	private @Nullable RButton back;
	private @Nullable RButton forward;
	private @Nullable RButton paneToggle;
	/** 帯のボタン (左から。帯を隠していれば空)。 */
	private final List<RButton> barButtons = new ArrayList<>();
	/** いまのページの欄の左端と右端 (保存中の数を出すときはその右から)。 */
	private int urlX;
	private int urlRight;
	/** 日本語の変換中の字 (無ければ null)。1.21.x には変換中の字を知らせる口が無いので、いつも null (#112)。 */
	//? if >=26.1 {
	private @Nullable IMEPreeditOverlay preedit;
	//?} else
	/*private @Nullable Object preedit;*/
	/** ページを最後に押した位置 (GUI の座標。変換中の字と候補の窓をここに出す)。まだ押していなければ -1。 */
	private int caretX = -1;
	private int caretY = -1;
	/** 左半分の保管庫 (出していなければ null。#60)。 */
	private @Nullable LibraryPane pane;
	/** この画面を組んだときに左半分に保管庫を出したか (変わったら組み直す)。 */
	private boolean paneLaidOut;
	/** 保管庫からページへ運んでいるファイル (運んでいなければ null)。 */
	private @Nullable Drag drag;
	/** ページの上で押したか (離したときもページへ知らせる)。 */
	private boolean pressedInPage;
	/** 直前にページへ落としたときの、落とす前の選択と時刻 (欄の外に落としたら戻す。#60)。 */
	private static List<Path> selectionBeforeDrop = List.of();
	private static long droppedAt;
	/** ページの JS のダイアログ (出していなければ null。#65)。 */
	private @Nullable PageDialog dialog;
	/** ページがマウスを閉じ込めている頼みの番号 (#88。閉じ込めていなければ 0)。 */
	private int pointerLockSeq;
	/** 閉じ込めたときのカーソルの位置 (窓の座標)。解いたらここへ戻す。 */
	private double pointerLockX;
	private double pointerLockY;
	/** ページを最後に押した時刻。 */
	private long pagePressedAt;
	/** Esc で閉じ込めを解いた (その Esc を離したこともページへ送らない)。 */
	private boolean escapeUsed;

	RedtactBrowserScreen(@Nullable String request) {
		super(MenuTab.REDTACT);
		this.request = request;
	}

	@Override
	protected boolean subBarAvailable() {
		return true; // 戻る・進むなどの帯
	}

	/** ページ (Chromium) の上端。帯の下。 */
	public int pageTop() {
		return barBottom();
	}

	/** ページの左端。左半分に保管庫を出していればその右 (#60)。 */
	public int pageLeft() {
		return paneLaidOut ? width / 2 : 0;
	}

	/** ページの幅。 */
	public int pageWidth() {
		return width - pageLeft();
	}

	private boolean inPage(double x, double y) {
		return y >= pageTop() && x >= pageLeft();
	}

	private boolean inPane(double x, double y) {
		return pane != null && y >= pageTop() && x < pageLeft();
	}

	@Override
	protected void initContent() {
		cancelDrag();
		paneLaidOut = LibraryNav.paneShown();
		// 置いてあるブラウザを使う (ほかのタブから戻ったとき・画面の大きさが変わったとき)。初めてなら作る
		browser = McefBridge.showTabBrowser(request);
		request = null;
		resizeBrowser();
		browser.setFocus(true);

		if (pointerLockSeq == 0) {
			GameInput.startTextInput(minecraft, this); // これが無いと字が届かない (#61)。閉じ込めている間は止めておく (#88)
		}

		back = null;
		forward = null;
		paneToggle = null;
		barButtons.clear();
		urlX = 0;
		urlRight = 0;
		pane = null;

		if (paneLaidOut) {
			pane = new LibraryPane(font);
			pane.init(minecraft, 0, pageTop(), pageLeft(), height - pageTop(), this::addRenderableWidget);
		}

		layoutDialog();

		if (!hasSubBar()) {
			return; // 帯を隠している (ページを広く)
		}

		// 帯のボタンはアイコンの正方形にし、空いた分をいまのページの欄に回す (#69。名前はツールチップ)
		int y = SUB_Y + (SUB_H - RButton.H) / 2;
		int x = MARGIN;
		back = barButton(x, y, Icon.BACK, Tr.t(BAR_BACK), RButton.Variant.SECONDARY, b -> cef().goBack());
		x += RButton.H + BAR_GAP;
		forward = barButton(x, y, Icon.FORWARD, Tr.t(BAR_FORWARD), RButton.Variant.SECONDARY, b -> cef().goForward());
		x += RButton.H + BAR_GAP;
		barButton(x, y, Icon.RELOAD, Tr.t(BAR_RELOAD), RButton.Variant.SECONDARY, b -> cef().reload());
		x += RButton.H + BAR_GAP;
		barButton(x, y, Icon.HOME, Tr.t(BAR_HOME), RButton.Variant.SECONDARY, b -> cef().loadURL(BrowserSupport.home().toString()));
		x += RButton.H + BAR_GAP;
		boolean shown = paneLaidOut;
		paneToggle = barButton(x, y, Icon.FOLDER, Tr.t(BAR_LIBRARY), shown ? RButton.Variant.PRIMARY : RButton.Variant.SECONDARY, b -> {
			LibraryNav.setPaneShown(!LibraryNav.paneShown());
			rebuildWidgets();
		}).tooltip(shown ? Tr.t("左の保管庫を閉じる (ページを全幅に)")
				: Tr.t("左半分に保管庫を出す (選んだファイルを右のページへドラッグして入れる。投稿・編集の画面で)"));
		x += RButton.H + URL_GAP;
		urlX = x;

		// 右から: 外のブラウザ・字を大きく・字を小さく
		int right = width - MARGIN - RButton.H;
		barButton(right, y, Icon.EXTERNAL, Tr.t(BAR_EXTERNAL), RButton.Variant.SECONDARY, b -> BrowserSupport.openExternal(currentUrl()));
		right -= RButton.H + BAR_GAP;
		barButton(right, y, Icon.TEXT_BIGGER, Tr.t(BAR_BIGGER), RButton.Variant.SECONDARY, b -> zoom(1));
		right -= RButton.H + BAR_GAP;
		barButton(right, y, Icon.TEXT_SMALLER, Tr.t(BAR_SMALLER), RButton.Variant.SECONDARY, b -> zoom(-1));
		urlRight = right - URL_GAP;
	}

	private RButton barButton(int x, int y, Icon icon, String label, RButton.Variant variant, RButton.Action action) {
		RButton b = addRenderableWidget(RButton.icon(x, y, icon, label, variant, action));
		barButtons.add(b);
		return b;
	}

	private org.cef.browser.CefBrowser cef() {
		return browser.getCefBrowser();
	}

	/** Chromium のいまのページ (まだ白紙なら about:blank。テスト用)。 */
	public String pageUrl() {
		String u = browser == null ? null : cef().getURL();
		return u == null ? "" : u;
	}

	/** Chromium の絵が届いているか (テスト用)。 */
	public boolean painted() {
		return browser != null && browser.getTextureView() != null;
	}

	/** ページにログインの受け口が入ったか (テスト用。同期で JS を読めないので、差し込んだかどうかで見る)。 */
	public boolean hasGameBridge() {
		return bridgeInjected;
	}

	/** ページを読み込んでいる途中か (テスト用)。 */
	public boolean loading() {
		return browser != null && cef().isLoading();
	}

	/** ページでスクリプトを動かす (テスト用)。 */
	public void executeScript(String js) {
		cef().executeJavaScript(js, cef().getURL(), 0);
	}

	/** この画面で url を開く (移動の決まりは同じ)。 */
	public void load(String url) {
		cef().loadURL(url);
	}

	private String currentUrl() {
		String u = browser == null ? null : cef().getURL();
		return u == null || u.isBlank() || u.startsWith("about:") ? BrowserSupport.lastOrHome() : u;
	}

	/**
	 * Chromium に描かせる大きさ。画面の実際の画素に、設定の解像度の割合を掛ける (#55。MCEF Modern は描き直すたびに
	 * 全画素を並べ替えて送るので、画素が少ないほど軽い)。描くときは画面いっぱいに引き伸ばす。
	 */
	private void resizeBrowser() {
		double gui = minecraft.getWindow().getGuiScale();
		// ページの幅は、左に保管庫を出していれば右半分 (#60)
		resolution = Configs.Generic.BROWSER_RESOLUTION.getOptionListValue().factor(pageWidth() * gui, (height - pageTop()) * gui);
		scale = gui * resolution;
		browserW = Math.max(1, (int) Math.round(pageWidth() * scale));
		browserH = Math.max(1, (int) Math.round((height - pageTop()) * scale));
		browser.resize(browserW, browserH);
		applyZoom();
	}

	private void zoom(int delta) {
		zoomSteps = Math.max(-6, Math.min(8, zoomSteps + delta));
		applyZoom();
	}

	/**
	 * 画面の高さに合わせた拡大 (1080 前後で等倍、4K で約 2 倍) に、A− A+ の段を足す。解像度を下げたときは、同じ並びに
	 * 見えるようその割合だけ縮める (ページの CSS の大きさは解像度によらない)。
	 */
	private void applyZoom() {
		double realH = browserH / resolution;
		double factor = Math.max(1.0, Math.min(3.0, realH / 1000.0)) * resolution;
		cef().setZoomLevel(Math.log(factor) / Math.log(1.2) + zoomSteps * ZOOM_STEP);
	}

	@Override
	public void tick() {
		ticks++;
		closeDialogIfDone();

		// 左の保管庫を出す・閉じるが変わった (帯のボタンのほか「redtact に投稿」など)
		if (LibraryNav.paneShown() != paneLaidOut) {
			rebuildWidgets();
			return;
		}

		boolean loading = cef().isLoading();

		// 読み込みが終わったら (新しいページになったら) 差し込む。同じページに 2 回入れても何もしない
		if (wasLoading && !loading) {
			cef().executeJavaScript(NO_POPUPS, cef().getURL(), 0);

			// ゲーム内のログインの受け口 (#50) と Pointer Lock の代役 (#88)。redtact のページだけ
			bridgeInjected = GameLoginBridge.shouldInject(cef().getURL());

			if (bridgeInjected) {
				cef().executeJavaScript(GameLoginBridge.SCRIPT, cef().getURL(), 0);
				cef().executeJavaScript(PointerLockBridge.SCRIPT, cef().getURL(), 0);

				if (PageKeyCodes.NEEDED) {
					// フライの WASD は code で見るが、jcef の Linux と Windows では code が違うキーや空になる (Linux は #88・Windows は #94)
					cef().executeJavaScript(PageKeyCodes.SCRIPT, cef().getURL(), 0);
				}
			}
		}

		if (loading) {
			bridgeInjected = false;
		}

		wasLoading = loading;

		// 閉じ込め (#88): ゲームの窓から離れたら解く (ブラウザと同じ)。ページが入れ替わってその閉じ込めを持っていなければ、ページが
		// 解く頼みを返す (読み直し・移動・要素が消えた)
		if (pointerLockSeq != 0) {
			if (!minecraft.isWindowActive()) {
				releasePointer(true, true);
			} else if (ticks % POINTER_LOCK_CHECK_TICKS == 0) {
				cef().executeJavaScript(PointerLockBridge.heartbeat(pointerLockSeq), cef().getURL(), 0);
			}
		}

		// Chromium の拡大はページ (ホスト) が変わると戻るので、ときどき掛け直す (同じ値なら何も起きない)。
		// コマ数も、作った直後 (Chromium 側のブラウザができる前) には効かないことがあるので掛け直す
		if (ticks % 10 == 0) {
			applyZoom();
			McefBridge.applyFrameRate(browser, true);
		}

		if (back != null && forward != null) {
			back.disabledReason(cef().canGoBack() ? null : Tr.t("戻れるページがありません"));
			forward.disabledReason(cef().canGoForward() ? null : Tr.t("進めるページがありません"));
		}

		if (pane != null) {
			pane.tick();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a); // 幕・ヘッダ・帯の段
		g.fill(pageLeft(), pageTop(), width, height, 0xFFFFFFFF); // 絵が届くまでの白
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int realMouseX, int realMouseY, float a) {
		sendPointerMoves(); // 閉じ込めている間の動きを毎コマ送る (#88)

		// ダイアログを出している間は、下の帯やページにマウスが乗っていないことにする (ツールチップ・カーソルを出さない)
		int mouseX = dialog == null ? realMouseX : -1;
		int mouseY = dialog == null ? realMouseY : -1;
		GpuTextureView view = browser == null ? null : browser.getTextureView();

		if (view != null) {
			// 1.21.x は GPU の絵を GUI に貼る口が private なので同じ中身を積む (1.21.10 までは絵の拡大縮小のしかたを絵の側に持つ)
			//? if >=26.1 {
			g.blit(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR), pageLeft(), pageTop(), width, height, 0f, 1f, 0f, 1f);
			//?} elif >=1.21.11 {
			/*g.guiRenderState.submitGuiElement(new BlitRenderState(RenderPipelines.GUI_TEXTURED, TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)), new Matrix3x2f(g.pose()), pageLeft(), pageTop(), width, height, 0f, 1f, 0f, 1f, -1, g.scissorStack.peek()));
			*///?} else {
			/*g.guiRenderState.submitGuiElement(new BlitRenderState(RenderPipelines.GUI_TEXTURED, TextureSetup.singleTexture(view), new Matrix3x2f(g.pose()), pageLeft(), pageTop(), width, height, 0f, 1f, 0f, 1f, -1, g.scissorStack.peek()));
			*///?}
		}

		if (inPage(mouseX, mouseY) && browser != null && drag == null) {
			g.requestCursor(browser.getCursorType());
		}

		if (pane != null) {
			pane.extract(g, mouseX, mouseY);
		}

		// 帯: いまのページと、保存の途中の数 (↓ と数。#69)。帯を隠しているときは、保存の途中の数だけをヘッダの空きに出す
		int inFlight = RedtactLive.get().downloads().inFlight();

		if (hasSubBar()) {
			int x = urlX;

			if (inFlight > 0) {
				x += Draw.downloadsBadge(g, font, x, SUB_Y + (SUB_H - Draw.BADGE_H) / 2, inFlight, mouseX, mouseY) + 4;
			}

			// F3 を開いているときは、いまのページの代わりに描き直しの計測とゲームの FPS を出す (#55)
			String text = minecraft.getDebugOverlay().showDebugScreen() ? Tr.f("{0}・ゲーム {1} FPS", BrowserStats.snapshot().text(), minecraft.getFps())
					: RedtactLinks.display(currentUrl());
			Draw.clippedText(g, font, text, x, SUB_Y + (SUB_H - 8) / 2, Math.max(0, urlRight - x), Style.MUT, mouseX, mouseY);
		} else if (inFlight > 0) {
			int w = Draw.downloadsBadgeWidth(font, inFlight);

			if (headerFreeRight() - w >= headerFreeLeft()) {
				Draw.downloadsBadge(g, font, headerFreeRight() - w, (BAR_H - Draw.BADGE_H) / 2, inFlight, mouseX, mouseY);
			}
		}

		//? if >=26.1 {
		if (preedit != null) {
			preedit.updateInputPosition(caretLeft(), caretTop());
			g.setPreeditOverlay(preedit); // いちばん上に描かれる (候補の窓の位置もここで合わせる)
		}
		//?}

		super.extractRenderState(g, mouseX, mouseY, a);

		// 運んでいるファイル (ページの上なら緑、外なら薄く)
		if (drag != null) {
			String label = drag.files.size() == 1 ? drag.files.getFirst().getFileName().toString() : Tr.n("{0} 件のファイル", drag.files.size());
			boolean over = inPage(mouseX, mouseY);
			Draw.badge(g, font, mouseX + 10, mouseY + 6, over ? Tr.f("ここに落とす: {0}", label) : label, over ? Draw.Tone.OK : Draw.Tone.MUTE);
		}

		if (dialog != null) {
			dialog.extract(g, font, width, height, realMouseX, realMouseY, a); // いちばん上
		}
	}

	// ---- ページの JS のダイアログ (#65)

	/**
	 * ページのダイアログを出す (クライアントスレッドから。{@link CefHooks})。redtact タブにそのブラウザを出していなければ
	 * キャンセルして返す (confirm は false・離脱は「とどまる」)。
	 */
	static void showDialog(PageDialog d) {
		// メニューを閉じるときの離脱の確認 (#67): ほかのタブで閉じたなら redtact タブに切り替えて出す
		if (d.kind == PageDialog.Kind.BEFORE_UNLOAD && McefBridge.closing(d.browser)) {
			d.markClosing();
			McefBridge.closeAsking(d.browser);

			if (!(Minecraft.getInstance().gui.screen() instanceof RedtactBrowserScreen s && s.browser != null && s.cef() == d.browser)) {
				Menu.openRedtact(null);
			}
		}

		if (Minecraft.getInstance().gui.screen() instanceof RedtactBrowserScreen s && s.browser != null && s.cef() == d.browser) {
			if (s.dialog != null) {
				s.dialog.finish(false);
			}

			s.releasePointer(true, true); // ダイアログを押せるように (#88)
			s.dialog = d;
			s.cancelDrag();
			s.layoutDialog();
		} else {
			d.finish(false);
		}
	}

	/** Chromium がダイアログを取り消した (ページが移ったなど)。返さずに閉じる。 */
	static void dropDialog(org.cef.browser.CefBrowser b) {
		if (Minecraft.getInstance().gui.screen() instanceof RedtactBrowserScreen s && s.dialog != null && s.dialog.browser == b) {
			s.dialog.drop();
			s.closeDialog();
		}
	}

	private void layoutDialog() {
		if (dialog != null) {
			RTextField field = dialog.layout(font, width, height);

			if (field != null) {
				field.setFocused(true); // 字はこの欄へ (バニラが文字入力の持ち主をこの欄にする)
			}
		}
	}

	private void closeDialogIfDone() {
		if (dialog != null && dialog.done()) {
			closeDialog();
		}
	}

	private void closeDialog() {
		PageDialog d = dialog;
		dialog = null;

		if (d != null && d.field() != null) {
			d.field().setFocused(false); // バニラが文字入力を止める
		}

		GameInput.startTextInput(minecraft, this); // ページのために始め直す (#61)

		// メニューを閉じるときの確認なら答えを渡す (閉じるならここでメニューが閉じる。#67)。文字入力より後に
		if (d != null && d.closing()) {
			McefBridge.closeAnswered(d.browser, d.done() && d.accepted());
		}
	}

	/** 出しているダイアログの種類 (ALERT・CONFIRM・PROMPT・BEFORE_UNLOAD。無ければ null。テスト用)。 */
	public @Nullable String dialogKind() {
		return dialog == null ? null : dialog.kind.name();
	}

	/** 出しているダイアログの題と本文 (テスト用)。 */
	public @Nullable String dialogText() {
		return dialog == null ? null : dialog.title() + "\n" + dialog.message();
	}

	/** ダイアログの OK (離れる) かキャンセルのボタン (テスト用)。 */
	public @Nullable AbstractWidget dialogButton(boolean ok) {
		return dialog == null ? null : ok ? dialog.okButton() : dialog.cancelButton();
	}

	/** prompt の入力欄 (テスト用)。 */
	public @Nullable RTextField dialogField() {
		return dialog == null ? null : dialog.field();
	}

	// ---- 日本語の変換 (#61)

	/** 変換中の字を出す入力欄の左端と上端 (GUI の座標)。ページを押した位置。まだ押していなければページの左上。 */
	private int caretLeft() {
		return caretX >= 0 ? caretX : pageLeft() + MARGIN;
	}

	private int caretTop() {
		return (caretY >= 0 ? caretY : pageTop() + PREEDIT_INPUT_H / 2) - PREEDIT_INPUT_H / 2;
	}

	// 変換中の字を知らせる口は 26.1 から (1.21.x では変換中の字はいつも無し。#112)
	//? if >=26.1 {
	@Override
	public boolean preeditUpdated(@Nullable PreeditEvent event) {
		if (dialog != null) {
			dialog.preeditUpdated(event); // prompt の欄が出す (欄が無ければ捨てる)
			return true;
		}

		if (pane != null && pane.pathFocused()) {
			return super.preeditUpdated(event); // 保管庫のパス欄の変換はパス欄が出す
		}

		preedit = event == null ? null : new IMEPreeditOverlay(event, font, PREEDIT_INPUT_H);
		return true;
	}
	//?}

	/** 変換中か (テスト用)。 */
	public boolean composing() {
		return preedit != null;
	}

	// ---- 入力 (帯はボタン、左半分は保管庫、その右は Chromium へ。座標は実際のピクセルに直す)

	private MouseButtonEvent toBrowser(MouseButtonEvent e) {
		return new MouseButtonEvent(bx(e.x()), by(e.y()), e.buttonInfo());
	}

	/** GUI の座標 → Chromium の画素 (ページの左上から)。 */
	private double bx(double x) {
		return (x - pageLeft()) * scale;
	}

	private double by(double y) {
		return (y - pageTop()) * scale;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (dialog != null) {
			dialog.mouseClicked(event, doubleClick); // 窓の外 (ページ・帯・タブ) は押せない
			closeDialogIfDone();
			return true;
		}

		pressedInPage = false;

		if (!inPage(event.x(), event.y()) || browser == null) {
			if (browser != null && inPane(event.x(), event.y())) {
				browser.setFocus(false); // 字は保管庫のパス欄へ
			}

			return super.mouseClicked(event, doubleClick);
		}

		setFocused(null); // 保管庫のパス欄から外す

		if (pointerLockSeq == 0) {
			GameInput.startTextInput(minecraft, this); // パス欄が外れるとバニラが文字入力を止めるので、ページのために始め直す (#61)
		}

		pressedInPage = true;
		pagePressedAt = System.currentTimeMillis();
		browser.setFocus(true);
		browser.onMouseClicked(toBrowser(event), doubleClick);

		// 入力欄を押したとみなし、日本語の変換の候補の窓をここに出す (どこを押したかはページに聞けないので、押した位置で)
		caretX = (int) event.x();
		caretY = (int) event.y();
		//? if >=26.1 {
		minecraft.textInputManager().setTextInputArea(caretLeft(), caretTop(), caretLeft() + 1, caretTop() + PREEDIT_INPUT_H);
		//?}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dialog != null) {
			return true;
		}

		if (drag != null) {
			drag.move(event.x(), event.y());
			return true;
		}

		if (pane != null && browser != null) {
			List<Path> files = pane.dragStart(event.x(), event.y());

			if (files != null) {
				if (!files.isEmpty()) {
					drag = new Drag(files);
					drag.move(event.x(), event.y());
				}

				return true;
			}
		}

		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dialog != null) {
			// ページで押している途中にダイアログが出たときは、離したこともページへ知らせる (押しっぱなしにしない)
			if (browser != null && pressedInPage) {
				browser.onMouseReleased(toBrowser(event));
			}

			pressedInPage = false;
			return true;
		}

		if (drag != null) {
			Drag d = drag;
			drag = null;
			d.release(event.x(), event.y());
			super.mouseReleased(event);
			return true;
		}

		if (pane != null && pane.release()) {
			super.mouseReleased(event);
			return true;
		}

		if (browser != null && pressedInPage) {
			browser.onMouseReleased(toBrowser(event));
		}

		pressedInPage = false;
		return super.mouseReleased(event);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		// 運んでいる間はドラッグの知らせだけを送る (Chromium がドロップの受け手を探している)
		if (browser != null && drag == null && dialog == null) {
			browser.onMouseMoved((int) bx(mouseX), (int) by(mouseY));
		}

		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (dialog != null) {
			return true;
		}

		if (inPage(mouseX, mouseY) && browser != null) {
			browser.onMouseScrolled((int) bx(mouseX), (int) by(mouseY), verticalAmount);
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (dialog != null) {
			dialog.keyPressed(event); // Enter で OK・Esc でキャンセル (メニューは閉じない)
			closeDialogIfDone();
			return true;
		}

		// 閉じ込めている間の Esc は閉じ込めを解くだけ (メニューは閉じない・ページへも送らない。ブラウザと同じ。#88)
		if (pointerLockSeq != 0 && event.isEscape()) {
			releasePointer(true, true);
			escapeUsed = true;
			return true;
		}

		if (preedit != null) {
			return true; // 変換中のキー (Enter・BackSpace・矢印・Esc) は IME のもの。ページへ送ると確定前に送信や削除が起きる
		}

		if (event.isEscape()) {
			if (drag != null) {
				cancelDrag(); // 運ぶのをやめる
				return true;
			}

			onClose();
			return true;
		}

		// 保管庫のパス欄に字を打っている (Enter で移る)
		if (pane != null && pane.pathFocused()) {
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				pane.goToField();
				return true;
			}

			return super.keyPressed(event);
		}

		if (browser != null) {
			// Windows のスキャンコードを付けて送る (MCEF の口のままだと Windows で BackSpace・Ctrl+A などが効かない。#61)
			if (!CefKeys.send(browser, true, event)) {
				browser.onKeyPressed(event);
			}

			// Enter は押下のあとに字 (\r) も送る (Windows と同じ。フォームの送信や改行は字のほうで起きる。文字入力では届かない)
			if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && !event.hasControlDown() && !event.hasAltDown()) {
				//? if >=26.1 {
				browser.onCharTyped(new CharacterEvent('\r'));
				//?} else
				/*browser.onCharTyped(new CharacterEvent('\r', 0));*/
			}
		}

		// Tab で帯のボタンへ移るなど、画面の既定のキー操作はしない (すべて Chromium へ)
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		// 閉じ込めを解いた Esc は離したこともページへ送らない (#88)
		if (escapeUsed && event.isEscape()) {
			escapeUsed = false;
			return true;
		}

		if (dialog != null) {
			return true;
		}

		if (pane != null && pane.pathFocused()) {
			return super.keyReleased(event);
		}

		if (browser != null && !CefKeys.send(browser, false, event)) {
			browser.onKeyReleased(event);
		}

		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (dialog != null) {
			return dialog.charTyped(event);
		}

		if (pane != null && pane.pathFocused()) {
			return super.charTyped(event);
		}

		if (browser != null) {
			browser.onCharTyped(event);
		}

		return true;
	}

	@Override
	public void removed() {
		if (dialog != null) {
			PageDialog d = dialog;
			dialog = null;
			d.finish(false); // ほかのタブへ移った・メニューを閉じた (confirm は false・離脱は「とどまる」)

			if (d.closing()) {
				McefBridge.closeAnswered(d.browser, false);
			}
		}

		preedit = null; // 文字入力はバニラの Gui#setScreen が止めている (変換も消える)
		cancelDrag();
		releasePointer(true, false); // 閉じ込めを解く (#88。文字入力は始め直さない。メニューを閉じたならバニラがゲームのために閉じ込め直す)

		// ほかのタブへ移ったならブラウザは置いておき、メニューを閉じたなら次の tick で閉じる (McefBridge.tick)
		if (browser != null) {
			McefBridge.hideTabBrowser();
			browser = null;
		}
	}

	private void cancelDrag() {
		Drag d = drag;
		drag = null;

		if (d != null) {
			d.cancel();
		}
	}

	// ---- Pointer Lock の肩代わり (#88)

	/**
	 * ページの頼み ({@link PointerLockBridge}。クライアントスレッドから)。redtact タブにそのブラウザを出していなければ、閉じ込めの
	 * 頼みは断る (解く頼みは何もしない)。
	 */
	static void pointerLockRequest(org.cef.browser.CefBrowser b, PointerLockBridge.Op op, int seq) {
		if (Minecraft.getInstance().gui.screen() instanceof RedtactBrowserScreen s && s.browser != null && s.cef() == b) {
			if (op == PointerLockBridge.Op.LOCK) {
				s.lockPointer(seq);
			} else if (s.pointerLockSeq == seq) {
				s.releasePointer(false, true); // ページが exitPointerLock した (ページは知っている)
			}
		} else if (op == PointerLockBridge.Op.LOCK && CefHooks.tracked(b)) {
			b.executeJavaScript(PointerLockBridge.denied(seq), b.getURL(), 0);
		}
	}

	/**
	 * マウスを閉じ込める。ページを押した直後で、カーソルがページの上にあり、ダイアログも運んでいるファイルも無いときだけ
	 * (閉じ込めている間に別の要素へ移すだけなら押した直後でなくてよい)。カーソルは消え、位置は閉じ込めたところのまま。
	 */
	private void lockPointer(int seq) {
		boolean moving = pointerLockSeq != 0;
		Window window = minecraft.getWindow();
		double x = minecraft.mouseHandler.xpos();
		double y = minecraft.mouseHandler.ypos();
		boolean onPage = inPage(MouseHandler.getScaledXPos(window, x), MouseHandler.getScaledYPos(window, y));
		boolean afterPress = System.currentTimeMillis() - pagePressedAt <= POINTER_LOCK_AFTER_PRESS_MS;

		if (dialog != null || drag != null || !minecraft.isWindowActive() || (!moving && (!onPage || !afterPress))) {
			cef().executeJavaScript(PointerLockBridge.denied(seq), cef().getURL(), 0);
			return;
		}

		if (!moving) {
			pointerLockX = x;
			pointerLockY = y;
			PointerCapture.begin(x, y);
			GameInput.grabMouse(window, x, y); // その場で相対モードに (ゲームの MouseHandler#grabMouse は画面を閉じるので使わない)
			preedit = null;
			GameInput.stopTextInput(minecraft, this); // 日本語入力のままでも WASD がページに届くように
		}

		pointerLockSeq = seq;
		cef().executeJavaScript(PointerLockBridge.granted(seq), cef().getURL(), 0);
	}

	/**
	 * 閉じ込めを解き、カーソルを閉じ込めたところに戻す。tellPage ならページにも知らせる (MOD の側で解いたとき)。restartTextInput なら
	 * 文字入力を始め直す (画面を閉じるときはバニラが止めるので始めない)。
	 */
	private void releasePointer(boolean tellPage, boolean restartTextInput) {
		int seq = pointerLockSeq;

		if (seq == 0) {
			return;
		}

		pointerLockSeq = 0;
		PointerCapture.end();
		GameInput.releaseMouse(minecraft.getWindow(), pointerLockX, pointerLockY);

		if (restartTextInput) {
			GameInput.startTextInput(minecraft, this);
		}

		if (tellPage && browser != null) {
			cef().executeJavaScript(PointerLockBridge.released(seq), cef().getURL(), 0);
		}
	}

	/** 閉じ込めている間にためた動き (窓の座標) を、Chromium の画素に直してページへ送る。 */
	private void sendPointerMoves() {
		if (pointerLockSeq == 0 || browser == null) {
			return;
		}

		double[] d = PointerCapture.drain();

		if (d != null) {
			Window window = minecraft.getWindow();
			// GUI の座標に直してから、ページの座標と同じ割合 (bx・by の scale) を掛ける
			double dx = MouseHandler.getScaledXPos(window, d[0]) * scale;
			double dy = MouseHandler.getScaledYPos(window, d[1]) * scale;
			cef().executeJavaScript(PointerLockBridge.move(dx, dy), cef().getURL(), 0);
		}
	}

	/** ページがマウスを閉じ込めているか (テスト用)。 */
	public boolean pointerLocked() {
		return pointerLockSeq != 0;
	}

	/**
	 * ページのファイルの欄の外に落とした (Chromium がファイルを開こうとしたのを止めた。{@link CefHooks})。落とす前の選択を戻す。
	 * クライアントスレッドから。
	 */
	static void dropMissed() {
		boolean dropped = System.currentTimeMillis() - droppedAt < 5_000;
		droppedAt = 0;

		if (!dropped) {
			Messages.info(Tr.t("ゲーム内ではファイルの URL を開きません"));
			return;
		}

		RedtactLive.get().fileSelection().set(selectionBeforeDrop);
		Messages.notice(Tr.t("ファイルの欄の外に落としました"), Tr.t("ページのファイルの欄 (「ファイルを追加」のところ) に落としてください。選んでいたファイルは戻しました"));
	}

	// ---- テスト用

	/** 左半分の保管庫 (出していなければ null)。 */
	public @Nullable LibraryPane pane() {
		return pane;
	}

	/** 帯の「保管庫」(帯を隠していれば null)。 */
	public @Nullable RButton paneToggle() {
		return paneToggle;
	}

	/** 帯のボタン (左から。帯を隠していれば空。#69)。 */
	public List<RButton> barButtons() {
		return List.copyOf(barButtons);
	}

	/** いまのページの欄の幅 (帯を隠していれば 0。#69)。 */
	public int urlWidth() {
		return hasSubBar() ? Math.max(0, urlRight - urlX) : 0;
	}

	/** A− A+ で変えた段 (ゲームを閉じるまで覚える)。 */
	public static int zoomSteps() {
		return zoomSteps;
	}

	/** Chromium に描かせている大きさ (実際の画素)。 */
	public int browserWidth() {
		return browserW;
	}

	/** 運んでいる途中か。 */
	public boolean dragging() {
		return drag != null;
	}

	/** 保管庫からページへ運んでいるファイル。ページに入ったら Chromium に知らせ、出たら取り消す。 */
	private final class Drag {
		private final List<Path> files;
		private boolean entered;

		Drag(List<Path> files) {
			this.files = List.copyOf(files);
		}

		void move(double x, double y) {
			if (browser == null) {
				return;
			}

			if (inPage(x, y)) {
				if (!entered) {
					entered = BrowserDrop.enter(cef(), files, (int) bx(x), (int) by(y));

					if (!entered) {
						Messages.error(Tr.t("ページにファイルを渡せません (MCEF Modern の版が違う)"));
					}
				} else {
					BrowserDrop.over(cef(), (int) bx(x), (int) by(y));
				}
			} else if (entered) {
				BrowserDrop.leave(cef());
				entered = false;
			}
		}

		void release(double x, double y) {
			if (browser == null) {
				return;
			}

			if (!inPage(x, y) || !entered) {
				cancel();
				return;
			}

			BrowserDrop.over(cef(), (int) bx(x), (int) by(y));
			BrowserDrop.drop(cef(), (int) bx(x), (int) by(y));
			entered = false;
			// 落としたものは選択から外す (続けて「ファイルを追加」を押してもまた入らないように)。欄の外なら dropMissed で戻す
			FileSelection selection = RedtactLive.get().fileSelection();
			selectionBeforeDrop = selection.existing();
			droppedAt = System.currentTimeMillis();
			files.forEach(f -> {
				if (selection.contains(f)) {
					selection.toggle(f);
				}
			});
			Messages.info(files.size() == 1 ? Tr.f("ページに落としました: {0}", files.getFirst().getFileName()) : Tr.n("ページに落としました: {0} 件", files.size()));
		}

		void cancel() {
			if (entered && browser != null) {
				BrowserDrop.leave(cef());
			}

			entered = false;
		}
	}
}
