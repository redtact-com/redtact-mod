package com.redtact.live.input;

import com.redtact.live.RedtactLive;
import com.redtact.live.config.Configs;
import com.redtact.live.gui.Menu;
import com.redtact.live.i18n.Tr;
import com.redtact.live.tool.SelectionTool;
import com.redtact.live.ui.Messages;

import net.minecraft.client.Minecraft;

/** ホットキーを押したときの動き。クライアントスレッドで呼ばれる (ゲーム中だけ)。 */
public final class KeyCallbacks {
	private KeyCallbacks() {
	}

	public static void init() {
		Configs.Hotkeys.OPEN_MENU.getKeybind().setCallback(consume(() -> Menu.open()));
		Configs.Hotkeys.SET_POS1.getKeybind().setCallback(inWorld(() -> RedtactLive.get().setPosFromLook(1)));
		Configs.Hotkeys.SET_POS2.getKeybind().setCallback(inWorld(() -> RedtactLive.get().setPosFromLook(2)));
		Configs.Hotkeys.PUSH.getKeybind().setCallback(inWorld(() -> RedtactLive.get().sessions().push()));
		Configs.Hotkeys.TOGGLE_AUTO_SYNC.getKeybind().setCallback(consume(() -> RedtactLive.get().setAutoSync(!Configs.Generic.AUTO_SYNC.getBooleanValue())));
		// 道具を持っていなければ false を返し、クリックはふつうに通る
		Configs.Hotkeys.TOOL_CORNER_1.getKeybind().setCallback(() -> SelectionTool.onClick(1));
		Configs.Hotkeys.TOOL_CORNER_2.getKeybind().setCallback(() -> SelectionTool.onClick(2));
		Configs.Hotkeys.TOOL_PICK.getKeybind().setCallback(SelectionTool::onPick);
		Configs.Hotkeys.TOGGLE_HUD.getKeybind().setCallback(consume(() -> {
			boolean on = !Configs.Generic.HUD_ENABLED.getBooleanValue();
			Configs.Generic.HUD_ENABLED.setBooleanValue(on);
			Configs.saveToFile();
			Messages.overlay(on ? Tr.t("HUD を表示にしました") : Tr.t("HUD を非表示にしました"));
		}));
	}

	private static Keybind.Callback consume(Runnable action) {
		return () -> {
			action.run();
			return true;
		};
	}

	private static Keybind.Callback inWorld(Runnable action) {
		return () -> {
			if (Minecraft.getInstance().player == null) {
				return false;
			}

			action.run();
			return true;
		};
	}
}
