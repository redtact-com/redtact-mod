package com.redtact.live.input;

import org.jspecify.annotations.Nullable;

/**
 * ゲーム内の redtact のページがマウスを閉じ込めている間 (Pointer Lock の肩代わり。#88)、ゲームのマウスの動きを画面へ渡さずに
 * ためておく。ためた分はゲーム内の redtact の画面が毎コマ取り出してページへ送る。
 *
 * <p>マウスの入口 ({@code MouseHandler#onMove}) の mixin から呼ぶので、MCEF Modern の型に触れない。描画のスレッドだけで使う
 * (SDL のイベントもゲームのスレッドの仕事もそこで動く)。
 */
public final class PointerCapture {
	private static boolean active;
	private static double dx;
	private static double dy;

	private PointerCapture() {
	}

	/** 閉じ込めているか。 */
	public static boolean active() {
		return active;
	}

	/** 閉じ込めを始める (ためた動きは捨てる)。 */
	public static void begin() {
		active = true;
		dx = 0;
		dy = 0;
	}

	/** 閉じ込めをやめる (ためた動きは捨てる)。 */
	public static void end() {
		active = false;
		dx = 0;
		dy = 0;
	}

	/**
	 * マウスの入口から。閉じ込めていれば相対の動き (窓の座標) をためて true を返す (ゲームの画面へは渡さない。カーソルの位置も
	 * 閉じ込めたときのまま)。閉じ込めていなければ false。
	 */
	public static boolean onMove(double xrel, double yrel) {
		if (!active) {
			return false;
		}

		dx += xrel;
		dy += yrel;
		return true;
	}

	/** ためた動き {dx, dy} (窓の座標) を取り出して空にする。動いていなければ null。 */
	public static double @Nullable [] drain() {
		if (dx == 0 && dy == 0) {
			return null;
		}

		double[] d = {dx, dy};
		dx = 0;
		dy = 0;
		return d;
	}
}
