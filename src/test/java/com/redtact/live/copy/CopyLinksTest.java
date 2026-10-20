package com.redtact.live.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.redtact.live.gui.rows.LiveRow;
import com.redtact.live.live.LiveText;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.Json;
import org.junit.jupiter.api.Test;

class CopyLinksTest {
	@Test
	void parsesSessionIdAndShareLinks() {
		assertEquals(Optional.of("a1B2c3D4e5"), CopyLinks.sessionId("a1B2c3D4e5"));
		assertEquals(Optional.of("a1B2c3D4e5"), CopyLinks.sessionId("  a1B2c3D4e5\n"));
		assertEquals(Optional.of("a1B2c3D4e5"), CopyLinks.sessionId("http://localhost:5173/s/a1B2c3D4e5"));
		assertEquals(Optional.of("a1B2c3D4e5"), CopyLinks.sessionId("https://live.redtact.com/s/a1B2c3D4e5/"));
		assertEquals(Optional.of("a1B2c3D4e5"), CopyLinks.sessionId("https://live.redtact.com/s/a1B2c3D4e5?from=x#c"));
		assertEquals(Optional.of("a1B2c3D4e5"), CopyLinks.sessionId("https://example.com/live/s/a1B2c3D4e5"));
		assertEquals(Optional.of("Zz9Yy8Xx7W"), CopyLinks.sessionId("http://127.0.0.1:8797/s/Zz9Yy8Xx7W#top"));

		assertEquals(Optional.empty(), CopyLinks.sessionId(null));
		assertEquals(Optional.empty(), CopyLinks.sessionId(""));
		assertEquals(Optional.empty(), CopyLinks.sessionId("a1B2c3D4e")); // 9 文字
		assertEquals(Optional.empty(), CopyLinks.sessionId("a1B2c3D4e5x")); // 11 文字
		assertEquals(Optional.empty(), CopyLinks.sessionId("a1B2c3-4e5"));
		assertEquals(Optional.empty(), CopyLinks.sessionId("http://localhost:5173/x/a1B2c3D4e5"));
		assertEquals(Optional.empty(), CopyLinks.sessionId("http://localhost:5173/s/a1B2c3D4e5/extra"));
		assertEquals(Optional.empty(), CopyLinks.sessionId("ftp://localhost/s/a1B2c3D4e5"));
		assertEquals(Optional.empty(), CopyLinks.sessionId("自動仕分け機"));
	}

	@Test
	void parsesContentDispositionPreferringFilenameStar() {
		// サーバが返す形: filename (ASCII) と filename* (UTF-8 の percent-encode、空白は %20)
		String header = "attachment; filename=\"a1B2c3D4e5-v7.litematic\"; filename*=UTF-8''%E8%87%AA%E5%8B%95%20%E4%BB%95%E5%88%86%E3%81%91-v7.litematic";
		assertEquals(Optional.of("自動 仕分け-v7.litematic"), CopyLinks.fileName(header));
		// + は空白にしない
		assertEquals(Optional.of("a+b-v1.litematic"), CopyLinks.fileName("attachment; filename*=UTF-8''a+b-v1.litematic"));
		// filename* が無ければ filename
		assertEquals(Optional.of("a1B2c3D4e5-v7.litematic"), CopyLinks.fileName("attachment; filename=\"a1B2c3D4e5-v7.litematic\""));
		assertEquals(Optional.of("plain.litematic"), CopyLinks.fileName("attachment; filename=plain.litematic"));
		// 壊れた filename* は捨てる
		assertEquals(Optional.of("ok.litematic"), CopyLinks.fileName("attachment; filename=\"ok.litematic\"; filename*=UTF-8''%E8%ZZ"));
		assertEquals(Optional.empty(), CopyLinks.fileName("attachment"));
		assertEquals(Optional.empty(), CopyLinks.fileName(null));
	}

	@Test
	void titleFromServerFileName() {
		assertEquals("自動 仕分け", CopyLinks.titleFromFileName("自動 仕分け-v7.litematic", 7));
		assertEquals("a-v1", CopyLinks.titleFromFileName("a-v1-v12.litematic", 12));
		assertEquals("名前-v3", CopyLinks.titleFromFileName("名前-v3.litematic", 4)); // 版が合わなければ拡張子だけ落とす
		assertNull(CopyLinks.titleFromFileName("-v2.litematic", 2));
		assertNull(CopyLinks.titleFromFileName(null, 1));
	}

