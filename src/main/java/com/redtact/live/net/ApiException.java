package com.redtact.live.net;

import com.redtact.live.i18n.Tr;

/** サーバが 4xx/5xx を返した、または通信できなかった。 */
public final class ApiException extends Exception {
	/** HTTP ステータス。通信できなかったときは 0。 */
	private final int status;
	private final String code;
	private final Integer currentVersion;

	public ApiException(int status, String code, String message) {
		this(status, code, message, null);
	}

	public ApiException(int status, String code, String message, Integer currentVersion) {
		super(message);
		this.status = status;
		this.code = code;
		this.currentVersion = currentVersion;
	}

	public ApiException(String message, Throwable cause) {
		super(message, cause);
		this.status = 0;
		this.code = "network";
		this.currentVersion = null;
	}

	/** 409 resync_required: 全体を送り直す。 */
	public boolean isResyncRequired() {
		return status == 409 || "resync_required".equals(code);
	}

	/** 413 too_large: 本文が大きすぎる。差分なら全体を送れば通る (差分の上限は全体より小さい)。 */
	public boolean isTooLarge() {
		return status == 413;
	}

	/** 409 のときのサーバの版 (無ければ null)。 */
	public Integer currentVersion() {
		return currentVersion;
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}

	public boolean isNotFound() {
		return status == 404;
	}

	/** チャットに出す説明。 */
	public String describe() {
		if (status == 0) {
			return Tr.f("サーバに接続できません ({0})", getMessage());
		}

		return getMessage() + " [" + status + " " + code + "]";
	}
}
