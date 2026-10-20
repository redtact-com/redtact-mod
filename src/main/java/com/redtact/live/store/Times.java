package com.redtact.live.store;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.ZoneOffset;

public final class Times {
	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("MM/dd HH:mm");
	private static final DateTimeFormatter RFC3339_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

	private Times() {
	}

	/** RFC 3339 (UTC, ミリ秒まで)。 */
	public static String now() {
		return format(Instant.now());
	}

	public static String format(Instant t) {
		return RFC3339_MILLIS.format(t);
	}

	/** 表示用 (ローカル時刻)。 */
	public static String local(Instant t) {
		return LOCAL.format(t.atZone(ZoneId.systemDefault()));
	}

	/** 表示用 (ローカル時刻)。読めなければそのまま返す。 */
	public static String local(String rfc3339) {
		if (rfc3339 == null) {
			return "?";
		}

		try {
			return LOCAL.format(Instant.parse(rfc3339).atZone(ZoneId.systemDefault()));
		} catch (DateTimeParseException e) {
			return rfc3339;
		}
	}
}
