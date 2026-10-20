package com.redtact.live.comment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import org.jspecify.annotations.Nullable;

/**
 * 開催中タブの詳細で開いているセッションのコメントを取る (#74)。{@code GET /api/sessions/{id}/comments?after=} を
 * {@link #INTERVAL_MS} ごとに呼び、最初は全部、以後は新着だけを足していく。応答のセッション (ホストの接続・閲覧者・版) も覚えるので、
 * 一覧に出ていない (リンクで開いた) セッションの詳細もこれで描ける。WebSocket は開かないので閲覧者には数えない。
 *
 * <p>見るのは 1 つだけ ({@link #watch} で切り替え、{@link #stop} でやめる)。通信は専用のスレッドで行い、
 * 画面は {@link #state()} を読み、{@link #tick} で間隔ごとに取りにいかせる。
 */
public final class CommentWatch {
	/** 取り直す間隔。 */
	public static final long INTERVAL_MS = 5_000;
	/** 持っておくコメントの上限 (中継サーバの 1 セッションの上限と同じ)。 */
	static final int MAX_COMMENTS = 2000;
	/** 時刻順 (ID はミリ秒の時刻から始まる)。 */
	private static final Comparator<Api.Comment> BY_ID = Comparator.comparing(c -> c.id);

	/**
	 * @param sessionId  見ているセッション (見ていなければ null)
	 * @param session    最後に取れたセッション (comments は空。まだなら null)
	 * @param comments   コメント (古い順)
	 * @param cursor     中継サーバから最後に受け取ったコメントの ID (次の after。書いたコメントの ID は使わない。
	 *                   書く前に別の人が書いたコメントを飛ばさないため)
	 * @param loading    取りにいっている最中か
	 * @param error       最後の取得が失敗したときの説明 (成功・終了なら null)
	 * @param ended       セッションが終わった (404)
	 * @param unsupported 中継サーバにこの入口が無い (古い中継サーバ。405)。もう取りにいかない
	 * @param generation  中身が変わるたびに増える
	 */
	public record State(@Nullable String sessionId, Api.@Nullable Session session, List<Api.Comment> comments, @Nullable String cursor, boolean loading,
			@Nullable String error, boolean ended, boolean unsupported, long generation) {
		static State idle(long generation) {
			return new State(null, null, List.of(), null, false, null, false, false, generation);
		}

		/** 一度でも取れたか (まだなら詳細は「読み込み中」)。 */
		public boolean loaded() {
			return session != null;
		}

		State with(Api.@Nullable Session session, List<Api.Comment> comments, @Nullable String cursor, boolean loading, @Nullable String error,
				boolean ended, boolean unsupported) {
			return new State(sessionId, session, comments, cursor, loading, error, ended, unsupported, generation + 1);
		}
	}

	private final LiveApi api;
	private final LongSupplier clock;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-comments").factory());
	private volatile State state = State.idle(0);
	/** 最後に取りにいった時刻 (clock)。 */
	private long lastRequest;
	/** watch・stop のたびに増やし、古い取得の結果を捨てる。 */
	private long epoch;

	public CommentWatch(LiveApi api, LongSupplier clock) {
		this.api = api;
		this.clock = clock;
	}

	public State state() {
		return state;
	}

	/** そのセッションを見る (同じセッションならそのまま。違えば・終わっていれば取り直す)。 */
	public synchronized void watch(String sessionId) {
		if (sessionId.equals(state.sessionId()) && !state.ended()) {
			return;
		}

		epoch++;
		state = new State(sessionId, null, List.of(), null, false, null, false, false, state.generation() + 1);
		refresh();
	}

	/** 見るのをやめる (詳細を閉じた・画面を閉じた)。 */
	public synchronized void stop() {
		if (state.sessionId() == null) {
			return;
		}

		epoch++;
		state = State.idle(state.generation() + 1);
	}

	/** 間隔がたっていれば取り直す (画面の tick から呼ぶ)。 */
	public synchronized void tick() {
		if (state.sessionId() != null && clock.getAsLong() - lastRequest >= INTERVAL_MS) {
			refresh();
		}
	}

	/** すぐ取り直す (書いた直後など。取りにいっている最中・終わったセッション・入口の無い中継サーバなら何もしない)。 */
	public synchronized void refresh() {
		State s = state;

		if (s.sessionId() == null || s.loading() || s.ended() || s.unsupported()) {
			return;
		}

		lastRequest = clock.getAsLong();
		state = new State(s.sessionId(), s.session(), s.comments(), s.cursor(), true, s.error(), false, false, s.generation());
		String id = s.sessionId();
		String after = s.cursor();
		long myEpoch = epoch;
		executor.execute(() -> {
			try {
				apply(myEpoch, api.comments(id, after), null, false, false);
			} catch (ApiException e) {
				boolean ended = "not_found".equals(e.code());
				boolean unsupported = "http_404".equals(e.code()) || "http_405".equals(e.code());
				apply(myEpoch, null, ended ? null : CommentSender.errorText(e, null), ended, unsupported);
			} catch (RuntimeException e) {
				apply(myEpoch, null, e.toString(), false, false);
			}
		});
	}

	/** 書いたコメント (POST の応答) をすぐ出す。次の取得で同じ ID が来ても重ねない。 */
	public synchronized void addPosted(String sessionId, Api.@Nullable Comment c) {
		State s = state;

		if (c == null || c.id == null || !sessionId.equals(s.sessionId())) {
			return;
		}

		List<Api.Comment> merged = merge(s.comments(), List.of(c));

		if (merged != s.comments()) {
			state = s.with(s.session(), merged, s.cursor(), s.loading(), s.error(), s.ended(), s.unsupported());
		}
	}

	private synchronized void apply(long myEpoch, Api.@Nullable CommentsResponse res, @Nullable String error, boolean ended, boolean unsupported) {
		if (myEpoch != epoch) {
			return; // 別のセッションに切り替えた・やめた
		}

		State s = state;

		if (res == null) {
			state = s.with(s.session(), s.comments(), s.cursor(), false, error, ended, unsupported);
			return;
		}

		String cursor = res.comments == null || res.comments.isEmpty() ? s.cursor() : res.comments.getLast().id;
		state = s.with(res.session != null ? res.session : s.session(), merge(s.comments(), res.comments), cursor, false, null, false, false);
	}

	/**
	 * 足して時刻順 (ID の順) に並べる (同じ ID は重ねない)。増えなければ known をそのまま返す。多すぎれば古い方を捨てる。
	 */
	static List<Api.Comment> merge(List<Api.Comment> known, @Nullable List<Api.Comment> incoming) {
		if (incoming == null || incoming.isEmpty()) {
			return known;
		}

		Set<String> seen = new HashSet<>();

		for (Api.Comment c : known) {
			seen.add(c.id);
		}

		List<Api.Comment> out = null;

		for (Api.Comment c : incoming) {
			if (c == null || c.id == null || !seen.add(c.id)) {
				continue;
			}

			if (out == null) {
				out = new ArrayList<>(known);
			}

			out.add(c);
		}

		if (out == null) {
			return known;
		}

		out.sort(BY_ID);

		if (out.size() > MAX_COMMENTS) {
			out = new ArrayList<>(out.subList(out.size() - MAX_COMMENTS, out.size()));
		}

		return List.copyOf(out);
	}

	public void shutdown() {
		executor.shutdownNow();
	}
}
