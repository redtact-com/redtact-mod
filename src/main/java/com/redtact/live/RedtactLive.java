package com.redtact.live;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import com.redtact.live.compat.WorldGizmos;
import com.redtact.live.branch.BranchManager;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.command.RliveCommand;
import com.redtact.live.comment.CommentSender;
import com.redtact.live.comment.CommentWatch;
import com.redtact.live.comment.GameJoiner;
import com.redtact.live.comment.ViewerSignIn;
import com.redtact.live.config.Configs;
import com.redtact.live.config.LiveConfig;
import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.RedtactScreen;
import com.redtact.live.hud.HudRenderer;
import com.redtact.live.i18n.Tr;
import com.redtact.live.input.KeyCallbacks;
import com.redtact.live.library.FileSelection;
import com.redtact.live.library.Library;
import com.redtact.live.library.LibraryDownloads;
import com.redtact.live.library.LibrarySearch;
import com.redtact.live.library.RedtactApi;
import com.redtact.live.library.SchematicsBrowser;
import com.redtact.live.live.LiveDirectory;
import com.redtact.live.login.GameLogin;
import com.redtact.live.net.LiveApi;
import com.redtact.live.preview.CircuitPreviewRenderer;
import com.redtact.live.preview.FilePreviewStore;
import com.redtact.live.preview.PreviewStore;
import com.redtact.live.preview.view.FullscreenPreviewRenderer;
import com.redtact.live.selection.Box;
import com.redtact.live.selection.SavedRangeRenderer;
import com.redtact.live.selection.SavedRanges;
import com.redtact.live.selection.Selection;
import com.redtact.live.selection.SelectionRenderer;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.LocalStore;
import com.redtact.live.structure.StructureLimits;
import com.redtact.live.sync.AutoSync;
import com.redtact.live.tool.SelectionTool;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
//?} else
/*import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;*/
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;

/** MOD 全体の部品をまとめる。設定 ({@link Configs})・ホットキー ({@link com.redtact.live.input.HotkeyManager})・HUD ({@link HudRenderer}) も自前。 */
public final class RedtactLive {
	public static final String MOD_ID = "redtact-live";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** 視線の先のブロックを探す距離。 */
	private static final double PICK_RANGE = 64.0;

	private static RedtactLive instance;

	private final Path dataDir;
	private final LiveConfig config = new LiveConfig();
	private final Selection selection = new Selection();
	private final SessionManager sessions;
	private final AutoSync autoSync;
	private final LiveDirectory liveDirectory;
	private final CircuitLoader circuitLoader;
	private final BranchManager branches;
	private final PreviewStore previews;
	private final SavedRanges savedRanges;
	private final Library library;
	private final LibraryDownloads downloads;
	/** 保管庫タブで行き来する schematics の下のフォルダと、redtact タブの横の保管庫で選んだファイル (#60)。 */
	private final SchematicsBrowser schematics = new SchematicsBrowser(CircuitLoader::schematicsDir);
	private final FileSelection fileSelection = new FileSelection();
	/** 保管庫タブのファイルのプレビュー (#70)。 */
	private final FilePreviewStore filePreviews = new FilePreviewStore(StructureLimits.PREVIEW);
	/** 保管庫タブの検索 (#70)。 */
	private final LibrarySearch.Service librarySearch;
	private final GameLogin gameLogin;
	/** MOD からのコメント (#74): ゲームのアカウントでのサインイン・送信・開催中の詳細のコメントの取得。 */
	private final CommentSender commentSender;
	private final CommentWatch commentWatch;

