package com.redtact.live.comment;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import org.jspecify.annotations.Nullable;

/** MOD からのコメントの判断 (#74。MC に依存しない。テストで確かめる)。 */
public final class CommentRules {
	private CommentRules() {
	}

	/**
	 * 自分が書いたコメントか。サインインしたコメントは UUID で、表示名だけのコメント (開発用) は名前で照らす
	 * (サインインしたコメントは名前が同じでも UUID が違えば別人)。
	 */
	public static boolean isMine(Api.Comment c, @Nullable String myUuid, @Nullable String myName) {
		if (c.authorUuid != null) {
			return myUuid != null && c.authorUuid.equalsIgnoreCase(myUuid);
		}

		return Boolean.FALSE.equals(c.authorVerified) && myName != null && myName.equals(c.author);
	}

	/**
	 * コメントタブの入力欄で書けない理由 (書けるなら null)。書けるのは、出している回路が開催中のセッションの回路のときだけ。
	 *
	 * @param activeCircuitId   開催中のセッションの回路 (開いていなければ null)
	 * @param activeCircuitName その名前
	 * @param shownCircuitId    コメントタブで出している回路 (無ければ null)
	 */
	public static @Nullable String blockedReason(@Nullable String activeCircuitId, @Nullable String activeCircuitName, @Nullable String shownCircuitId) {
		if (activeCircuitId == null) {
			return Tr.t("セッションを開くと、ここから閲覧者に返事を書けます");
		}

		if (!activeCircuitId.equals(shownCircuitId)) {
			return Tr.f("書けるのは開催中の回路「{0}」だけです", activeCircuitName);
		}

		return null;
	}
}
