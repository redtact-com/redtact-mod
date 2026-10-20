package com.redtact.live.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.redtact.live.capture.BlockEntityNbt;
import com.redtact.live.litematic.LitematicWriterTest;
import com.redtact.live.state.CircuitDiff;
import com.redtact.live.state.CircuitState;
import com.redtact.live.state.StateDiffer;
import com.redtact.live.state.StateJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 中継サーバ (server/) を相手にした通しの確認。サーバが無い環境では走らない。
 * {@code ./gradlew test -PliveServer=http://localhost:8797}
 */
@EnabledIfSystemProperty(named = "redtactLive.server", matches = ".+")
class LiveServerIntegrationTest {
	private final String base = System.getProperty("redtactLive.server");

	@Test
	void sessionPushCommentAckClose() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");

		// 1. セッション開始
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest("IT 回路", "c0ffee00c0ffee00", "26.3", 4671));
		String id = created.session.sessionId;
		String token = created.hostToken;
		assertNotNull(token);
		assertTrue(created.shareUrl.endsWith("/s/" + id), created.shareUrl);

		// まだ全体を送っていないので、差分は 409
		CircuitState s1 = sampleState();
		ApiException early = assertThrows(ApiException.class,
				() -> api.patchState(id, token, StateDiffer.diff(s1, 0, s1)));
		assertTrue(early.isResyncRequired(), early.describe());

		// 2. 全体 (PUT, gzip)
		Api.Snapshot snap = api.putState(id, token, s1);
		assertEquals(1, snap.version);
		assertArrayEquals(new int[] {3, 2, 4}, snap.size);
		assertEquals(s1.nonAirCount(), snap.blockCount);
		StateJson.State got = api.getState(id).orElseThrow();
		assertEquals(1, got.version);
		assertTrue(CircuitState.fromJson(got).sameContent(s1), "GET state が送った全体と違う");

		// 3. 1 ブロックだけ変えて差分 (PATCH)
		CircuitState s2 = withBlock(s1, s1.index(2, 1, 3), "minecraft:gold_block");
		CircuitDiff d = StateDiffer.diff(s1, 1, s2);
		assertEquals(1, d.changeCount());
		Api.Snapshot snap2 = api.patchState(id, token, d);
		assertEquals(2, snap2.version);
		assertEquals(s1.nonAirCount() + 1, snap2.blockCount);
		assertTrue(CircuitState.fromJson(api.getState(id).orElseThrow()).sameContent(s2), "差分を当てた結果が違う");

		// ブロックエンティティを消す差分
		CircuitState s3 = new CircuitState(3, 2, 4, s2.palette(), s2.blocks(), java.util.Map.of());
		Api.Snapshot snap3 = api.patchState(id, token, StateDiffer.diff(s2, 2, s3));
		assertEquals(3, snap3.version);
		assertTrue(CircuitState.fromJson(api.getState(id).orElseThrow()).sameContent(s3));

		// 古い版への差分は 409 (currentVersion 付き)。書き込みは 1 秒 4 回までなので間を空ける
		Thread.sleep(1100);
		ApiException stale = assertThrows(ApiException.class, () -> api.patchState(id, token, StateDiffer.diff(s1, 1, s2)));
		assertTrue(stale.isResyncRequired());
		assertEquals(3, stale.currentVersion());

		// 範囲が変わったら全体を送り直す
		CircuitState s4 = new CircuitState(1, 1, 2, java.util.List.of("minecraft:air", "minecraft:stone"), new int[] {1, 0}, java.util.Map.of());
		Api.Snapshot snap4 = api.putState(id, token, s4);
		assertEquals(4, snap4.version);
		assertArrayEquals(new int[] {1, 1, 2}, snap4.size);
		assertTrue(CircuitState.fromJson(api.getState(id).orElseThrow()).sameContent(s4));

		// 閲覧者がコメントしてから WebSocket を張る -> hello.pending で届く
		postComment(id, "viewer1", "接続前のコメント");

		List<Api.Comment> received = new CopyOnWriteArrayList<>();
		CountDownLatch hello = new CountDownLatch(1);
		CountDownLatch live = new CountDownLatch(1);
		CountDownLatch ended = new CountDownLatch(1);
		List<String> endReason = new CopyOnWriteArrayList<>();
		ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
		HostSocket[] holder = new HostSocket[1];
		HostSocket socket = new HostSocket(api.http(), api.hostSocketUri(id), token, "mod-it", new HostSocket.Handler() {
			@Override
			public void onHello(Api.Session session, List<Api.Comment> pending, boolean reconnected) {
				received.addAll(pending);
				holder[0].sendAck(pending.stream().map(c -> c.id).toList());
				hello.countDown();
			}

			@Override
			public void onComment(Api.Comment comment) {
				received.add(comment);
				holder[0].sendAck(List.of(comment.id));
				live.countDown();
			}

			@Override
			public void onViewers(int count) {
			}

			@Override
			public void onEnded(String reason) {
				endReason.add(reason);
				ended.countDown();
			}

			@Override
			public void onRejected(int status) {
			}

			@Override
			public void onReplaced() {
			}

			@Override
			public void onDisconnected(String why, long retryMillis, int attempt) {
			}
		}, Runnable::run, sched);
		holder[0] = socket;
		socket.start();
		assertTrue(hello.await(10, TimeUnit.SECONDS), "hello が来ない");
		assertEquals(1, received.size());
		assertEquals("接続前のコメント", received.getFirst().text);

		postComment(id, "viewer2", "リピーターを 2 tick に");
		assertTrue(live.await(10, TimeUnit.SECONDS), "comment が来ない");
		assertEquals("viewer2", received.get(1).author);

		// ack 済みなので、張り直しても pending は空
		Thread.sleep(300);
		Api.Session state = api.getSession(id).orElseThrow();
		assertTrue(state.hostOnline);
		assertEquals(2, state.comments.size());

		// 終了 -> closed が届き、GET は 404
		api.deleteSession(id, token);
		assertTrue(ended.await(10, TimeUnit.SECONDS), "closed が来ない");
		assertEquals("host_closed", endReason.getFirst());
		assertTrue(api.getSession(id).isEmpty());
		assertTrue(api.getState(id).isEmpty());
		socket.stop();
		sched.shutdownNow();
	}

	/** マイクラアカウントの確認: チャレンジの形、未確認での作成 (このサーバは許している)、存在しないチャレンジは 401。 */
	@Test
	void minecraftVerification() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		Api.Challenge ch = api.minecraftChallenge();
		assertNotNull(ch.challengeId);
		assertTrue(!ch.challengeId.isBlank(), "challengeId が空");
		assertTrue(ch.serverId.matches("[0-9a-f]{40}"), "serverId が 40 文字の 16 進でない: " + ch.serverId);
		assertNotNull(ch.expiresAt);
		assertTrue(!api.minecraftChallenge().serverId.equals(ch.serverId), "serverId が毎回違わない");

		// 確認を付けない (X-Live-User) 作成は、LIVE_ALLOW_UNVERIFIED_HOSTS のサーバなら通り、未確認になる
		Api.CreateSessionResponse unverified = api.createSession(new Api.CreateSessionRequest("IT 未確認", "c0ffee00c0ffee07", "26.3", 4671));
		assertTrue(!unverified.session.hostVerified, "未確認なのに hostVerified");
		assertEquals(null, unverified.session.hostUuid);
		assertEquals("mod-it", unverified.session.host);
		api.deleteSession(unverified.session.sessionId, unverified.hostToken);

		// 存在しないチャレンジを付けた作成は 401 minecraft_unverified
		ApiException bad = assertThrows(ApiException.class, () -> api.createSession(new Api.CreateSessionRequest(
				"IT 偽チャレンジ", "c0ffee00c0ffee08", "26.3", 4671, false, new Api.MinecraftProof("mc_does_not_exist", "taku128"))));
		assertEquals(401, bad.status());
		assertEquals("minecraft_unverified", bad.code());

		// join していない本物のチャレンジも 401 minecraft_unverified (Mojang の hasJoined が 204)。Mojang に届かない環境では 503
		ApiException notJoined = assertThrows(ApiException.class, () -> api.createSession(new Api.CreateSessionRequest(
				"IT join なし", "c0ffee00c0ffee09", "26.3", 4671, false, new Api.MinecraftProof(ch.challengeId, "taku128"))));
		assertTrue(("minecraft_unverified".equals(notJoined.code()) && notJoined.status() == 401)
				|| ("minecraft_unavailable".equals(notJoined.code()) && notJoined.status() == 503), notJoined.describe());
	}

	/**
	 * MOD からのコメント (#74): 表示名 (このサーバは LIVE_ALLOW_UNVERIFIED_VIEWERS) で自分のセッションに書くとホストの印が付き、
	 * GET .../comments で新着だけを取れる。偽のチャレンジでのトークンは 401 minecraft_unverified。
	 */
	@Test
	void commentsFromTheMod() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest("IT コメント", "c0ffee00c0ffee0b", "26.3", 4671));
		String id = created.session.sessionId;

		Api.CommentsResponse empty = api.comments(id, null);
		assertEquals(id, empty.session.sessionId);
		assertTrue(empty.comments.isEmpty());

		// ホスト本人 (同じ表示名) が書くと authorIsHost。ほかの人は付かない
		Api.Comment mine = api.postComment(id, null, "返事です");
		assertTrue(mine.byHost(), "ホストのコメントに印が無い");
		assertEquals(Boolean.FALSE, mine.authorVerified);
		postComment(id, "viewer1", "ほかの人");
		Api.CommentsResponse all = api.comments(id, null);
		assertEquals(List.of("返事です", "ほかの人"), all.comments.stream().map(c -> c.text).toList());
		assertTrue(!all.comments.get(1).byHost());
		// after を付けると新着だけ
		Api.CommentsResponse after = api.comments(id, mine.id);
		assertEquals(List.of("ほかの人"), after.comments.stream().map(c -> c.text).toList());
		assertTrue(api.comments(id, all.comments.getLast().id).comments.isEmpty());

		// 偽のチャレンジは 401
		ApiException bad = assertThrows(ApiException.class, () -> api.viewerToken(new Api.MinecraftProof("mc_does_not_exist", "taku128")));
		assertEquals(401, bad.status());
		assertEquals("minecraft_unverified", bad.code());

		// 終わったセッションは 404 not_found
		api.deleteSession(id, created.hostToken);
		ApiException gone = assertThrows(ApiException.class, () -> api.comments(id, null));
		assertEquals("not_found", gone.code());
	}

	/** 未確認のホストを許さないサーバでは、確認を付けない作成は 401 minecraft_required。 */
	@Test
	@EnabledIfSystemProperty(named = "redtactLive.strictServer", matches = ".+")
	void unverifiedHostRejectedByStrictServer() {
		LiveApi strict = new LiveApi(LiveApi.newHttpClient(), () -> System.getProperty("redtactLive.strictServer"), () -> "mod-it");
		ApiException e = assertThrows(ApiException.class, () -> strict.createSession(new Api.CreateSessionRequest("IT 厳格", "c0ffee00c0ffee0a", "26.3", 4671)));
		assertEquals(401, e.status());
		assertEquals("minecraft_required", e.code());
	}

	/** listed=true で開いたものだけが開催中の一覧に出て、PATCH で出し入れでき、終われば消える。 */
	@Test
	void listedSessions() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		Api.CreateSessionResponse shown = api.createSession(new Api.CreateSessionRequest("IT 一覧に出す", "c0ffee00c0ffee05", "26.3", 4671, true));
		Api.CreateSessionResponse hidden = api.createSession(new Api.CreateSessionRequest("IT 出さない", "c0ffee00c0ffee06", "26.3", 4671));
		assertTrue(shown.session.listed);
		assertTrue(!hidden.session.listed, "listed の既定は false");

		List<String> ids = api.listSessions().stream().map(x -> x.sessionId).toList();
		assertTrue(ids.contains(shown.session.sessionId), "listed のセッションが一覧に無い");
		assertTrue(!ids.contains(hidden.session.sessionId), "listed でないセッションが一覧にある");
		Api.Session inList = api.listSessions().stream().filter(x -> x.sessionId.equals(shown.session.sessionId)).findFirst().orElseThrow();
		assertEquals("IT 一覧に出す", inList.title);
		assertEquals("mod-it", inList.host);
		assertTrue(!inList.hostOnline, "ホストが繋いでいないので hostOnline=false");
		assertEquals(shown.shareUrl, inList.shareUrl, "一覧の shareUrl が POST の shareUrl と違う");
		assertEquals(shown.shareUrl, shown.session.shareUrl, "POST の session.shareUrl が最上位の shareUrl と違う");
		assertTrue(inList.shareUrl.endsWith("/s/" + shown.session.sessionId), inList.shareUrl);

		// 出していたものを外す・出していなかったものを出す
		assertTrue(!api.setListed(shown.session.sessionId, shown.hostToken, false).listed);
		assertTrue(api.setListed(hidden.session.sessionId, hidden.hostToken, true).listed);
		ids = api.listSessions().stream().map(x -> x.sessionId).toList();
		assertTrue(!ids.contains(shown.session.sessionId), "外したのに一覧に残っている");
		assertTrue(ids.contains(hidden.session.sessionId), "出したのに一覧に無い");

		// トークン違いは 401
		ApiException wrong = assertThrows(ApiException.class, () -> api.setListed(hidden.session.sessionId, "wrong-token", false));
		assertEquals(401, wrong.status());

		// 終わったら消える
		api.deleteSession(hidden.session.sessionId, hidden.hostToken);
		api.deleteSession(shown.session.sessionId, shown.hostToken);
		ids = api.listSessions().stream().map(x -> x.sessionId).toList();
		assertTrue(!ids.contains(hidden.session.sessionId), "終わったのに一覧に残っている");
	}

	/**
	 * GET .litematic: サーバ (Go) が詰めた BlockStates を MOD の LitematicaBitArray で読み、送った回路と 1 マスずつ比べる。
	 * パレットの 0 番は空気・TileEntities は領域内の x,y,z 付き・版とファイル名はヘッダ。
	 */
	@Test
	void litematicMatchesSentCircuit() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		String title = "IT 複製 a+b";
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest(title, "c0ffee00c0ffee07", "26.3", 4671));
		String id = created.session.sessionId;
		assertTrue(created.session.copyAllowed(), "allowCopy の既定は true");
		assertEquals(Boolean.TRUE, created.session.allowCopy, "サーバが allowCopy を返していない");

		// まだ回路が無いので 404
		ApiException none = assertThrows(ApiException.class, () -> api.downloadLitematic(id));
		assertEquals(404, none.status());
		assertEquals("not_found", none.code());

		CircuitState s1 = sampleState();
		api.putState(id, created.hostToken, s1);
		LiveApi.Litematic lit = api.downloadLitematic(id);
		assertEquals(1, lit.version());
		assertEquals(title + "-v1.litematic", lit.fileName(), "filename* (UTF-8) のタイトルが戻らない");
		assertSameCircuit(s1, lit.data(), title);

		// パレット 34 種 (6 bit) の大きめの回路で、long をまたぐ詰め方まで Go と Java で合うか
		CircuitState s2 = randomState(7, 3, 5, 34, 12345L);
		api.putState(id, created.hostToken, s2);
		LiveApi.Litematic lit2 = api.downloadLitematic(id);
		assertEquals(2, lit2.version());
		assertSameCircuit(s2, lit2.data(), title);
		api.deleteSession(id, created.hostToken);

		// 終わったセッションは 404
		ApiException gone = assertThrows(ApiException.class, () -> api.downloadLitematic(id));
		assertEquals(404, gone.status());
	}

	/** allowCopy=false で開くと .litematic と state は 403 copy_not_allowed。PATCH で許せば取れる。 */
	@Test
	void copyNotAllowed() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest("IT 複製不可", "c0ffee00c0ffee08", "26.3", 4671, true, null, false));
		String id = created.session.sessionId;
		assertEquals(Boolean.FALSE, created.session.allowCopy);
		api.putState(id, created.hostToken, sampleState());

		ApiException denied = assertThrows(ApiException.class, () -> api.downloadLitematic(id));
		assertEquals(403, denied.status());
		assertEquals("copy_not_allowed", denied.code());
		ApiException deniedState = assertThrows(ApiException.class, () -> api.getState(id));
		assertEquals(403, deniedState.status());
		assertEquals("copy_not_allowed", deniedState.code());
		// 一覧にも allowCopy=false で出る
		Api.Session inList = api.listSessions().stream().filter(x -> x.sessionId.equals(id)).findFirst().orElseThrow();
		assertTrue(!inList.copyAllowed());

		// トークン違いは 401
		assertEquals(401, assertThrows(ApiException.class, () -> api.setAllowCopy(id, "wrong-token", true)).status());

		// 許す → 取れる。一覧に出すかは変わらない
		Api.Session allowed = api.setAllowCopy(id, created.hostToken, true);
		assertTrue(allowed.copyAllowed());
		assertTrue(allowed.listed, "allowCopy だけの PATCH で listed が変わった");
		assertSameCircuit(sampleState(), api.downloadLitematic(id).data(), "IT 複製不可");

		// listed だけの PATCH で allowCopy は変わらない
		Api.Session unlisted = api.setListed(id, created.hostToken, false);
		assertTrue(unlisted.copyAllowed(), "listed だけの PATCH で allowCopy が変わった");
		assertTrue(!api.setAllowCopy(id, created.hostToken, false).copyAllowed());
		assertEquals(403, assertThrows(ApiException.class, () -> api.downloadLitematic(id)).status());
		api.deleteSession(id, created.hostToken);
	}

	private static void assertSameCircuit(CircuitState expected, byte[] litematic, String regionName) throws Exception {
		net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(litematic),
				net.minecraft.nbt.NbtAccounter.unlimitedHeap());
		assertEquals(7, root.getIntOr("Version", -1));
		net.minecraft.nbt.CompoundTag regions = root.getCompoundOrEmpty("Regions");
		assertEquals(1, regions.size());
		net.minecraft.nbt.CompoundTag r = regions.getCompoundOrEmpty(regionName);
		net.minecraft.nbt.CompoundTag size = r.getCompoundOrEmpty("Size");
		assertArrayEquals(expected.size(), new int[] {size.getIntOr("x", -1), size.getIntOr("y", -1), size.getIntOr("z", -1)});

		net.minecraft.nbt.ListTag palette = r.getListOrEmpty("BlockStatePalette");
		assertEquals("minecraft:air", palette.getCompoundOrEmpty(0).getStringOr("Name", ""), "パレットの 0 番が空気でない");
		List<String> names = new java.util.ArrayList<>();

		for (int i = 0; i < palette.size(); i++) {
			net.minecraft.nbt.CompoundTag e = palette.getCompoundOrEmpty(i);
			java.util.Map<String, String> props = new java.util.TreeMap<>();
			net.minecraft.nbt.CompoundTag p = e.getCompoundOrEmpty("Properties");

			for (String k : p.keySet()) {
				props.put(k, p.getStringOr(k, ""));
			}

			names.add(com.redtact.live.litematic.BlockStateEntry.of(e.getStringOr("Name", ""), props).toString());
		}

		long[] states = r.getLongArray("BlockStates").orElseThrow();
		int bits = com.redtact.live.litematic.LitematicaBitArray.bitsForPaletteSize(palette.size());
		assertEquals(com.redtact.live.litematic.LitematicaBitArray.longCount(expected.volume(), bits), states.length, "BlockStates の長さ");

		for (int i = 0; i < expected.volume(); i++) {
			assertEquals(expected.blockAt(i), names.get(com.redtact.live.litematic.LitematicaBitArray.get(states, bits, i)), "添字 " + i);
		}

		net.minecraft.nbt.ListTag tes = r.getListOrEmpty("TileEntities");
		assertEquals(expected.blockEntities().size(), tes.size());

		for (int i = 0; i < tes.size(); i++) {
			net.minecraft.nbt.CompoundTag te = tes.getCompoundOrEmpty(i);
			int index = expected.index(te.getIntOr("x", -1), te.getIntOr("y", -1), te.getIntOr("z", -1));
			assertTrue(expected.blockEntities().containsKey(index), "TileEntity の位置 " + te);
			net.minecraft.nbt.CompoundTag sent = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(
					new java.io.ByteArrayInputStream(expected.blockEntities().get(index))), net.minecraft.nbt.NbtAccounter.unlimitedHeap());

			for (String k : sent.keySet()) {
				assertEquals(sent.get(k), te.get(k), "TileEntity の " + k);
			}
		}
	}

	/** palette 種類のブロック (0 番は空気) をばらまいた回路。ブロックエンティティは 2 個。 */
	private static CircuitState randomState(int sx, int sy, int sz, int paletteSize, long seed) {
		List<String> palette = new java.util.ArrayList<>();
		palette.add("minecraft:air");
		palette.add("minecraft:stone");

		for (int p = 0; palette.size() < paletteSize && p < 16; p++) {
			palette.add("minecraft:redstone_wire[east=none,north=none,power=" + p + ",south=side,west=side]");
		}

		String[] facings = {"east", "north", "south", "west"};

		for (int d = 1; palette.size() < paletteSize && d <= 4; d++) {
			for (String f : facings) {
				if (palette.size() < paletteSize) {
					palette.add("minecraft:repeater[delay=" + d + ",facing=" + f + ",locked=false,powered=false]");
				}
			}
		}

		java.util.Random rnd = new java.util.Random(seed);
		int[] blocks = new int[sx * sy * sz];

		for (int i = 0; i < blocks.length; i++) {
			blocks[i] = i < palette.size() ? i : rnd.nextInt(palette.size()); // 全部の種類を使う
		}

		net.minecraft.nbt.CompoundTag te = new net.minecraft.nbt.CompoundTag();
		te.putString("id", "minecraft:comparator");
		te.putInt("OutputSignal", 9);
		net.minecraft.nbt.CompoundTag te2 = new net.minecraft.nbt.CompoundTag();
		te2.putString("id", "minecraft:comparator");
		te2.putInt("OutputSignal", 15);
		int last = blocks.length - 1;
		return new CircuitState(sx, sy, sz, palette, blocks, java.util.Map.of(3, BlockEntityNbt.encode(te), last, BlockEntityNbt.encode(te2)));
	}

	/**
	 * 分岐 (#13): 親に回路を送ってから parent 付きで子を開く。親の GET と WebSocket に branches が出て、
	 * 子が複製の許可を変えると branches が配り直され、親が終わると子の WebSocket に parent (ended) が届く。
	 */
	@Test
	void branching() throws Exception {
		LiveApi parentApi = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it-親");
		LiveApi childApi = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it-子");
		Api.CreateSessionResponse p = parentApi.createSession(new Api.CreateSessionRequest("IT 分岐元", "c0ffee00c0ffee10", "26.3", 4671, false, null, true));
		String pid = p.session.sessionId;
		assertTrue(p.session.parent == null && p.session.branchList().isEmpty(), "分岐していないセッションに parent・branches がある");

		// まだ回路が無い親からは分岐できない (400)
		ApiException noCircuit = assertThrows(ApiException.class, () -> childApi.createSession(branchReq("IT 早すぎ", pid, 1)));
		assertEquals(400, noCircuit.status());
		assertEquals("bad_request", noCircuit.code());

		parentApi.putState(pid, p.hostToken, sampleState());
		Thread.sleep(300);
		parentApi.putState(pid, p.hostToken, withBlock(sampleState(), 0, "minecraft:gold_block")); // v2

		ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
		Recorder parentRec = new Recorder();
		HostSocket parentSocket = new HostSocket(parentApi.http(), parentApi.hostSocketUri(pid), p.hostToken, "mod-it-親", parentRec, Runnable::run, sched);
		parentSocket.start();
		assertTrue(parentRec.hello.await(10, TimeUnit.SECONDS));

		// 版が 1〜親の版から外れれば 400
		assertEquals(400, assertThrows(ApiException.class, () -> childApi.createSession(branchReq("IT 版 0", pid, 0))).status());
		assertEquals(400, assertThrows(ApiException.class, () -> childApi.createSession(branchReq("IT 版 3", pid, 3))).status());
		// 無い親は 404 parent_not_found
		ApiException noParent = assertThrows(ApiException.class, () -> childApi.createSession(branchReq("IT 親なし", "Nope0Nope0", 1)));
		assertEquals(404, noParent.status());
		assertEquals("parent_not_found", noParent.code());

		// 子を開く (v1 から)
		Api.CreateSessionResponse c = childApi.createSession(branchReq("IT 分岐した子", pid, 1));
		String cid = c.session.sessionId;
		Api.Parent cp = c.session.parent;
		assertNotNull(cp, "子に parent が無い");
		assertEquals(pid, cp.sessionId);
		assertEquals("IT 分岐元", cp.title);
		assertEquals("mod-it-親", cp.host);
		assertEquals(1, cp.version);
		assertEquals(p.shareUrl, cp.shareUrl);
		assertTrue(!cp.ended);

		// 親の GET と WebSocket に branches
		Api.Session pv = parentApi.getSession(pid).orElseThrow();
		assertEquals(1, pv.branchList().size());
		Api.Branch b = pv.branchList().get(0);
		assertEquals(cid, b.sessionId);
		assertEquals("IT 分岐した子", b.title);
		assertEquals("mod-it-子", b.host);
		assertEquals(1, b.fromVersion);
		assertTrue(b.copyAllowed());
		assertEquals(c.shareUrl, b.shareUrl);
		List<Api.Branch> pushed = parentRec.branches.poll(10, TimeUnit.SECONDS);
		assertNotNull(pushed, "親の WebSocket に branches が届かない");
		assertEquals(List.of(cid), pushed.stream().map(x -> x.sessionId).toList());

		// 子が複製を止めると、親に branches が配り直される
		childApi.setAllowCopy(cid, c.hostToken, false);
		List<Api.Branch> afterCopy = parentRec.branches.poll(10, TimeUnit.SECONDS);
		assertNotNull(afterCopy, "子の allowCopy の変化で branches が届かない");
		assertTrue(!afterCopy.get(0).copyAllowed());
		childApi.setAllowCopy(cid, c.hostToken, true);
		assertNotNull(parentRec.branches.poll(10, TimeUnit.SECONDS));

		// 親が複製を止めると分岐できない (403)
		parentApi.setAllowCopy(pid, p.hostToken, false);
		ApiException denied = assertThrows(ApiException.class, () -> childApi.createSession(branchReq("IT 複製不可", pid, 1)));
		assertEquals(403, denied.status());
		assertEquals("copy_not_allowed", denied.code());
		parentApi.setAllowCopy(pid, p.hostToken, true);

		// 子の WebSocket: hello に parent。親が終わると parent (ended) が届き、子は続く
		Recorder childRec = new Recorder();
		HostSocket childSocket = new HostSocket(childApi.http(), childApi.hostSocketUri(cid), c.hostToken, "mod-it-子", childRec, Runnable::run, sched);
		childSocket.start();
		assertTrue(childRec.hello.await(10, TimeUnit.SECONDS));
		assertEquals(pid, childRec.helloSession.parent.sessionId);
		parentApi.deleteSession(pid, p.hostToken);
		Api.Parent ended = childRec.parents.poll(10, TimeUnit.SECONDS);
		assertNotNull(ended, "親が終わったのに parent が届かない");
		assertTrue(ended.ended);
		assertEquals(pid, ended.sessionId);
		Api.Session cv = childApi.getSession(cid).orElseThrow();
		assertTrue(cv.parent.ended, "子の GET の parent.ended が true でない");
		assertEquals(1, childApi.putState(cid, c.hostToken, sampleState()).version, "親が終わっても子に回路を送れる");

		// 終わった親からは分岐できない (404)
		assertEquals("parent_not_found", assertThrows(ApiException.class, () -> childApi.createSession(branchReq("IT 終了後", pid, 1))).code());

		childSocket.stop();
		parentSocket.stop();
		childApi.deleteSession(cid, c.hostToken);
		sched.shutdownNow();
	}

	private static Api.CreateSessionRequest branchReq(String title, String parentId, int version) {
		return new Api.CreateSessionRequest(title, "c0ffee00c0ffee1" + Math.abs(title.hashCode() % 10), "26.3", 4671, false, null, null,
				new Api.ParentRef(parentId, version));
	}

	/**
	 * 差分の本文は展開後 8 MiB (LIVE_MAX_DIFF_BYTES) まで。超えると 413 で、MOD は同じ状態を全体で送る (#17)。
	 * 全体の上限 (展開後 32 MiB) の方が大きいので、全体なら通る。
	 */
	@Test
	void tooLargeDiffFallsBackToFull() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest("IT 大きな差分", "c0ffee00c0ffee05", "26.3", 4671));
		String id = created.session.sessionId;
		String token = created.hostToken;
		CircuitState s1 = new CircuitState(16, 2, 16, List.of("minecraft:air", "minecraft:chest"), new int[16 * 2 * 16], java.util.Map.of());
		assertEquals(1, api.putState(id, token, s1).version);

		// 48 KiB のブロックエンティティ 200 件 (base64 で約 12.5 MB) を足す差分
		int[] blocks = new int[16 * 2 * 16];
		java.util.Map<Integer, byte[]> bes = new java.util.HashMap<>();
		for (int i = 0; i < 200; i++) {
			blocks[i] = 1;
			byte[] nbt = new byte[48 << 10];
			java.util.Arrays.fill(nbt, (byte) (i % 7 + 1));
			bes.put(i, nbt);
		}
		CircuitState s2 = new CircuitState(16, 2, 16, s1.palette(), blocks, bes);
		ApiException tooLarge = assertThrows(ApiException.class, () -> api.patchState(id, token, StateDiffer.diff(s1, 1, s2)));
		assertTrue(tooLarge.isTooLarge(), tooLarge.describe());
		assertEquals("too_large", tooLarge.code());

		Thread.sleep(1100); // 書き込みは 1 秒 4 回まで
		assertEquals(2, api.putState(id, token, s2).version);
		assertTrue(CircuitState.fromJson(api.getState(id).orElseThrow()).sameContent(s2), "全体で送った状態と違う");
		api.deleteSession(id, token);
	}

	/** state の書き込みは 1 セッション 1 秒に 4 回まで。超えると 429 rate_limited (本文を読む前)。 */
	@Test
	void stateWritesAreRateLimited() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest("IT 制限", "c0ffee00c0ffee04", "26.3", 4671));
		String id = created.session.sessionId;
		CircuitState s = sampleState();
		int ok = 0;
		ApiException limited = null;

		for (int i = 0; i < 6 && limited == null; i++) {
			try {
				api.putState(id, created.hostToken, s);
				ok++;
			} catch (ApiException e) {
				limited = e;
			}
		}

		assertNotNull(limited, "6 回続けて書いても 429 にならない");
		assertEquals(429, limited.status());
		assertEquals("rate_limited", limited.code());
		assertTrue(ok >= 1 && ok <= 4, "429 までに通った回数: " + ok);
		Thread.sleep(1100);
		assertEquals(ok + 1, api.putState(id, created.hostToken, s).version, "待てばまた書ける");
		api.deleteSession(id, created.hostToken);
	}

	/** 日本語・空白・記号を含む表示名が percent-encode で通り、そのまま host になる。 */
	@Test
	void nonAsciiUserName() throws Exception {
		String name = "たく 太郎+%/α";
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> name);
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest("IT 日本語ユーザ", "c0ffee00c0ffee03", "26.3", 4671));
		assertEquals(name, created.session.host);
		ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
		Recorder r = new Recorder();
		HostSocket s = new HostSocket(api.http(), api.hostSocketUri(created.session.sessionId), created.hostToken, name, r, Runnable::run, sched);
		s.start();
		assertTrue(r.hello.await(10, TimeUnit.SECONDS));
		s.stop();
		api.deleteSession(created.session.sessionId, created.hostToken);
		sched.shutdownNow();
	}

	/** 同じトークンで 2 本目を張ると 1 本目は 4001 で閉じられ、再接続しない。終了後の接続は 404 で終了扱い。 */
	@Test
	void replacedAndGone() throws Exception {
		LiveApi api = new LiveApi(LiveApi.newHttpClient(), () -> base, () -> "mod-it");
		Api.CreateSessionResponse created = api.createSession(new Api.CreateSessionRequest("IT 置き換え", "c0ffee00c0ffee01", "26.3", 4671));
		String id = created.session.sessionId;
		ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();

		Recorder first = new Recorder();
		HostSocket s1 = new HostSocket(api.http(), api.hostSocketUri(id), created.hostToken, "mod-it", first, Runnable::run, sched);
		s1.start();
		assertTrue(first.hello.await(10, TimeUnit.SECONDS));

		Recorder second = new Recorder();
		HostSocket s2 = new HostSocket(api.http(), api.hostSocketUri(id), created.hostToken, "mod-it", second, Runnable::run, sched);
		s2.start();
		assertTrue(second.hello.await(10, TimeUnit.SECONDS));
		assertTrue(first.replaced.await(10, TimeUnit.SECONDS), "4001 で閉じられない");
		Thread.sleep(1500);
		assertEquals(0, first.disconnects.size(), "置き換えられた側が再接続しようとした");
		assertTrue(api.getSession(id).orElseThrow().hostOnline);

		api.deleteSession(id, created.hostToken);
		assertTrue(second.ended.await(10, TimeUnit.SECONDS));
		s2.stop();

		// 終わったセッションへ張ると 404 -> onEnded("not_found")
		Recorder third = new Recorder();
		HostSocket s3 = new HostSocket(api.http(), api.hostSocketUri(id), created.hostToken, "mod-it", third, Runnable::run, sched);
		s3.start();
		assertTrue(third.ended.await(10, TimeUnit.SECONDS));
		assertEquals("not_found", third.endReason);

		// トークン違いは 401 -> onRejected
		Api.CreateSessionResponse other = api.createSession(new Api.CreateSessionRequest("IT 401", "c0ffee00c0ffee02", "26.3", 4671));
		Recorder fourth = new Recorder();
		HostSocket s4 = new HostSocket(api.http(), api.hostSocketUri(other.session.sessionId), "wrong-token", "mod-it", fourth, Runnable::run, sched);
		s4.start();
		assertTrue(fourth.rejected.await(10, TimeUnit.SECONDS));
		assertEquals(401, fourth.rejectedStatus);
		api.deleteSession(other.session.sessionId, other.hostToken);
		sched.shutdownNow();
	}

	private static final class Recorder implements HostSocket.Handler {
		final CountDownLatch hello = new CountDownLatch(1);
		final CountDownLatch replaced = new CountDownLatch(1);
		final CountDownLatch ended = new CountDownLatch(1);
		final CountDownLatch rejected = new CountDownLatch(1);
		final List<String> disconnects = new CopyOnWriteArrayList<>();
		/** 受け取った branches (一覧の全体) を順に。 */
		final java.util.concurrent.LinkedBlockingQueue<List<Api.Branch>> branches = new java.util.concurrent.LinkedBlockingQueue<>();
		final java.util.concurrent.LinkedBlockingQueue<Api.Parent> parents = new java.util.concurrent.LinkedBlockingQueue<>();
		volatile Api.Session helloSession;
		volatile String endReason;
		volatile int rejectedStatus;

		@Override
		public void onHello(Api.Session session, List<Api.Comment> pending, boolean reconnected) {
			helloSession = session;
			hello.countDown();
		}

		@Override
		public void onBranches(List<Api.Branch> list) {
			branches.add(list);
		}

		@Override
		public void onParent(Api.Parent parent) {
			parents.add(parent);
		}

		@Override
		public void onComment(Api.Comment comment) {
		}

		@Override
		public void onViewers(int count) {
		}

		@Override
		public void onEnded(String reason) {
			endReason = reason;
			ended.countDown();
		}

		@Override
		public void onRejected(int status) {
			rejectedStatus = status;
			rejected.countDown();
		}

		@Override
		public void onReplaced() {
			replaced.countDown();
		}

		@Override
		public void onDisconnected(String why, long retryMillis, int attempt) {
			disconnects.add(why);
		}
	}

	/** LitematicWriterTest と同じ 3x2x4 + コンパレーターのブロックエンティティ (本物の NBT)。 */
	static CircuitState sampleState() {
		net.minecraft.nbt.CompoundTag te = new net.minecraft.nbt.CompoundTag();
		te.putString("id", "minecraft:comparator");
		te.putInt("OutputSignal", 5);
		CircuitState s = CircuitState.of(LitematicWriterTest.sampleForOtherTests(), java.util.Map.of());
		return new CircuitState(3, 2, 4, s.palette(), s.blocks(), java.util.Map.of(s.index(1, 1, 2), BlockEntityNbt.encode(te)));
	}

	static CircuitState withBlock(CircuitState s, int index, String block) {
		java.util.List<String> palette = new java.util.ArrayList<>(s.palette());
		int p = palette.indexOf(block);

		if (p < 0) {
			palette.add(block);
			p = palette.size() - 1;
		}

		int[] blocks = s.blocks().clone();
		blocks[index] = p;
		return new CircuitState(s.sizeX(), s.sizeY(), s.sizeZ(), palette, blocks, s.blockEntities());
	}

	private void postComment(String id, String user, String text) throws Exception {
		HttpResponse<String> res = java.net.http.HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/sessions/" + id + "/comments"))
				.header("Content-Type", "application/json")
				.header("X-Live-User", user)
				.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(java.util.Map.of("text", text)), StandardCharsets.UTF_8))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(201, res.statusCode(), res.body());
	}
}