	private RedtactLive(Path dataDir) {
		this.dataDir = dataDir;
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), config::baseUrl, config::userName);
		this.sessions = new SessionManager(config, new LocalStore(dataDir), api, selection);
		this.autoSync = new AutoSync(config, sessions);
		// 閲覧者のサインイン (#74) はコメントと開催中の一覧のフォロー中 (#90) で同じものを使う (join はゲームを起動して 1 回)
		ViewerSignIn viewerSignIn = new ViewerSignIn(api, new GameJoiner());
		this.liveDirectory = new LiveDirectory(api, viewerSignIn);
		this.circuitLoader = new CircuitLoader(api);
		this.branches = new BranchManager(sessions, circuitLoader, selection);
		this.previews = new PreviewStore(api);
		this.savedRanges = new SavedRanges(sessions.store(), selection);
		// redtact から取ってきた回路の保管庫 (#46)。取り途中のファイルは schematics の外に置く
		this.library = new Library(() -> CircuitLoader.schematicsDir().resolve(Library.FOLDER), dataDir.resolve("downloads"));
		this.downloads = new LibraryDownloads(library, new RedtactApi(RedtactApi.newHttpClient()), BrowserSupport::home);
		this.librarySearch = new LibrarySearch.Service(schematics, library);
		// ゲーム内の redtact のログイン (#50)。外のブラウザで済ませて 127.0.0.1 で受け取る
		this.gameLogin = new GameLogin(BrowserSupport::openExternal, task -> Minecraft.getInstance().execute(task), System::currentTimeMillis);
		this.commentSender = new CommentSender(api, viewerSignIn, task -> Minecraft.getInstance().execute(task));
		this.commentWatch = new CommentWatch(api, System::currentTimeMillis);
	}

	public static RedtactLive get() {
		return instance;
	}

	static void init() {
		// 画面の言語の「自動」はマイクラの言語に合わせる (#79)
		Tr.gameLanguage(() -> {
			Minecraft mc = Minecraft.getInstance();
			return mc == null || mc.getLanguageManager() == null ? null : mc.getLanguageManager().getSelected();
		});
		Path dir = FabricLoader.getInstance().getGameDir().resolve(MOD_ID);
		LOGGER.info("[redtact-live] data={} config={}", dir, Configs.configFile());
		instance = new RedtactLive(dir);
		instance.registerAll();
	}

	private void registerAll() {
		Configs.loadFromFile();
		KeyCallbacks.init();
		HudRenderer.register();
		WorldGizmos.register(); // 範囲の枠と名前 (1.21.10 は自分で描く。#112)
		// 「開催中」タブの回路のプレビュー (#34) と全画面のプレビュー (#102)。26.1 までは描く先のまとめ (bufferSource) を渡して作る。
		// 1.21.x の Fabric API では登録の口の名前が SpecialGuiElementRegistry (#112)
		//? if >=26.2 {
		PictureInPictureRendererRegistry.register(context -> new CircuitPreviewRenderer());
		PictureInPictureRendererRegistry.register(context -> new FullscreenPreviewRenderer());
		//?} elif >=26.1 {
		/*PictureInPictureRendererRegistry.register(context -> new CircuitPreviewRenderer(context.bufferSource()));
		PictureInPictureRendererRegistry.register(context -> new FullscreenPreviewRenderer(context.bufferSource()));
		*///?} else {
		/*SpecialGuiElementRegistry.register(context -> new CircuitPreviewRenderer(context.vertexConsumers()));
		SpecialGuiElementRegistry.register(context -> new FullscreenPreviewRenderer(context.vertexConsumers()));
		*///?}
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientChunkEvents.CHUNK_LOAD.register(autoSync::onChunkLoad);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> RliveCommand.register(dispatcher, this));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			Configs.saveToFile();
			sessions.shutdown();
			liveDirectory.shutdown();
			previews.shutdown();
			filePreviews.shutdown();
			librarySearch.shutdown();
			downloads.shutdown();
			gameLogin.cancel();
			commentSender.shutdown();
			commentWatch.shutdown();
		});
		// 選択はワールドに紐づくので、抜けたら消す (セッションの範囲は circuit.json に残る)
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			sessions.flushLocalSnapshot(); // 保存していない最新の版を残す
			client.execute(this::clearSelection);
		});
		new SelectionRenderer(selection).register();
		new SavedRangeRenderer(savedRanges, selection).register();
		registerToolGuards();
	}

	/**
	 * 道具を持っているあいだは、クライアントでのブロックの破壊・操作・設置・アイテムの使用を止める (念のための二重の網)。
	 * ふつうはクリックの段階 (MouseHandler の頭。{@link com.redtact.live.input.HotkeyManager}) で打ち消すので、ここまで来ない。
	 * 統合サーバ側 (isClientSide でない) では何もしない。
	 */
	private static void registerToolGuards() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				level.isClientSide() && SelectionTool.enabled() && SelectionTool.isHolding(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				level.isClientSide() && SelectionTool.enabled() && SelectionTool.isHolding(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				level.isClientSide() && SelectionTool.enabled() && SelectionTool.isHolding(player) ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	private void onTick(Minecraft mc) {
		Menu.tick(mc);
		BrowserSupport.tick(mc, mc.gui.screen() instanceof RedtactScreen);
		gameLogin.tick();
		autoSync.tick(mc);
		branches.tick();
		SessionManager.Active a = sessions.active();
		savedRanges.tick(a == null ? null : a.circuitId);
	}

	public Selection selection() {
		return selection;
	}

	public SessionManager sessions() {
		return sessions;
	}

	public LiveConfig config() {
		return config;
	}

	public AutoSync autoSync() {
		return autoSync;
	}

	public LiveDirectory liveDirectory() {
		return liveDirectory;
	}

	/** 開催中の回路の読み込み (.litematic・Litematica)。 */
	public CircuitLoader circuitLoader() {
		return circuitLoader;
	}

	/** 分岐 (#13)。 */
	public BranchManager branches() {
		return branches;
	}

	/** 開催中の回路のプレビュー (#34)。 */
	public PreviewStore previews() {
		return previews;
	}

	public Path dataDir() {
		return dataDir;
	}

	/** 保存済みの範囲と、選んでいる範囲 (#39)。 */
	/** redtact から取ってきた回路の保管庫 (#46)。 */
	public Library library() {
		return library;
	}

	public LibraryDownloads downloads() {
		return downloads;
	}

	/** schematics の下のフォルダ (#60)。 */
	public SchematicsBrowser schematics() {
		return schematics;
	}

	/** 保管庫タブのファイルのプレビュー (#70)。 */
	public FilePreviewStore filePreviews() {
		return filePreviews;
	}

	/** 保管庫タブの検索 (#70)。 */
	public LibrarySearch.Service librarySearch() {
		return librarySearch;
	}

	/** redtact タブの横の保管庫で選んでいるファイル (#60)。 */
	public FileSelection fileSelection() {
		return fileSelection;
	}

	/** ゲーム内の redtact のログイン (#50)。 */
	public GameLogin gameLogin() {
		return gameLogin;
	}

	public CommentSender commentSender() {
		return commentSender;
	}

	public CommentWatch commentWatch() {
		return commentWatch;
	}

	public SavedRanges savedRanges() {
		return savedRanges;
	}

	/** 選択を解除する。選んだ保存済みの範囲も外す。 */
	public void clearSelection() {
		selection.clear();
		savedRanges.clearPick();
	}

	/**
	 * 保存済みの範囲を選ぶ (セッションタブのカード・羽根の中クリック)。選択をその範囲にする。
	 * 開催中は選べない (同期する範囲が変わってしまうため)。別のワールドの範囲も選べない。
	 *
	 * @return 選んだら true
	 */
	public boolean pickSavedRange(CircuitRecord r) {
		if (!pickable(r)) {
			return false;
		}

		savedRanges.pick(r);
		Box box = r.box();
		Messages.overlay(Tr.f("「{0}」を選びました{1}。セッションタブの「開始」で再開します", r.name, box == null ? "" : " (" + box.sizeText() + ")"));
		return true;
	}

	/**
	 * 保存済みの範囲のカードの「再開」(#44)。その範囲を選んで (選択を保存済みの範囲にして) から、その回路を開く。
	 * 選ばずに開くと、開始直後の push がいまの選択を送り、保存済みの範囲まで上書きしてしまう。
	 * 選べなければ (開催中・別のワールド) 開かない。listed・allowCopy は開くときだけ取り出す。
	 */
	public void resumeSavedRange(CircuitRecord r, BooleanSupplier listed, BooleanSupplier allowCopy) {
		if (!pickable(r)) {
			return;
		}

		savedRanges.pick(r);
		sessions.open(r.name, listed.getAsBoolean(), allowCopy.getAsBoolean());
	}

	/**
	 * 保存済みの範囲をゴミ箱へ移す (#77。セッションタブのカードのゴミ箱を 2 回押したとき)。選んでいた範囲なら外す。
	 * 済んだら (ゲームのスレッドで) done を呼ぶ。
	 */
	public void trashSavedRange(CircuitRecord r, Runnable done) {
		sessions.trashCircuit(r.circuitId).whenComplete((dest, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
				Messages.error(Tr.f("「{0}」を消せませんでした: {1}", r.name, cause.getMessage() == null ? cause.toString() : cause.getMessage()));
			} else {
				if (r.circuitId.equals(savedRanges.pickedId())) {
					savedRanges.clearPick();
				}

				Messages.info(Tr.f("「{0}」をゴミ箱 (redtact-live/trash/{1}) へ移しました", r.name, dest.getFileName()));
			}

			done.run();
		}));
	}

	/** 保存済みの範囲を選べるか (開催中でなく、このワールドの範囲)。選べなければ理由をアクションバーに出す。 */
	private boolean pickable(CircuitRecord r) {
		SessionManager.Active a = sessions.active();

		if (a != null) {
			Messages.overlay(Tr.f("開催中は保存済みの範囲を選べません (「{0}」を終了してから)", a.circuitName));
			return false;
		}

		Minecraft mc = Minecraft.getInstance();

		if (mc.level == null || !SavedRanges.inWorld(r, SessionManager.worldKey(mc))) {
			Messages.overlay(Tr.f("「{0}」は別のワールド ({1}) の範囲です", r.name, r.world));
			return false;
		}

		return true;
	}

	/** いまの選択を名前を付けて保存する (#39)。開催中でなければ、保存した範囲を選んだ状態にする。 */
	public @Nullable CircuitRecord saveRange(String name) {
		CircuitRecord rec = sessions.saveRange(name);

		if (rec != null && sessions.active() == null) {
			savedRanges.mark(rec.circuitId);
		}

		return rec;
	}

	/** 自動同期の on/off。設定ファイルにも書く。 */
	public void setAutoSync(boolean enabled) {
		autoSync.setEnabled(enabled);
		Messages.info(enabled
				? Tr.f("自動同期を on にしました (変化が {0} 秒止まるか、最初の変化から {1} 秒で送ります)", config.autoSyncQuietMs() / 1000.0, config.autoSyncMaxDelayMs() / 1000.0)
				: Tr.t("自動同期を off にしました (K キーかメニューの push で送れます)"));
	}

	/** 視線の先のブロック (無ければ足元) を pos1/pos2 にする。クライアントスレッドから呼ぶ。 */
	public void setPosFromLook(int which) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.player == null || mc.level == null) {
			return;
		}

		Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
		HitResult hit = camera.pick(PICK_RANGE, 1.0F, false);
		BlockPos pos = hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
				? bhr.getBlockPos()
				: mc.player.blockPosition();
		setPos(which, pos, mc.level.dimension().identifier().toString());
	}

	public void setPos(int which, BlockPos pos, String dimension) {
		if (which == 1) {
			selection.setPos1(pos, dimension);
		} else {
			selection.setPos2(pos, dimension);
		}

		Box box = selection.box();
		String text = "pos" + which + " = " + pos.toShortString();

		if (box == null) {
			Messages.overlay(text);
			return;
		}

		String err = box.limitError();
		Messages.overlay(err != null ? Tr.f("{0}  範囲 {1}  {2}", text, box.sizeText(), err) : Tr.n("{1}  範囲 {2} = {0} ブロック", box.volume(), text, box.sizeText()));
	}
}
