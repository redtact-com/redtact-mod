package com.redtact.live.net;

import java.util.List;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * 中継サーバが返すエラーの文 (#79)。サーバは日本語の message を返すので、英語の画面ではこの表の原文に一致するものを訳して出す
 * (一致しなければサーバの文のまま)。サーバの文を変えたら、ここも合わせる。
 */
public final class ServerMessages {
	/** サーバの message そのもの (server/internal の writeErr・errf)。 */
	static final List<String> EXACT = List.of(
			Tr.ja("gzip を展開できない"),
			Tr.ja("listed か allowCopy (true / false) が要る"),
			Tr.ja("合言葉の受付が混み合っている。しばらくして試す"),
			Tr.ja("確認の受付が混み合っている。しばらくして試す"),
			Tr.ja("コードが違うか、期限が切れている (5 分・1 回だけ)"),
			Tr.ja("スキンの置き場に届かない"),
			Tr.ja("サーバ内部のエラー"),
			Tr.ja("使っていない合言葉が多すぎる。10 分たつか、作った /connect を使ってから作り直す"),
			Tr.ja("マイクラアカウントでの確認 (minecraft) が必要"),
			Tr.ja("マイクラの認証サーバに問い合わせられなかった"),
			Tr.ja("マイクラアカウントを確認できなかった (チャレンジの期限切れか、join していない)"),
			Tr.ja("このホストのスキンから顔を作れない"),
			Tr.ja("このホストの顔はない (未確認のホストか既定のスキン)"),
			Tr.ja("この中継サーバはマイクラアカウントでのサインインを受け付けていない"),
			Tr.ja("この中継サーバは統合版のシングルプレイ (/connect) を受け付けていない"),
			Tr.ja("セッションが見つからないか、終了している"),
			Tr.ja("コードの入力が多すぎる。しばらく待ってから試す"),
			Tr.ja("サインインの期限が切れたか無効になった。もう一度サインインする"),
			Tr.ja("マイクラアカウントでのサインインが必要"),
			Tr.ja("回路データが大きすぎる"),
			Tr.ja("X-Live-User (1〜32 文字の表示名) が必要"),
			Tr.ja("after が長すぎる"),
			Tr.ja("blockEntities の i が範囲の外"),
			Tr.ja("blocks に palette の外の添字がある"),
			Tr.ja("blocks にパレットに無い添字がある"),
			Tr.ja("blocks の数が範囲の体積と合わない"),
			Tr.ja("changes が体積より多い"),
			Tr.ja("changes の添字が範囲の外"),
			Tr.ja("changes は [添字, palette の添字] の組の並び"),
			Tr.ja("circuitId か mcVersion が長すぎる"),
			Tr.ja("removedBlockEntities の添字が範囲の外"),
			Tr.ja("まだ回路が同期されていない"),
			Tr.ja("分岐元にまだ回路が同期されていない"),
			Tr.ja("統合版の範囲を送れるのは edition が bedrock のセッションだけ"),
			Tr.ja("サーバの回路キャッシュがいっぱい。しばらくしてから試す"),
			Tr.ja("ホストがこのセッションの複製 (ダウンロード・分岐) を許可していない"),
			Tr.ja("このセッションのコメント数が上限に達した"),
			Tr.ja("コメントが投稿の基準に合わない"),
			Tr.ja("いまはコメントを受け付けられない"),
			Tr.ja("そのコメントは無い"),
			Tr.ja("分岐元のセッションが見つからないか、終了している"),
			Tr.ja("連続で投稿できるのは 2 秒に 1 件まで"),
			// 開催中の一覧の検索・並べ替え・絞り込み (#90。GET /api/sessions)
			Tr.ja("q は 64 文字まで"),
			Tr.ja("sort は \"new\"・\"viewers\"・\"views\" のどれか"),
			Tr.ja("following は 1 だけ"),
			Tr.ja("hosts と following は一緒に使えない"),
			Tr.ja("hosts は 200 件まで"),
			Tr.ja("hosts はマイクラ (Java 版) の UUID をカンマで区切る"),
			Tr.ja("フォロー中で絞るには、マイクラアカウントでのサインインが必要"));
	/** 後ろに理由が続く文の頭 (例「JSON を読めない: …」)。頭だけ訳して後ろはそのまま。 */
	static final List<String> PREFIXES = List.of(
			Tr.ja("JSON を読めない: "),
			Tr.ja("本文を読めない: "),
			Tr.ja("統合版のストラクチャーを読めない: "));

	private ServerMessages() {
	}

	/** サーバの message をいまの言語にする (知らない文はそのまま)。 */
	public static @Nullable String localize(@Nullable String message) {
		if (message == null || message.isEmpty()) {
			return message;
		}

		if (EXACT.contains(message)) {
			return Tr.t(message);
		}

		for (String p : PREFIXES) {
			if (message.startsWith(p)) {
				return Tr.t(p) + message.substring(p.length());
			}
		}

		return message;
	}
}
