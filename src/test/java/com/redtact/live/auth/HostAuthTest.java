package com.redtact.live.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.redtact.live.live.LiveText;
import com.redtact.live.net.Api;
import org.junit.jupiter.api.Test;

class HostAuthTest {
	static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNTQ3MzkyODQ3NjE3MCJ9.c2lnbmF0dXJlLXNpZ25hdHVyZQ";

	@Test
	void verifiableLooksAtTokenModeAndName() {
		assertTrue(HostAuth.looksVerifiable(JWT, false, "taku128"));
		assertFalse(HostAuth.looksVerifiable("FabricMC", false, "Player0"), "開発環境のトークンは JWT でない");
		assertFalse(HostAuth.looksVerifiable("0", false, "Player0"));
		assertFalse(HostAuth.looksVerifiable(null, false, "Player0"));
		assertFalse(HostAuth.looksVerifiable(JWT, true, "taku128"), "オフラインの開発者モード");
		assertFalse(HostAuth.looksVerifiable(JWT, false, "テスト ホスト"), "マイクラの名前の書式でない");
	}

	@Test
	void minecraftNameFormat() {
		assertTrue(HostAuth.isMinecraftName("taku128"));
		assertTrue(HostAuth.isMinecraftName("A_b"));
		assertFalse(HostAuth.isMinecraftName("ab"));
		assertFalse(HostAuth.isMinecraftName("a".repeat(17)));
		assertFalse(HostAuth.isMinecraftName("taku-128"));
		assertFalse(HostAuth.isMinecraftName(null));
	}

	@Test
	void createErrorMessages() {
		String required = HostAuth.createErrorMessage("minecraft_required", HostAuth.JoinFailure.OFFLINE_ACCOUNT, "Invalid session");
		assertTrue(required.startsWith("マイクラアカウントで確認できませんでした (オフラインのアカウントか開発環境のため)"), required);
		String unavailableJoin = HostAuth.createErrorMessage("minecraft_required", HostAuth.JoinFailure.MOJANG_UNAVAILABLE, null);
		assertTrue(unavailableJoin.contains("Mojang の認証サーバに接続できないため"), "join が Mojang に届かなかったことも出す: " + unavailableJoin);
		assertTrue(HostAuth.createErrorMessage("minecraft_unverified", null, null).contains("確認に失敗しました"));
		assertTrue(HostAuth.createErrorMessage("minecraft_unavailable", null, null).contains("中継サーバが Mojang に問い合わせられません"));
		assertNull(HostAuth.createErrorMessage("limit_reached", null, null), "確認と関係ないエラーは別の文言");
		assertNull(HostAuth.createErrorMessage(null, null, null));
	}

	@Test
	void reasonText() {
		assertEquals("確認に失敗したため: boom", HostAuth.reasonText(HostAuth.JoinFailure.OTHER, "boom"));
		assertEquals("Mojang の認証サーバに接続できないため", HostAuth.reasonText(HostAuth.JoinFailure.MOJANG_UNAVAILABLE, "timeout"));
		assertEquals("理由不明", HostAuth.reasonText(null, null));
	}

	@Test
	void sessionCarriesHostVerification() {
		List<Api.Session> list = LiveText.parseList("""
				{"sessions":[
				 {"sessionId":"a1B2c3D4e5","title":"t","host":"taku128","hostVerified":true,
				  "hostUuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","createdAt":"2026-10-13T01:00:00.000Z","hostOnline":true,
				  "listed":true,"shareUrl":"http://localhost:5173/s/a1B2c3D4e5","viewerCount":0,"snapshot":null,"comments":[]},
				 {"sessionId":"Zz9Yy8Xx7W","title":"u","host":"テスト","hostVerified":false,"hostUuid":null,
				  "createdAt":"2026-10-13T01:00:00.000Z","hostOnline":true,"listed":true,"shareUrl":"x","viewerCount":0,"snapshot":null,"comments":[]}
				]}
				""");
		assertTrue(list.get(0).hostVerified);
		assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", list.get(0).hostUuid);
		assertFalse(list.get(1).hostVerified);
		assertNull(list.get(1).hostUuid);
	}

	@Test
	void createRequestOmitsMinecraftWhenUnverified() {
		String without = com.redtact.live.net.Json.GSON.toJson(new Api.CreateSessionRequest("t", "c", "26.3", 5023, false, null));
		assertFalse(without.contains("minecraft\""), without);
		String with = com.redtact.live.net.Json.GSON.toJson(new Api.CreateSessionRequest("t", "c", "26.3", 5023, true, new Api.MinecraftProof("mc_x", "taku128")));
		assertTrue(with.contains("\"minecraft\":{\"challengeId\":\"mc_x\",\"name\":\"taku128\"}"), with);
	}
}
