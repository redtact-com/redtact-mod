package com.redtact.live.live;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.redtact.live.comment.ViewerSignIn;
import com.redtact.live.net.Api;
import com.redtact.live.net.ApiException;
import com.redtact.live.net.LiveApi;
import org.jspecify.annotations.Nullable;

/**
 * 開催中の一覧 (GET /api/sessions) の取得。通信は専用のスレッドで行い、結果を持っておく。
 *
 * <p>問い合わせ ({@link LiveQuery}: 探す字・並べ替え・フォロー中。#90) は呼ぶ側 (開催中タブ) が持ち、取り直すたびに渡す。
 * 問い合わせが変わったら、前の問い合わせの答えはあとから届いても捨てる (まだ順番を待っていれば取りにもいかない)。
 * フォロー中は、コメントと同じ閲覧者のサインイン ({@link ViewerSignIn}。ゲームのアカウントで join) のトークンで問い合わせる。
 * 確かめられないアカウント (開発環境・オフライン) では問い合わせずに理由を返す。
 */
public final class LiveDirectory {
	/** フォロー中で絞ったときの様子 (#90)。 */
	public enum FollowStatus {
		/** 絞れた (フォローしている人のうち、マイクラアカウントを公開にしている人のセッション)。 */
		OK,
		/** このマイクラアカウントが redtact に連携されていない。 */
		NOT_LINKED,
		/** 中継サーバが redtact に問い合わせられなかった (一時的)。 */
		UNAVAILABLE,
		/** 中継サーバがフォロー中に対応していない (古い中継サーバは following を付けずに全部を返す)。 */
		UNSUPPORTED,
		/** ゲームのアカウントで確かめられない (開発環境・オフラインのアカウント・join の失敗)。 */
		UNVERIFIED
	}

	/**
	 * @param accounts フォローしている人のうち、マイクラアカウント (Java 版) の連携を公開にしている人の数 (OK のときだけ)
	 * @param reason   確かめられなかった理由 (UNVERIFIED のときだけ。人向けの文)
	 */
	public record Following(FollowStatus status, int accounts, @Nullable String reason) {
	}

	/** 取れた一覧。following は問い合わせでフォロー中にしたときだけ。 */
	public record Result(List<Api.Session> sessions, @Nullable Following following) {
	}

	/** 一覧の取り方 (本物は中継サーバ。テストで差し替える)。通信用のスレッドで呼ぶ。 */
	@FunctionalInterface
	public interface Fetcher {
		Result fetch(LiveQuery query) throws ApiException;
	}

	/**
	 * @param fetchedAt  最後に取れた時刻 (まだなら null)
	 * @param error      最後の取得が失敗したときの説明 (成功なら null)
	 * @param generation 結果が変わるたびに増える
	 * @param query      sessions を取った問い合わせ (失敗したときは前に取れた問い合わせのまま)
	 * @param following  sessions をフォロー中で絞ったときの様子 (絞っていなければ null)
	 */
	public record State(List<Api.Session> sessions, @Nullable Instant fetchedAt, @Nullable String error, boolean loading, long generation,
			LiveQuery query, @Nullable Following following) {
		/**
		 * 中継サーバが探す字・並べ替えに対応していないか (#90 より前の中継サーバ。知らない問い合わせを無視して新しい順の全部を返す)。
		 * 新しい中継サーバのセッションにはいつも views があるので、それで見分ける (一覧が空なら分からないので false)。
		 */
		public boolean searchUnsupported() {
			return (query.searching() || query.sort() != LiveQuery.Sort.NEW) && !sessions.isEmpty() && sessions.stream().allMatch(s -> s.views == null);
		}
	}

	private final Fetcher fetcher;
	private final Executor executor;
	/** 自分で作ったスレッド (shutdown で止める。テストで渡したものは止めない)。 */
	private final @Nullable ExecutorService owned;
	private volatile State state = new State(List.of(), null, null, false, 0, LiveQuery.DEFAULT, null);
	/** 最後に頼まれた問い合わせと、その番号 (問い合わせが変わるたびに増える)。番号が古い答えは捨てる。 */
	private LiveQuery wanted = LiveQuery.DEFAULT;
	private long wantedSeq;
	/** 取りにいっている (順番待ちを含む) 問い合わせの番号。無ければ -1。 */
	private long pendingSeq = -1;

