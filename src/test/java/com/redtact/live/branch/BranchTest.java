package com.redtact.live.branch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.Json;
import com.redtact.live.selection.Box;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

class BranchTest {
	private static final String CHILD = """
			{"session":{"sessionId":"Zz9Yy8Xx7W","title":"hana の案","host":"hana","hostVerified":true,"createdAt":"2026-10-14T01:00:00.000Z",
			 "hostOnline":true,"listed":false,"allowCopy":true,"shareUrl":"http://localhost:5173/s/Zz9Yy8Xx7W","viewerCount":0,"snapshot":null,
			 "parent":{"sessionId":"a1B2c3D4e5","title":"自動仕分け機","host":"taku","hostVerified":false,"version":4,
			           "shareUrl":"http://localhost:5173/s/a1B2c3D4e5","ended":false},
			 "branches":[],"comments":[]}}
			""";
	private static final String PARENT = """
			{"session":{"sessionId":"a1B2c3D4e5","title":"自動仕分け機","host":"taku","hostVerified":false,"createdAt":"2026-10-14T00:00:00.000Z",
			 "hostOnline":true,"listed":true,"allowCopy":true,"shareUrl":"http://localhost:5173/s/a1B2c3D4e5","viewerCount":2,"snapshot":null,
			 "parent":null,
			 "branches":[
			  {"sessionId":"Zz9Yy8Xx7W","title":"hana の案","host":"hana","hostVerified":true,"fromVersion":4,"createdAt":"2026-10-14T01:00:00.000Z",
			   "allowCopy":true,"shareUrl":"http://localhost:5173/s/Zz9Yy8Xx7W"},
			  {"sessionId":"Q1w2E3r4T5","title":"別案","host":"jiro","hostVerified":false,"fromVersion":2,"createdAt":"2026-10-14T01:10:00.000Z",
			   "allowCopy":false,"shareUrl":"http://localhost:5173/s/Q1w2E3r4T5"}],
			 "comments":[]}}
			""";

	@Test
	void parsesParentAndBranches() {
		Api.Session child = Json.GSON.fromJson(CHILD, Api.SessionResponse.class).session;
		assertEquals("a1B2c3D4e5", child.parent.sessionId);
		assertEquals("自動仕分け機", child.parent.title);
		assertEquals("taku", child.parent.host);
		assertFalse(child.parent.hostVerified);
		assertEquals(4, child.parent.version);
		assertEquals("http://localhost:5173/s/a1B2c3D4e5", child.parent.shareUrl);
		assertFalse(child.parent.ended);
		assertTrue(child.branchList().isEmpty());

		Api.Session parent = Json.GSON.fromJson(PARENT, Api.SessionResponse.class).session;
		assertNull(parent.parent);
		List<Api.Branch> b = parent.branchList();
		assertEquals(2, b.size());
		assertEquals("Zz9Yy8Xx7W", b.get(0).sessionId);
		assertEquals("hana の案", b.get(0).title);
		assertEquals("hana", b.get(0).host);
		assertTrue(b.get(0).hostVerified);
		assertEquals(4, b.get(0).fromVersion);
		assertTrue(b.get(0).copyAllowed());
		assertEquals("http://localhost:5173/s/Zz9Yy8Xx7W", b.get(0).shareUrl);
		assertFalse(b.get(1).copyAllowed());

		// 古いサーバ (parent・branches が無い)
		Api.Session old = Json.GSON.fromJson("{\"sessionId\":\"a1B2c3D4e5\",\"title\":\"t\"}", Api.Session.class);
		assertNull(old.parent);
		assertTrue(old.branchList().isEmpty());
	}

	@Test
	void parsesWebSocketMessages() {
		Api.WsMessage branches = Json.GSON.fromJson("""
				{"type":"branches","branches":[{"sessionId":"Zz9Yy8Xx7W","title":"hana の案","host":"hana","hostVerified":true,"fromVersion":4,
				 "createdAt":"2026-10-14T01:00:00.000Z","allowCopy":true,"shareUrl":"http://localhost:5173/s/Zz9Yy8Xx7W"}]}
				""", Api.WsMessage.class);
		assertEquals("branches", branches.type);
		assertEquals(1, branches.branches.size());
		assertEquals(4, branches.branches.get(0).fromVersion);

		Api.WsMessage parent = Json.GSON.fromJson("""
				{"type":"parent","parent":{"sessionId":"a1B2c3D4e5","title":"自動仕分け機","host":"taku","hostVerified":false,"version":4,
				 "shareUrl":"http://localhost:5173/s/a1B2c3D4e5","ended":true}}
				""", Api.WsMessage.class);
		assertEquals("parent", parent.type);
		assertTrue(parent.parent.ended);
	}

	@Test
	void createRequestCarriesParentOnlyWhenBranching() {
		String plain = Json.GSON.toJson(new Api.CreateSessionRequest("t", "c", "26.3", 4671, false, null, true));
		assertFalse(plain.contains("parent"), plain);

		String branch = Json.GSON.toJson(new Api.CreateSessionRequest("t", "c", "26.3", 4671, false, null, true, new Api.ParentRef("a1B2c3D4e5", 4)));
		assertTrue(branch.contains("\"parent\":{\"sessionId\":\"a1B2c3D4e5\",\"version\":4}"), branch);
	}

