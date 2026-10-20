package com.redtact.live.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ログインの受け取り口 (#50)。固定すること: 127.0.0.1 だけで待つ・Host が違えば 403・state が違えば 400 で待ち続ける・
 * 合えば結果を 1 回だけ渡して閉じる・GET /callback はフラグメントを POST するページ。
 */
class LoopbackServerTest {
	private static final String STATE = "s".repeat(43);
	private final HttpClient http = HttpClient.newHttpClient();
	private final CompletableFuture<LoopbackServer.Result> got = new CompletableFuture<>();
	private LoopbackServer server;

	@AfterEach
	void close() {
		if (server != null) {
			server.close();
		}
	}

	private LoopbackServer start() throws IOException {
		server = new LoopbackServer(STATE, got::complete);
		return server;
	}

	private HttpResponse<String> post(String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/complete"))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void callbackPagePostsTheFragment() throws Exception {
		start();
		HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/callback")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("fetch(\"/complete\""));
		assertTrue(res.body().contains("history.replaceState"), "フラグメントを履歴から消す");
		assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(""));
	}

	@Test
	void deliversOnceWhenStateMatches() throws Exception {
		start();
		assertEquals(400, post("state=wrong&result=credential&id_token=x").statusCode());
		assertTrue(!got.isDone(), "state が違えば渡さない");

		HttpResponse<String> ok = post("state=" + STATE + "&result=credential&method=google&id_token=a.b.c&raw_nonce=n%2B1");
		assertEquals(200, ok.statusCode());
		assertTrue(ok.body().contains("\"result\":\"credential\""));
		LoopbackServer.Result r = got.get(5, TimeUnit.SECONDS);
		assertEquals("a.b.c", r.get("id_token"));
		assertEquals("n+1", r.get("raw_nonce"));
		assertTrue(server.done());
		// 受け取ったら閉じる
		assertThrows(Exception.class, () -> post("state=" + STATE + "&result=credential"));
	}

	@Test
	void rejectsOtherHostsAndOrigins() throws Exception {
		start();
		assertEquals(403, raw("GET /callback HTTP/1.1\r\nHost: evil.example\r\n\r\n"));
		assertEquals(403, raw("GET /callback HTTP/1.1\r\nHost: localhost:" + server.port() + "\r\n\r\n"));
		String body = "state=" + STATE + "&result=credential";
		assertEquals(403, raw("POST /complete HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nOrigin: https://evil.example\r\nContent-Length: "
				+ body.length() + "\r\n\r\n" + body));
		assertEquals(404, raw("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\n\r\n"));
		assertTrue(!got.isDone());
	}

	/**
	 * 要求の途中で相手が切っても (RST)、受け取り口は止まらず次の要求に答える (#54)。外のブラウザが接続を途中で切ると、
	 * 前は受け付けのループを抜けて、ログインの結果を受け取れなかった。
	 */
	@Test
	void keepsServingAfterAbortedConnections() throws Exception {
		start();

		for (int i = 0; i < 3; i++) {
			try (Socket s = new Socket("127.0.0.1", server.port())) {
				s.setSoLinger(true, 0); // 閉じるときに RST を送る
				s.getOutputStream().write("GET /cal".getBytes(StandardCharsets.ISO_8859_1));
			}

			Thread.sleep(50);
			assertEquals(404, raw("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\n\r\n"), "途中で切られた接続のあとに答えない");
		}

		// 結果も受け取れる
		assertEquals(200, post("state=" + STATE + "&result=credential&method=google&id_token=a.b.c").statusCode());
		assertEquals("a.b.c", got.get(5, TimeUnit.SECONDS).get("id_token"));
	}

	@Test
	void bindsOnlyToLoopback() throws Exception {
		start();
		// 127.0.0.1 にだけ bind する (同じ機械のほかのアドレスからも届かない)
		assertThrows(ConnectException.class, () -> {
			try (Socket s = new Socket("127.0.0.2", server.port())) {
				s.getOutputStream().write(1);
			}
		});
	}

	@Test
	void parsesFragments() {
		var m = LoopbackServer.parseForm("#state=a&id_token=x.y.z&empty=&bad=%E3%8");
		assertEquals("a", m.get("state"));
		assertEquals("x.y.z", m.get("id_token"));
		assertEquals("", m.get("empty"));
		assertNull(m.get("bad"));
	}

	/** 生の HTTP を送って状態コードを返す (答えなければ 5 秒で失敗する。止まらないように)。 */
	private int raw(String request) throws IOException {
		try (Socket s = new Socket("127.0.0.1", server.port())) {
			s.setSoTimeout(5_000);
			OutputStream out = s.getOutputStream();
			out.write(request.getBytes(StandardCharsets.UTF_8));
			out.flush();
			InputStream in = s.getInputStream();
			String head = new String(in.readNBytes(12), StandardCharsets.ISO_8859_1);
			return Integer.parseInt(head.substring(9, 12));
		}
	}
}
