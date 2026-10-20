package com.redtact.live.config;

import com.redtact.live.i18n.Tr;

/**
 * 設定の読み口 (中身は {@link Configs})。通信や同期の部品はこれだけを見る。
 */
public final class LiveConfig {
	/** 末尾の / を除いた serverUrl。 */
	public String baseUrl() {
		String s = Configs.Generic.SERVER_URL.getStringValue().trim();

		while (s.endsWith("/")) {
			s = s.substring(0, s.length() - 1);
		}

		return s.isEmpty() ? Configs.DEFAULT_SERVER_URL : s;
	}

	/** X-Live-User に載せる値 (前後の空白を除く)。 */
	public String userName() {
		return Configs.Generic.USER.getStringValue().trim();
	}

	/**
	 * X-Live-User として送れない理由。送れるなら null。
	 *
	 * <p>サーバは (percent-encode を戻したあと) 1〜32 文字・制御文字なしを求める。
	 */
	public String userError() {
		return userError(userName());
	}

	public static String userError(String u) {
		int n = u.codePointCount(0, u.length());

		if (n < 1 || n > 32) {
			return Tr.f("表示名は 1〜32 文字にしてください (メニューの「全般」。今: \"{0}\")", u);
		}

		if (u.codePoints().anyMatch(Character::isISOControl)) {
			return Tr.t("表示名に制御文字は使えません");
		}

		return null;
	}

	public boolean autoSync() {
		return Configs.Generic.AUTO_SYNC.getBooleanValue();
	}

	public void setAutoSync(boolean enabled) {
		Configs.Generic.AUTO_SYNC.setBooleanValue(enabled);
		Configs.saveToFile();
	}

	public long autoSyncQuietMs() {
		return Configs.Generic.AUTO_SYNC_QUIET_MS.getIntegerValue();
	}

	/** 静止時間より短くはしない。 */
	public long autoSyncMaxDelayMs() {
		return Math.max(autoSyncQuietMs(), Configs.Generic.AUTO_SYNC_MAX_DELAY_MS.getIntegerValue());
	}

	/** 新しいセッションで複製を許すか (既定 true)。 */
	public boolean defaultAllowCopy() {
		return Configs.Generic.DEFAULT_ALLOW_COPY.getBooleanValue();
	}

	public long localSnapshotIntervalMs() {
		return Configs.Generic.LOCAL_SNAPSHOT_INTERVAL_MS.getIntegerValue();
	}

	public static String defaultUser() {
		String name = System.getProperty("user.name", "");
		StringBuilder sb = new StringBuilder();

		name.codePoints()
				.filter(c -> !Character.isISOControl(c) && !Character.isWhitespace(c))
				.limit(32)
				.forEach(sb::appendCodePoint);
		return sb.isEmpty() ? "host" : sb.toString();
	}
}
