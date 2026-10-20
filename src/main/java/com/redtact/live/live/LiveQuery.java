package com.redtact.live.live;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * 開催中の一覧の問い合わせ (#90): 探す字・並べ替え・フォロー中だけ。{@code GET /api/sessions} の {@code q}・{@code sort}・
 * {@code following} になる (既定のものは付けない)。探す字は中継サーバがホストのマイクラの名前・公開した redtact の名前・題の
 * 部分一致 (大文字小文字は区別しない) で探す。MC に依存しない。
 *
 * @param q         探す字 (前後の空白を除き、{@link #MAX_Q} 文字まで。空なら探さない)
 * @param sort      並べ替え
 * @param following フォローしている人のセッションだけ (閲覧者のトークンで問い合わせる)
 */
public record LiveQuery(String q, Sort sort, boolean following) {
	/** 探す字の長さの上限 (中継サーバと同じ)。 */
	public static final int MAX_Q = 64;
	public static final LiveQuery DEFAULT = new LiveQuery("", Sort.NEW, false);

	/** 並べ替え。押すたびに新しい順 → 視聴中 → 閲覧数 → 新しい順 と回る。 */
	public enum Sort {
		/** 開始が新しい順 (既定)。 */
		NEW("new", Tr.ja("新しい順"), Tr.ja("新しい順: 開始が新しいセッションから")),
		/** いま見ている人が多い順。 */
		VIEWERS("viewers", Tr.ja("視聴中"), Tr.ja("視聴中: いま見ている人 (Web の画面と、MOD で詳細を開いている人) が多い順")),
		/** 開始からの延べ人数が多い順。 */
		VIEWS("views", Tr.ja("閲覧数"), Tr.ja("閲覧数: 開始からの延べ人数 (同じ回線は 1 回・ホストは数えない) が多い順"));

		private final String param;
		private final String label;
		private final String description;

		Sort(String param, String label, String description) {
			this.param = param;
			this.label = label;
			this.description = description;
		}

		/** {@code sort} の値。 */
		public String param() {
			return param;
		}

		/** ボタンに出す名前 (日本語の原文。{@link Tr#t} を通して出す)。 */
		public String label() {
			return label;
		}

		/** ツールチップの 1 行 (日本語の原文。{@link Tr#t} を通して出す)。 */
		public String description() {
			return description;
		}

		public Sort next() {
			Sort[] all = values();
			return all[(ordinal() + 1) % all.length];
		}

		public Sort previous() {
			Sort[] all = values();
			return all[(ordinal() + all.length - 1) % all.length];
		}
	}

	public LiveQuery {
		q = normalize(q);
		sort = sort == null ? Sort.NEW : sort;
	}

	/** 探す字を前後の空白を除いて {@link #MAX_Q} 文字 (サロゲートペアも 1 文字) までにする。 */
	public static String normalize(@Nullable String q) {
		String t = q == null ? "" : q.strip();

		if (t.codePointCount(0, t.length()) > MAX_Q) {
			t = t.substring(0, t.offsetByCodePoints(0, MAX_Q)).strip();
		}

		return t;
	}

	public LiveQuery withQ(@Nullable String q) {
		return new LiveQuery(q, sort, following);
	}

	public LiveQuery withSort(Sort sort) {
		return new LiveQuery(q, sort, following);
	}

	public LiveQuery withFollowing(boolean following) {
		return new LiveQuery(q, sort, following);
	}

	/** 探す字があるか。 */
	public boolean searching() {
		return !q.isEmpty();
	}

	/** 探す字かフォロー中で絞っているか (空の一覧の説明を変える)。 */
	public boolean narrowed() {
		return searching() || following;
	}

	/** {@code GET /api/sessions} の後ろに付ける問い合わせ (例 {@code ?q=hana&sort=views&following=1})。既定なら空。 */
	public String queryString() {
		List<String> parts = new ArrayList<>(3);

		if (!q.isEmpty()) {
			parts.add("q=" + URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20"));
		}

		if (sort != Sort.NEW) {
			parts.add("sort=" + sort.param);
		}

		if (following) {
			parts.add("following=1");
		}

		return parts.isEmpty() ? "" : "?" + String.join("&", parts);
	}
}
