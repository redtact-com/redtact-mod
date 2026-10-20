package com.redtact.live.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** 撮るときの補助 (窓の大きさと GUI の倍率の切り替え・待つ・カーソルを外す。#77)。 */
@SuppressWarnings("UnstableApiUsage")
final class LiveClientGameTestShots {
	/** {@link #resize} の前の窓の幅・高さ・GUI の倍率 (戻すまで null でない)。 */
	private static int[] orig;

	private LiveClientGameTestShots() {
	}

	/** 窓を size = {幅, 高さ, GUI の倍率} にする (最初の 1 回だけ元の大きさを覚える)。 */
	static void resize(ClientGameTestContext context, int[] size) {
		if (orig == null) {
			orig = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});
		}

		context.getInput().resizeWindow(size[0], size[1]);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(size[2]);
			mc.resizeGui();
		});
		context.waitTicks(5);
	}

	/** {@link #resize} の前の大きさに戻す。 */
	static void restore(ClientGameTestContext context) {
		if (orig == null) {
			return;
		}

		int[] o = orig;
		orig = null;
		context.getInput().resizeWindow(o[0], o[1]);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(o[2]);
			mc.resizeGui();
		});
		context.waitTicks(5);
	}

	static void wait(ClientGameTestContext context, long millis) {
		long end = System.currentTimeMillis() + millis;

		while (System.currentTimeMillis() < end) {
			context.waitTicks(1);
		}
	}

	/** マウスを画面の左上 (ロゴの上) に置く (撮る前にホバーとツールチップを消す)。 */
	static void neutralCursor(ClientGameTestContext context) {
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(2);
	}
}
