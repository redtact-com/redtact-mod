package com.redtact.live.comment;

import com.redtact.live.auth.HostAuth;
import com.redtact.live.auth.MinecraftJoin;

/** ゲームのアカウントで Mojang に join する ({@link MinecraftJoin} をそのまま使う。ホストの確認と同じ)。 */
public final class GameJoiner implements ViewerSignIn.Joiner {
	@Override
	public boolean looksVerifiable() {
		return MinecraftJoin.looksVerifiable();
	}

	@Override
	public ViewerSignIn.JoinResult join(String serverId) {
		MinecraftJoin.Result r = MinecraftJoin.join(serverId);
		return r.ok() ? new ViewerSignIn.JoinResult(r.name(), null) : new ViewerSignIn.JoinResult(null, HostAuth.reasonText(r.failure(), r.detail()));
	}
}
