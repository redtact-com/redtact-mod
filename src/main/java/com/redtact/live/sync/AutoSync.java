package com.redtact.live.sync;

import com.redtact.live.config.LiveConfig;
import com.redtact.live.selection.Box;
import com.redtact.live.session.SessionManager;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 自動同期 (F-M5)。範囲内のブロックの変化を検知して、{@link Debouncer} の判定で push する。
 *
 * <p>検知は mixin から {@link #onBlockChanged} / {@link #onServerBlockEntityChanged} が呼ばれる
 * (どのスレッドからでもよい)。判定と push の起動は {@link #tick} (クライアントスレッド)。
 * 毎 tick の全走査はしない。
 */
public final class AutoSync implements SessionManager.PushListener {
	/** 429 rate_limited の後の待ち。 */
	static final long RATE_LIMIT_RETRY_MS = 1_000;
	/** チャンク未読み込みなど、まだ送れないときの待ち。 */
	static final long NOT_READY_RETRY_MS = 5_000;
	static final long MAX_FAILURE_RETRY_MS = 60_000;

	private static volatile @Nullable AutoSync instance;

	/** いま見ている範囲。null なら何も拾わない。 */
	private record Watch(ResourceKey<Level> dimension, Box box, @Nullable MinecraftServer server) {
		boolean contains(BlockPos pos) {
			return box.contains(pos.getX(), pos.getY(), pos.getZ());
		}

		boolean overlapsChunk(int chunkX, int chunkZ) {
			return chunkX >= box.minX() >> 4 && chunkX <= box.maxX() >> 4 && chunkZ >= box.minZ() >> 4 && chunkZ <= box.maxZ() >> 4;
		}
	}

	private final LiveConfig config;
	private final SessionManager sessions;
	private final Debouncer debouncer;
	private volatile @Nullable Watch watch;
	/** 送っている最中の変化 (失敗したら戻す)。 */
	private volatile Debouncer.@Nullable Pending inFlight;
	private int failures;
	/** 手動の push が 429 だったときに送り直す時刻 (-1 なら無し)。自動同期が off でも送り直す。 */
	private volatile long manualRetryAt = -1;
	private volatile int manualRetries;
	static final int MAX_MANUAL_RETRIES = 5;
	// worldKey のキャッシュ (レベルが変わったときだけ計算し直す)
	private @Nullable ClientLevel cachedLevel;
	private String cachedWorldKey = "";
	// 前の tick の同期の対象と、そのセッション (開催中に角を動かしたら送る。#44)
	private @Nullable String targetSessionId;
	private SessionManager.@Nullable Target lastTarget;

	public AutoSync(LiveConfig config, SessionManager sessions) {
		this.config = config;
		this.sessions = sessions;
		this.debouncer = new Debouncer(config.autoSyncQuietMs(), config.autoSyncMaxDelayMs());
		sessions.setPushListener(this);
		instance = this;
	}

	public boolean enabled() {
		return config.autoSync();
	}

	public void setEnabled(boolean enabled) {
		config.setAutoSync(enabled);
	}

	public Debouncer debouncer() {
		return debouncer;
	}

	public boolean watching() {
		return watch != null;
	}

	// ---------------------------------------------------------------- 検知 (mixin から。どのスレッドでも)

	/** クライアントのワールドでブロック状態が変わった (ClientLevel.setBlocksDirty)。 */
	public static void onBlockChanged(Level level, BlockPos pos) {
		AutoSync self = instance;

		if (self == null) {
			return;
		}

		Watch w = self.watch;

		if (w != null && level.isClientSide() && level.dimension().equals(w.dimension()) && w.contains(pos)) {
			self.debouncer.markChanged(System.currentTimeMillis());
		}
	}

	/** クライアントにブロックエンティティのデータが届いた (看板の文字など)。 */
	public static void onClientBlockEntityData(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;

		if (level != null) {
			onBlockChanged(level, pos);
		}
	}

	/**
	 * 統合サーバ (シングルプレイ) のブロックエンティティが変わった (Level.blockEntityChanged =
	 * チェストの中身など setChanged、ComparatorBlockEntity.setOutputSignal)。サーバスレッドから呼ばれる。
	 */
	public static void onServerBlockEntityChanged(Level level, BlockPos pos) {
		AutoSync self = instance;

		if (self == null) {
			return;
		}

		Watch w = self.watch;

		if (w != null && w.server() != null && !level.isClientSide() && level.getServer() == w.server()
				&& level.dimension().equals(w.dimension()) && w.contains(pos)) {
			self.debouncer.markChanged(System.currentTimeMillis());
		}
	}

	/** 範囲にかかるチャンクが読み込まれた (離れていたあいだの変化を拾う)。 */
	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		Watch w = watch;

		if (w != null && level.dimension().equals(w.dimension()) && w.overlapsChunk(chunk.getPos().x(), chunk.getPos().z())) {
			debouncer.markChanged(System.currentTimeMillis());
		}
	}

	// ---------------------------------------------------------------- 判定 (クライアントスレッド)

	public void tick(Minecraft mc) {
		Watch w = computeWatch(mc);
		watch = w;
		long retryAt = manualRetryAt;

		if (retryAt > 0 && System.currentTimeMillis() >= retryAt && !sessions.isBusy()) {
			manualRetryAt = -1;

			if (sessions.active() != null) {
				sessions.push(SessionManager.Trigger.MANUAL);
			}

			return;
		}

		if (w == null) {
			if (sessions.active() == null) {
				debouncer.clear(); // セッションが無いあいだの変化は持ち越さない
			}

			return;
		}

		debouncer.setTimings(config.autoSyncQuietMs(), config.autoSyncMaxDelayMs());

		if (!config.autoSync() || sessions.isBusy()) {
			return; // off のあいだも変化は覚えておき、on にしたら送る
		}

		long now = System.currentTimeMillis();

		if (debouncer.due(now) && !sessions.push(SessionManager.Trigger.AUTO)) {
			// 送り始められなかった (範囲が上限を超えたなど)。毎 tick 試さない
			debouncer.postpone(now, NOT_READY_RETRY_MS);
		}
	}

	/** 開いたワールドの、範囲のあるディメンションにいるときだけ見る。 */
	private @Nullable Watch computeWatch(Minecraft mc) {
		SessionManager.Active a = sessions.active();
		ClientLevel level = mc.level;

		if (a == null) {
			targetSessionId = null;
			lastTarget = null;
		}

		if (a == null || level == null) {
			return null;
		}

		if (level != cachedLevel) {
			cachedLevel = level;
			cachedWorldKey = SessionManager.worldKey(mc);
		}

		if (!cachedWorldKey.equals(a.world())) {
			return null;
		}

		SessionManager.Target target = SessionManager.target(a, sessions.selection());
		noteTarget(a.sessionId, target);

		if (!SessionManager.dimensionId(level).equals(target.dimension())) {
			return null;
		}

		Watch current = watch;

		if (current != null && current.box().equals(target.box()) && current.dimension() == level.dimension()
				&& current.server() == mc.getSingleplayerServer()) {
			return current;
		}

		return new Watch(level.dimension(), target.box(), mc.getSingleplayerServer());
	}

	/**
	 * 開催中に同期の対象 (範囲・ディメンション) が変わったら、ブロックが変わっていなくても変化として数える (#44)。
	 * 角を動かすと、自動同期で新しい範囲を全体で送り、保存済みの範囲も更新する。最初の 1 回 (開いた直後) は数えない。
	 */
	private void noteTarget(String sessionId, SessionManager.Target target) {
		if (sessionId.equals(targetSessionId) && lastTarget != null && !lastTarget.equals(target)) {
			debouncer.markChanged(System.currentTimeMillis());
		}

		targetSessionId = sessionId;
		lastTarget = target;
	}

	// ---------------------------------------------------------------- push の前後

	@Override
	public void onPushStarting(SessionManager.Trigger trigger) {
		// 手動の push でも、ここまでの変化はこの push に含まれる
		inFlight = debouncer.take();
	}

	@Override
	public void onPushFinished(SessionManager.Trigger trigger, SessionManager.Outcome outcome) {
		Debouncer.Pending pending = inFlight;
		inFlight = null;
		long now = System.currentTimeMillis();

		switch (outcome) {
			case SENT, UNCHANGED -> {
				failures = 0;

				if (trigger == SessionManager.Trigger.MANUAL) {
					manualRetries = 0;
				}
			}
			case RATE_LIMITED -> {
				debouncer.restore(pending, now, RATE_LIMIT_RETRY_MS);

				if (trigger == SessionManager.Trigger.MANUAL && manualRetries < MAX_MANUAL_RETRIES) {
					manualRetries++;
					manualRetryAt = now + RATE_LIMIT_RETRY_MS;
				}
			}
			case NOT_READY -> debouncer.restore(pending, now, NOT_READY_RETRY_MS);
			case FAILED -> {
				failures = Math.min(failures + 1, 6);
				debouncer.restore(pending, now, Math.min(MAX_FAILURE_RETRY_MS, 1_000L << failures));
			}
			case ENDED -> {
				debouncer.clear();
				manualRetryAt = -1;
			}
		}
	}
}
