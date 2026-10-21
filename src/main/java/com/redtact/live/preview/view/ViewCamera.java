package com.redtact.live.preview.view;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 全画面のプレビュー (#102) のカメラ。redtact の web の 3D プレビューと同じく、回す (オービット)・フライ・真上 (2D) の 3 つの見方を持つ。
 * 座標は回路のマス目 (x 東・y 上・z 南。1 ブロック = 1)。角度は度。ゲームのクラスに触れないので単体テストで確かめられる。
 *
 * <ul>
 * <li>オービット: 見る点 (target) の周りを yaw・pitch で回り、distance 離れて見る。pitch が正なら上から見下ろす
 * <li>フライ: 位置と向き (yaw・pitch。pitch が正なら下を向く)。移動は水平の前後左右と上下 (マイクラのクリエイティブの飛行と同じ)
 * <li>真上: 正射影で真上から (画面の上が北)。topSpan が画面の縦に入るブロック数
 * </ul>
 */
public final class ViewCamera {
	public enum Mode {
		ORBIT, FLY, TOP
	}

	/** 縦の画角 (度。web と同じ 70°)。 */
	public static final float FOV = 70.0F;
	/** 初めの向き (止まったプレビューの既定と同じ。北東の上から南西を見下ろす)。 */
	public static final float DEFAULT_YAW = 135.0F;
	public static final float DEFAULT_PITCH = 30.0F;
	private static final float NEAR = 0.05F;
	private static final float FAR = 4096.0F;
	private static final float MIN_DISTANCE = 1.0F;
	private static final float MAX_DISTANCE = 2048.0F;

	private Mode mode = Mode.ORBIT;
	private float sizeX = 1;
	private float sizeY = 1;
	private float sizeZ = 1;
	// オービット
	private float yaw = DEFAULT_YAW;
	private float pitch = DEFAULT_PITCH;
	private float distance = 10.0F;
	private final Vector3f target = new Vector3f();
	// フライ
	private final Vector3f flyPos = new Vector3f();
	private float flyYaw;
	private float flyPitch;
	// 真上
	private final Vector3f topCenter = new Vector3f();
	private float topSpan = 10.0F;

	public Mode mode() {
		return mode;
	}

	/**
	 * 止まったプレビュー ({@code PreviewView}) の yaw を、このカメラの yaw に直す。止まったプレビューは回路を yaw だけ回して
	 * 描くので、回路の周りを回るカメラでは逆回りになる。
	 */
	public static float yawFromPreview(float previewYaw) {
		return wrap(-previewYaw);
	}

	/** 回路の大きさに合わせて全部の見方を初めの形 (既定の向き) に戻す。 */
	public void reset(int sx, int sy, int sz, float aspect) {
		reset(sx, sy, sz, aspect, DEFAULT_YAW, DEFAULT_PITCH);
	}

	/**
	 * 回路の大きさに合わせて全部の見方を初めの形に戻す (回路が画面にいっぱいに収まる距離)。aspect は画面の幅 / 高さ、
	 * yaw・pitch はオービットの向き。
	 */
	public void reset(int sx, int sy, int sz, float aspect, float yaw, float pitch) {
		sizeX = Math.max(1, sx);
		sizeY = Math.max(1, sy);
		sizeZ = Math.max(1, sz);
		target.set(sizeX / 2.0F, sizeY / 2.0F, sizeZ / 2.0F);
		this.yaw = wrap(yaw);
		this.pitch = clamp(pitch, -89.0F, 89.0F);
		// 回路を囲む球が、縦と横の狭いほうの画角に入る距離
		float radius = 0.5F * (float) Math.sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ);
		float halfFov = (float) Math.toRadians(FOV / 2.0F);
		float halfFovH = (float) Math.atan(Math.tan(halfFov) * Math.max(0.1F, aspect));
		distance = clamp(radius / (float) Math.sin(Math.min(halfFov, halfFovH)) * 1.05F, MIN_DISTANCE, MAX_DISTANCE);
		topCenter.set(sizeX / 2.0F, sizeY, sizeZ / 2.0F);
		topSpan = Math.max(sizeZ, sizeX / Math.max(0.1F, aspect)) * 1.1F + 1.0F;
		toFlyFromOrbit();
	}

	/** 見方を変える。オービットからフライに移るときは、いまの位置と向きを引き継ぐ (画面が飛ばない。web の orbitToFly と同じ)。 */
	public void setMode(Mode next) {
		if (next == Mode.FLY && mode != Mode.FLY) {
			if (mode == Mode.ORBIT) {
				toFlyFromOrbit();
			}
		}

		mode = next;
	}

	private void toFlyFromOrbit() {
		flyPos.set(orbitEye());
		flyYaw = yaw;
		flyPitch = pitch;
	}

	// ---- 操作

	/** オービット: ドラッグで回す (dx・dy は GUI の画素)。右へドラッグすると回路が右へ回る。 */
	public void orbitDrag(double dx, double dy) {
		yaw = wrap(yaw - (float) dx * 0.4F);
		pitch = clamp(pitch + (float) dy * 0.4F, -89.0F, 89.0F);
	}

	/** オービット・真上: 右ドラッグで動かす (dx・dy は GUI の画素、viewHeight は画面の高さの GUI の画素)。景色がカーソルについてくる。 */
	public void pan(double dx, double dy, float viewHeight) {
		if (mode == Mode.TOP) {
			float perPixel = topSpan / Math.max(1.0F, viewHeight);
			topCenter.x -= (float) dx * perPixel;
			topCenter.z -= (float) dy * perPixel;
			return;
		}

		float perPixel = 2.0F * distance * (float) Math.tan(Math.toRadians(FOV / 2.0F)) / Math.max(1.0F, viewHeight);
		Vector3f[] basis = basis(orbitDirection());
		target.fma((float) -dx * perPixel, basis[0]).fma((float) dy * perPixel, basis[1]);
	}

	/** ホイールで寄る (steps が正なら寄る)。 */
	public void zoom(double steps) {
		float k = (float) Math.pow(0.88, steps);

		if (mode == Mode.TOP) {
			topSpan = clamp(topSpan * k, 2.0F, MAX_DISTANCE);
		} else {
			distance = clamp(distance * k, MIN_DISTANCE, MAX_DISTANCE);
		}
	}

	/** フライ: マウスで見回す (度)。右へ動かすと右を向き、下へ動かすと下を向く。 */
	public void flyLook(double dYawDeg, double dPitchDeg) {
		flyYaw = wrap(flyYaw - (float) dYawDeg);
		flyPitch = clamp(flyPitch + (float) dPitchDeg, -89.9F, 89.9F);
	}

	/** フライ: forward (前 +)・right (右 +)・up (上 +) の向きに、blocks だけ動く (前後左右は水平)。 */
	public void flyMove(float forward, float right, float up, float blocks) {
		double y = Math.toRadians(flyYaw);
		float fx = (float) -Math.sin(y);
		float fz = (float) -Math.cos(y);
		float rx = (float) Math.cos(y);
		float rz = (float) -Math.sin(y);
		Vector3f d = new Vector3f(fx * forward + rx * right, up, fz * forward + rz * right);

		if (d.lengthSquared() > 1.0E-6F) {
			d.normalize().mul(blocks);
			flyPos.add(d);
		}
	}

	/** フライの標準の速さ (ブロック/秒)。回路が大きいほど速い。 */
	public float flySpeed() {
		return Math.max(6.0F, Math.max(sizeX, Math.max(sizeY, sizeZ)) / 5.0F);
	}

	// ---- 描くための値

	/** カメラの位置 (回路の座標)。 */
	public Vector3f eye() {
		return switch (mode) {
			case ORBIT -> orbitEye();
			case FLY -> new Vector3f(flyPos);
			case TOP -> new Vector3f(topCenter.x, sizeY + 64.0F, topCenter.z);
		};
	}

	/** 見る向き (単位ベクトル)。 */
	public Vector3f direction() {
		return switch (mode) {
			case ORBIT -> orbitDirection();
			case FLY -> lookDirection(flyYaw, flyPitch);
			case TOP -> new Vector3f(0, -1, 0);
		};
	}

	/** カメラの向きだけの行列 (位置はゲームの Globals のカメラの位置で引くので入れない)。 */
	public Matrix4f view() {
		Vector3f d = direction();
		Vector3f up = mode == Mode.TOP ? new Vector3f(0, 0, -1) : new Vector3f(0, 1, 0);
		return new Matrix4f().lookAt(0, 0, 0, d.x, d.y, d.z, up.x, up.y, up.z);
	}

	/**
	 * 投影の行列。width・height は画素、zZeroToOne は GPU の深度の範囲 (0〜1 か -1〜1)。reversed ならゲーム (26.2 から) と同じく
	 * 深度は逆向き (近いほど大きい。手前と奥を入れ替えて作る)。
	 */
	public Matrix4f projection(float width, float height, boolean zZeroToOne, boolean reversed) {
		float aspect = width / Math.max(1.0F, height);

		if (mode == Mode.TOP) {
			float h = topSpan;
			float w = h * aspect;
			return new Matrix4f().setOrtho(-w / 2, w / 2, -h / 2, h / 2, reversed ? FAR : -FAR, reversed ? -FAR : FAR, zZeroToOne);
		}

		return new Matrix4f().setPerspective((float) Math.toRadians(FOV), aspect, reversed ? FAR : NEAR, reversed ? NEAR : FAR, zZeroToOne);
	}

	// ---- テストと表示のため

	public float yaw() {
		return mode == Mode.FLY ? flyYaw : yaw;
	}

	public float pitch() {
		return mode == Mode.FLY ? flyPitch : pitch;
	}

	public float distance() {
		return distance;
	}

	public float topSpan() {
		return topSpan;
	}

	public Vector3f target() {
		return new Vector3f(target);
	}

	public Vector3f topCenter() {
		return new Vector3f(topCenter);
	}

	// ---- 計算

	/** オービットで見る点からカメラへの向き。 */
	private Vector3f orbitOffsetDirection() {
		double y = Math.toRadians(yaw);
		double p = Math.toRadians(pitch);
		return new Vector3f((float) (Math.sin(y) * Math.cos(p)), (float) Math.sin(p), (float) (Math.cos(y) * Math.cos(p)));
	}

	private Vector3f orbitEye() {
		return orbitOffsetDirection().mul(distance).add(target);
	}

	private Vector3f orbitDirection() {
		return orbitOffsetDirection().negate();
	}

	/** yaw・pitch の向き (オービットのカメラから見る点への向きと同じ式)。 */
	static Vector3f lookDirection(float yawDeg, float pitchDeg) {
		double y = Math.toRadians(yawDeg);
		double p = Math.toRadians(pitchDeg);
		return new Vector3f((float) (-Math.sin(y) * Math.cos(p)), (float) -Math.sin(p), (float) (-Math.cos(y) * Math.cos(p)));
	}

	/** 向き d の画面の右と上 (どちらも d に直交する単位ベクトル)。 */
	private static Vector3f[] basis(Vector3f d) {
		Vector3f right = new Vector3f(d).cross(0, 1, 0);

		if (right.lengthSquared() < 1.0E-6F) {
			right.set(1, 0, 0);
		}

		right.normalize();
		Vector3f up = new Vector3f(right).cross(d).normalize();
		return new Vector3f[] {right, up};
	}

	private static float wrap(float deg) {
		return ((deg % 360.0F) + 360.0F) % 360.0F;
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
