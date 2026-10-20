package com.redtact.live.gametest.mixin;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * gametest だけ: {@code REDTACT_LIVE_WEBGL=1} のとき、MCEF Modern の Chromium にソフトウェア描画の WebGL を許すスイッチを足す
 * (#88)。Xvfb には GPU が無く、Chromium は WebGL を止めるので、本物の redtact の 3D プレビュー (フライ) を確かめられない。
 * MCEF Modern が無ければ当たらない (@Pseudo)。
 */
@Pseudo
@Mixin(targets = "net.dimaskama.mcef.impl.MCEFApiImpl", remap = false)
public abstract class McefWebGlMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lme/friwi/jcefmaven/CefAppBuilder;addJcefArgs([Ljava/lang/String;)V"), remap = false)
	private String[] redtactLiveGametest$allowSoftwareWebGl(String[] args) {
		if (System.getenv("REDTACT_LIVE_WEBGL") == null) {
			return args;
		}

		String[] out = Arrays.copyOf(args, args.length + 2);
		out[args.length] = "--enable-unsafe-swiftshader";
		out[args.length + 1] = "--ignore-gpu-blocklist";
		return out;
	}
}
