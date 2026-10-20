package com.redtact.live.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ゲーム内のログイン (#50)。固定すること: 外のブラウザで {@code <origin>/login/game?method=&port=&state=} を開く・
 * 受け取り口に届いた資格情報を 1 回だけ返す・同時に 1 つ (2 つ目は BUSY)・5 分で TIMEOUT・方法の違う資格情報は FAILED・
 * 取り消しは CANCELLED。
 */
class GameLoginTest {
	private final List<String> opened = new ArrayList<>();
	private final List<String> notices = new ArrayList<>();
	private final AtomicLong now = new AtomicLong(1_000_000);
	private final HttpClient http = HttpClient.newHttpClient();
	private final GameLogin login = new GameLogin(opened::add, Runnable::run, now::get, new GameLogin.Notices() {
		@Override
		public void notice(String title, String text) {
			notices.add(title);
		}

		@Override
		public void info(String text) {
			notices.add(text);
		}

		@Override
		public void error(String text) {
			notices.add(text);
		}
	});

	@AfterEach
	void cancel() {
		login.cancel();
	}

	private static Map<String, String> query(String url) {
		return LoopbackServer.parseForm(URI.create(url).getRawQuery());
	}

	private void deliver(String body) throws Exception {
		HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + login.port() + "/complete"))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode(), res.body());
	}

	@Test
	void opensLoginGameAndReturnsTheCredential() throws Exception {
		CompletableFuture<GameLogin.Reply> reply = new CompletableFuture<>();
		login.start("https://dev.redtact.com", "apple", reply::complete);

		assertTrue(login.pending());
		assertEquals(1, opened.size());
		String url = opened.getFirst();
		assertTrue(url.startsWith("https://dev.redtact.com/login/game?"), url);
		Map<String, String> q = query(url);
		assertEquals("apple", q.get("method"));
		assertEquals(String.valueOf(login.port()), q.get("port"));
		String state = URLDecoder.decode(q.get("state"), StandardCharsets.UTF_8);
		assertTrue(state.matches("[A-Za-z0-9_-]{43}"), state);

		deliver("state=" + state + "&result=credential&method=apple&id_token=a.b.c&raw_nonce=raw");
		GameLogin.Reply r = reply.get(5, TimeUnit.SECONDS);
		assertEquals(GameLogin.Outcome.CREDENTIAL, r.outcome());
		assertEquals("apple", r.method());
		assertEquals("a.b.c", r.idToken());
		assertEquals("raw", r.rawNonce());
		assertFalse(login.pending());
	}

	@Test
	void secondRequestIsBusy() {
		List<GameLogin.Reply> replies = new ArrayList<>();
		login.start("https://redtact.com", "google", replies::add);
		login.start("https://redtact.com", "google", replies::add);
		assertEquals(List.of(GameLogin.Outcome.BUSY), replies.stream().map(GameLogin.Reply::outcome).toList());
		assertEquals(1, opened.size());
	}

	@Test
	void timesOutAfterFiveMinutes() {
		List<GameLogin.Reply> replies = new ArrayList<>();
		login.start("https://redtact.com", "google", replies::add);
		now.addAndGet(GameLogin.TIMEOUT_MS);
		login.tick();
		assertTrue(replies.isEmpty());
		now.addAndGet(1);
		login.tick();
		assertEquals(GameLogin.Outcome.TIMEOUT, replies.getFirst().outcome());
		assertFalse(login.pending());
	}

	@Test
	void mismatchedMethodFailsAndCancelIsReported() throws Exception {
		CompletableFuture<GameLogin.Reply> first = new CompletableFuture<>();
		login.start("https://redtact.com", "google", first::complete);
		String state = URLDecoder.decode(query(opened.getLast()).get("state"), StandardCharsets.UTF_8);
		deliver("state=" + state + "&result=credential&method=apple&id_token=a.b.c&raw_nonce=raw");
		GameLogin.Reply r = first.get(5, TimeUnit.SECONDS);
		assertEquals(GameLogin.Outcome.FAILED, r.outcome());
		assertNull(r.idToken());

		CompletableFuture<GameLogin.Reply> second = new CompletableFuture<>();
		login.start("https://redtact.com", "google", second::complete);
		state = URLDecoder.decode(query(opened.getLast()).get("state"), StandardCharsets.UTF_8);
		deliver("state=" + state + "&result=cancelled&method=google");
		assertEquals(GameLogin.Outcome.CANCELLED, second.get(5, TimeUnit.SECONDS).outcome());
	}

	@Test
	void unknownMethodFailsWithoutOpening() {
		List<GameLogin.Reply> replies = new ArrayList<>();
		login.start("https://redtact.com", "github", replies::add);
		assertEquals(GameLogin.Outcome.FAILED, replies.getFirst().outcome());
		assertTrue(opened.isEmpty());
		assertEquals("game_failed", GameLogin.Outcome.FAILED.code());
		assertEquals("game_cancelled", GameLogin.Outcome.CANCELLED.code());
	}
}
