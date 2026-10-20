package com.redtact.live.net;

import java.util.List;

/** protocol.md の JSON の形。 */
public final class Api {
	private Api() {
	}

	public static final class Session {
		public String sessionId;
		public String title;
		/** ホスト名。確認済みならマイクラの名前、未確認なら X-Live-User。 */
		public String host;
		/** マイクラアカウントで確認したか。 */
		public boolean hostVerified;
		/** 確認済みのときのマイクラの UUID (ハイフン付き)。未確認なら null。 */
		public String hostUuid;
		public String createdAt;
		public boolean hostOnline;
		/** 開催中の一覧 (GET /api/sessions) に出すか。 */
		public boolean listed;
		/** Web で開くリンク ({@code <LIVE_PUBLIC_WEB_URL>/s/<id>})。 */
		public String shareUrl;
		/** 複製 (.litematic の取得・読み込み・分岐) を許すか。古いサーバで無ければ null (許す扱い)。 */
		public Boolean allowCopy;

		/** いま見ている人 (視聴中): Web の画面と、MOD で詳細を開いている人 (#90 から)。 */
		public int viewerCount;
		/** 開始からの延べ人数 (閲覧数。同じ回線は 1 回・ホストは数えない。#90)。古いサーバでは null (出さない)。 */
		public Integer views;
		/** ホストが redtact で公開したマイクラアカウントの連携から引いた redtact のプロフィール (#90)。無ければ null。 */
		public HostUser hostUser;
		public Snapshot snapshot;
		/** 分岐元 (分岐して開いたセッションだけ。#13)。 */
		public Parent parent;
		/** このセッションから分岐して開催中の子 (古い順)。古いサーバでは null。 */
		public List<Branch> branches;
		public List<Comment> comments;

		public List<Branch> branchList() {
			return branches == null ? List.of() : branches;
		}

		/** 古いサーバ (allowCopy が無い) は許す扱い。 */
		public boolean copyAllowed() {
			return allowCopy == null || allowCopy;
		}
	}

	/** ホストの redtact の公開プロフィール (#90)。 */
	public static final class HostUser {
		/** redtact のユーザー ID。 */
		public String id;
		/** redtact の名前。 */
		public String name;
		/** アイコンの URL (無ければ空)。 */
		public String picture;
		/** プロフィールのページ (絶対 URL)。 */
		public String url;
	}

	/** 子のセッションが持つ分岐元 (分岐したときに写し、親が終わっても残る)。 */
	public static final class Parent {
		public String sessionId;
		public String title;
		public String host;
		public boolean hostVerified;
		/** 分岐した親の版。 */
		public int version;
		public String shareUrl;
		/** 親のセッションが終わったら true。 */
		public boolean ended;
	}

	/** 親のセッションから見た、分岐して開催中の子。 */
	public static final class Branch {
		public String sessionId;
		public String title;
		public String host;
		public boolean hostVerified;
		/** 分岐した親の版。 */
		public int fromVersion;
		public String createdAt;
		/** 子の回路を取れるか。古いサーバで無ければ null (許す扱い)。 */
		public Boolean allowCopy;
		public String shareUrl;

		public boolean copyAllowed() {
			return allowCopy == null || allowCopy;
		}
	}

	/** 最新の回路の概要 (中身は CircuitState / CircuitDiff)。 */
	public static final class Snapshot {
		public int version;
		public int[] size;
		public int blockCount;
		public String updatedAt;
	}

	public static final class Comment {
		public String id;
		public String author;
		/** マイクラアカウントでサインインした人か (false は開発用の表示名)。古いサーバでは null。 */
		public Boolean authorVerified;
		/** サインインした人のマイクラの UUID (ハイフン付き)。表示名だけなら null。 */
		public String authorUuid;
		/** セッションのホスト本人が書いたか (#74)。古いサーバでは null。 */
		public Boolean authorIsHost;
		public String text;
		public String createdAt;

		public boolean byHost() {
			return authorIsHost != null && authorIsHost;
		}
	}

	/** POST /api/sessions/{id}/comments の req。 */
	public record PostCommentRequest(String text) {
	}

	/** POST /api/sessions/{id}/comments の res。 */
	public static final class CommentResponse {
		public Comment comment;
	}

	/** GET /api/sessions/{id}/comments の res (#74): セッション (comments は空) と after より後のコメント (古い順)。 */
	public static final class CommentsResponse {
		public Session session;
		public List<Comment> comments;
	}

