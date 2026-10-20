package com.redtact.live.mixin;

import com.redtact.live.input.HotkeyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/** キーボードの入力を、ゲームより先にホットキー ({@link HotkeyManager}) に見せる。当たって打ち消すならゲームに渡さない。 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void redtactLive$onKey(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (handle == Minecraft.getInstance().getWindow().handle() && HotkeyManager.onKeyboard(event.key(), action)) {
			ci.cancel();
		}
	}
}
