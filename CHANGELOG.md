# Changelog

## 0.2.0 (2026-10-21)

More Minecraft versions. / 対応するマイクラの版を増やした。

- Supports Minecraft 1.21.10, 1.21.11, 26.1.x and 26.2 in addition to 26.3. One jar per Minecraft version: `redtact-0.2.0+<minecraft>.jar` (the 26.1.2 jar is for 26.1–26.1.2) / 26.3 に加えてマイクラ 1.21.10・1.21.11・26.1.x・26.2 に対応。マイクラの版ごとの jar (`redtact-0.2.0+<マイクラの版>.jar`。26.1.2 の jar は 26.1〜26.1.2 用)
- On 1.21.x, 26.1 and 26.2, screenshots from the full-screen preview are saved to a file only (copying the image to the clipboard needs 26.3) / 1.21.x・26.1・26.2 では、全画面のプレビューの撮影はファイルへの保存だけ (クリップボードへの写しは 26.3 だけ)
- On 1.21.x, the redtact tab does not show text being composed with an IME (only the committed text is sent), and bell bodies and enchanting table books are not drawn in 3D previews / 1.21.x では、redtact タブで日本語の変換中の字が出ない (確定した字だけ送る)。立体のプレビューにベルの本体とエンチャントテーブルの本が描かれない
- On 1.21.10, the selection box is hidden behind blocks / 1.21.10 では範囲の枠がブロックに隠れる

## 0.1.0 (2026-10-20)

First public release. / 最初の公開版。

- redtact tab (redtact in game with MCEF Modern) and the vault (`schematics/redtact/`) / redtact タブ (MCEF Modern でゲーム内に redtact) と保管庫
- Vault: folders, search, 3D preview (still image with 15° rotate buttons; full screen with fly mode, layers, top view and screenshots), posting to redtact / 保管庫: フォルダ・検索・立体のプレビュー (15° ずつ回す静止画・フライ・1 層ずつ・真上から・撮影ができる全画面)・redtact への投稿
- Live sharing: region tool (feather), sessions, auto sync, comments, the list of live sessions (search, sort, following), loading into Litematica, branching / ライブ共有: 範囲の道具 (羽根)・セッション・自動同期・コメント・開催中の一覧 (検索・並べ替え・フォロー中)・Litematica への読み込み・分岐
- English and Japanese UI / 英語と日本語の画面
