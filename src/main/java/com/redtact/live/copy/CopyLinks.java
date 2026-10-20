package com.redtact.live.copy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.redtact.live.i18n.Tr;

/** 回路の読み込み (Litematica 連携) まわりの文字列の解釈 (MC に依存しない)。 */
public final class CopyLinks {
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{10}");
	/** 共有リンク {@code <web>/s/<id>} (後ろの / ・? ・# は許す)。 */
	private static final Pattern SHARE = Pattern.compile("^https?://[^/]+(?:/[^?#]*)?/s/([A-Za-z0-9]{10})/?(?:[?#].*)?$");
	private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern FILENAME = Pattern.compile("filename\\s*=\\s*\"([^\"]*)\"|filename\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

	private CopyLinks() {
	}

	/** 共有リンクかセッション ID からセッション ID を取り出す。どちらでもなければ empty。 */
	public static Optional<String> sessionId(String input) {
		if (input == null) {
			return Optional.empty();
		}

		String s = input.trim();

		if (ID.matcher(s).matches()) {
			return Optional.of(s);
		}

		Matcher m = SHARE.matcher(s);
		return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
	}

	/**
	 * Content-Disposition からファイル名を取り出す。{@code filename*} (RFC 5987、UTF-8 の percent-encode) を優先し、
	 * 無ければ {@code filename}。どちらも無ければ empty。
	 */
	public static Optional<String> fileName(String contentDisposition) {
		if (contentDisposition == null) {
			return Optional.empty();
		}

		Matcher star = FILENAME_STAR.matcher(contentDisposition);

		if (star.find()) {
			String charset = star.group(1).trim().isEmpty() ? "UTF-8" : star.group(1).trim();

			try {
				// percent-encode を戻す (+ は空白にしない)
				return Optional.of(URLDecoder.decode(star.group(2).trim().replace("+", "%2B"),
						charset.toUpperCase(Locale.ROOT).equals("UTF-8") ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1));
			} catch (IllegalArgumentException e) {
				// 壊れた filename* は捨てて filename を使う
			}
		}

		Matcher plain = FILENAME.matcher(contentDisposition);

		while (plain.find()) {
			if (plain.start() > 0 && contentDisposition.charAt(plain.start() - 1) == '*') {
				continue;
			}

			String v = plain.group(1) != null ? plain.group(1) : plain.group(2);

			if (v != null && !v.isBlank()) {
				return Optional.of(v.trim());
			}
		}

		return Optional.empty();
	}

	/**
	 * サーバのファイル名 ({@code <タイトル>-v<版>.litematic}) からタイトルを取り出す。
	 * 形が違えば拡張子だけ落とす。null・空なら null。
	 */
	public static String titleFromFileName(String fileName, int version) {
		if (fileName == null || fileName.isBlank()) {
			return null;
		}

		String s = fileName.trim();

		if (s.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
			s = s.substring(0, s.length() - ".litematic".length());
		}

		String suffix = "-v" + version;

		if (version > 0 && s.endsWith(suffix)) {
			s = s.substring(0, s.length() - suffix.length());
		}

		return s.isBlank() ? null : s;
	}

	/** 読み込めなかったときの説明 (トーストの本文)。 */
	public static String failureText(com.redtact.live.net.ApiException e) {
		if (e.status() == 403 || "copy_not_allowed".equals(e.code())) {
			return Tr.t("ホストが複製を許可していません");
		}

		if (e.status() == 404 || "not_found".equals(e.code())) {
			return Tr.t("セッションが無いか、まだ回路が同期されていません");
		}

		if (e.status() == 0) {
			return Tr.f("サーバに接続できません ({0})", e.getMessage());
		}

		return e.describe();
	}

	/** ファイル名として安全にする (区切り・予約文字・制御文字を _ に。長さを抑え、拡張子を付ける)。 */
	public static String safeFileName(String name, String fallback) {
		String s = name == null ? "" : name.trim();
		StringBuilder sb = new StringBuilder();

		s.codePoints().forEach(c -> sb.appendCodePoint(c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c));
		String out = sb.toString().strip();

		while (out.startsWith(".")) {
			out = out.substring(1);
		}

		if (out.isBlank()) {
			out = fallback;
		}

		if (!out.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
			out = out + ".litematic";
		}

		if (out.length() > 120) {
			out = out.substring(0, 120 - ".litematic".length()) + ".litematic";
		}

		return out;
	}
}
