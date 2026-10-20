package com.redtact.live.auth;

import java.util.regex.Pattern;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/** ホストのマイクラアカウントでの確認まわりの判断と文言 (MC に依存しない)。 */
public final class HostAuth {
	/** 中継サーバが受けるマイクラの名前 (3〜16 文字の英数字と _)。 */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	/** Microsoft アカウントのアクセストークンは JWT (base64url を . で 3 つつないだもの)。 */
	private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

	/**
	 * Mojang への join から中継サーバの確認 (hasJoined) までを 1 つずつにする錠 (セッションの開始と、コメントのサインイン #74)。
	 * 同じアカウントの join が続くと、先の serverId の確認が通らなくなることがあるため。
	 */
	public static final Object JOIN_LOCK = new Object();

	private HostAuth() {
	}

	/** join に失敗した理由。 */
	public enum JoinFailure {
		/** InvalidCredentials 系: オフラインのアカウント・開発環境・期限切れのログイン */
		OFFLINE_ACCOUNT(Tr.ja("オフラインのアカウントか開発環境のため")),
		/** AuthenticationUnavailable 系: Mojang の認証サーバに届かない */
		MOJANG_UNAVAILABLE(Tr.ja("Mojang の認証サーバに接続できないため")),
		/** InsufficientPrivileges: マルチプレイが許されていない */
		INSUFFICIENT_PRIVILEGES(Tr.ja("このアカウントはマルチプレイが許可されていないため")),
		/** UserBanned / ForcedUsernameChange */
		BANNED(Tr.ja("アカウントが使えない状態のため (BAN・名前の変更待ち)")),
		/** 中継サーバからチャレンジを受け取れなかった */
		CHALLENGE_FAILED(Tr.ja("中継サーバから確認用の値を受け取れなかったため")),
		/** 名前がマイクラの名前の書式でない */
		INVALID_NAME(Tr.ja("プレイヤー名がマイクラの名前の書式でないため")),
		OTHER(Tr.ja("確認に失敗したため"));

		private final String reason;

		JoinFailure(String reason) {
			this.reason = reason;
		}

		public String reason() {
			return Tr.t(reason);
		}
	}

	/**
	 * 開く前の見込み: マイクラアカウントで確認できそうか (最終的には join の成否で決まる)。
	 * オフラインの開発者モードや、アクセストークンが JWT でない (開発環境の "FabricMC" など) ときは確認できない。
	 */
	public static boolean looksVerifiable(@Nullable String accessToken, boolean offlineDeveloperMode, @Nullable String playerName) {
		return !offlineDeveloperMode && accessToken != null && JWT.matcher(accessToken).matches() && isMinecraftName(playerName);
	}

	public static boolean isMinecraftName(@Nullable String name) {
		return name != null && NAME.matcher(name).matches();
	}

	/** 理由の文 (詳細があれば付ける)。 */
	public static String reasonText(@Nullable JoinFailure failure, @Nullable String detail) {
		if (failure == null) {
			return Tr.t("理由不明");
		}

		return detail == null || detail.isBlank() || failure != JoinFailure.OTHER ? failure.reason() : failure.reason() + ": " + detail;
	}

	/**
	 * セッションを開けなかったときの、確認まわりのエラーの文言。確認と関係ないエラーなら null。
	 *
	 * @param failure join に失敗していればその理由 (確認を付けずに開こうとした)
	 */
	public static @Nullable String createErrorMessage(@Nullable String code, @Nullable JoinFailure failure, @Nullable String detail) {
		if (code == null) {
			return null;
		}

		return switch (code) {
			case "minecraft_required" -> Tr.f("マイクラアカウントで確認できませんでした ({0})。中継サーバが確認なしのホストを受け付けていません",
					reasonText(failure, detail));
			case "minecraft_unverified" -> Tr.t("マイクラアカウントの確認に失敗しました (中継サーバが Mojang で確かめられませんでした)。もう一度開いてください");
			case "minecraft_unavailable" -> Tr.t("中継サーバが Mojang に問い合わせられませんでした。しばらくしてから開いてください");
			default -> null;
		};
	}
}
