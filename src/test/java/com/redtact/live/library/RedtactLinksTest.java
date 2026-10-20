package com.redtact.live.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

class RedtactLinksTest {
	private static final URI PROD = URI.create("https://redtact.com");
	private static final URI LOCAL = URI.create("http://localhost:5173");
	/** redtact 本番の期限付き URL の形 (署名は偽物)。 */
	private static final String R2 = "https://00676ea89e9374ec431b368941e4cc02.r2.cloudflarestorage.com/redtact-circuit-private-prod/6BxMfe5Vfjp4/adb81e60f4b849de.schem"
			+ "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=deadbeef&response-content-disposition=attachment";

	@Test
	void home() {
		assertEquals("https://redtact.com", RedtactLinks.home("https://redtact.com/").toString());
		assertEquals("https://dev.redtact.com", RedtactLinks.home(" https://dev.redtact.com ").toString());
		assertEquals("http://localhost:5173", RedtactLinks.home("http://localhost:5173").toString());
		assertEquals(RedtactLinks.DEFAULT_HOME, RedtactLinks.home("redtact.com").toString(), "スキームが無ければ既定");
		assertEquals(RedtactLinks.DEFAULT_HOME, RedtactLinks.home("javascript:alert(1)").toString());
		assertEquals(RedtactLinks.DEFAULT_HOME, RedtactLinks.home(null).toString());
	}

	@Test
	void accountSettings() {
		// 開催中のフォロー中の案内 (#90): redtact の設定のアカウント (マイクラアカウントの連携)
		assertEquals("https://redtact.com/settings?tab=account", RedtactLinks.accountSettings(PROD));
		assertEquals("http://localhost:5173/settings?tab=account", RedtactLinks.accountSettings(RedtactLinks.home("http://localhost:5173/")));
		assertTrue(RedtactLinks.allowedInGame(RedtactLinks.accountSettings(PROD), PROD), "ゲーム内の redtact で開ける");
	}

	@Test
	void onlyRedtactAndItsFilesOpenInGame() {
		assertTrue(RedtactLinks.allowedInGame("https://redtact.com/circuits/abc", PROD));
		assertTrue(RedtactLinks.allowedInGame("https://dev.redtact.com/", PROD));
		assertTrue(RedtactLinks.allowedInGame(R2, PROD), "ダウンロード (R2) は通す");
		assertTrue(RedtactLinks.allowedInGame("about:blank", PROD));
		assertTrue(RedtactLinks.allowedInGame("blob:https://redtact.com/123", PROD));
		assertTrue(RedtactLinks.allowedInGame("http://localhost:5173/x", LOCAL), "設定のトップと同じホスト");

		assertFalse(RedtactLinks.allowedInGame("https://www.youtube.com/watch?v=x", PROD));
		assertFalse(RedtactLinks.allowedInGame("https://redtact.com.evil.example/", PROD));
		assertFalse(RedtactLinks.allowedInGame("https://evilredtact.com/", PROD));
		assertFalse(RedtactLinks.allowedInGame("file:///etc/passwd", PROD));
		assertFalse(RedtactLinks.allowedInGame("http://localhost:5173/x", PROD));
	}

	@Test
	void signIn() {
		assertTrue(RedtactLinks.isSignIn("https://accounts.google.com/o/oauth2/auth?x=1"));
		assertTrue(RedtactLinks.isSignIn("https://redtact-prod.firebaseapp.com/__/auth/handler?apiKey=x"));
		assertTrue(RedtactLinks.isSignIn("https://appleid.apple.com/auth/authorize"));
		assertFalse(RedtactLinks.isSignIn("https://redtact.com/login"));
		assertFalse(RedtactLinks.isSignIn(null));
	}