	/** サインインした閲覧者 (POST /api/auth/minecraft/token の viewer)。 */
	public static final class Viewer {
		public String name;
		public boolean verified;
		/** マイクラの UUID (ハイフン付き)。 */
		public String uuid;
	}

	/** POST /api/auth/minecraft/token の res (#74): ゲームのアカウントで確かめた閲覧者のトークン (Web と同じ・30 日)。 */
	public static final class ViewerToken {
		public String token;
		public String expiresAt;
		public Viewer viewer;
	}

	/**
	 * @param minecraft マイクラアカウントでの確認 (join できたとき)。null なら付けない (X-Live-User で開く)
	 */
	public record CreateSessionRequest(String title, String circuitId, String mcVersion, int dataVersion, boolean listed, MinecraftProof minecraft,
			Boolean allowCopy, ParentRef parent) {
		public CreateSessionRequest(String title, String circuitId, String mcVersion, int dataVersion, boolean listed, MinecraftProof minecraft,
				Boolean allowCopy) {
			this(title, circuitId, mcVersion, dataVersion, listed, minecraft, allowCopy, null);
		}

		public CreateSessionRequest(String title, String circuitId, String mcVersion, int dataVersion, boolean listed, MinecraftProof minecraft) {
			this(title, circuitId, mcVersion, dataVersion, listed, minecraft, null, null);
		}

		public CreateSessionRequest(String title, String circuitId, String mcVersion, int dataVersion, boolean listed) {
			this(title, circuitId, mcVersion, dataVersion, listed, null, null, null);
		}

		public CreateSessionRequest(String title, String circuitId, String mcVersion, int dataVersion) {
			this(title, circuitId, mcVersion, dataVersion, false, null, null, null);
		}
	}

	/** POST /api/sessions の parent (分岐して開くときだけ)。version は読み込んだ親の版 (.litematic の X-Live-Version)。 */
	public record ParentRef(String sessionId, int version) {
	}

	/** POST /api/sessions の minecraft。 */
	public record MinecraftProof(String challengeId, String name) {
	}

	/** POST /api/auth/minecraft/challenge の res。 */
	public static final class Challenge {
		public String challengeId;
		/** Mojang の join に渡す値 (40 文字の 16 進)。 */
		public String serverId;
		public String expiresAt;
	}

	/** GET /api/sessions (開催中の一覧)。 */
	public static final class SessionList {
		public List<Session> sessions;
		/** フォロー中で問い合わせた (following=1) ときだけ (#90)。古い中継サーバは付けない (絞らずに全部を返す)。 */
		public Following following;
	}

	/** GET /api/sessions?following=1 の following (#90)。 */
	public static final class Following {
		/**
		 * {@code ok}・{@code not_linked} (このマイクラアカウントが redtact に連携されていない)・{@code unavailable}
		 * (中継サーバが redtact に問い合わせられなかった。一時的)。ok でなければ sessions は空。
		 */
		public String status;
		/** フォローしている人のうち、マイクラアカウント (Java 版) の連携を公開にしている人の数 (ok でなければ 0)。 */
		public int accounts;
	}

	/** PATCH /api/sessions/{id} の req。変えないものは null (書き出さない)。 */
	public record UpdateSessionRequest(Boolean listed, Boolean allowCopy) {
	}

	/** PATCH /api/sessions/{id} の res。 */
	public static final class SessionResponse {
		public Session session;
	}

	public static final class CreateSessionResponse {
		public Session session;
		public String hostToken;
		public String shareUrl;
	}

	/** PUT / PATCH /api/sessions/{id}/state の応答。 */
	public static final class SnapshotResponse {
		public Snapshot snapshot;
	}

	public static final class ErrorBody {
		public String error;
		public String message;
		/** 409 resync_required のとき、サーバのいまの版。 */
		public Integer currentVersion;
	}

	/** WebSocket で受け取るメッセージ (type ごとに使うフィールドが違う)。 */
	public static final class WsMessage {
		public String type;
		public Session session;
		public List<Comment> pending;
		public Comment comment;
		public Integer count;
		public String reason;
		/** {@code branches} (一覧の全体)。 */
		public List<Branch> branches;
		/** {@code parent} (分岐元が終わったとき)。 */
		public Parent parent;
	}

	public record AckMessage(String type, List<String> commentIds) {
		public AckMessage(List<String> commentIds) {
			this("ack", commentIds);
		}
	}
}
