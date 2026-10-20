# redtact (Fabric mod)

English | [日本語](#日本語)

A client-side Fabric mod for Minecraft: Java Edition 26.3 that connects your game to [redtact](https://redtact.com), a site for sharing redstone circuits.

- **redtact in game**: open redtact from the mod menu (needs MCEF Modern) and download circuits straight into a vault folder (`schematics/redtact/`).
- **Vault**: browse your schematic folders, search them, and look at circuits in 3D (a still image with rotate buttons, or a full-screen view with fly mode, layer-by-layer view, top view and screenshots). Place circuits with Litematica, or post your own circuit files to redtact.
- **Live sharing**: select a region with a feather, start a session, and the circuit appears on the web in real time. Viewers can comment, and you read and reply in game. You can also browse other people's live sessions, load them into Litematica (when the host allows copying), and branch from them.

Open the menu with `R` + `L` (hold `R`, press `L`) or `/rlive menu`. The UI is available in English and Japanese.

## Requirements

- Minecraft: Java Edition 26.3, Fabric Loader 0.19.5 or newer, [Fabric API](https://modrinth.com/mod/fabric-api)
- Optional: [Litematica](https://modrinth.com/mod/litematica) 0.29.1+ (load circuits as placements)
- Optional: [MCEF Modern](https://modrinth.com/mod/mcef-modern) 0.3.3+ (the redtact tab; MCEF Modern downloads Chromium the first time you use it)

## Network and data

This mod connects to online services run by redtact. It sends data only when you use these features:

- **live.redtact.com** (the live sharing relay): when you start a live session, the mod uploads the blocks and block entity data (such as sign text and container contents) inside the region you selected, the session title, and your Minecraft name and UUID, and it keeps sending changes while the session is open. Comments you write are sent with your Minecraft name. The relay keeps sessions in memory and removes them after the session ends; if a session or comment is reported, a copy is kept so that we can handle the report. Browsing live sessions only downloads data.
- **Mojang's session server**: to prove to the relay that you own your Minecraft account, the mod performs the same "join server" step as joining a multiplayer server. Your access token is sent only to Mojang, never to redtact.
- **redtact.com / api.redtact.com**: the redtact tab shows redtact.com in game, and the vault downloads circuit files from it. Signing in to redtact happens in your normal web browser; the mod listens on 127.0.0.1 for a short time to receive the result.
- No analytics or telemetry.

Privacy policy: https://redtact.com/privacy

## Use of AI

Most of the code in this mod was written by an AI coding assistant (Claude Code by Anthropic) under the direction of the redtact author, who decided the features and design, reviewed the changes and tested them in game. This README and the project description were also drafted with AI assistance. The icon is not AI-generated.

## Building

With JDK 25: `./gradlew build` builds `build/libs/redtact-<version>.jar` and runs the unit tests.
The client game tests (`./gradlew runClientGameTest`) for live sharing need the relay server, which is not in this repository. The vault tests run without it: `REDTACT_LIVE_LIBRARY_ONLY=1 ./gradlew runClientGameTest`.

## License

[MIT](LICENSE). Third-party notices are in [NOTICE.md](NOTICE.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.

---

## 日本語

[redtact](https://redtact.com) (レッドストーン回路の共有サイト) とゲームをつなぐ、Minecraft: Java Edition 26.3 の Fabric のクライアント MOD です。

- **ゲーム内の redtact**: MOD のメニューで redtact を開き (MCEF Modern が要ります)、回路を保管庫のフォルダ (`schematics/redtact/`) にそのままダウンロードできます。
- **保管庫**: schematics のフォルダを行き来・検索し、回路を立体で見られます (回すボタン付きの静止画と、フライ・1 層ずつ・真上から・撮影ができる全画面)。Litematica で配置したり、自分の回路のファイルを redtact に投稿したりできます。
- **ライブ共有**: 羽根で範囲を選んでセッションを始めると、回路が Web にそのまま映ります。閲覧者のコメントをゲームの中で読み、返事を書けます。ほかの人のライブを見たり、Litematica に読み込んだり (ホストが複製を許可しているとき)、分岐したりもできます。

メニューは `R` を押しながら `L` か `/rlive menu` で開きます。画面は日本語と英語に対応しています。

### 必要なもの

- Minecraft: Java Edition 26.3・Fabric Loader 0.19.5 以上・[Fabric API](https://modrinth.com/mod/fabric-api)
- 任意: [Litematica](https://modrinth.com/mod/litematica) 0.29.1 以上 (回路を配置として読み込む)
- 任意: [MCEF Modern](https://modrinth.com/mod/mcef-modern) 0.3.3 以上 (redtact タブ。初めて使うときに MCEF Modern が Chromium をダウンロードします)

### 通信と送る情報

この MOD は redtact が運営するサービスにつながります。送るのは次の機能を使ったときだけです。

- **live.redtact.com** (ライブ共有の中継サーバ): セッションを始めると、選んだ範囲のブロックとブロックエンティティ (看板の文字・チェストの中身など)・セッションの題・マイクラの名前と UUID を送り、開いているあいだは変化を送り続けます。書いたコメントはマイクラの名前と一緒に送ります。中継サーバはセッションをメモリに持ち、終わったら消します。セッションやコメントが通報されたときは、対応のために写しを残します。ほかの人のライブを見るときは受け取るだけです。
- **Mojang のセッションサーバ**: マイクラアカウントの本人であることを中継サーバに示すため、マルチプレイのサーバに入るときと同じ「join」の手順を使います。アクセストークンは Mojang にだけ送り、redtact には送りません。
- **redtact.com / api.redtact.com**: redtact タブはゲーム内で redtact.com を表示し、保管庫は redtact から回路のファイルをダウンロードします。redtact へのログインはふだんのブラウザで行い、その結果を受け取るために MOD が少しのあいだ 127.0.0.1 で待ち受けます。
- 利用状況の計測 (アナリティクス・テレメトリ) はしません。

プライバシーポリシー: https://redtact.com/privacy

### AI の使用

この MOD のコードの大部分は、redtact の作者の指示のもとで AI のコーディングアシスタント (Anthropic の Claude Code) が書きました。機能と設計は作者が決め、変更を確かめ、ゲームの中で動作を確認しています。この README とプロジェクトの説明も AI の助けを借りて書いています。アイコンは AI で作ったものではありません。

### ビルド

JDK 25 で `./gradlew build` を実行すると `build/libs/redtact-<版>.jar` ができ、単体テストも流れます。
ライブ共有のクライアントのゲームテスト (`./gradlew runClientGameTest`) は中継サーバが要ります (このリポジトリには含みません)。保管庫のテストは中継サーバなしで動きます: `REDTACT_LIVE_LIBRARY_ONLY=1 ./gradlew runClientGameTest`

### ライセンス

[MIT](LICENSE)。ほかのプロジェクトの著作権表示は [NOTICE.md](NOTICE.md) にあります。

Minecraft の公式の製品ではありません。Mojang・Microsoft が承認したものでも、関係するものでもありません。
