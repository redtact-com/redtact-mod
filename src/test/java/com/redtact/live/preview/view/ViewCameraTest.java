package com.redtact.live.preview.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/** 全画面のプレビューのカメラ (#102)。web の 3D プレビューと同じ動き (回す・動かす・寄る・フライ・真上) と、ゲームと同じ逆向きの深度。 */
class ViewCameraTest {
	private static final float EPS = 1.0E-3F;

	private static ViewCamera camera() {
		ViewCamera c = new ViewCamera();
		c.reset(10, 4, 6, 16 / 9.0F);
		return c;
	}

	@Test
	void resetLooksAtTheCenterFromTheDefaultSide() {
		ViewCamera c = camera();
		assertEquals(ViewCamera.Mode.ORBIT, c.mode());
		assertVec(new Vector3f(5, 2, 3), c.target());
		assertEquals(ViewCamera.DEFAULT_YAW, c.yaw(), EPS);
		assertEquals(ViewCamera.DEFAULT_PITCH, c.pitch(), EPS);
		// 既定は北東 (x が大きく z が小さい) の上から南西を見下ろす (止まったプレビューと同じ側)
		Vector3f d = c.direction();
		assertTrue(d.x < 0 && d.y < 0 && d.z > 0, "向き: " + d);
		Vector3f eye = c.eye();
		assertTrue(eye.x > 5 && eye.y > 2 && eye.z < 3, "位置: " + eye);
		assertEquals(c.distance(), eye.distance(c.target()), EPS);
	}

	/** 回路を囲む球が、縦と横の狭いほうの画角に入る (はみ出さない)。 */
	@Test
	void resetFitsTheBoundingSphere() {
		ViewCamera c = new ViewCamera();
		c.reset(40, 10, 40, 0.5F); // 縦長の画面では横の画角で決まる
		float radius = 0.5F * (float) Math.sqrt(40 * 40 + 10 * 10 + 40 * 40);
		float halfFovH = (float) Math.atan(Math.tan(Math.toRadians(ViewCamera.FOV / 2)) * 0.5);
		assertTrue(c.distance() * Math.sin(halfFovH) >= radius, "近すぎる: " + c.distance());
	}

	/** 止まったプレビューの向き (回路を yaw だけ回す) は、カメラでは逆回り。既定どうしが同じ側になる。 */
	@Test
	void previewYawTurnsTheOtherWay() {
		assertEquals(ViewCamera.DEFAULT_YAW, ViewCamera.yawFromPreview(225.0F), EPS);
		assertEquals(150.0F, ViewCamera.yawFromPreview(210.0F), EPS); // 右に回す (yaw -15) → カメラは +15
		assertEquals(0.0F, ViewCamera.yawFromPreview(0.0F), EPS);
		assertEquals(270.0F, ViewCamera.yawFromPreview(90.0F), EPS);
		ViewCamera c = new ViewCamera();
		c.reset(3, 3, 3, 1, ViewCamera.yawFromPreview(210.0F), 30.0F);
		assertEquals(150.0F, c.yaw(), EPS);
	}

	@Test
	void orbitDragTurnsAndTilts() {
		ViewCamera c = camera();
		c.orbitDrag(10, 5);
		assertEquals(ViewCamera.DEFAULT_YAW - 4, c.yaw(), EPS);
		assertEquals(ViewCamera.DEFAULT_PITCH + 2, c.pitch(), EPS);
		c.orbitDrag(0, 10_000);
		assertEquals(89.0F, c.pitch(), EPS); // 真上で止まる (裏返らない)
		c.orbitDrag(1_000, 0);
		assertTrue(c.yaw() >= 0 && c.yaw() < 360, "yaw が 0〜360 に収まらない: " + c.yaw());
	}

	/** 右ドラッグで動かすと、見る点が画面の左右・上下に動く (景色がカーソルについてくる)。距離と向きは変わらない。 */
	@Test
	void panMovesTheTargetAcrossTheScreen() {
		ViewCamera c = camera();
		Vector3f before = c.target();
		Vector3f d = c.direction();
		Vector3f right = new Vector3f(d).cross(0, 1, 0).normalize();
		float distance = c.distance();
		c.pan(30, 0, 480);
		Vector3f moved = c.target().sub(before);
		assertTrue(moved.dot(right) < 0, "右へドラッグしたら見る点は左へ: " + moved);
		assertEquals(0, moved.dot(d), EPS);
		assertEquals(distance, c.distance(), EPS);
		before = c.target();
		c.pan(0, 30, 480);
		assertTrue(c.target().sub(before).y > 0, "下へドラッグしたら見る点は上へ");
	}

