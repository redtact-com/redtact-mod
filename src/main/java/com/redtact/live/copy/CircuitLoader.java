package com.redtact.live.copy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import com.redtact.live.ui.EventLog;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * 開催中の回路を .litematic で取ってきて保存し、Litematica が入っていれば配置まで作る。
 * 取得と保存は専用のスレッド、Litematica の呼び出しはクライアントスレッド。
 */
public final class CircuitLoader {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	/** schematics の下のこの名前のフォルダに、セッションごとに置く。 */
	public static final String FOLDER = "redtact-live";

	private final LiveApi api;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-copy").factory());
	private final AtomicBoolean busy = new AtomicBoolean();
	private volatile @Nullable Result last;

	/** 直前の読み込みの結果 (テスト・表示用)。placed が false なら保存だけ。 */
	public record Result(String sessionId, String title, int version, Path file, boolean placed, boolean replaced, @Nullable BlockPos origin,
			Purpose purpose) {
	}

	/** 何のための読み込みか (配置の名前とトーストが変わる)。 */
	public enum Purpose {
		/** 開催中の回路を足元に読み込む (#12)。 */
		LOAD(" (redtact-live)"),
		/** 分岐の準備: 分岐元の回路を足元に出す (#13)。トーストは呼び出し側。 */
		BRANCH(Tr.ja(" (redtact-live 分岐元)")),
		/** 分岐した子の回路を、自分の範囲の最小の角に重ねる (#13)。 */
		OVERLAY(Tr.ja(" (redtact-live 分岐)"));

		final String nameSuffix;

		Purpose(String nameSuffix) {
			this.nameSuffix = nameSuffix;
		}
	}

	/**
	 * 読み込みの頼み。fixedOrigin を渡せば足元ではなくそこに置く。then は保存・配置まで済んだあとに
	 * クライアントスレッドで呼ぶ (失敗したら呼ばない)。
	 */
	public record Request(String sessionId, @Nullable String titleHint, Purpose purpose, @Nullable BlockPos fixedOrigin,
			@Nullable Consumer<Result> then) {
		public static Request load(String sessionId, @Nullable String titleHint) {
			return new Request(sessionId, titleHint, Purpose.LOAD, null, null);
		}
	}

	public CircuitLoader(LiveApi api) {
		this.api = api;
	}

	public boolean isBusy() {
		return busy.get();
	}

	public @Nullable Result last() {
		return last;
	}

	/** Litematica が入っているか (配置まで作れるか)。 */
	public static boolean litematicaLoaded() {
		return LitematicaSupport.available();
	}

	/** 共有リンクかセッション ID から読み込む (クライアントスレッドから)。解釈できなければトースト。 */
	public void load(String linkOrId) {
		Optional<String> id = CopyLinks.sessionId(linkOrId);

		if (id.isEmpty()) {
			Messages.error(Tr.t("共有リンク (…/s/<ID>) かセッション ID (英数字 10 文字) を入れてください"));
			return;
		}

		loadSession(id.get(), null);
	}

	/** セッション ID で読み込む (クライアントスレッドから)。titleHint はファイル名にタイトルが無いときに使う。 */
	public void loadSession(String sessionId, @Nullable String titleHint) {
		load(Request.load(sessionId, titleHint));
	}

	/** 読み込む (クライアントスレッドから)。 */
	public void load(Request req) {
		String sessionId = req.sessionId();

		// ID はそのままフォルダ名になるので、英数字 10 文字以外は受けない (一覧の値もサーバから来たもの)
		if (!CopyLinks.sessionId(sessionId).map(sessionId::equals).orElse(false)) {
			Messages.error(Tr.f("セッション ID の形が違います: {0}", sessionId));
			return;
		}

		if (!busy.compareAndSet(false, true)) {
			Messages.error(Tr.t("前の読み込みがまだ終わっていません"));
			return;
		}

		Path dir = schematicsDir().resolve(FOLDER).resolve(sessionId);
		Messages.info(Tr.t("回路を取ってきています…"));
		worker.execute(() -> {
			try {
				download(req, dir);
			} finally {
				busy.set(false);
			}
		});
	}

