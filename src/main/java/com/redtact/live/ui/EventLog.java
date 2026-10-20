package com.redtact.live.ui;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 最近の出来事 (メニューの「セッション」タブに出す)。チャットの代わり。 */
public final class EventLog {
	public enum Level {
		INFO,
		ERROR
	}

	public record Entry(Instant time, Level level, String text) {
	}

	private static final int CAPACITY = 100;
	private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

	private EventLog() {
	}

	public static synchronized void add(Level level, String text) {
		ENTRIES.addLast(new Entry(Instant.now(), level, text));

		while (ENTRIES.size() > CAPACITY) {
			ENTRIES.removeFirst();
		}
	}

	/** 新しい順に最大 n 件。 */
	public static synchronized List<Entry> latest(int n) {
		List<Entry> out = new ArrayList<>(n);
		var it = ENTRIES.descendingIterator();

		while (it.hasNext() && out.size() < n) {
			out.add(it.next());
		}

		return out;
	}
}
