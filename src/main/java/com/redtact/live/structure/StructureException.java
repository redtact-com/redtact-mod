package com.redtact.live.structure;

/** 回路のファイルを読めない (#70)。メッセージはそのまま画面に出す (日本語)。 */
public final class StructureException extends Exception {
	public StructureException(String message) {
		super(message);
	}

	public StructureException(String message, Throwable cause) {
		super(message, cause);
	}
}
