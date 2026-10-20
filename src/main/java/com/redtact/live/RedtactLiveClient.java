package com.redtact.live;

import net.fabricmc.api.ClientModInitializer;

public final class RedtactLiveClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RedtactLive.init();
	}
}
