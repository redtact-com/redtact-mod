package com.redtact.live.gui.rows;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.preview.PreviewStore;
import org.jspecify.annotations.Nullable;

/** 開催中の一覧の 1 行。url はサーバの shareUrl (無ければ null でボタンを押せない)。 */
public record LiveRow(Api.Session session, boolean mine, @Nullable String url, String ago) {
	/** 題 (空なら「(無題)」)。 */
	public String title() {
		return session.title == null || session.title.isBlank() ? Tr.t("(無題)") : session.title;
	}

	/** ホストの名前 (確認済みなら前に ✔)。 */
	public String hostLabel() {
		return (session.hostVerified ? "✔ " : "") + (session.host == null ? "" : session.host);
	}

	/** ホストの redtact の名前 (公開した連携が無ければ null。#90)。 */
	public @Nullable String redtactName() {
		Api.HostUser u = session.hostUser;
		return u == null || u.name == null || u.name.isBlank() ? null : u.name;
	}

	/** ホストの顔と名前に重ねたときのツールチップ (#90): 名前と redtact の名前。redtact の名前が無ければ null (切ったときだけ全文)。 */
	public @Nullable String hostTooltip() {
		String name = redtactName();
		return name == null ? null : hostLabel() + "\n" + Tr.f("redtact: {0}", name);
	}

	/** 視聴中と閲覧数の短い字 (例「視聴中 3・閲覧数 12」。古い中継サーバで閲覧数が無ければ視聴中だけ。#90)。 */
	public String audienceText() {
		String watching = Tr.n("視聴中 {0}", session.viewerCount);
		return session.views == null ? watching : Tr.f("{0}・{1}", watching, Tr.n("閲覧数 {0}", session.views));
	}

	/** カードの右上の数に重ねたときのツールチップ (#90)。 */
	public String audienceTooltip() {
		String watching = Tr.n("視聴中 {0} 人 (Web の画面と、MOD で詳細を開いている人)", session.viewerCount);
		return session.views == null ? watching : watching + "\n" + Tr.n("閲覧数 {0} (開始からの延べ人数。同じ回線は 1 回・ホストは数えない)", session.views);
	}

	/** 大きさ (例「12×5×8」)。未同期なら null。 */
	public @Nullable String sizeText() {
		Api.Snapshot snap = session.snapshot;

		if (snap == null || snap.size == null || snap.size.length != 3) {
			return null;
		}

		return snap.size[0] + "×" + snap.size[1] + "×" + snap.size[2];
	}

	/**
	 * カードのサムネを取りにいかない理由 (短く。サムネの代わりに出す)。取りにいくなら null (#56)。
	 * 詳細のプレビューの説明は LiveListScreen.placeholder (長い文)。
	 */
	public @Nullable String thumbBlockedReason() {
		Api.Snapshot snap = session.snapshot;

		if (snap == null || snap.size == null || snap.size.length != 3) {
			return Tr.t("まだ同期されていません");
		}

		if ((long) snap.size[0] * snap.size[1] * snap.size[2] > PreviewStore.MAX_VOLUME) {
			return Tr.t("範囲が大きいので絵はありません");
		}

		return null;
	}

	/** 「Litematica に読み込む」を押せない理由。押せるなら null。 */
	public @Nullable String loadBlockedReason() {
		if (!session.copyAllowed()) {
			return Tr.t("ホストが複製を許可していません");
		}

		if (session.snapshot == null) {
			return Tr.t("まだ回路が同期されていません");
		}

		return null;
	}

	/** 「分岐する」を押せない理由。押せるなら null。 */
	public @Nullable String branchBlockedReason() {
		String reason = loadBlockedReason();

		if (reason != null) {
			return reason;
		}

		if (mine) {
			return Tr.t("自分のセッションからは分岐できません");
		}

		return com.redtact.live.branch.BranchManager.unavailableReason();
	}
}