	@Test
	void safeFileNames() {
		assertEquals("自動 仕分け-v7.litematic", CopyLinks.safeFileName("自動 仕分け-v7", "id-v7"));
		assertEquals("a_b_c_d_e_f_g_h_i-v1.litematic", CopyLinks.safeFileName("a/b\\c:d*e?f\"g<h>i-v1", "id-v1"));
		assertEquals("x_y.litematic", CopyLinks.safeFileName("x\ny", "id"));
		assertEquals("_.._evil.litematic", CopyLinks.safeFileName("../../evil", "id")); // 区切りが残らない
		assertEquals("id-v2.litematic", CopyLinks.safeFileName("  ", "id-v2"));
		assertEquals("hidden.litematic", CopyLinks.safeFileName("...hidden", "id"));
		assertEquals("a.litematic", CopyLinks.safeFileName("a.litematic", "id"));
		String longName = CopyLinks.safeFileName("あ".repeat(300), "id");
		assertEquals(120, longName.length());
		assertTrue(longName.endsWith(".litematic"));
	}

	@Test
	void failureTexts() {
		assertEquals("ホストが複製を許可していません", CopyLinks.failureText(new ApiException(403, "copy_not_allowed", "x")));
		assertEquals("セッションが無いか、まだ回路が同期されていません", CopyLinks.failureText(new ApiException(404, "not_found", "x")));
		assertTrue(CopyLinks.failureText(new ApiException("ConnectException", new java.io.IOException())).startsWith("サーバに接続できません"));
		assertEquals("壊れた [500 internal]", CopyLinks.failureText(new ApiException(500, "internal", "壊れた")));
	}

	@Test
	void listJsonCarriesAllowCopy() {
		String json = """
				{"sessions":[
				 {"sessionId":"a1B2c3D4e5","title":"許可","host":"taku","createdAt":"2026-10-13T01:00:00.000Z","hostOnline":true,"listed":true,
				  "allowCopy":true,"shareUrl":"http://localhost:5173/s/a1B2c3D4e5","viewerCount":0,
				  "snapshot":{"version":4,"size":[16,8,32],"blockCount":812,"updatedAt":"2026-10-13T00:40:00.000Z"},"comments":[]},
				 {"sessionId":"Zz9Yy8Xx7W","title":"不可","host":"viewer","createdAt":"2026-10-13T00:30:00.000Z","hostOnline":true,"listed":true,
				  "allowCopy":false,"shareUrl":"http://localhost:5173/s/Zz9Yy8Xx7W","viewerCount":0,
				  "snapshot":{"version":2,"size":[1,1,1],"blockCount":1,"updatedAt":"2026-10-13T00:40:00.000Z"},"comments":[]},
				 {"sessionId":"Q1w2E3r4T5","title":"未同期","host":"h","createdAt":"2026-10-13T00:20:00.000Z","hostOnline":true,"listed":true,
				  "allowCopy":true,"shareUrl":"http://localhost:5173/s/Q1w2E3r4T5","viewerCount":0,"snapshot":null,"comments":[]},
				 {"sessionId":"Old0Server","title":"古いサーバ","host":"h","createdAt":"2026-10-13T00:10:00.000Z","hostOnline":true,"listed":true,
				  "shareUrl":"http://localhost:5173/s/Old0Server","viewerCount":0,
				  "snapshot":{"version":1,"size":[1,1,1],"blockCount":1,"updatedAt":"2026-10-13T00:40:00.000Z"},"comments":[]}
				]}
				""";
		List<Api.Session> list = LiveText.parseList(json);
		assertTrue(list.get(0).copyAllowed());
		assertFalse(list.get(1).copyAllowed());
		assertTrue(list.get(3).copyAllowed()); // allowCopy が無ければ許す扱い
		assertNull(list.get(3).allowCopy);

		assertNull(row(list.get(0)).loadBlockedReason());
		assertEquals("ホストが複製を許可していません", row(list.get(1)).loadBlockedReason());
		assertEquals("まだ回路が同期されていません", row(list.get(2)).loadBlockedReason());
		assertNull(row(list.get(3)).loadBlockedReason());
	}

	@Test
	void updateRequestOmitsUnchangedFields() {
		assertEquals("{\"allowCopy\":false}", Json.GSON.toJson(new Api.UpdateSessionRequest(null, false)));
		assertEquals("{\"listed\":true}", Json.GSON.toJson(new Api.UpdateSessionRequest(true, null)));
		String create = Json.GSON.toJson(new Api.CreateSessionRequest("t", "c", "26.3", 4000, false, null, false));
		assertTrue(create.contains("\"allowCopy\":false"), create);
		assertFalse(Json.GSON.toJson(new Api.CreateSessionRequest("t", "c", "26.3", 4000)).contains("allowCopy"));
	}

	private static LiveRow row(Api.Session s) {
		return new LiveRow(s, false, s.shareUrl, "");
	}
}
