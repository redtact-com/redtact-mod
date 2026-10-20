package com.redtact.live.store;

import com.redtact.live.net.Api;

/** comments.jsonl の 1 行 (Comment + sessionId)。 */
public final class StoredComment {
	public String id;
	public String sessionId;
	public String author;
	/** マイクラアカウントでサインインした人か。前の版で書いた行と古いサーバのコメントは null。 */
	public Boolean authorVerified;
	/** サインインした人のマイクラの UUID (表示名だけなら null)。 */
	public String authorUuid;
	/**
	 * セッションのホスト本人が書いたか (#74)。comments.jsonl は自分が開いたセッションのコメントなので、true なら自分が MOD から書いたもの。
	 * 前の版で書いた行は null。
	 */
	public Boolean authorIsHost;
	public String text;
	public String createdAt;

	public static StoredComment of(String sessionId, Api.Comment c) {
		StoredComment s = new StoredComment();
		s.id = c.id;
		s.sessionId = sessionId;
		s.author = c.author;
		s.authorVerified = c.authorVerified;
		s.authorUuid = c.authorUuid;
		s.authorIsHost = c.authorIsHost;
		s.text = c.text;
		s.createdAt = c.createdAt;
		return s;
	}

	public boolean byHost() {
		return authorIsHost != null && authorIsHost;
	}
}
