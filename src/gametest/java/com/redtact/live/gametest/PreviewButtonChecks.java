package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;
import static com.redtact.live.gametest.LibraryClientGameTest.screen;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.gui.kit.PreviewView;
import com.redtact.live.gui.kit.RButton;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * プレビューの右上のボタン (#93)。止まった絵なのでドラッグでは回らず、「右に回す」(上から見て時計回り = yaw を減らす)・
 * 「左に回す」で 15° ずつ回る。
 */
final class PreviewButtonChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");

	private PreviewButtonChecks() {
	}

	/** 保管庫: file を選んで回すボタンを見る。 */
	static void library(ClientGameTestContext context, Path file) {
		LibraryPreviewChecks.selectAndWait(context, file);

		// ドラッグでは回らない (描き直さない)
		float yaw0 = yaw(context);
		int[] mid = context.computeOnClient(mc -> {
			PreviewView p = screen(mc).preview();
			return new int[] {p.getX() + p.getWidth() / 2, p.getY() + p.getHeight() / 2};
		});
		moveTo(context, mid[0], mid[1]);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		context.getInput().moveCursor(60, 24);
		context.waitTicks(2);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		check(yaw(context) == yaw0, "ドラッグで回った: " + yaw0 + " -> " + yaw(context));

		// 右に回す → 右に回す → 左に回す 3 回で、元から左に 1 段
		click(context, 1);
		check(yaw(context) == mod(yaw0 - PreviewView.STEP), "「右に回す」で " + PreviewView.STEP + "° 回らない: " + yaw0 + " -> " + yaw(context));
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5f-1-right");
		click(context, 1);
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5f-2-right");

		for (int i = 0; i < 3; i++) {
			click(context, 0);
		}

		check(yaw(context) == mod(yaw0 + PreviewView.STEP), "「左に回す」で " + PreviewView.STEP + "° 回らない: " + yaw0 + " -> " + yaw(context));
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5f-preview-buttons");
		LOGGER.info("[library] プレビューのボタン: ドラッグでは回らず、回すボタンで {}° ずつ", PreviewView.STEP);
	}

	private static void click(ClientGameTestContext context, int index) {
		int[] at = context.computeOnClient(mc -> {
			RButton b = screen(mc).preview().controls().get(index);
			check(b.visible, "プレビューのボタン " + index + " が出ていない");
			return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
		});
		LibraryClientGameTest.clickGui(context, at[0], at[1]);
		context.waitTicks(1);
	}

	/** カーソルを GUI の座標へ (押さない)。 */
	private static void moveTo(ClientGameTestContext context, double gx, double gy) {
		double scale = context.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		context.getInput().setCursorPos(gx * scale, gy * scale);
		context.waitTicks(1);
	}

	private static float yaw(ClientGameTestContext context) {
		return context.computeOnClient(mc -> screen(mc).preview().yaw());
	}

	private static float mod(float yaw) {
		return (yaw % 360.0F + 360.0F) % 360.0F;
	}
}
