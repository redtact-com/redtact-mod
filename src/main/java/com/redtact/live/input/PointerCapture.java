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
	/** 最後に来たカーソルの位置 (26.2 まで。閉じ込めたところから GLFW が隠したカーソルの仮想の位置を進める) */
	private static double cursorX;
	private static double cursorY;

	private PointerCapture() {
	}

	/** 閉じ込めているか。 */
	public static boolean active() {
		return active;
	}

	/** 閉じ込めを始める (ためた動きは捨てる)。x, y はいまのカーソルの位置 (窓の座標)。 */
	public static void begin(double x, double y) {
		active = true;
		dx = 0;
		dy = 0;
		cursorX = x;
		cursorY = y;
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

	/**
	 * 26.2 まで (GLFW) のマウスの入口から。onMove には動いた量が無く位置だけが来るので、前に来た位置 (はじめは閉じ込めたところ)
	 * との差を {@link #onMove} に渡す。閉じ込めている間 GLFW は隠したカーソルの仮想の位置を進め続ける (#112)。
	 */
	public static boolean onMoveTo(double x, double y) {
		if (!active) {
			return false;
		}

		double xrel = x - cursorX;
		double yrel = y - cursorY;
		cursorX = x;
		cursorY = y;
		return onMove(xrel, yrel);
	}

	/** 最後に来たカーソルの位置 {x, y} (26.2 まで。テストが GLFW の代わりに次の位置を作るため)。 */
	public static double[] lastCursor() {
		return new double[] {cursorX, cursorY};
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
