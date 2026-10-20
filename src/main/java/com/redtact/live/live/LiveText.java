package com.redtact.live.live;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.redtact.live.i18n.Tr;
import com.redtact.live.net.Api;
import com.redtact.live.net.Json;
import org.jspecify.annotations.Nullable;

/** 開催中の一覧の表示まわり (MC に依存しない)。 */
public final class LiveText {
	private LiveText() {
	}

	/** GET /api/sessions の本文を読む。 */
	public static java.util.List<Api.Session> parseList(String json) {
		Api.SessionList list = Json.GSON.fromJson(json, Api.SessionList.class);
		return list == null || list.sessions == null ? java.util.List.of() : list.sessions;
	}

	/**
	 * 一覧が空のときの説明 (#90)。フォロー中で絞ったときはその様子 (連携していない・問い合わせられない・確かめられないなど)、
	 * 探す字で何も無ければ「条件に合うセッションはありません」。どちらでもなければ null (ふだんの説明を出す)。
	 *
	 * @param query     一覧を取った問い合わせ
	 * @param following フォロー中で絞ったときの様子 (絞っていなければ null)
	 */
	public static @Nullable String emptyText(LiveQuery query, LiveDirectory.@Nullable Following following) {
		if (query.following() && following != null) {
			return switch (following.status()) {
				case OK -> query.searching()
						? Tr.n("条件に合うセッションはありません。フォロー中に出るのは、フォローしている人のうちマイクラアカウントを公開にしている人 (いま {0} 人) だけです",
								following.accounts())
						: Tr.n("フォローしている人の開催中のセッションはありません。出るのは、フォローしている人のうちマイクラアカウントを公開にしている人 (いま {0} 人) だけです",
								following.accounts());
				case NOT_LINKED -> Tr.t("このマイクラアカウントは redtact に連携されていません。redtact の設定 (アカウント → マイクラアカウント) で連携すると、"
						+ "フォローしている人で絞れます (連携は非公開でもよい)。出るのは、フォローしている人のうちマイクラアカウントを公開にしている人だけです");
				case UNAVAILABLE -> Tr.t("いまはフォロー中で絞れません (中継サーバが redtact に問い合わせられませんでした)。しばらくしてから試してください");
				case UNSUPPORTED -> Tr.t("この中継サーバはフォロー中の絞り込みに対応していません (中継サーバの更新待ち)");
				case UNVERIFIED -> Tr.f("フォロー中で絞るには、マイクラアカウントでの確認が要ります ({0})",
						following.reason() == null || following.reason().isBlank() ? Tr.t("理由不明") : following.reason());
			};
		}

		return query.searching() ? Tr.t("条件に合うセッションはありません") : null;
	}

	/** 開始からの時間 (例「12 分前」「1 時間 5 分前」)。読めない時刻は "-"。 */
	public static String ago(String createdAt, Instant now) {
		try {
			return ago(Duration.between(Instant.parse(createdAt), now));
		} catch (DateTimeParseException | NullPointerException e) {
			return "-";
		}
	}

	public static String ago(Duration d) {
		long min = Math.max(0, d.toMinutes());

		if (min < 1) {
			return Tr.t("1 分以内");
		}

		if (min < 60) {
			return Tr.n("{0} 分前", min);
		}

		long h = min / 60;
		long m = min % 60;

		if (h < 24) {
			return m == 0 ? Tr.n("{0} 時間前", h) : Tr.n("{0} 時間 {1} 分前", h, m);
		}

		return Tr.n("{0} 日前", h / 24);
	}
}
