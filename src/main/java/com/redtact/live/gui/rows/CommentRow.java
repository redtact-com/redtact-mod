package com.redtact.live.gui.rows;

import java.util.List;

import com.redtact.live.store.StoredComment;

/**
 * コメントの 1 件 (折り返した行を持つ)。
 *
 * @param currentSession いま見ているセッションのコメントか (違えば時刻の横にセッション ID を出す)
 * @param mine           自分が書いたものか (他人のセッションの詳細で「自分」の印。ホストの印があれば出さない)
 */
public record CommentRow(StoredComment comment, boolean unread, boolean currentSession, boolean mine, List<String> wrapped) {
	public CommentRow(StoredComment comment, boolean unread, boolean currentSession, List<String> wrapped) {
		this(comment, unread, currentSession, false, wrapped);
	}
}
