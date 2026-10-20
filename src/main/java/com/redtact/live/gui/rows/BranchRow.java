package com.redtact.live.gui.rows;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import org.jspecify.annotations.Nullable;

/** 自分のセッションから分岐して開催中の子 (#13)。 */
public record BranchRow(Api.Branch branch) implements SessionRow {
	/** 「重ねて読み込む」を押せない理由。押せるなら null。 */
	public @Nullable String overlayBlockedReason() {
		return branch.copyAllowed() ? null : Tr.t("子のホストが複製を許可していません");
	}
}
