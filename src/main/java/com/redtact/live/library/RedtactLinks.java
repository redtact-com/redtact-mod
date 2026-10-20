package com.redtact.live.library;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * ゲーム内の redtact (#46) の URL の決まりごと。ゲーム内で開くのは redtact とそのファイルの置き場だけで、
 * ほかのサイトは外のブラウザに回す。ダウンロードの URL (R2 の期限付き URL) から回路とファイルを割り出す。
 */
public final class RedtactLinks {
	public static final String DEFAULT_HOME = "https://redtact.com";
	/** redtact の回路・ファイルの ID (英数字と - _)。 */
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	/** 回路のページ ({@code /circuits/<ID>}。言語の接頭辞 {@code /en} が付くこともある)。 */
	private static final Pattern CIRCUIT_PAGE = Pattern.compile("^(?:/[a-z]{2})?/circuits/([A-Za-z0-9_-]{1,64})(?:/.*)?$");
	private static final String R2_SUFFIX = ".r2.cloudflarestorage.com";

	/** 期限付き URL の中の回路のファイル ({@code /<バケット>/<回路 ID>/<保存先での名前>})。 */
	public record FileRef(String circuitId, String storedFileName) {
		/** 保管庫の行の目印 (同じファイルを取り直したら置き換える)。 */
		public String sourceKey() {
			return circuitId + "/" + storedFileName;
		}
	}

	private RedtactLinks() {
	}

	/** 設定の値をトップの URL にする。http(s) でホストがあるものだけ。読めなければ既定 (https://redtact.com)。 */
	public static URI home(@Nullable String configured) {
		URI u = parse(configured == null ? "" : configured.strip());

		if (u == null || u.getHost() == null || !isHttp(u)) {
			return URI.create(DEFAULT_HOME);
		}

		String path = u.getRawPath() == null ? "" : u.getRawPath();
		return URI.create(u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getRawAuthority() + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path));
	}

	/** redtact の設定のアカウント (マイクラアカウントの連携がある。#90 の開催中のフォロー中の案内)。 */
	public static String accountSettings(URI home) {
		return home + "/settings?tab=account";
	}

	/** redtact.com かその下 (dev.redtact.com など)。 */
	public static boolean isRedtactHost(@Nullable String host) {
		if (host == null) {
			return false;
		}

		String h = host.toLowerCase(Locale.ROOT);
		return h.equals("redtact.com") || h.endsWith(".redtact.com");
	}

	/**
	 * ゲーム内 (埋め込みのブラウザ) で開いてよいか (上のフレームの移動)。redtact・設定のトップと同じホスト・
	 * ファイルの置き場 (R2。ダウンロードになる) と、about: / data: / blob: だけ。
	 */
	public static boolean allowedInGame(@Nullable String url, URI home) {
		if (url == null || url.isBlank()) {
			return true;
		}

		String lower = url.strip().toLowerCase(Locale.ROOT);

		if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:")) {
			return true;
		}

		URI u = parse(url.strip());

		if (u == null || !isHttp(u) || u.getHost() == null) {
			return false;
		}

		String host = u.getHost().toLowerCase(Locale.ROOT);
		return isRedtactHost(host) || host.equals(home.getHost().toLowerCase(Locale.ROOT)) || host.endsWith(R2_SUFFIX);
	}

	/** ログインの画面 (Google・Apple・Firebase の認証)。埋め込みのブラウザでは通らない。 */
	public static boolean isSignIn(@Nullable String url) {
		URI u = parse(url == null ? "" : url.strip());

		if (u == null || u.getHost() == null) {
			return false;
		}

		String host = u.getHost().toLowerCase(Locale.ROOT);
		String path = u.getRawPath() == null ? "" : u.getRawPath();
		return host.equals("accounts.google.com") || host.equals("appleid.apple.com") || path.startsWith("/__/auth/");
	}

	/**
	 * ダウンロードの URL から回路とファイルを割り出す。R2 の期限付き URL ({@code https://<アカウント>.r2.cloudflarestorage.com/<バケット>/<回路 ID>/<名前>})
	 * か、設定のトップと同じホストで同じ形のパス (手元の確かめ用) だけ。合わなければ null。
	 */
	public static @Nullable FileRef fileRef(@Nullable String downloadUrl, URI home) {
		URI u = parse(downloadUrl == null ? "" : downloadUrl.strip());

		if (u == null || u.getHost() == null || !isHttp(u)) {
			return null;
		}

		String host = u.getHost().toLowerCase(Locale.ROOT);

		if (!host.endsWith(R2_SUFFIX) && !host.equals(home.getHost().toLowerCase(Locale.ROOT))) {
			return null;
		}

		String[] parts = (u.getPath() == null ? "" : u.getPath()).split("/");

		// "", バケット, 回路 ID, 名前
		if (parts.length != 4 || parts[1].isEmpty() || !ID.matcher(parts[2]).matches() || !Library.isPlainName(parts[3])) {
			return null;
		}

		return new FileRef(parts[2], parts[3]);
	}

	/** 回路のページの URL なら回路 ID。 */
	public static @Nullable String circuitIdFromPage(@Nullable String pageUrl) {
		URI u = parse(pageUrl == null ? "" : pageUrl.strip());

		if (u == null || u.getRawPath() == null) {
			return null;
		}

		Matcher m = CIRCUIT_PAGE.matcher(u.getRawPath());
		return m.matches() ? m.group(1) : null;
	}

	/**
	 * redtact の API の場所。redtact.com → https://api.redtact.com、{@code <env>.redtact.com} → {@code https://<env>-api.redtact.com}
	 * (redtact の決まり)。それ以外 (手元の確かめなど) は分からないので null (表示名はファイル名から作る)。
	 */
	public static @Nullable String apiBase(URI home) {
		String host = home.getHost() == null ? "" : home.getHost().toLowerCase(Locale.ROOT);

		if (host.equals("redtact.com") || host.equals("www.redtact.com")) {
			return "https://api.redtact.com";
		}

		if (host.endsWith(".redtact.com")) {
			String env = host.substring(0, host.length() - ".redtact.com".length());
			return env.contains(".") || env.endsWith("-api") ? null : "https://" + env + "-api.redtact.com";
		}

		return null;
	}

	/** 回路のページの URL。 */
	public static String circuitPage(URI home, String circuitId) {
		if (!ID.matcher(circuitId).matches()) {
			throw new IllegalArgumentException(Tr.f("回路 ID の形が違う: {0}", circuitId));
		}

		return home + "/circuits/" + circuitId;
	}

	/** 回路の投稿の画面 (#60。redtact の {@code /circuits/new}。ログインしていなければ redtact がログインの画面へ回す)。 */
	public static String newCircuitPage(URI home) {
		return home + "/circuits/new";
	}

	/** 記録に残すための形 (クエリ = 期限付き URL の署名を落とす)。 */
	public static String redact(@Nullable String url) {
		if (url == null) {
			return "";
		}

		int q = url.indexOf('?');
		int h = url.indexOf('#');
		int cut = q < 0 ? h : h < 0 ? q : Math.min(q, h);
		return cut < 0 ? url : url.substring(0, cut) + "?…";
	}

	/** ゲーム内の redtact の帯に出す形 (#69)。{@link #redact} に加えて https:// を省く (Chrome と同じ。http などほかは出す)。 */
	public static String display(@Nullable String url) {
		String r = redact(url);
		return r.regionMatches(true, 0, "https://", 0, 8) ? r.substring(8) : r;
	}

	private static boolean isHttp(URI u) {
		String s = u.getScheme();
		return s != null && (s.equalsIgnoreCase("https") || s.equalsIgnoreCase("http"));
	}

	private static @Nullable URI parse(String s) {
		if (s.isEmpty()) {
			return null;
		}

		try {
			return new URI(s);
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
