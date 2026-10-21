package com.redtact.live.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

//? if <26.3 {
/*import com.redtact.live.input.PointerCapture;
*///?}

/**
 * カーソルの擬似入力の、マイクラの版で違うところ (#112)。26.3 は SDL3、26.2 までは GLFW。
 */
final class GameTestMouse {
	private GameTestMouse() {
	}

	/**
	 * カーソルを窓の画素で dx, dy だけ動かす。マウスを閉じ込めている間も実機と同じ動きを入れる。
	 *
	 * <p>26.2 まで (GLFW) は、閉じ込めている間 GLFW が隠したカーソルの仮想の位置を前に知らせた位置から進める。Fabric の
	 * moveCursor はゲームが覚えている位置 (MOD が閉じ込めたところのままにしている) から足すので、何度動かしても同じ位置が届いて
	 * 2 回目からは動かない。閉じ込めている間は GLFW と同じく前に届いた位置から足す。
	 */
	static void move(ClientGameTestContext context, double dx, double dy) {
		//? if >=26.3 {
		context.getInput().moveCursor(dx, dy);
		//?} else {
		/*double[] last = context.computeOnClient(mc -> PointerCapture.active() ? PointerCapture.lastCursor() : null);

		if (last == null) {
			context.getInput().moveCursor(dx, dy);
		} else {
			context.getInput().setCursorPos(last[0] + dx, last[1] + dy);
		}
		*///?}
	}
}
