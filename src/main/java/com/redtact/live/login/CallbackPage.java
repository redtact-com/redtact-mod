package com.redtact.live.login;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redtact.live.i18n.Tr;

/**
 * 受け取り口の {@code GET /callback} で返すページ (#50)。フラグメントの結果を読み、まず履歴から消してから
 * {@code POST /complete} に送り、ゲームに戻るよう出す。外部のものは何も読み込まない。
 */
final class CallbackPage {
	/**
	 * ページの型 (#79)。__LANG__ に言語、__WAIT__ に最初の文、__TEXT__ に結果ごとの文 (JSON) を入れる。
	 * script はまず資格情報を履歴に残さないよう消す (history.replaceState)。
	 */
	private static final String TEMPLATE = """
			<!doctype html>
			<html lang="__LANG__"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
			<meta name="referrer" content="no-referrer"><title>redtact-live</title>
			<style>
			body{margin:0;min-height:100vh;display:grid;place-items:center;background:#120d0a;color:#ece2d2;font:16px/1.6 system-ui,sans-serif}
			main{max-width:28rem;padding:2rem;border:1px solid #3a2c22;background:#241b15;text-align:center}
			h1{font-size:1.2rem;margin:0 0 .5rem;color:#e0a26a}
			</style></head>
			<body><main><h1 id="t">redtact-live</h1><p id="m">__WAIT__</p></main>
			<script>
			(function () {
			  var s = __TEXT__;
			  var hash = location.hash.replace(/^#/, "");
			  history.replaceState(null, "", "/callback");
			  var t = document.getElementById("t"), m = document.getElementById("m");
			  function show(title, text) { t.textContent = title; m.textContent = text; }
			  if (!hash) { show("redtact-live", s.nothing); return; }
			  fetch("/complete", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: hash })
			    .then(function (r) { return r.json().then(function (j) { return { status: r.status, body: j }; }); })
			    .then(function (r) {
			      if (r.status !== 200) { show(s.refusedTitle, s.refused); return; }
			      if (r.body.result === "credential") show(s.doneTitle, s.done);
			      else if (r.body.result === "cancelled") show(s.cancelledTitle, s.cancelled);
			      else show(s.failedTitle, s.failed);
			    })
			    .catch(function () { show(s.unreachableTitle, s.unreachable); });
			})();
			</script></body></html>
			""";
	private CallbackPage() {
	}

	/** 受け取り口が返すページ。開くたびにいまの言語で作る (#79)。 */
	static String html() {
		JsonObject s = new JsonObject();
		s.addProperty("nothing", Tr.t("受け取るものがありません。このページは閉じてかまいません。"));
		s.addProperty("refusedTitle", Tr.t("受け取れませんでした"));
		s.addProperty("refused", Tr.t("ゲーム内の redtact のログインボタンから、もう一度お試しください。"));
		s.addProperty("doneTitle", Tr.t("ログインの情報を渡しました"));
		s.addProperty("done", Tr.t("ゲームに戻ってください。ゲーム内の redtact でログインが完了します。このページは閉じてかまいません。"));
		s.addProperty("cancelledTitle", Tr.t("ログインを中止しました"));
		s.addProperty("cancelled", Tr.t("ゲームに戻ってください。このページは閉じてかまいません。"));
		s.addProperty("failedTitle", Tr.t("ログインできませんでした"));
		s.addProperty("failed", Tr.t("ゲームに戻り、もう一度お試しください。このページは閉じてかまいません。"));
		s.addProperty("unreachableTitle", Tr.t("ゲームに届きませんでした"));
		s.addProperty("unreachable", Tr.t("ゲームが起動しているか確かめ、もう一度お試しください。"));
		// Gson の既定は < > & などを書き換えるので、script の中に置いても閉じタグにならない
		return TEMPLATE.replace("__LANG__", Tr.english() ? "en" : "ja")
				.replace("__WAIT__", escapeHtml(Tr.t("ゲームへ渡しています…")))
				.replace("__TEXT__", new Gson().toJson(s));
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
