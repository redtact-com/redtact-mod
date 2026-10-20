package com.redtact.live.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.redtact.live.net.Api;
import org.junit.jupiter.api.Test;

class LiveTextTest {
	@Test
	void parsesListResponse() {
		String json = """
				{"sessions":[
				 {"sessionId":"a1B2c3D4e5","title":"自動仕分け機","host":"taku","createdAt":"2026-10-13T01:00:00.000Z",
				  "hostOnline":false,"listed":true,"shareUrl":"http://localhost:5173/s/a1B2c3D4e5","viewerCount":3,"snapshot":null,"comments":[]},
				 {"sessionId":"Zz9Yy8Xx7W","title":"クロック","host":"viewer","createdAt":"2026-10-13T00:30:00.000Z",
				  "hostOnline":true,"listed":true,"shareUrl":"http://localhost:5173/s/Zz9Yy8Xx7W","viewerCount":0,
				  "snapshot":{"version":4,"size":[16,8,32],"blockCount":812,"updatedAt":"2026-10-13T00:40:00.000Z"},"comments":[]}
				]}
				""";
		List<Api.Session> list = LiveText.parseList(json);
		assertEquals(2, list.size());
		Api.Session a = list.get(0);
		assertEquals("a1B2c3D4e5", a.sessionId);
		assertEquals("自動仕分け機", a.title);
		assertEquals("taku", a.host);
		assertFalse(a.hostOnline);
		assertTrue(a.listed);
		assertEquals(3, a.viewerCount);
		assertEquals("http://localhost:5173/s/a1B2c3D4e5", a.shareUrl);
		assertEquals("http://localhost:5173/s/Zz9Yy8Xx7W", list.get(1).shareUrl);
		assertNull(a.snapshot);
		assertEquals(4, list.get(1).snapshot.version);
	}

	@Test
	void parsesViewsHostUserAndFollowing() {
		// #90 の中継サーバ: views・hostUser と、following=1 のときの following
		String json = """
				{"sessions":[
				 {"sessionId":"a1B2c3D4e5","title":"加算器","host":"hana","hostVerified":true,"viewerCount":2,"views":9,
				  "hostUser":{"id":"u1","name":"はな","picture":"https://redtact.com/a.png","url":"https://redtact.com/users/u1"},"comments":[]}
				],"following":{"status":"ok","accounts":4}}
				""";
		Api.SessionList list = com.redtact.live.net.Json.GSON.fromJson(json, Api.SessionList.class);
		Api.Session s = list.sessions.get(0);
		assertEquals(9, s.views);
		assertEquals("はな", s.hostUser.name);
		assertEquals("https://redtact.com/users/u1", s.hostUser.url);
		assertEquals("ok", list.following.status);
		assertEquals(4, list.following.accounts);
		// 古い中継サーバ: どれも無い
		Api.Session old = LiveText.parseList("{\"sessions\":[{\"sessionId\":\"x\",\"viewerCount\":1}]}").get(0);
		assertNull(old.views);
		assertNull(old.hostUser);
		assertNull(com.redtact.live.net.Json.GSON.fromJson("{\"sessions\":[]}", Api.SessionList.class).following);
	}

	@Test
	void emptyTextForNarrowedLists() {
		LiveQuery follow = LiveQuery.DEFAULT.withFollowing(true);
		assertNull(LiveText.emptyText(LiveQuery.DEFAULT, null), "絞っていなければふだんの説明");
		assertNull(LiveText.emptyText(LiveQuery.DEFAULT.withSort(LiveQuery.Sort.VIEWS), null), "並べ替えだけならふだんの説明");
		assertEquals("条件に合うセッションはありません", LiveText.emptyText(LiveQuery.DEFAULT.withQ("hana"), null));
		assertEquals("フォローしている人の開催中のセッションはありません。出るのは、フォローしている人のうちマイクラアカウントを公開にしている人 (いま 3 人) だけです",
				LiveText.emptyText(follow, following(LiveDirectory.FollowStatus.OK, 3, null)));
		assertTrue(LiveText.emptyText(follow.withQ("hana"), following(LiveDirectory.FollowStatus.OK, 3, null)).startsWith("条件に合うセッションはありません。"));
		String notLinked = LiveText.emptyText(follow, following(LiveDirectory.FollowStatus.NOT_LINKED, 0, null));
		assertTrue(notLinked.contains("連携されていません") && notLinked.contains("アカウント → マイクラアカウント") && notLinked.contains("公開にしている人だけ"), notLinked);
		assertTrue(LiveText.emptyText(follow, following(LiveDirectory.FollowStatus.UNAVAILABLE, 0, null)).contains("しばらくしてから"));
		assertTrue(LiveText.emptyText(follow, following(LiveDirectory.FollowStatus.UNSUPPORTED, 0, null)).contains("対応していません"));
		assertEquals("フォロー中で絞るには、マイクラアカウントでの確認が要ります (オフラインのアカウントか開発環境のため)",
				LiveText.emptyText(follow, following(LiveDirectory.FollowStatus.UNVERIFIED, 0, "オフラインのアカウントか開発環境のため")));
		assertEquals("フォロー中で絞るには、マイクラアカウントでの確認が要ります (理由不明)",
				LiveText.emptyText(follow, following(LiveDirectory.FollowStatus.UNVERIFIED, 0, null)));
		// フォロー中の答えがまだ無い (前の問い合わせの一覧): 探す字だけで決める
		assertNull(LiveText.emptyText(follow, null));
	}

	private static LiveDirectory.Following following(LiveDirectory.FollowStatus status, int accounts, String reason) {
		return new LiveDirectory.Following(status, accounts, reason);
	}

	@Test
	void emptyOrMissingListIsEmpty() {
		assertEquals(0, LiveText.parseList("{\"sessions\":[]}").size());
		assertEquals(0, LiveText.parseList("{}").size());
	}

	@Test
	void agoText() {
		assertEquals("1 分以内", LiveText.ago(Duration.ofSeconds(30)));
		assertEquals("1 分前", LiveText.ago(Duration.ofSeconds(60)));
		assertEquals("12 分前", LiveText.ago(Duration.ofMinutes(12).plusSeconds(40)));
		assertEquals("1 時間前", LiveText.ago(Duration.ofMinutes(60)));
		assertEquals("1 時間 5 分前", LiveText.ago(Duration.ofMinutes(65)));
		assertEquals("2 日前", LiveText.ago(Duration.ofHours(49)));
		assertEquals("1 分以内", LiveText.ago(Duration.ofSeconds(-5)), "時計のずれで未来でも負にしない");
		Instant now = Instant.parse("2026-10-13T01:12:30.000Z");
		assertEquals("12 分前", LiveText.ago("2026-10-13T01:00:00.000Z", now));
		assertEquals("-", LiveText.ago("not a time", now));
		assertEquals("-", LiveText.ago(null, now));
	}
}