	public LiveDirectory(LiveApi api, ViewerSignIn signIn) {
		this(q -> fetch(api, signIn, q), Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("redtact-live-list").factory()), true);
	}

	/** テスト用: 取り方と、取るスレッド (順番を決めて走らせる) を渡す。 */
	LiveDirectory(Fetcher fetcher, Executor executor) {
		this(fetcher, executor, false);
	}

	private LiveDirectory(Fetcher fetcher, Executor executor, boolean own) {
		this.fetcher = fetcher;
		this.executor = executor;
		this.owned = own && executor instanceof ExecutorService es ? es : null;
	}

	public State state() {
		return state;
	}

	/** 最後に頼まれた問い合わせ。 */
	public synchronized LiveQuery wanted() {
		return wanted;
	}

	/**
	 * query で取り直す。同じ問い合わせを取得中なら何もしない。問い合わせが変わったら、取得中の前の問い合わせの答えは捨てる
	 * (取り終わるまで loading のまま、前に取れた一覧を持っておく)。
	 */
	public synchronized void refresh(LiveQuery query) {
		if (!query.equals(wanted)) {
			wanted = query;
			wantedSeq++;
		} else if (pendingSeq == wantedSeq) {
			return; // 同じ問い合わせを取得中
		}

		long seq = wantedSeq;
		pendingSeq = seq;
		State s = state;
		state = new State(s.sessions(), s.fetchedAt(), s.error(), true, s.generation(), s.query(), s.following());
		executor.execute(() -> run(query, seq));
	}

	private void run(LiveQuery query, long seq) {
		if (!current(seq)) {
			return; // 順番を待つ間に新しい問い合わせが来た (そちらを取りにいく)
		}

		Result result = null;
		String error = null;

		try {
			Result r = fetcher.fetch(query);
			result = new Result(r.sessions().stream().filter(Objects::nonNull).toList(), r.following());
		} catch (ApiException e) {
			error = e.describe();
		} catch (RuntimeException e) {
			error = e.toString();
		}

		finish(query, seq, result, error);
	}

	private synchronized boolean current(long seq) {
		return seq == wantedSeq;
	}

	private synchronized void finish(LiveQuery query, long seq, @Nullable Result r, @Nullable String error) {
		if (seq != wantedSeq) {
			return; // 前の問い合わせの答え: 捨てる (新しい問い合わせの取得がこのあと走る)
		}

		pendingSeq = -1;
		State prev = state;
		state = r != null ? new State(r.sessions(), Instant.now(), null, false, prev.generation() + 1, query, r.following())
				: new State(prev.sessions(), prev.fetchedAt(), error, false, prev.generation() + 1, prev.query(), prev.following());
	}

	/**
	 * 中継サーバから取る (通信用のスレッドで)。フォロー中は閲覧者のトークンを付け、期限切れ (401 signin_expired) なら
	 * 1 回だけ取り直して問い合わせ直す (コメントと同じ)。確かめられないアカウントは問い合わせない。
	 */
	static Result fetch(LiveApi api, ViewerSignIn signIn, LiveQuery query) throws ApiException {
		if (!query.following()) {
			return result(query, api.listSessions(query, null));
		}

		ViewerSignIn.Identity id;

		try {
			id = signIn.identity();
		} catch (ApiException e) {
			if ("http_404".equals(e.code()) || "http_405".equals(e.code())) {
				// トークンの入口が無い古い中継サーバ (#74 より前。フォロー中にも対応していない)
				return new Result(List.of(), new Following(FollowStatus.UNSUPPORTED, 0, null));
			}

			throw e;
		}

		if (id.token() == null) {
			return unverified(id);
		}

		Api.SessionList list;

		try {
			list = api.listSessions(query, id.token());
		} catch (ApiException e) {
			if (!"signin_expired".equals(e.code())) {
				throw e;
			}

			// トークンの期限切れ・中継サーバの鍵が変わった: 取り直して 1 回だけ問い合わせ直す
			signIn.invalidate(id.token());
			id = signIn.identity();

			if (id.token() == null) {
				return unverified(id);
			}

			list = api.listSessions(query, id.token());
		}

		return result(query, list);
	}

	private static Result unverified(ViewerSignIn.Identity id) {
		return new Result(List.of(), new Following(FollowStatus.UNVERIFIED, 0, id.unverifiedReason()));
	}

	/**
	 * 応答を読む (#90)。フォロー中で問い合わせたのに following が無ければ、古い中継サーバで絞れていない (全部が返っている) ので
	 * 一覧は出さない。ok でなければ一覧は空。
	 */
	static Result result(LiveQuery query, Api.SessionList list) {
		List<Api.Session> sessions = list.sessions == null ? List.of() : list.sessions;

		if (!query.following()) {
			return new Result(sessions, null);
		}

		Following f = following(list.following);
		return new Result(f.status() == FollowStatus.OK ? sessions : List.of(), f);
	}

	/** following を読む (無ければ古い中継サーバ。知らない status は一時的に問い合わせられなかった扱い)。 */
	static Following following(Api.@Nullable Following f) {
		if (f == null) {
			return new Following(FollowStatus.UNSUPPORTED, 0, null);
		}

		return switch (f.status == null ? "" : f.status) {
			case "ok" -> new Following(FollowStatus.OK, Math.max(0, f.accounts), null);
			case "not_linked" -> new Following(FollowStatus.NOT_LINKED, 0, null);
			default -> new Following(FollowStatus.UNAVAILABLE, 0, null);
		};
	}

	public void shutdown() {
		if (owned != null) {
			owned.shutdownNow();
		}
	}
}
