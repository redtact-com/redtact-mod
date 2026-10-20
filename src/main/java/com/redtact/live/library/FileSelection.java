package com.redtact.live.library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * redtact タブの横の保管庫で選んでいるファイル (#60)。ページへのドロップと、ページの「ファイルを追加」(ファイル選択) に使う。
 * 選んだ順を保つ。数は redtact の上限 ({@link RedtactUpload#MAX_FILES}) まで。Chromium のスレッドからも読むので同期する。
 */
public final class FileSelection {
	private final Set<Path> paths = new LinkedHashSet<>();
	/** 変えるたびに増やす (画面が描き直す合図)。 */
	private volatile int revision;

	private static Path key(Path p) {
		return p.toAbsolutePath().normalize();
	}

	/** 選ぶ・外す。選べなかった (上限) なら false。 */
	public synchronized boolean toggle(Path p) {
		Path k = key(p);

		if (paths.remove(k)) {
			revision++;
			return true;
		}

		if (paths.size() >= RedtactUpload.MAX_FILES) {
			return false;
		}

		paths.add(k);
		revision++;
		return true;
	}

	/** 選んでいるものを置き換える (上限より多ければ先頭から)。 */
	public synchronized void set(List<Path> list) {
		paths.clear();

		for (Path p : list) {
			if (paths.size() < RedtactUpload.MAX_FILES) {
				paths.add(key(p));
			}
		}

		revision++;
	}

	public synchronized void clear() {
		if (!paths.isEmpty()) {
			paths.clear();
			revision++;
		}
	}

	public synchronized boolean contains(Path p) {
		return paths.contains(key(p));
	}

	public synchronized int size() {
		return paths.size();
	}

	public synchronized boolean isEmpty() {
		return paths.isEmpty();
	}

	/** 選んだ順。消されたファイルは除く。 */
	public synchronized List<Path> existing() {
		List<Path> out = new ArrayList<>();

		for (Path p : paths) {
			if (Files.isRegularFile(p)) {
				out.add(p);
			}
		}

		return out;
	}

	public int revision() {
		return revision;
	}
}
