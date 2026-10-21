package com.redtact.live.ui;

import com.redtact.live.i18n.Tr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * 利用者への知らせ。<b>チャットには出さない</b> (状態は HUD、新着コメントとエラーはトースト、
 * 細かい出来事はアクションバーとメニューの「最近の出来事」)。どのスレッドから呼んでもよい。
 */
public final class Messages {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final SystemToast.SystemToastId COMMENT_TOAST = new SystemToast.SystemToastId(6000L);
	private static final SystemToast.SystemToastId NOTICE_TOAST = new SystemToast.SystemToastId(5000L);
	private static final SystemToast.SystemToastId ERROR_TOAST = new SystemToast.SystemToastId(8000L);

	private Messages() {
	}

	/** 細かい知らせ: アクションバー + 最近の出来事。 */
	public static void info(String text) {
		EventLog.add(EventLog.Level.INFO, text);
		overlay(text);
	}

	public static void success(String text) {
		info(text);
	}

	/** 失敗: トースト + 最近の出来事 + ログ。 */
	public static void error(String text) {
		EventLog.add(EventLog.Level.ERROR, text);
		LOGGER.info("[redtact-live] error: {}", text);
		toast(ERROR_TOAST, Tr.t("redtact: エラー"), text);
	}

	/** 大事な知らせ (セッションの開始・終了など): トースト + 最近の出来事。 */
	public static void notice(String title, String text) {
		EventLog.add(EventLog.Level.INFO, title + ": " + text);
		toast(NOTICE_TOAST, "redtact: " + title, text);
	}

	/** アクションバー (ホットバーの上) にだけ出す。 */
	public static void overlay(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				//? if >=26.1 {
				mc.player.sendOverlayMessage(Component.literal("redtact: " + text).withStyle(ChatFormatting.GRAY));
				//?} else
				/*mc.player.displayClientMessage(Component.literal("redtact: " + text).withStyle(ChatFormatting.GRAY), true);*/
			}
		});
	}

	public static void commentToast(String author, String text) {
		String body = text.length() > 120 ? text.substring(0, 119) + "…" : text;
		Minecraft mc = Minecraft.getInstance();
		// コメントは 1 件ずつ並べる (置き換えない)
		mc.execute(() -> SystemToast.add(mc.gui.toastManager(), COMMENT_TOAST, Component.literal("redtact: " + author),
				Component.literal(body.replace('\n', ' '))));
	}

	private static void toast(SystemToast.SystemToastId id, String title, String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(), id, Component.literal(title), Component.literal(text)));
	}
}
