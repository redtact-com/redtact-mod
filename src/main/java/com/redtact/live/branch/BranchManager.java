package com.redtact.live.branch;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.copy.CopyLinks;
import com.redtact.live.copy.LitematicaSupport;
import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.selection.Box;
import com.redtact.live.selection.Selection;
import com.redtact.live.session.SessionManager;
import com.redtact.live.ui.Messages;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * 分岐 (#13)。子の側: 分岐元を Litematica の配置として足元に出し (分岐の準備)、貼り付けてもらってから
 * 配置の範囲で parent 付きのセッションを開く。親の側: 分岐した子の回路を自分の範囲の最小の角に重ねる。
 * すべてクライアントスレッドから呼ぶ。
 */
public final class BranchManager {
	private final SessionManager sessions;
	private final CircuitLoader loader;
	private final Selection selection;
	private volatile @Nullable Prep prep;

	/**
	 * 分岐の準備。sessionId・title・version は分岐元 (読み込んだ版)、file は保存した .litematic。
	 * 配置は Litematica の中にあり、sessionId で引く (利用者が動かしてもよい)。
	 */
	public record Prep(String sessionId, String title, @Nullable String host, int version, Path file) {
		public String defaultName() {
			return BranchText.defaultName(title);
		}
	}

	public BranchManager(SessionManager sessions, CircuitLoader loader, Selection selection) {
		this.sessions = sessions;
		this.loader = loader;
		this.selection = selection;
	}

	public @Nullable Prep prep() {
		return prep;
	}

	/** 分岐できる環境か (Litematica が要る)。できなければ理由。 */
	public static @Nullable String unavailableReason() {
		return LitematicaSupport.available() ? null : Tr.t("分岐には Litematica が要ります");
	}

	/** 共有リンクかセッション ID の分岐元で、分岐の準備をする。 */
	public void prepare(String linkOrId) {
		Optional<String> id = CopyLinks.sessionId(linkOrId);

		if (id.isEmpty()) {
			Messages.error(Tr.t("共有リンク (…/s/<ID>) かセッション ID (英数字 10 文字) を入れてください"));
			return;
		}

		prepare(id.get(), null, null);
	}

	/**
	 * 分岐の準備: 分岐元の回路を Litematica の配置として足元に出し (同じ分岐元の配置があればその位置のまま)、
	 * 分岐元と版を覚える。title・host は一覧の行から (無ければファイル名のタイトル)。
	 */
	public void prepare(String sessionId, @Nullable String title, @Nullable String host) {
		String reason = unavailableReason();

		if (reason != null) {
			Messages.error(Tr.f("{0} (分岐元の回路を配置として出し、貼り付けてから開くため)", reason));
			return;
		}

		SessionManager.Active a = sessions.active();

		if (a != null && a.sessionId.equals(sessionId)) {
			Messages.error(Tr.t("自分のセッションからは分岐できません (分岐を開くには、いまのセッションを終える必要があるため)"));
			return;
		}

		loader.load(new CircuitLoader.Request(sessionId, title, CircuitLoader.Purpose.BRANCH, null, r -> {
			if (!r.placed()) {
				Messages.error(Tr.t("分岐の準備ができません: Litematica に配置を作れませんでした"));
				return;
			}

			prep = new Prep(sessionId, r.title(), host, r.version(), r.file());
			Messages.notice(Tr.t("分岐の準備ができました"), Tr.f("「{0}」v{1} を足元に出しました。Litematica で位置を合わせて"
					+ "貼り付けてから、メニューの live の「セッション」で「この配置で分岐を開始」を押してください", r.title(), r.version()));
		}));
	}

	/** 分岐の準備をやめる (Litematica の配置はそのまま残す)。 */
	public void cancel() {
		Prep p = prep;
		prep = null;

		if (p == null) {
			Messages.error(Tr.t("分岐の準備はありません"));
		} else {
			Messages.info(Tr.t("分岐の準備をやめました (Litematica の配置は残しています)"));
		}
	}

	/** いまの配置の様子 (準備が無い・配置が無ければ null)。 */
	public LitematicaSupport.@Nullable PlacementInfo placementInfo(boolean countMatches) {
		Prep p = prep;
		ClientLevel level = Minecraft.getInstance().level;
		return p == null ? null : LitematicaSupport.placementInfo(p.sessionId(), countMatches ? level : null);
	}

	/**
	 * 「この配置で分岐を開始」: 配置の範囲 (回転・反転していれば囲む箱) を範囲にして、parent 付きのセッションを開く。
	 * 範囲の中に schematic のブロックが 1 つも無ければ (まだ貼り付けていない) 開かない。
	 *
	 * @param rawName 空なら「<分岐元> の分岐」
	 * @param listed 一覧に出すか (開くときだけ読む。断ったときは選択を消費しない)
	 * @param allowCopy 複製を許すか (同上)
	 */
	public void start(@Nullable String rawName, BooleanSupplier listed, BooleanSupplier allowCopy) {
		Prep p = prep;

		if (p == null) {
			Messages.error(Tr.t("分岐の準備がありません。メニューの live の「開催中」で「分岐する」を押してください"));
			return;
		}

		SessionManager.Active a = sessions.active();

		if (a != null) {
			Messages.error(Tr.f("セッション「{0}」が開いています。先に終了してください (メニューの live の「セッション」)", a.circuitName));
			return;
		}

		ClientLevel level = Minecraft.getInstance().level;
		LitematicaSupport.PlacementInfo info = level == null ? null : LitematicaSupport.placementInfo(p.sessionId(), level);

		if (info == null) {
			Messages.error(Tr.t("分岐元の Litematica の配置が見つかりません。外したなら、もう一度「分岐する」を押してください"));
			return;
		}

		if (info.matched() == 0) {
			Messages.error(Tr.t("まだ貼り付けていないようです: 配置の範囲に分岐元のブロックが 1 つもありません。Litematica で貼り付けてから押してください"));
			return;
		}

		Box box = info.box();
		String err = box.limitError();

		if (err != null) {
			Messages.error(Tr.f("配置の範囲が上限を超えています: {0}", err));
			return;
		}

		String name = rawName == null || rawName.isBlank() ? p.defaultName() : rawName.strip();
		selection.set(box, SessionManager.dimensionId(level));
		sessions.openBranch(name, listed.getAsBoolean(), allowCopy.getAsBoolean(), new Api.ParentRef(p.sessionId(), p.version()));
	}

	/** クライアントの tick ごとに呼ぶ。準備した分岐元から分岐したセッションが開いたら、準備を片付ける。 */
	public void tick() {
		Prep p = prep;
		SessionManager.Active a = sessions.active();

		if (p != null && a != null && a.parent() != null && p.sessionId().equals(a.parent().sessionId)) {
			prep = null;
		}
	}

	/**
	 * 親の側: 分岐した子の回路を、自分のセッションの範囲の最小の角に重ねる (同じ子を読み直したら同じ原点で置き換える)。
	 * Litematica が無ければ保存だけ。
	 */
	public void overlay(Api.Branch b) {
		SessionManager.Active a = sessions.active();

		if (a == null) {
			Messages.error(Tr.t("開いているセッションはありません"));
			return;
		}

		if (!b.copyAllowed()) {
			Messages.error(Tr.f("「{0}」のホストが複製を許可していません", b.title));
			return;
		}

		Box r = a.region();
		loader.load(new CircuitLoader.Request(b.sessionId, b.title, CircuitLoader.Purpose.OVERLAY, new BlockPos(r.minX(), r.minY(), r.minZ()), null));
	}
}
