package com.redtact.live.mixin;

import com.redtact.live.input.KeyNames;
import com.redtact.live.input.HotkeyManager;
import com.redtact.live.input.PointerCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * マウスのボタンを、ゲームより先にホットキー ({@link HotkeyManager}) に見せる。範囲指定の道具 (羽根) のクリックはここで打ち消すので、
 * ブロックの破壊・設置・アイテムの使用は起きず、サーバにも送らない (Litematica の道具と同じ)。
 *
 * <p>ゲーム内の redtact のページがマウスを閉じ込めている間 (#88) は、マウスの動きを {@link PointerCapture} に回して画面へは渡さない。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void redtactLive$onButton(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
		if (handle == Minecraft.getInstance().getWindow().handle() && HotkeyManager.onMouse(KeyNames.fromGameButton(rawButtonInfo.button()), action)) {
			ci.cancel();
		}
	}

	//? if >=26.3 {
	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void redtactLive$onMove(long handle, double xpos, double ypos, double xrel, double yrel, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();

		if (handle == mc.getWindow().handle() && PointerCapture.onMove(xrel, yrel)) {
	//?} else {
	/*// 26.2 まで (GLFW) は onMove に動いた量が無く位置だけが来るので、閉じ込めたところからの差にする (#112)
	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void redtactLive$onMove(long handle, double xpos, double ypos, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();

		if (handle == mc.getWindow().handle() && PointerCapture.onMoveTo(xpos, ypos)) {
	*///?}
			// 見回しているだけでも操作中 (1 分動かさないと FPS を絞るのを、閉じ込めている間の動きでも止める)
			mc.getFramerateLimitTracker().onInputReceived();
			ci.cancel();
		}
	}
}