	@Test
	void texts() {
		assertEquals("自動仕分け機 の分岐", BranchText.defaultName("自動仕分け機"));
		assertEquals("回路 の分岐", BranchText.defaultName("  "));
		String longName = BranchText.defaultName("あ".repeat(80));
		assertEquals(BranchText.MAX_NAME, longName.codePointCount(0, longName.length()));
		assertTrue(longName.endsWith("… の分岐"));

		assertEquals("分岐元のセッションが終わっています (分岐はできません)", BranchText.createError(new ApiException(404, "parent_not_found", "x")));
		assertEquals("分岐元のホストが複製を許可していません", BranchText.createError(new ApiException(403, "copy_not_allowed", "x")));
		assertEquals("分岐元の版が合いません (parent.version は 1〜4)", BranchText.createError(new ApiException(400, "bad_request", "parent.version は 1〜4")));
		assertTrue(BranchText.createError(new ApiException(429, "limit_reached", "20 件まで")).contains("20 件まで"));

		Api.Session parent = Json.GSON.fromJson(PARENT, Api.SessionResponse.class).session;
		List<Api.Branch> all = parent.branchList();
		assertEquals(List.of(all.get(1)), BranchText.added(List.of(all.get(0)), all));
		assertTrue(BranchText.added(all, List.of(all.get(0))).isEmpty(), "消えた子は知らせない");
		assertEquals("hana の案 (✔ hana)・v4 から", BranchText.addedToast(all.get(0)));

		Api.Parent p = Json.GSON.fromJson(CHILD, Api.SessionResponse.class).session.parent;
		assertTrue(BranchText.parentLine(p).contains("v4") && BranchText.parentLine(p).contains("開催中"));
		p.ended = true;
		assertTrue(BranchText.parentLine(p).contains("終了"));
	}

	@Test
	void placementTransformMatchesLitematica() {
		// Litematica の PositionUtils.getTransformedBlockPos と同じ: 反転してから回す
		BlockPos p = new BlockPos(5, 1, 3);
		assertEquals(new BlockPos(5, 1, 3), PlacementGeometry.transform(p, Mirror.NONE, Rotation.NONE));
		assertEquals(new BlockPos(-3, 1, 5), PlacementGeometry.transform(p, Mirror.NONE, Rotation.CLOCKWISE_90));
		assertEquals(new BlockPos(-5, 1, -3), PlacementGeometry.transform(p, Mirror.NONE, Rotation.CLOCKWISE_180));
		assertEquals(new BlockPos(3, 1, -5), PlacementGeometry.transform(p, Mirror.NONE, Rotation.COUNTERCLOCKWISE_90));
		assertEquals(new BlockPos(5, 1, -3), PlacementGeometry.transform(p, Mirror.LEFT_RIGHT, Rotation.NONE));
		assertEquals(new BlockPos(-5, 1, 3), PlacementGeometry.transform(p, Mirror.FRONT_BACK, Rotation.NONE));
		// 反転 (前後) してから 90°: (-5,1,3) -> (-3,1,-5)
		assertEquals(new BlockPos(-3, 1, -5), PlacementGeometry.transform(p, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90));
	}

	@Test
	void enclosingBoxOfRotatedPlacement() {
		BlockPos origin = new BlockPos(20, -60, 20);
		Vec3i size = new Vec3i(6, 2, 4);
		// 回さなければ原点から +x +y +z
		assertEquals(new Box(20, -60, 20, 25, -59, 23), PlacementGeometry.regionBox(origin, BlockPos.ZERO, size, PlacementGeometry.Transform.NONE));
		// 時計回りに 90°: x の広がりが +z に、z の広がりが -x に
		PlacementGeometry.Transform cw = new PlacementGeometry.Transform(Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE);
		Box rotated = PlacementGeometry.regionBox(origin, BlockPos.ZERO, size, cw);
		assertEquals(new Box(17, -60, 20, 20, -59, 25), rotated);
		assertEquals(4, rotated.sizeX());
		assertEquals(6, rotated.sizeZ());
		assertEquals(new BlockPos(17, -59, 25), PlacementGeometry.toWorld(new BlockPos(5, 1, 3), origin, BlockPos.ZERO, cw));
		// 180° と反転 (左右)
		assertEquals(new Box(15, -60, 17, 20, -59, 20), PlacementGeometry.regionBox(origin, BlockPos.ZERO, size,
				new PlacementGeometry.Transform(Mirror.NONE, Rotation.CLOCKWISE_180, Mirror.NONE, Rotation.NONE)));
		assertEquals(new Box(20, -60, 17, 25, -59, 20), PlacementGeometry.regionBox(origin, BlockPos.ZERO, size,
				new PlacementGeometry.Transform(Mirror.LEFT_RIGHT, Rotation.NONE, Mirror.NONE, Rotation.NONE)));
		// サブリージョンの位置も配置全体の回転で回る
		assertEquals(new Box(17, -60, 22, 20, -59, 27), PlacementGeometry.regionBox(origin, new BlockPos(2, 0, 0), size, cw));
		// 大きさが負の軸は原点から負の向き
		assertEquals(new Box(15, -60, 20, 20, -59, 23), PlacementGeometry.regionBox(origin, BlockPos.ZERO, new Vec3i(-6, 2, 4), PlacementGeometry.Transform.NONE));
		// 囲む箱
		assertEquals(new Box(15, -60, 20, 25, -59, 25), PlacementGeometry.enclose(List.of(new Box(15, -60, 20, 20, -59, 23), new Box(20, -60, 21, 25, -59, 25))));
		assertNull(PlacementGeometry.enclose(List.of()));
		assertEquals("回転 90°", PlacementGeometry.describe(cw));
		assertEquals("", PlacementGeometry.describe(PlacementGeometry.Transform.NONE));
	}
}