	@Test
	void fileRefFromSignedUrl() {
		RedtactLinks.FileRef ref = RedtactLinks.fileRef(R2, PROD);
		assertNotNull(ref);
		assertEquals("6BxMfe5Vfjp4", ref.circuitId());
		assertEquals("adb81e60f4b849de.schem", ref.storedFileName());
		assertEquals("6BxMfe5Vfjp4/adb81e60f4b849de.schem", ref.sourceKey());

		// 手元の確かめ: トップと同じホストで同じ形
		assertNotNull(RedtactLinks.fileRef("http://localhost:5173/bucket/c1/f.litematic", LOCAL));
		// 形が違う・ほかのホスト
		assertNull(RedtactLinks.fileRef("https://example.com/bucket/c1/f.schem", PROD));
		assertNull(RedtactLinks.fileRef("https://x.r2.cloudflarestorage.com/bucket/f.schem", PROD));
		assertNull(RedtactLinks.fileRef("https://x.r2.cloudflarestorage.com/bucket/c!d/f.schem", PROD));
		assertNull(RedtactLinks.fileRef("https://x.r2.cloudflarestorage.com/bucket/c1/../f.schem", PROD));
		assertNull(RedtactLinks.fileRef("blob:https://redtact.com/1", PROD));
		assertNull(RedtactLinks.fileRef(null, PROD));
	}

	@Test
	void circuitIdFromPage() {
		assertEquals("6BxMfe5Vfjp4", RedtactLinks.circuitIdFromPage("https://redtact.com/circuits/6BxMfe5Vfjp4"));
		assertEquals("6BxMfe5Vfjp4", RedtactLinks.circuitIdFromPage("https://redtact.com/circuits/6BxMfe5Vfjp4?tab=downloads"));
		assertEquals("abc", RedtactLinks.circuitIdFromPage("https://redtact.com/en/circuits/abc/edit"));
		assertNull(RedtactLinks.circuitIdFromPage("https://redtact.com/circuits"));
		assertNull(RedtactLinks.circuitIdFromPage("https://redtact.com/users/abc"));
		assertNull(RedtactLinks.circuitIdFromPage(null));
	}

	@Test
	void apiBase() {
		assertEquals("https://api.redtact.com", RedtactLinks.apiBase(PROD));
		assertEquals("https://dev-api.redtact.com", RedtactLinks.apiBase(URI.create("https://dev.redtact.com")));
		assertNull(RedtactLinks.apiBase(LOCAL));
		assertNull(RedtactLinks.apiBase(URI.create("https://a.b.redtact.com")));
	}

	@Test
	void circuitPageAndRedact() {
		assertEquals("https://redtact.com/circuits/abc", RedtactLinks.circuitPage(PROD, "abc"));
		assertThrows(IllegalArgumentException.class, () -> RedtactLinks.circuitPage(PROD, "../x"));
		String redacted = RedtactLinks.redact(R2);
		assertFalse(redacted.contains("Signature"), "署名を記録に残さない");
		assertTrue(redacted.endsWith("adb81e60f4b849de.schem?…"));
		assertEquals("https://redtact.com/x", RedtactLinks.redact("https://redtact.com/x"));
	}

	/** 帯に出す形は https:// を省く (#69)。http などほかのスキームは出す。クエリは redact と同じく落とす。 */
	@Test
	void displayDropsHttpsOnly() {
		assertEquals("redtact.com/circuits/abc", RedtactLinks.display("https://redtact.com/circuits/abc"));
		assertEquals("dev.redtact.com/", RedtactLinks.display("HTTPS://dev.redtact.com/"));
		assertEquals("http://localhost:5173/circuits/new", RedtactLinks.display("http://localhost:5173/circuits/new"));
		assertEquals("redtact.com/x?…", RedtactLinks.display("https://redtact.com/x?token=secret#frag"));
		assertEquals("about:blank", RedtactLinks.display("about:blank"));
		assertEquals("", RedtactLinks.display(null));
		assertEquals("https:/", RedtactLinks.display("https:/"), "https:// で始まらなければそのまま");
	}
}
