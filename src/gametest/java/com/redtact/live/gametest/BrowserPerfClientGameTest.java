package com.redtact.live.gametest;

import com.redtact.live.browser.BrowserSupport;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * ゲーム内の redtact の重さを計る (#55)。{@code REDTACT_LIVE_PERF=1} で、MCEF Modern が入っているとき ({@code -Pmcef}) だけ
 * {@link BrowserPerfChecks} を動かす (MCEF Modern の型に触れるクラスは、入っていないときに読み込まない)。
 */
@SuppressWarnings("UnstableApiUsage")
public class BrowserPerfClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// 確かめる文は日本語 (#79。gametest のマイクラは英語なので、自動のままだと英語になる)
		context.runOnClient(mc -> com.redtact.live.config.Configs.Generic.LANGUAGE.setOptionListValue(com.redtact.live.i18n.UiLanguage.JA));

		if (System.getenv("REDTACT_LIVE_PERF") != null && context.computeOnClient(mc -> BrowserSupport.available())) {
			BrowserPerfChecks.run(context);
		}
	}
}
