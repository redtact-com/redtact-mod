package com.redtact.live.branch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;

/** 分岐 (#13) の文言と小さな計算 (MC に依存しない)。 */
public final class BranchText {
	/** セッション名の上限 (SessionManager と同じ)。 */
	public static final int MAX_NAME = 64;

	private BranchText() {
	}

	/** 分岐の名前の既定「<親のタイトル> の分岐」。長ければ親のタイトルを詰める。 */
	public static String defaultName(String parentTitle) {
		String format = Tr.t("{0} の分岐");
		String t = parentTitle == null || parentTitle.isBlank() ? Tr.t("回路") : parentTitle.strip();
		int room = MAX_NAME - (format.length() - "{0}".length());

		if (t.codePointCount(0, t.length()) > room) {
			t = t.substring(0, t.offsetByCodePoints(0, room - 1)) + "…";
		}

		return Tr.f("{0} の分岐", t);
	}

	/** 分岐して開けなかったときの説明 (POST /api/sessions に parent を付けたとき)。 */
	public static String createError(ApiException e) {
		String code = e.code();

		if ("parent_not_found".equals(code)) {
			return Tr.t("分岐元のセッションが終わっています (分岐はできません)");
		}

		if ("copy_not_allowed".equals(code)) {
			return Tr.t("分岐元のホストが複製を許可していません");
		}

		if ("bad_request".equals(code)) {
			return Tr.f("分岐元の版が合いません ({0})", e.getMessage());
		}

		if ("limit_reached".equals(code)) {
			return Tr.f("上限です: {0}", e.getMessage());
		}

		return Tr.f("分岐して開けません: {0}", e.describe());
	}

	/** before に無かった子 (sessionId で比べる)。並びは after のまま。 */
	public static List<Api.Branch> added(List<Api.Branch> before, List<Api.Branch> after) {
		Set<String> known = new HashSet<>();

		for (Api.Branch b : before) {
			known.add(b.sessionId);
		}

		List<Api.Branch> out = new ArrayList<>();

		for (Api.Branch b : after) {
			if (b.sessionId != null && !known.contains(b.sessionId)) {
				out.add(b);
			}
		}

		return out;
	}

	/** ホスト名 (確認済みなら ✔ 付き)。 */
	public static String host(String name, boolean verified) {
		return (verified ? "✔ " : "") + (name == null ? "?" : name);
	}

	/** 子のセッションタブの「分岐元」の行。 */
	public static String parentLine(Api.Parent p) {
		String host = host(p.host, p.hostVerified);
		return p.ended ? Tr.f("分岐元: 「{0}」 {1} の v{2}・§c終了§r", p.title, host, p.version)
				: Tr.f("分岐元: 「{0}」 {1} の v{2}・§a開催中§r", p.title, host, p.version);
	}

	/** 子のセッションタブの「分岐元」の行 (色の記号なし。メニューの画面で色を付けて描く)。 */
	public static String parentText(Api.Parent p) {
		String host = host(p.host, p.hostVerified);
		return p.ended ? Tr.f("分岐元: 「{0}」 {1} の v{2}・終了", p.title, host, p.version)
				: Tr.f("分岐元: 「{0}」 {1} の v{2}・開催中", p.title, host, p.version);
	}

	/** 「分岐が開かれました」のトーストの本文。 */
	public static String addedToast(Api.Branch b) {
		return Tr.f("{0} ({1})・v{2} から", b.title, host(b.host, b.hostVerified), b.fromVersion);
	}
}
