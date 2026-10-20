package com.redtact.live.gametest;

import com.redtact.live.browser.BrowserSupport;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Pointer Lock の肩代わり (#88) だけを確かめる。{@code REDTACT_LIVE_POINTER_LOCK_ONLY=1} で、MCEF Modern が入っているとき
 * ({@code -Pmcef}) だけ {@link PointerLockChecks} を動かす (MCEF Modern の型に触れるクラスは、入っていないときに読み込まない)。
 * ほかの gametest は飛ばす (通しの {@link McefChecks} にも同じ確かめが入っている)。{@code REDTACT_LIVE_REAL_CIRCUIT}
 * (回路のページの URL) があれば、本物の redtact の 3D プレビューのフライも確かめる。
 * <pre>
 * REDTACT_LIVE_POINTER_LOCK_ONLY=1 REDTACT_LIVE_LIBRARY_ONLY=1 JAVA_HOME=~/opt/jdk-25 ./gradlew runClientGameTest -Pmcef
 * </pre>
 */
@SuppressWarnings("UnstableApiUsage")
public class PointerLockClientGameTest implements FabricClientGameTest {
	static boolean only() {
		return System.getenv("REDTACT_LIVE_POINTER_LOCK_ONLY") != null;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (only() && context.computeOnClient(mc -> BrowserSupport.available())) {
			PointerLockChecks.runStandalone(context);
		}
	}
}