	@Test
	void zoomStaysInRange() {
		ViewCamera c = camera();
		float d0 = c.distance();
		c.zoom(1);
		assertEquals(d0 * 0.88F, c.distance(), EPS);
		c.zoom(-2);
		assertTrue(c.distance() > d0);
		c.zoom(1_000);
		assertEquals(1.0F, c.distance(), EPS);
	}

	/** オービットからフライに移っても画面が飛ばない (同じ位置と向き)。 */
	@Test
	void flyStartsWhereTheOrbitWas() {
		ViewCamera c = camera();
		c.orbitDrag(25, -10);
		Vector3f eye = c.eye();
		Vector3f dir = c.direction();
		c.setMode(ViewCamera.Mode.FLY);
		assertVec(eye, c.eye());
		assertVec(dir, c.direction());
	}

	/** フライ: 前後左右は水平 (見下ろしていても沈まない)・上下はそのまま・マウスの右で右を向き、下で下を向く。 */
	@Test
	void flyMovesHorizontallyAndLooks() {
		ViewCamera c = camera();
		c.setMode(ViewCamera.Mode.FLY);
		Vector3f e0 = c.eye();
		Vector3f d = c.direction();
		c.flyMove(1, 0, 0, 2);
		Vector3f step = c.eye().sub(e0);
		assertEquals(0, step.y, EPS);
		assertEquals(2, step.length(), EPS);
		assertTrue(step.dot(new Vector3f(d.x, 0, d.z)) > 0, "前へ進まない: " + step);
		Vector3f e1 = c.eye();
		c.flyMove(0, 0, 1, 3);
		assertVec(new Vector3f(e1).add(0, 3, 0), c.eye());
		Vector3f e2 = c.eye();
		c.flyMove(0, 1, 0, 1);
		Vector3f side = c.eye().sub(e2);
		Vector3f right = new Vector3f(d.x, 0, d.z).cross(0, 1, 0).normalize();
		assertEquals(1, side.dot(right), EPS);

		float yaw = c.yaw();
		float pitch = c.pitch();
		c.flyLook(10, 5);
		assertEquals(yaw - 10, c.yaw(), EPS);
		assertEquals(pitch + 5, c.pitch(), EPS);
		c.flyLook(0, 1_000);
		assertEquals(89.9F, c.pitch(), EPS);
		assertTrue(c.flySpeed() >= 6.0F);
	}

	/** 真上: 正射影で真下を見る。画面の上が北 (-z)・右が東 (+x)。ドラッグで景色がついてくる。 */
	@Test
	void topLooksStraightDownWithNorthUp() {
		ViewCamera c = camera();
		c.setMode(ViewCamera.Mode.TOP);
		assertVec(new Vector3f(0, -1, 0), c.direction());
		Matrix4f view = c.view();
		Vector4f east = view.transform(new Vector4f(1, 0, 0, 0));
		Vector4f north = view.transform(new Vector4f(0, 0, -1, 0));
		assertEquals(1, east.x, EPS);
		assertEquals(1, north.y, EPS);
		Vector3f center = c.topCenter();
		c.pan(20, 10, 480);
		assertTrue(c.topCenter().x < center.x && c.topCenter().z < center.z, "ドラッグで動かない");
		float span = c.topSpan();
		c.zoom(1);
		assertEquals(span * 0.88F, c.topSpan(), EPS);
	}

	/** 深度はゲームと同じ逆向き (近いほど大きい)。透視と正射影のどちらも。 */
	@Test
	void depthIsReversed() {
		for (ViewCamera.Mode mode : new ViewCamera.Mode[] {ViewCamera.Mode.ORBIT, ViewCamera.Mode.TOP}) {
			ViewCamera c = camera();
			c.setMode(mode);
			Matrix4f m = new Matrix4f(c.projection(1600, 900, true)).mul(c.view());
			Vector3f d = c.direction();
			float near = depth(m, new Vector3f(d).mul(2));
			float far = depth(m, new Vector3f(d).mul(50));
			assertTrue(near > far, mode + ": 近い " + near + " / 遠い " + far);
			assertTrue(near <= 1 && far >= 0, mode + ": 0〜1 に入らない " + near + " " + far);
		}
	}

	private static float depth(Matrix4f m, Vector3f p) {
		Vector4f v = m.transform(new Vector4f(p, 1));
		return v.z / v.w;
	}

	private static void assertVec(Vector3f want, Vector3f got) {
		assertTrue(want.distance(got) < EPS, "want " + want + " got " + got);
	}
}
