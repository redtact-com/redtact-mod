# 統合版のブロックの対応表

このフォルダの JSON は [Taku128/mc-nbt-converter](https://github.com/Taku128/mc-nbt-converter) の `shared/mappings/`
(commit 7f17b26。Go 版の `packages/go/data/` と同じ中身) をそのまま写したもの。
`com.redtact.live.structure.bedrock` (統合版の .mcstructure を読むコード) が読む。そのコードも mc-nbt-converter の Go 版
(`pkg/mcstruct`・`pkg/mapping`・`pkg/buildnbt`) を Java に写したもの。手で直さず、mc-nbt-converter を直してから写し直す。

| ファイル | 中身 |
|---|---|
| `aliases.json` | 統合版の古いブロック名 → 新しい名前 |
| `overrides.json` | Chunker の表より先に引く対応 (いまは空) |
| `chunker-mappings.json` | 統合版 → Java 版のブロック名 (HiveGamesOSS/Chunker から取り出したもの) と、赤石の線がつながるブロック |
| `fallbacks.json` | どの表にも無いときの扱い (同じ名前のまま使う) |
| `state-rules.json` | ブロックの状態 (向き・ON/OFF など) の直し方 |

## ライセンス

mc-nbt-converter と Chunker はどちらも MIT License。

- mc-nbt-converter: Copyright (c) 2026 Taku128 / Copyright (c) 2024 mcworld_render Project
- Chunker (`chunker-mappings.json` の元のデータ): Copyright (c) 2024 Hive Games — https://github.com/HiveGamesOSS/Chunker

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
