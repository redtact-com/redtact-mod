package com.redtact.live.gui.rows;

/** メニューの「セッション」の一覧の 1 行 (保存済みの回路・分岐した子・見出し)。 */
public sealed interface SessionRow permits CircuitRow, BranchRow, HeaderRow {
}