	private void download(Request req, Path dir) {
		String sessionId = req.sessionId();
		String titleHint = req.titleHint();
		LiveApi.Litematic lit;

		try {
			lit = api.downloadLitematic(sessionId);
		} catch (ApiException e) {
			Messages.error(Tr.f("回路を読み込めません: {0}", CopyLinks.failureText(e)));
			return;
		}

		String fromName = CopyLinks.titleFromFileName(lit.fileName(), lit.version());
		// filename* が無ければ filename (= <id>-v<版>) からしか取れないので、行のタイトルを優先する
		String title = fromName != null && !fromName.equals(sessionId) ? fromName : titleHint != null && !titleHint.isBlank() ? titleHint : sessionId;
		Path file = dir.resolve(CopyLinks.safeFileName(title + "-v" + lit.version(), sessionId + "-v" + lit.version()));

		try {
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, ".download-", ".tmp");

			try {
				Files.write(tmp, lit.data());
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (IOException e) {
			LOGGER.warn("[redtact-live] .litematic を保存できません: {}", e.toString());
			Messages.error(Tr.f(".litematic を保存できません: {0}", e.getMessage()));
			return;
		}

		int version = lit.version();
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> place(req, title, version, file, mc));
	}

	private void place(Request req, String title, int version, Path file, Minecraft mc) {
		String sessionId = req.sessionId();

		if (!LitematicaSupport.available() || mc.player == null) {
			Result r = new Result(sessionId, title, version, file, false, false, null, req.purpose());
			last = r;
			Messages.notice(Tr.t(".litematic を保存しました"), LitematicaSupport.available() ? title + " v" + version + " → " + shortPath(file)
					: Tr.f("{0} v{1} → {2} (Litematica を入れると、その場に配置まで作ります)", title, version, shortPath(file)));
			finish(req, r);
			return;
		}

		Result r;

		try {
			String name = title + " v" + version + Tr.t(req.purpose().nameSuffix);
			LitematicaSupport.Placed p = LitematicaSupport.place(sessionId, file, name, mc.player.blockPosition(), req.fixedOrigin());
			r = new Result(sessionId, title, version, file, true, p.replaced(), p.origin(), req.purpose());
			last = r;
		} catch (RuntimeException e) {
			LOGGER.warn("[redtact-live] Litematica に読み込めません", e);
			last = new Result(sessionId, title, version, file, false, false, null, req.purpose());
			Messages.error(Tr.f("Litematica に読み込めません ({0})。保存はしました: {1}", e.getMessage(), shortPath(file)));
			return;
		}

		switch (req.purpose()) {
			case LOAD -> Messages.notice(Tr.t("Litematica に読み込みました"), r.replaced()
					? Tr.f("{0} v{1}・前の配置と同じ位置に置き直しました", title, version)
					: Tr.f("{0} v{1}・足元 ({2}) に置きました", title, version, r.origin().toShortString()));
			case OVERLAY -> Messages.notice(Tr.t("分岐を重ねました"), r.replaced()
					? Tr.f("{0} v{1} を自分の範囲の角 ({2}) に置き直しました。違いは Litematica の検証 (Schematic Verifier) で", title, version, r.origin().toShortString())
					: Tr.f("{0} v{1} を自分の範囲の角 ({2}) に置きました。違いは Litematica の検証 (Schematic Verifier) で", title, version, r.origin().toShortString()));
			case BRANCH -> {
				// 案内のトーストは呼び出し側 (分岐の準備)
			}
		}

		finish(req, r);
	}

	private static void finish(Request req, Result r) {
		if (req.then() != null) {
			try {
				req.then().accept(r);
			} catch (RuntimeException e) {
				LOGGER.warn("[redtact-live] 読み込みの後の処理に失敗", e);
			}
		}
	}

	/** Litematica の schematics フォルダ (設定で変えていればそちら)。無ければ {@code <game>/schematics}。 */
	public static Path schematicsDir() {
		if (LitematicaSupport.available()) {
			try {
				return LitematicaSupport.schematicsDir();
			} catch (RuntimeException | LinkageError e) {
				EventLog.add(EventLog.Level.ERROR, Tr.f("Litematica の schematics フォルダが分かりません: {0}", e));
			}
		}

		return FabricLoader.getInstance().getGameDir().resolve("schematics");
	}

	/** ゲームフォルダからの相対パス (外ならそのまま)。 */
	private static String shortPath(Path file) {
		Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path abs = file.toAbsolutePath().normalize();
		return abs.startsWith(game) ? game.relativize(abs).toString() : abs.toString();
	}
}
