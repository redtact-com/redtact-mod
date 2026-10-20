package com.redtact.live.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.redtact.live.net.Api;
import org.junit.jupiter.api.Test;

class CommentRulesTest {
	private static Api.Comment comment(String author, Boolean verified, String uuid) {
		Api.Comment c = new Api.Comment();
		c.id = "c_1";
		c.author = author;
		c.authorVerified = verified;
		c.authorUuid = uuid;
		return c;
	}

	@Test
	void mineBySignedInUuidOrDisplayName() {
		String me = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
		assertTrue(CommentRules.isMine(comment("Steve", true, me), me, "表示名"));
		assertTrue(CommentRules.isMine(comment("Steve", true, me.toUpperCase()), me, null), "UUID の大文字小文字は見ない");
		assertFalse(CommentRules.isMine(comment("Steve", true, "00000000-0000-0000-0000-000000000001"), me, "Steve"), "同じ名前でも UUID が違えば別人");
		assertFalse(CommentRules.isMine(comment("Steve", true, me), null, "Steve"));
		// 表示名だけのコメント (開発用) は名前で照らす
		assertTrue(CommentRules.isMine(comment("テスト ホスト", false, null), me, "テスト ホスト"));
		assertFalse(CommentRules.isMine(comment("テスト ホスト", null, null), me, "テスト ホスト"), "古いサーバのコメント (確認の有無が無い) は自分と決めない");
		assertFalse(CommentRules.isMine(comment("hana", false, null), me, "テスト ホスト"));
	}

	@Test
	void commentsTabWritesOnlyToTheLiveCircuit() {
		assertEquals("セッションを開くと、ここから閲覧者に返事を書けます", CommentRules.blockedReason(null, null, "c1"));
		assertEquals("書けるのは開催中の回路「自動仕分け機」だけです", CommentRules.blockedReason("c2", "自動仕分け機", "c1"));
		assertEquals("書けるのは開催中の回路「自動仕分け機」だけです", CommentRules.blockedReason("c2", "自動仕分け機", null));
		assertNull(CommentRules.blockedReason("c1", "自動仕分け機", "c1"));
	}
}
