package com.redtact.live.session;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.redtact.live.auth.HostAuth;
import com.redtact.live.auth.MinecraftJoin;
import com.redtact.live.capture.BlockEntityNbt;
import com.redtact.live.capture.RegionCapture;
import com.redtact.live.config.LiveConfig;
import com.redtact.live.i18n.Tr;
import com.redtact.live.litematic.LitematicWriter;
import com.redtact.live.litematic.RegionBlocks;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.HostSocket;
import com.redtact.live.net.LiveApi;
import com.redtact.live.selection.Box;
import com.redtact.live.selection.Selection;
import com.redtact.live.state.CircuitDiff;
import com.redtact.live.state.CircuitState;
import com.redtact.live.state.StateDiffer;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.store.LocalStore;
import com.redtact.live.store.SnapshotThrottle;
import com.redtact.live.store.StoredComment;
import com.redtact.live.store.Times;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * セッション (F-M4〜F-M8) のまとめ役。
 *
 * <p>スレッド: ワールドの読み取りと選択範囲はクライアントスレッド、通信・ファイル・セッション状態の
 * 書き換えは {@link #worker} (1 本) で行う。WebSocket のハンドラも worker で呼ばれる。
 */
public final class SessionManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final int MAX_NAME_LENGTH = 64;
	private static final int COMMENTS_SHOWN = 20;

	private final LiveConfig config;
	private final LocalStore store;
	private final LiveApi api;
	private final Selection selection;
	private final ExecutorService worker;
	private final ScheduledExecutorService scheduler;
	private final AtomicBoolean busy = new AtomicBoolean();

	/** サーバが版 version として持っている (と MOD が知っている) 回路。 */
	public record Sent(CircuitState state, int version, Box box) {
	}

	public enum PushKind {
		/** PUT (全体) */
		FULL,
		/** PATCH (差分) */
		DIFF,
		/** 変化がないので送らなかった */
		UNCHANGED
	}

	/** 直前の push の結果 (表示と確認用)。 */
	public record PushResult(PushKind kind, int version, int changes, int blockEntities, int removedBlockEntities, @Nullable String reason, Trigger trigger) {
	}

	private volatile @Nullable PushResult lastPush;
	/** 開催中の回路の未読コメント数。 */
	private volatile int unread;
	/** 開催中の回路のコメントを保存するたびに増える ({@link #commentsGeneration()})。 */
	private volatile long commentsGeneration;

	/** push のきっかけ。 */
	public enum Trigger {
		/** K キー・/rlive push・open 直後。結果をチャットに出す */
		MANUAL,
		/** 自動同期。成功はアクションバー、失敗は続けて起きても 1 回だけチャット */
		AUTO
	}

	public enum Outcome {
		SENT,
		UNCHANGED,
		/** 429 rate_limited */
		RATE_LIMITED,
		/** チャンク未読み込みなどで、まだ送れない */
		NOT_READY,
		FAILED,
		/** セッションが終わっていた */
		ENDED
	}

	/** push の前後を知りたい側 (自動同期)。 */
	public interface PushListener {
		/** クライアントスレッド、ワールドを読む直前。 */
		void onPushStarting(Trigger trigger);

		/** クライアントスレッドか worker。 */
		void onPushFinished(Trigger trigger, Outcome outcome);
	}

	private static final PushListener NO_LISTENER = new PushListener() {
		@Override
		public void onPushStarting(Trigger trigger) {
		}

		@Override
		public void onPushFinished(Trigger trigger, Outcome outcome) {
		}
	};

	private volatile PushListener pushListener = NO_LISTENER;

	/** 自動同期の失敗をもうチャットに出したか (成功で戻す)。 */
	private volatile boolean autoFailureShown;

	/** 開催中のセッション。worker だけが書き換える。 */
	private volatile @Nullable Active active;

	/** 開催中のセッションの状態。 */
	public static final class Active {
		public final String circuitId;
		public final String circuitName;
		public final String sessionId;
		public final String shareUrl;
		final String hostToken;
		/** セッションを開いたワールド ({@link #worldKey})。別のワールドでは push しない。 */
		final String world;
		volatile Box region;
		volatile String dimension;
		volatile int viewers;
		volatile boolean online;
		volatile int lastVersion;
		/** サーバに送れた最新の状態 (次の push の差分の元)。null なら次は全体を送る。 */
		volatile @Nullable Sent sent;
		/** 開催中の一覧に出しているか (サーバの値)。 */
		volatile boolean listed;
		/** マイクラアカウントで確認したホストか (サーバの値)。 */
		volatile boolean hostVerified;
		/** サーバに登録されたホスト名 (確認済みならマイクラの名前)。 */
		volatile String hostName = "";
		/** 一覧の切り替えを送っている最中。 */
		volatile boolean listedPending;
		/** 複製 (.litematic の取得・読み込み・分岐) を許しているか (サーバの値)。 */
		volatile boolean allowCopy = true;
		/** 複製の切り替えを送っている最中。 */
		volatile boolean allowCopyPending;
		/** 分岐元 (分岐して開いたセッションだけ。サーバの値)。 */
		volatile Api.@Nullable Parent parent;
		/** このセッションから分岐して開催中の子 (古い順。サーバの値)。 */
		volatile List<Api.Branch> branches = List.of();
		/** branches・parent が変わるたびに増える (画面の作り直し用)。 */
		volatile int branchGeneration;
		/** 一度でも branches を受け取ったか (最初の hello では「分岐が開かれました」を出さない)。 */
		volatile boolean branchesKnown;
		/** ローカルの litematic の間引き。 */
		final SnapshotThrottle<LocalSnapshot> snapshots = new SnapshotThrottle<>();
		/** 最後に同期した版 (保管庫へ入れる元。#73)。まだ送れていなければ null。 */
		volatile @Nullable LocalSnapshot synced;
		/** sent が無いとき (最初の push・再接続・409 の後) に全体を送る理由の表示。 */
		volatile String firstPushReason = Tr.t("最初の送信");
		HostSocket socket;

		Active(String circuitId, String circuitName, String sessionId, String shareUrl, String hostToken, String world, Box region, String dimension, int lastVersion) {
			this.world = world;
			this.circuitId = circuitId;
			this.circuitName = circuitName;
			this.sessionId = sessionId;
			this.shareUrl = shareUrl;
			this.hostToken = hostToken;
			this.region = region;
			this.dimension = dimension;
			this.lastVersion = lastVersion;
		}

		public Box region() {
			return region;
		}

		public String dimension() {
			return dimension;
		}

		public int viewers() {
			return viewers;
		}

		public boolean online() {
			return online;
		}

		public int lastVersion() {
			return lastVersion;
		}

		public @Nullable Sent sent() {
			return sent;
		}

		public String world() {
			return world;
		}

		public boolean listed() {
			return listed;
		}

		public boolean listedPending() {
			return listedPending;
		}

		public boolean hostVerified() {
			return hostVerified;
		}

		public boolean allowCopy() {
			return allowCopy;
		}

		public boolean allowCopyPending() {
			return allowCopyPending;
		}

		public Api.@Nullable Parent parent() {
			return parent;
		}

		public List<Api.Branch> branches() {
			return branches;
		}

		public int branchGeneration() {
			return branchGeneration;
		}

		public String hostName() {
			return hostName;
		}
	}

	public SessionManager(LiveConfig config, LocalStore store, LiveApi api, Selection selection) {
		this.config = config;
		this.store = store;
		this.api = api;
		this.selection = selection;
		this.worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-worker").factory());
		this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("redtact-live-timer").factory());
	}

	public @Nullable Active active() {
		return active;
	}

	public boolean isBusy() {
		return busy.get();
	}

	public @Nullable PushResult lastPush() {
		return lastPush;
	}

	public void setPushListener(PushListener listener) {
		this.pushListener = listener == null ? NO_LISTENER : listener;
	}

	public Selection selection() {
		return selection;
	}

	public LocalStore store() {
		return store;
	}

	public LiveConfig config() {
		return config;
	}

	// ---------------------------------------------------------------- open

	/** クライアントスレッドから呼ぶ。選択があれば選択を、無ければ保存済みの範囲を使う。 */
	public void open(String rawName) {
		open(rawName, false, config.defaultAllowCopy(), null);
	}

	/**
	 * メニューから開く。listed なら開催中の一覧に出す (既定は出さない。前回の設定は引き継がない)。
	 * 終了を記録していないセッションに再接続するときは、サーバに残っている設定のまま。
	 */
	public void open(String rawName, boolean listed) {
		open(rawName, listed, config.defaultAllowCopy(), null);
	}

	/** allowCopy: 複製を許すか (新しく開くときだけ。猶予時間内の再接続はサーバの値のまま)。 */
	public void open(String rawName, boolean listed, boolean allowCopy) {
		open(rawName, listed, allowCopy, null);
	}

	/**
	 * 分岐して開く (#13)。範囲はいまの選択 (分岐の準備の配置を囲む箱)。parent は分岐元と読み込んだ版。
	 * 開けなかったときの説明は分岐用 (分岐元が終わった・複製不可・版が合わない)。
	 */
	public void openBranch(String rawName, boolean listed, boolean allowCopy, Api.ParentRef parent) {
		open(rawName, listed, allowCopy, parent);
	}

	/**
	 * いまの選択を名前を付けて保存する (セッションは開かない。#39)。同じ名前の回路があれば、その範囲を更新する。
	 * 開催中のセッションの回路は更新しない (範囲は push のたびに選択から決まるため)。クライアントスレッドから呼ぶ。
	 *
	 * @return 保存した回路。保存できなければ null (理由はトースト)
	 */
	public @Nullable CircuitRecord saveRange(String rawName) {
		String name = rawName == null ? "" : rawName.trim();

		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			Messages.error(Tr.f("名前は 1〜{0} 文字にしてください", MAX_NAME_LENGTH));
			return null;
		}

		Box box = selection.box();
		String dim = selection.dimension();

		if (box == null || dim == null) {
			Messages.error(Tr.t("範囲が指定されていません。角を 2 つ決めてから保存してください ([ ] キー・羽根の左右クリック・「範囲」タブ)"));
			return null;
		}

		String err = box.limitError();

		if (err != null) {
			Messages.error(err);
			return null;
		}

		Active a = active;

		if (a != null && name.equals(a.circuitName)) {
			Messages.error(Tr.f("開催中の回路「{0}」には保存できません。別の名前にするか、終了してから保存してください", name));
			return null;
		}

		if (busy.get()) {
			Messages.error(Tr.t("前の処理がまだ終わっていません"));
			return null;
		}

		try {
			Optional<CircuitRecord> found = store.findByName(name);
			CircuitRecord rec = found.isPresent() ? found.get() : store.create(name);
			rec.region = CircuitRecord.Region.of(box);
			rec.dimension = dim;
			rec.world = worldKey(Minecraft.getInstance());
			store.save(rec);
			Messages.success(found.isPresent() ? Tr.f("範囲「{0}」を更新しました ({1})。セッションタブで選んで開始できます", name, box.sizeText())
					: Tr.f("範囲「{0}」を保存しました ({1})。セッションタブで選んで開始できます", name, box.sizeText()));
			return rec;
		} catch (IOException e) {
			LOGGER.error("[redtact-live] 範囲の保存に失敗", e);
			Messages.error(Tr.f("ローカル保存に失敗しました: {0}", e.getMessage()));
			return null;
		}
	}

	private void open(String rawName, boolean listed, boolean allowCopy, Api.@Nullable ParentRef parent) {
		String name = rawName == null ? "" : rawName.trim();

		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			Messages.error(Tr.f("名前は 1〜{0} 文字にしてください", MAX_NAME_LENGTH));
			return;
		}

		Active current = active;

		if (current != null) {
			Messages.error(Tr.f("セッション「{0}」が開いています。先に終了してください (メニューの「セッション」)", current.circuitName));
			return;
		}

		// 選択があれば選択、無ければ保存済みの範囲 (カードの「再開」は先に選択を保存済みの範囲にしてから呼ぶ。#44)
		Box box = selection.box();
		String dim = selection.dimension();

		if (box != null) {
			String err = box.limitError();

			if (err != null) {
				Messages.error(err);
				return;
			}
		}

		if (!busy.compareAndSet(false, true)) {
			Messages.error(Tr.t("前の処理がまだ終わっていません"));
			return;
		}

		String world = worldKey(Minecraft.getInstance());
		Messages.info(Tr.t("セッションを開いています…"));
		worker.execute(() -> {
			try {
				doOpen(name, box, dim, world, listed, allowCopy, parent);
			} finally {
				busy.set(false);
			}
		});
	}

	private void doOpen(String name, @Nullable Box selBox, @Nullable String selDim, String world, boolean listed, boolean allowCopy,
			Api.@Nullable ParentRef parentRef) {
		try {
			CircuitRecord rec = store.findByName(name).orElse(null);
			Box region;
			String dim;

			if (selBox != null) {
				region = selBox;
				dim = selDim;
			} else if (rec != null && rec.box() != null) {
				if (rec.world != null && !rec.world.equals(world)) {
					Messages.error(Tr.f("回路「{0}」は別のワールド ({1}) のものです。このワールドで使うなら範囲を指定してから open してください", name, rec.world));
					return;
				}

				region = rec.box();
				dim = rec.dimension;
				String err = region.limitError();

				if (err != null) {
					Messages.error(Tr.f("保存済みの範囲が上限を超えています: {0}", err));
					return;
				}
			} else {
				Messages.error(Tr.t("範囲が指定されていません。[ と ] キーか、メニューの「範囲」で指定してください"));
				return;
			}

			boolean reused = rec != null;

			if (rec == null) {
				rec = store.create(name);
			}

			rec.region = CircuitRecord.Region.of(region);
			rec.dimension = dim;
			rec.world = world;

			// 前回のセッションがサーバに残っていれば (猶予時間内) 再接続する
			CircuitRecord.SessionRecord session = null;
			CircuitRecord.SessionRecord prev = rec.openSession();
			boolean listedNow = listed;
			boolean allowCopyNow = allowCopy;
			Api.Parent parentNow = null;
			List<Api.Branch> branchesNow = List.of();
			boolean hostVerified = false;
			String hostName = config.userName();

			if (prev != null) {
				Optional<Api.Session> alive = prev.hostToken != null && config.baseUrl().equals(prev.serverUrl)
						? api.getSession(prev.sessionId) : Optional.empty();

				// 分岐の名前が、まだ開いている別のセッションの回路と同じなら、分岐ではなく再接続になってしまうので断る
				if (alive.isPresent() && parentRef != null
						&& (alive.get().parent == null || !parentRef.sessionId().equals(alive.get().parent.sessionId))) {
					Messages.error(Tr.f("「{0}」の前のセッションがまだ開いています。分岐には別の名前を付けてください", name));
					return;
				}

				if (alive.isPresent()) {
					// 猶予時間内の再接続は、持っている hostToken を使う (マイクラアカウントの確認はしない)
					session = prev;
					listedNow = alive.get().listed;
					allowCopyNow = alive.get().copyAllowed();
					parentNow = alive.get().parent;
					branchesNow = alive.get().branchList();
					hostVerified = alive.get().hostVerified;
					hostName = alive.get().host;
				} else {
					prev.endedAt = Times.now();
					prev.endReason = prev.endReason == null ? "expired" : prev.endReason;
					prev.hostToken = null;
				}
			}

			boolean reconnect = session != null;

			HostAuth.JoinFailure joinFailure = null;

			if (session == null) {
				// マイクラアカウントで確認する: チャレンジ → Mojang に join → チャレンジと名前を付けて開く。
				// join できなければ付けずに X-Live-User (表示名) で開く (中継サーバが許していれば)
				Api.MinecraftProof proof = null;
				String joinDetail = null;
				Api.CreateSessionResponse res;

				// join から中継サーバの確認 (hasJoined) までは、コメントのサインイン (#74) と重ねない
				synchronized (HostAuth.JOIN_LOCK) {
					try {
						Api.Challenge challenge = api.minecraftChallenge();
						MinecraftJoin.Result joined = MinecraftJoin.join(challenge.serverId);

						if (joined.ok()) {
							proof = new Api.MinecraftProof(challenge.challengeId, joined.name());
						} else {
							joinFailure = joined.failure();
							joinDetail = joined.detail();
						}
					} catch (ApiException e) {
						joinFailure = HostAuth.JoinFailure.CHALLENGE_FAILED;
						joinDetail = e.describe();
					}

					if (proof == null) {
						String userError = config.userError();

						if (userError != null) {
							Messages.error(Tr.f("マイクラアカウントで確認できず ({0})、{1}", HostAuth.reasonText(joinFailure, joinDetail), userError));
							return;
						}
					}

					try {
						res = api.createSession(new Api.CreateSessionRequest(
								name, rec.circuitId, SharedConstants.getCurrentVersion().name(), dataVersion(), listed, proof, allowCopy, parentRef));
					} catch (ApiException e) {
						String authMessage = HostAuth.createErrorMessage(e.code(), joinFailure, joinDetail);

						if (authMessage != null) {
							Messages.error(authMessage);
							return;
						}

						// 分岐元に関わる失敗 (終わった・複製不可・版が合わない・子の上限) は分岐用の説明で
						if (parentRef != null && e.status() != 0) {
							Messages.error(com.redtact.live.branch.BranchText.createError(e));
							return;
						}

						throw e;
					}
				}

				listedNow = res.session != null && res.session.listed;
				allowCopyNow = res.session == null ? allowCopy : res.session.copyAllowed();
				parentNow = res.session == null ? null : res.session.parent;
				branchesNow = res.session == null ? List.of() : res.session.branchList();
				hostVerified = res.session != null && res.session.hostVerified;
				hostName = res.session != null ? res.session.host : hostName;
				session = new CircuitRecord.SessionRecord();
				session.sessionId = res.session.sessionId;
				session.title = res.session.title;
				session.shareUrl = res.shareUrl;
				session.serverUrl = config.baseUrl();
				session.startedAt = Times.now();
				session.hostToken = res.hostToken;
				rec.sessions.add(session);
			}

			store.save(rec);

			Active a = new Active(rec.circuitId, rec.name, session.sessionId, session.shareUrl, session.hostToken, world, region, dim, session.lastVersion);
			a.socket = new HostSocket(api.http(), api.hostSocketUri(a.sessionId), a.hostToken, config.userName(), new SocketHandler(a), worker, scheduler);
			a.listed = listedNow;
			a.allowCopy = allowCopyNow;
			a.parent = parentNow;
			a.branches = branchesNow;
			a.branchesKnown = true;
			a.hostVerified = hostVerified;
			a.hostName = hostName;

			if (reconnect) {
				a.firstPushReason = Tr.t("再接続");
			}

			active = a;
			a.socket.start();

			List<StoredComment> past = store.readComments(rec.circuitId);
			unread = unreadCount(rec, past);
			String branched = parentNow != null ? " " + Tr.f("(「{0}」v{1} から分岐)", parentNow.title, parentNow.version) : "";
			String resumed = reused && !reconnect ? " " + Tr.n("(保存済みの回路を再開。過去のコメント {0} 件)", past.size()) : "";

			// 前のセッションの子は、同じ回路を開き直すと中継サーバが付け直す (#87)
			if (!reconnect && !branchesNow.isEmpty()) {
				resumed += " " + Tr.n("(前のセッションの分岐 {0} 件を付け直しました)", branchesNow.size());
			}

			String detail = hostVerified ? Tr.f("{0}{1}{2}・ホスト: {3} (マイクラで確認済み)", name, branched, resumed, hostName)
					: Tr.f("{0}{1}{2}・ホスト: {3} (確認なし)", name, branched, resumed, hostName);

			if (!hostVerified && joinFailure != null) {
				// 開けたが確認できなかった理由は、トーストを長くしないよう最近の出来事とアクションバーに出す
				Messages.info(Tr.f("マイクラアカウントで確認できなかったので、表示名で開きました ({0})", HostAuth.reasonText(joinFailure, null)));
			}

			if (reconnect) {
				Messages.notice(Tr.t("再接続しました"), detail);
			} else {
				// 次にすることは共有なので、リンクをクリップボードに入れておく (メニューの「セッション」からも写せる)
				copyToClipboard(a.shareUrl);
				Messages.notice(Tr.t("セッションを開始しました"), Tr.f("{0}。共有リンクをコピーしました", detail));
			}

			busy.set(false); // 下の自動 push より先に下ろす

			Minecraft.getInstance().execute(() -> {
				if (selection.box() == null) {
					selection.set(region, dim);
				}

				ClientLevel level = Minecraft.getInstance().level;

				// 開始・再接続の直後に 1 回送る (再接続でも MOD は前に送った状態を知らないので全体)
				if (level != null && dimensionId(level).equals(dim)) {
					push();
				} else {
					Messages.info(Tr.t("回路を送るには、範囲のあるディメンションで K キー (またはメニューの push)"));
				}
			});
		} catch (ApiException e) {
			if ("limit_reached".equals(e.code())) {
				Messages.error(Tr.f("同時に開けるセッション数の上限です。ほかのセッションを閉じてください ({0})", e.getMessage()));
			} else {
				Messages.error(Tr.f("セッションを開始できません: {0}", e.describe()));
			}
		} catch (IOException e) {
			LOGGER.error("[redtact-live] ローカル保存に失敗", e);
			Messages.error(Tr.f("ローカル保存に失敗しました: {0}", e.getMessage()));
		} catch (RuntimeException e) {
			LOGGER.error("[redtact-live] open に失敗", e);
			Messages.error(Tr.f("セッションを開始できません: {0}", e));
		}
	}

	// ---------------------------------------------------------------- push

	/** 手動の push (K キー・/rlive push・open 直後)。 */
	public void push() {
		push(Trigger.MANUAL);
	}

	/**
	 * クライアントスレッドから呼ぶ。ワールドを読んでから通信を worker に渡す。
	 *
	 * @return 送り始めたら true (結果は {@link PushListener#onPushFinished} に来る)
	 */
	public boolean push(Trigger trigger) {
		Active a = active;

		if (a == null) {
			report(trigger, Tr.t("セッションが開いていません。メニューの「セッション」で開いてください"));
			return false;
		}

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;

		if (level == null) {
			report(trigger, Tr.t("ワールドに入ってから送ってください"));
			return false;
		}

		if (!worldKey(mc).equals(a.world)) {
			report(trigger, Tr.f("セッションを開いたワールド ({0}) ではありません。そのワールドで送るか、終了してから開き直してください", a.world));
			return false;
		}

		Target target = target(a);
		String err = target.box().limitError();

		if (err != null) {
			report(trigger, err);
			return false;
		}

		if (!dimensionId(level).equals(target.dimension())) {
			report(trigger, Tr.f("範囲は {0} にあります。そのディメンションで送ってください", target.dimension()));
			return false;
		}

		if (!busy.compareAndSet(false, true)) {
			if (trigger == Trigger.MANUAL) {
				Messages.error(Tr.t("前の処理がまだ終わっていません"));
			}

			return false;
		}

		pushListener.onPushStarting(trigger);
		RegionCapture.Result captured;
		long t0 = System.nanoTime();

		try {
			captured = RegionCapture.capture(level, target.box());
		} catch (RegionCapture.CaptureException e) {
			busy.set(false);
			report(trigger, e.getMessage());
			pushListener.onPushFinished(trigger, Outcome.NOT_READY);
			return true;
		} catch (RuntimeException e) {
			busy.set(false);
			LOGGER.error("[redtact-live] 範囲の読み取りに失敗", e);
			report(trigger, Tr.f("範囲を読み取れません: {0}", e));
			pushListener.onPushFinished(trigger, Outcome.FAILED);
			return true;
		}

		long captureMs = (System.nanoTime() - t0) / 1_000_000;
		IntegratedServer server = mc.getSingleplayerServer();
		ResourceKey<Level> dimensionKey = level.dimension();
		worker.execute(() -> {
			Outcome outcome = Outcome.FAILED;

			try {
				outcome = doPush(a, trigger, target.box(), target.dimension(), captured, captureMs, server, dimensionKey);
			} finally {
				busy.set(false);
				pushListener.onPushFinished(trigger, outcome);
			}
		});
		return true;
	}

	/** 同期する範囲: 選択があれば選択、無ければセッションの範囲。 */
	public record Target(Box box, String dimension) {
	}

	public static Target target(Active a, Selection selection) {
		Box box = selection.box();
		return box != null ? new Target(box, selection.dimension()) : new Target(a.region, a.dimension);
	}

	private Target target(Active a) {
		return target(a, selection);
	}

	/**
	 * 失敗の知らせ。手動ならチャット、自動は連続した失敗のうち最初の 1 回だけチャットに出す
	 * (チャンク未読み込みなどでチャットを埋めないため)。
	 */
	private void report(Trigger trigger, String message) {
		if (trigger == Trigger.MANUAL) {
			Messages.error(message);
		} else if (!autoFailureShown) {
			autoFailureShown = true;
			Messages.error(Tr.f("自動同期: {0} (直るまで、このお知らせは繰り返しません)", message));
		}
	}

	/**
	 * シングルプレイなら統合サーバのワールドからブロックエンティティを読み直す。クライアントのワールドには
	 * チェストの中身やコンパレーターの出力などが届かないため。マルチプレイではクライアントの分を使う。
	 */
	private static SortedMap<Integer, CompoundTag> serverBlockEntities(@Nullable IntegratedServer server, ResourceKey<Level> dimension, Box box,
			SortedMap<Integer, CompoundTag> fallback) {
		if (server == null) {
			return fallback;
		}

		try {
			SortedMap<Integer, CompoundTag> fromServer = server.submit(() -> {
				ServerLevel level = server.getLevel(dimension);
				return level == null ? null : RegionCapture.captureBlockEntities(level, box);
			}).get(5, TimeUnit.SECONDS);
			return fromServer == null ? fallback : fromServer;
		} catch (Exception e) {
			LOGGER.warn("[redtact-live] 統合サーバからブロックエンティティを読めないので、クライアントの分を使います: {}", e.toString());
			return fallback;
		}
	}

	private Outcome doPush(Active a, Trigger trigger, Box box, String dim, RegionCapture.Result captured, long captureMs,
			@Nullable IntegratedServer server, ResourceKey<Level> dimensionKey) {
		if (active != a) {
			report(trigger, Tr.t("セッションは終了しています"));
			return Outcome.ENDED;
		}

		try {
			long t0 = System.nanoTime();
			SortedMap<Integer, CompoundTag> tags = serverBlockEntities(server, dimensionKey, box, captured.blockEntities());
			BlockEntityNbt.Encoded encoded = BlockEntityNbt.encodeAll(tags);
			CircuitState current = CircuitState.of(captured.blocks(), encoded.blockEntities());

			// 前に送れた状態と比べて、全体か差分かを決める
			Sent sent = a.sent;
			String fullReason = null;
			CircuitDiff diff = null;

			if (sent == null) {
				fullReason = a.firstPushReason;
			} else if (!sent.box().equals(box) || !sent.state().sameSize(current)) {
				fullReason = Tr.t("範囲の変更");
			} else {
				diff = StateDiffer.diff(sent.state(), sent.version(), current);

				if (diff.isEmpty()) {
					lastPush = new PushResult(PushKind.UNCHANGED, sent.version(), 0, 0, 0, null, trigger);
					autoFailureShown = false;

					if (trigger == Trigger.MANUAL) {
						Messages.info(Tr.f("変化はありません (v{0} のまま)", sent.version()));
					}

					return Outcome.UNCHANGED;
				}

				if (StateDiffer.tooLarge(diff, current.volume())) {
					fullReason = Tr.t("変化が大きい");
					diff = null;
				}
			}

			Api.Snapshot snap;
			PushResult result;

			if (diff != null) {
				try {
					snap = api.patchState(a.sessionId, a.hostToken, diff);
					result = new PushResult(PushKind.DIFF, snap.version, diff.changeCount(), diff.blockEntities().size(), diff.removedBlockEntities().length, null, trigger);
				} catch (ApiException e) {
					if (e.isTooLarge()) {
						// 差分の本文の上限は全体より小さい。全体なら通る
						fullReason = Tr.t("差分が大きすぎる");
					} else if (e.isResyncRequired()) {
						fullReason = Tr.f("サーバと版が食い違った (サーバ v{0})", e.currentVersion());
					} else {
						throw e;
					}

					// 次の PUT が 429 などで失敗しても、もう差分は作らない (次の機会も全体)
					a.sent = null;
					a.firstPushReason = fullReason;
					snap = api.putState(a.sessionId, a.hostToken, current);
					result = new PushResult(PushKind.FULL, snap.version, 0, current.blockEntities().size(), 0, fullReason, trigger);
				}
			} else {
				snap = api.putState(a.sessionId, a.hostToken, current);
				result = new PushResult(PushKind.FULL, snap.version, 0, current.blockEntities().size(), 0, fullReason, trigger);
			}

			long netMs = (System.nanoTime() - t0) / 1_000_000;
			a.sent = new Sent(current, snap.version, box);
			a.lastVersion = snap.version;
			a.region = box;
			a.dimension = dim;
			lastPush = result;

			// ローカルの litematic (サーバには送らない)。手動は毎回、自動は前回の保存から間隔があいたときだけ。
			// 保存しなかった最新の版は覚えておき、終了・切断のときに残す
			LocalSnapshot local = new LocalSnapshot(snap.version, Instant.now(), captured.blocks(), tags, box);
			a.synced = local;

			if (a.snapshots.offer(local, trigger == Trigger.MANUAL, System.currentTimeMillis(), config.localSnapshotIntervalMs())) {
				saveLocal(a, local);
			}

			Optional<CircuitRecord> rec = store.findById(a.circuitId);

			if (rec.isPresent()) {
				CircuitRecord r = rec.get();
				r.region = CircuitRecord.Region.of(box);
				r.dimension = dim;
				CircuitRecord.SessionRecord s = r.session(a.sessionId);

				if (s != null) {
					s.lastVersion = snap.version;
				}

				store.save(r);
			}

			LOGGER.info("[redtact-live] {} push v{} {}: changes={} blockEntities={} removed={} ({}) capture {} ms, send {} ms",
					trigger, snap.version, result.kind(), result.changes(), result.blockEntities(), result.removedBlockEntities(),
					result.reason() == null ? "-" : result.reason(), captureMs, netMs);
			autoFailureShown = false;
			int entities = result.blockEntities() + result.removedBlockEntities();
			String what;

			if (result.kind() == PushKind.DIFF) {
				what = entities > 0 ? Tr.n("差分 {0} ブロック・ブロックエンティティ {1}", result.changes(), entities)
						: Tr.n("差分 {0} ブロック", result.changes());
			} else {
				what = result.reason() == null ? Tr.f("全体 {0}", box.sizeText()) : Tr.f("全体 {0}・{1}", box.sizeText(), result.reason());
			}

			if (trigger == Trigger.MANUAL) {
				Messages.success(Tr.n("同期しました v{1} ({2}。計 {0} ブロック)", snap.blockCount, snap.version, what));
			} else {
				// 自動の push はチャットに出さず、アクションバーに控えめに出す
				Messages.overlay(result.kind() == PushKind.DIFF ? Tr.f("同期 v{0}・差分 {1}", snap.version, result.changes())
						: Tr.f("同期 v{0}・全体", snap.version));
			}

			if (encoded.skipped() > 0) {
				report(trigger, Tr.n("大きすぎるブロックエンティティ {0} 件は送りませんでした (1 件 {1} KiB まで。ローカルの litematic には入っています)",
						encoded.skipped(), BlockEntityNbt.MAX_BYTES / 1024));
			}

			return Outcome.SENT;
		} catch (ApiException e) {
			if (e.isNotFound()) {
				endLocally(a, "not_found");
				Messages.error(Tr.f("セッションはサーバ側で終了していました。メニューの「セッション」から「{0}」を再開できます", a.circuitName));
				return Outcome.ENDED;
			} else if (e.status() == 429 && "rate_limited".equals(e.code())) {
				// 送る回数の上限 (1 秒 4 回)。少し待って次の機会に送る
				if (trigger == Trigger.MANUAL) {
					Messages.info(Tr.f("送る回数の上限 ({0}) に当たったので、少し待って送り直します", e.getMessage()));
				}

				return Outcome.RATE_LIMITED;
			} else if (e.status() == 401 || e.status() == 403) {
				report(trigger, Tr.f("同期できません (権限): {0}", e.describe()));
			} else if (e.status() == 413) {
				report(trigger, Tr.f("回路データが大きすぎます: {0}", e.describe()));
			} else if ("capacity_exceeded".equals(e.code())) {
				report(trigger, Tr.f("サーバのキャッシュが満杯で送れません。時間をおいて試してください ({0})", e.getMessage()));
			} else {
				report(trigger, Tr.f("同期に失敗しました: {0}", e.describe()));
			}

			return Outcome.FAILED;
		} catch (IOException e) {
			LOGGER.error("[redtact-live] スナップショットの保存に失敗", e);
			Messages.error(Tr.f("サーバには送れましたが、ローカル保存に失敗しました: {0}", e.getMessage()));
			return Outcome.SENT;
		} catch (RuntimeException e) {
			LOGGER.error("[redtact-live] push に失敗", e);
			report(trigger, Tr.f("同期に失敗しました: {0}", e));
			return Outcome.FAILED;
		}
	}

	// ---------------------------------------------------------------- close

	public void close() {
		Active a = active;

		if (a == null) {
			Messages.error(Tr.t("開いているセッションはありません"));
			return;
		}

		worker.execute(() -> {
			if (active != a) {
				return;
			}

			a.socket.stop();
			String unreached = null;

			try {
				api.deleteSession(a.sessionId, a.hostToken);
			} catch (ApiException e) {
				if (!e.isNotFound()) {
					unreached = e.describe();
				}
			}

			endLocally(a, "host_closed");
			Messages.success(unreached == null ? Tr.f("セッションを終了しました: {0}", a.circuitName)
					: Tr.f("セッションを終了しました: {0} (サーバに届きませんでした: {1}。サーバ側は猶予時間の後に終了します)", a.circuitName, unreached));
		});
	}

	/**
	 * 接続だけ切り、サーバ側のセッションと hostToken は残す (ゲーム終了時と同じ状態)。
	 * 猶予時間内に同じ名前で open すれば同じセッションに再接続し、留守中のコメントは hello.pending で届く。
	 */
	public void suspend() {
		Active a = active;

		if (a == null) {
			return;
		}

		worker.execute(() -> {
			if (active == a) {
				active = null;
				a.socket.stop();
				flushLocal(a);
			}
		});
	}

	/** ゲーム終了時。サーバ側のセッションは消さない (猶予時間内なら次回の open で再接続できる)。 */
	public void shutdown() {
		Active a = active;

		if (a != null && a.socket != null) {
			a.socket.stop();
		}

		if (a != null) {
			worker.execute(() -> flushLocal(a));
		}

		worker.shutdown();
		scheduler.shutdownNow();

		try {
			worker.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** ローカルに残す litematic の元 (保存するときに書き出す)。 */
	record LocalSnapshot(int version, Instant time, RegionBlocks blocks, SortedMap<Integer, CompoundTag> blockEntities, Box box) {
	}

	/** worker で呼ぶ。 */
	private void saveLocal(Active a, LocalSnapshot s) throws IOException {
		store.saveSnapshot(a.circuitId, s.version(), litematic(a, s), s.time());
	}

	private byte[] litematic(Active a, LocalSnapshot s) {
		return LitematicWriter.toBytes(s.blocks(), BlockEntityNbt.forLitematic(s.blockEntities(), s.box().sizeX(), s.box().sizeZ()),
				new LitematicWriter.Metadata(a.circuitName, config.userName(), "redtact-live", dataVersion(), s.time().toEpochMilli()));
	}

	/**
	 * 保存済みの範囲 (回路) をゴミ箱へ移す (#77。{@link LocalStore#trashCircuit})。開催中の回路は移さない。
	 * worker で行う (セッションの開始と同じ順番待ちなので、開いている途中の回路を消すことはない)。移した先を返す。
	 */
	public CompletableFuture<Path> trashCircuit(String circuitId) {
		try {
			return CompletableFuture.supplyAsync(() -> {
				Active a = active;

				if (a != null && a.circuitId.equals(circuitId)) {
					throw new IllegalStateException(Tr.t("開催中のセッションの回路は消せません (終了してから)"));
				}

				try {
					return store.trashCircuit(circuitId, Instant.now());
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}, worker);
		} catch (RejectedExecutionException e) {
			return CompletableFuture.failedFuture(new IllegalStateException(Tr.t("ゲームを終了しています")));
		}
	}

	/** 保管庫へ入れる元 (#73): 開催中の回路の最後に同期した版の .litematic。 */
	public record Export(String circuitId, String circuitName, int version, byte[] litematic) {
	}

	/** 最後に同期した版を保管庫へ入れられない理由 (入れられるなら null)。 */
	public @Nullable String exportUnavailableReason() {
		Active a = active;

		if (a == null) {
			return Tr.t("開催中のセッションがありません");
		}

		return a.synced == null ? Tr.t("まだ同期していません (「今すぐ同期」を押すか、自動同期を待ってください)") : null;
	}

	/**
	 * 開催中の回路の最後に同期した版を .litematic にする (#73)。中継サーバから取らないので、複製の許可に関係なく作れる。
	 * worker で作るので、送っている途中の push が済んでからの版になる。
	 */
	public CompletableFuture<Export> exportSynced() {
		Active a = active;

		if (a == null || a.synced == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(exportUnavailableReason()));
		}

		try {
			return CompletableFuture.supplyAsync(() -> {
				LocalSnapshot s = a.synced;
				return new Export(a.circuitId, a.circuitName, s.version(), litematic(a, s));
			}, worker);
		} catch (RejectedExecutionException e) {
			return CompletableFuture.failedFuture(new IllegalStateException(Tr.t("ゲームを終了しています")));
		}
	}

	/** 保存していない最新の版があれば残す (終了・切断のとき)。worker で呼ぶ。 */
	private void flushLocal(Active a) {
		LocalSnapshot s = a.snapshots.drainUnsaved(System.currentTimeMillis());

		if (s == null) {
			return;
		}

		try {
			saveLocal(a, s);
			LOGGER.info("[redtact-live] 保存していなかった v{} をローカルに残しました", s.version());
		} catch (IOException | RuntimeException e) {
			LOGGER.error("[redtact-live] スナップショットの保存に失敗", e);
			Messages.error(Tr.f("最新の版 (v{0}) をローカルに保存できません: {1}", s.version(), e.getMessage()));
		}
	}

	/** ワールドを抜けるとき。セッションは続くが、保存していない最新の版を残す。 */
	public void flushLocalSnapshot() {
		Active a = active;

		if (a != null) {
			worker.execute(() -> flushLocal(a));
		}
	}

	/** worker で呼ぶ。 */
	private void endLocally(Active a, String reason) {
		if (active == a) {
			active = null;
		}

		flushLocal(a);

		if (a.socket != null) {
			a.socket.stop();
		}

		try {
			Optional<CircuitRecord> rec = store.findById(a.circuitId);

			if (rec.isPresent()) {
				CircuitRecord.SessionRecord s = rec.get().session(a.sessionId);

				if (s != null && s.endedAt == null) {
					s.endedAt = Times.now();
					s.endReason = reason;
					s.hostToken = null;
					store.save(rec.get());
				}
			}
		} catch (IOException e) {
			LOGGER.warn("[redtact-live] セッションの終了を記録できません: {}", e.toString());
		}
	}

	// ---------------------------------------------------------------- メニュー・HUD 用

	/** 開催中の回路の未読コメント数 (HUD に出す)。 */
	public int unread() {
		return unread;
	}

	public List<CircuitRecord> listCircuits() {
		try {
			return store.listCircuits();
		} catch (IOException e) {
			LOGGER.warn("[redtact-live] 回路の一覧を読めません: {}", e.toString());
			return List.of();
		}
	}

	public List<StoredComment> comments(String circuitId) {
		try {
			return store.readComments(circuitId);
		} catch (IOException e) {
			LOGGER.warn("[redtact-live] コメントを読めません: {}", e.toString());
			return List.of();
		}
	}

	/** 未読 = 最後に読んだ id より新しい (id は時刻順に並ぶ文字列)。自分 (ホスト) が MOD から書いたものは数えない (#74)。 */
	public static int unreadCount(CircuitRecord rec, List<StoredComment> comments) {
		int n = 0;

		for (StoredComment c : comments) {
			if (isUnread(rec, c)) {
				n++;
			}
		}

		return n;
	}

	public static boolean isUnread(CircuitRecord rec, StoredComment c) {
		return isUnread(rec.lastReadCommentId, c);
	}

	/** lastReadCommentId (最後に読んだ id。無ければ null) より新しく、自分 (ホスト) が書いたものでないか。 */
	public static boolean isUnread(@Nullable String lastReadCommentId, StoredComment c) {
		return !c.byHost() && (lastReadCommentId == null || c.id.compareTo(lastReadCommentId) > 0);
	}

	/** 開催中の回路のコメントを保存するたびに増える (コメントタブが一覧を読み直す目印。#74)。 */
	public long commentsGeneration() {
		return commentsGeneration;
	}

	/** コメントの一覧を見たら、その回路のコメントをすべて既読にする。 */
	public void markRead(String circuitId) {
		worker.execute(() -> {
			try {
				Optional<CircuitRecord> rec = store.findById(circuitId);

				if (rec.isEmpty()) {
					return;
				}

				List<StoredComment> all = store.readComments(circuitId);
				String max = all.stream().map(c -> c.id).max(String::compareTo).orElse(null);

				if (max != null && !max.equals(rec.get().lastReadCommentId)) {
					rec.get().lastReadCommentId = max;
					store.save(rec.get());
				}

				Active a = active;

				if (a != null && a.circuitId.equals(circuitId)) {
					unread = 0;
				}
			} catch (IOException e) {
				LOGGER.warn("[redtact-live] 既読を記録できません: {}", e.toString());
			}
		});
	}

	/** 開催中に、一覧に出すかを切り替える。失敗したらトーストで知らせ、表示はサーバの値のまま。 */
	public void setListed(boolean listed) {
		Active a = active;

		if (a == null) {
			Messages.error(Tr.t("開いているセッションはありません"));
			return;
		}

		a.listedPending = true;
		worker.execute(() -> {
			try {
				Api.Session s = api.setListed(a.sessionId, a.hostToken, listed);
				a.listed = s != null ? s.listed : listed;
				Messages.overlay(a.listed ? Tr.t("開催中の一覧に出しました") : Tr.t("開催中の一覧から外しました"));
			} catch (ApiException e) {
				Messages.error(Tr.f("一覧の切り替えに失敗しました: {0}", e.describe()));
			} finally {
				a.listedPending = false;
			}
		});
	}

	/** 開催中に、複製 (.litematic の取得・読み込み・分岐) を許すかを切り替える。失敗したらトーストで知らせ、表示はサーバの値のまま。 */
	public void setAllowCopy(boolean allowCopy) {
		Active a = active;

		if (a == null) {
			Messages.error(Tr.t("開いているセッションはありません"));
			return;
		}

		a.allowCopyPending = true;
		worker.execute(() -> {
			try {
				Api.Session s = api.setAllowCopy(a.sessionId, a.hostToken, allowCopy);
				a.allowCopy = s != null ? s.copyAllowed() : allowCopy;
				Messages.overlay(a.allowCopy ? Tr.t("複製を許可しました (閲覧者が .litematic を取れます)") : Tr.t("複製を止めました"));
			} catch (ApiException e) {
				Messages.error(Tr.f("複製の切り替えに失敗しました: {0}", e.describe()));
			} finally {
				a.allowCopyPending = false;
			}
		});
	}

	/**
	 * 分岐した子の一覧を入れ替える。前に受け取った一覧があれば、増えた子をトーストで知らせる
	 * (最初の hello・開いた直後の一覧では知らせない)。消えた子は知らせない。
	 */
	private static void updateBranches(Active a, List<Api.Branch> branches) {
		List<Api.Branch> before = a.branches;
		boolean known = a.branchesKnown;
		a.branches = List.copyOf(branches);
		a.branchesKnown = true;
		a.branchGeneration++;

		if (known) {
			List<Api.Branch> added = com.redtact.live.branch.BranchText.added(before, branches);

			// トーストは 1 枚を書き換えるので、まとめて 1 回で出す
			if (added.size() == 1) {
				Messages.notice(Tr.t("分岐が開かれました"), com.redtact.live.branch.BranchText.addedToast(added.getFirst()));
			} else if (added.size() > 1) {
				Messages.notice(Tr.n("分岐が {0} 件開かれました", added.size()),
						String.join(" / ", added.stream().map(com.redtact.live.branch.BranchText::addedToast).toList()));
			}
		}
	}

	/** /rlive comments などの補完用。 */
	public List<String> circuitNames() {
		return listCircuits().stream().map(r -> r.name).filter(n -> n != null).toList();
	}

	public void copyShareLink() {
		Active a = active;

		if (a == null) {
			Messages.error(Tr.t("開いているセッションはありません"));
			return;
		}

		copyToClipboard(a.shareUrl);
		Messages.notice(Tr.t("共有リンクをコピーしました"), a.shareUrl);
	}

	private static void copyToClipboard(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				mc.keyboardHandler.setClipboard(text);
			} catch (RuntimeException e) {
				LOGGER.warn("[redtact-live] クリップボードに書けません: {}", e.toString());
			}
		});
	}

	// ---------------------------------------------------------------- WebSocket

	private final class SocketHandler implements HostSocket.Handler {
		private final Active a;

		SocketHandler(Active a) {
			this.a = a;
		}

		@Override
		public void onHello(Api.Session session, List<Api.Comment> pending, boolean reconnected) {
			if (active != a) {
				return;
			}

			a.online = true;

			if (session != null) {
				a.viewers = session.viewerCount;
				a.listed = session.listed;
				a.allowCopy = session.copyAllowed();
				a.parent = session.parent;
				updateBranches(a, session.branchList());
				a.hostVerified = session.hostVerified;

				if (session.host != null) {
					a.hostName = session.host;
				}

				if (session.snapshot != null) {
					a.lastVersion = Math.max(a.lastVersion, session.snapshot.version);
				}
			}

			if (reconnected) {
				Messages.info(Tr.t("サーバに再接続しました"));
			}

			receive(pending, true);
		}

		@Override
		public void onComment(Api.Comment comment) {
			if (active == a) {
				receive(List.of(comment), false);
			}
		}

		@Override
		public void onBranches(List<Api.Branch> branches) {
			if (active == a) {
				updateBranches(a, branches);
			}
		}

		@Override
		public void onParent(Api.Parent parent) {
			if (active != a) {
				return;
			}

			boolean endedNow = parent.ended && (a.parent == null || !a.parent.ended);
			// 分岐元のホストが同じ回路を開き直すと、中継サーバが新しいセッションに付け直す (#87)
			boolean reopenedNow = !parent.ended && a.parent != null && a.parent.ended;
			a.parent = parent;
			a.branchGeneration++;

			if (endedNow) {
				Messages.info(Tr.f("分岐元「{0}」のセッションが終わりました (このセッションは続きます)", parent.title));
			} else if (reopenedNow) {
				Messages.info(Tr.f("分岐元「{0}」のセッションが開き直されました (分岐元を付け直しました)", parent.title));
			}
		}

		@Override
		public void onViewers(int count) {
			if (active != a) {
				return;
			}

			int before = a.viewers;
			a.viewers = count;

			if (count != before) {
				Messages.overlay(Tr.n("閲覧者 {0} 人", count));
			}
		}

		@Override
		public void onEnded(String reason) {
			if (active != a) {
				return;
			}

			endLocally(a, reason);
			String why = switch (reason) {
				case "host_closed" -> Tr.t("ホストが終了");
				case "expired" -> Tr.t("期限切れ");
				case "not_found" -> Tr.t("サーバにセッションがありません (猶予切れ・サーバ再起動)");
				default -> reason;
			};
			Messages.notice(Tr.t("セッションが終了しました"), Tr.f("{0} ({1})。コメントはメニューの「コメント」で見られます", a.circuitName, why));
		}

		@Override
		public void onRejected(int status) {
			if (active != a) {
				return;
			}

			endLocally(a, "unauthorized");
			Messages.error(Tr.f("ホストのトークンが通りません (HTTP {0})。メニューの「セッション」から開き直してください", status));
		}

		@Override
		public void onReplaced() {
			if (active != a) {
				return;
			}

			// セッションは別の接続 (別のクライアント) が引き継いだので、終了扱いにはしない
			active = null;
			flushLocal(a);
			Messages.error(Tr.f("別の接続がセッション「{0}」を引き継いだため、こちらは切断しました", a.circuitName));
		}

		@Override
		public void onDisconnected(String why, long retryMillis, int attempt) {
			if (active != a) {
				return;
			}

			a.online = false;

			if (attempt == 0 || attempt % 5 == 4) {
				Messages.error(Tr.n("サーバとの接続が切れました ({1})。{0} 秒後に再接続します", Math.max(1, retryMillis / 1000), why));
			}
		}

		private void receive(List<Api.Comment> comments, boolean fromHello) {
			if (comments.isEmpty()) {
				return;
			}

			LocalStore.AppendResult res;

			try {
				res = store.appendComments(a.circuitId, a.sessionId, comments);
			} catch (IOException e) {
				// 保存できないものは ack しない (次の接続でもう一度届く)
				LOGGER.error("[redtact-live] コメントを保存できません", e);
				Messages.error(Tr.f("コメントをローカルに保存できません: {0}", e.getMessage()));
				return;
			}

			a.socket.sendAck(res.stored());
			List<Api.Comment> stored = res.added();

			if (stored.isEmpty()) {
				return;
			}

			commentsGeneration++;
			// 自分 (ホスト) が MOD から書いたものは知らせない (#74)
			List<Api.Comment> added = stored.stream().filter(c -> !c.byHost()).toList();

			if (added.isEmpty()) {
				return;
			}

			// 新着はトーストで知らせ、HUD の未読を増やす。一覧はメニューの「コメント」
			unread += added.size();
			Api.Comment last = added.getLast();

			if (added.size() == 1) {
				Messages.commentToast(last.author, last.text == null ? "" : last.text);
			} else {
				Messages.commentToast(fromHello ? Tr.n("留守中のコメント {0} 件", added.size()) : Tr.n("コメント {0} 件", added.size()),
						last.author + ": " + (last.text == null ? "" : last.text));
			}
		}
	}

	// ---------------------------------------------------------------- util

	/** いまのワールドの識別子。シングルプレイはセーブのフォルダ名、マルチプレイはサーバのアドレス。 */
	public static String worldKey(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();

		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "singleplayer:" + (root.getFileName() == null ? root : root.getFileName());
		}

		ServerData data = mc.getCurrentServer();
		return data != null ? "server:" + data.ip : "unknown";
	}

	public static String dimensionId(ClientLevel level) {
		return level.dimension().identifier().toString();
	}

	public static int dataVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}
}
