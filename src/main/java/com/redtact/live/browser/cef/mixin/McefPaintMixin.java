package com.redtact.live.browser.cef.mixin;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

import com.redtact.live.browser.cef.BrowserStats;
import org.cef.browser.CefBrowser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MCEF Modern の描き直しを数える (#55)。MCEF Modern が入っているときだけ当てる ({@link McefMixinPlugin})。
 * onPaint は Chromium のスレッドで、絵を写して BGRA → RGBA に並べ替える。onPaintInternal はゲームの描画のスレッドで、
 * テクスチャへ転送する。
 */
@Pseudo
@Mixin(targets = "net.dimaskama.mcef.impl.MCEFBrowserImpl", remap = false)
public abstract class McefPaintMixin {
	@Inject(method = "onPaint", at = @At("HEAD"), remap = false)
	private void redtactLive$paintStart(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height,
			CallbackInfo ci) {
		if (!popup) {
			BrowserStats.paintStart(width, height);
		}
	}

	@Inject(method = "onPaint", at = @At("RETURN"), remap = false)
	private void redtactLive$paintEnd(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height,
			CallbackInfo ci) {
		BrowserStats.paintEnd();
	}

	// MCEF Modern の onPaintInternal の引数は、マイクラの版ごとの MCEF の作りで違う (26.3 用は画素・幅・高さだけ、26.2 用は
	// ポップアップか・更新された範囲も先に来る。#112)
	//? if >=26.3 {
	@Inject(method = "onPaintInternal", at = @At("HEAD"), remap = false)
	private void redtactLive$uploadStart(ByteBuffer buffer, int width, int height, CallbackInfo ci) {
		BrowserStats.uploadStart();
	}

	@Inject(method = "onPaintInternal", at = @At("RETURN"), remap = false)
	private void redtactLive$uploadEnd(ByteBuffer buffer, int width, int height, CallbackInfo ci) {
		BrowserStats.uploadEnd();
	}
	//?} else {
	/*@Inject(method = "onPaintInternal", at = @At("HEAD"), remap = false)
	private void redtactLive$uploadStart(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height, CallbackInfo ci) {
		BrowserStats.uploadStart();
	}

	@Inject(method = "onPaintInternal", at = @At("RETURN"), remap = false)
	private void redtactLive$uploadEnd(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height, CallbackInfo ci) {
		BrowserStats.uploadEnd();
	}
	*///?}
}
