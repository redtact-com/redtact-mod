package com.redtact.live.auth;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.ForcedUsernameChangeException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * ゲームのアカウントで Mojang の session server に join する。バニラがサーバに入るとき
 * (ClientHandshakePacketListenerImpl.authenticateServer) と同じ呼び出しで、通信するので worker スレッドから呼ぶ。
 * Microsoft のアクセストークンは Mojang にだけ送り、中継サーバには渡さない。
 */
public final class MinecraftJoin {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");

	private MinecraftJoin() {
	}

	/** @param name 成功したときのプレイヤー名。failure は失敗したときだけ */
	public record Result(@Nullable String name, HostAuth.@Nullable JoinFailure failure, @Nullable String detail) {
		public boolean ok() {
			return failure == null;
		}
	}

	public static Result join(String serverId) {
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();

		if (mc.isOfflineDeveloperMode()) {
			return new Result(null, HostAuth.JoinFailure.OFFLINE_ACCOUNT, "offlineDeveloperMode");
		}

		if (!HostAuth.isMinecraftName(user.getName())) {
			return new Result(null, HostAuth.JoinFailure.INVALID_NAME, user.getName());
		}

		try {
			mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
			return new Result(user.getName(), null, null);
		} catch (AuthenticationUnavailableException e) {
			return fail(HostAuth.JoinFailure.MOJANG_UNAVAILABLE, e);
		} catch (InvalidCredentialsException e) {
			return fail(HostAuth.JoinFailure.OFFLINE_ACCOUNT, e);
		} catch (InsufficientPrivilegesException e) {
			return fail(HostAuth.JoinFailure.INSUFFICIENT_PRIVILEGES, e);
		} catch (UserBannedException | ForcedUsernameChangeException e) {
			return fail(HostAuth.JoinFailure.BANNED, e);
		} catch (AuthenticationException | RuntimeException e) {
			return fail(HostAuth.JoinFailure.OTHER, e);
		}
	}

	private static Result fail(HostAuth.JoinFailure failure, Exception e) {
		LOGGER.info("[redtact-live] マイクラアカウントで join できません ({}): {}", failure, e.toString());
		return new Result(null, failure, e.getMessage());
	}

	/** 開く前の見込み (セッションタブの表示用)。 */
	public static boolean looksVerifiable() {
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();
		return HostAuth.looksVerifiable(user.getAccessToken(), mc.isOfflineDeveloperMode(), user.getName());
	}

	public static String playerName() {
		return Minecraft.getInstance().getUser().getName();
	}
}
