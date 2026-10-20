package com.redtact.live.gui.rows;

import com.redtact.live.store.CircuitRecord;
import org.jspecify.annotations.Nullable;

/**
 * 保存済みの回路 (範囲) の 1 行。picked = 選んでいる範囲、otherWorld = ほかのワールドの回路ならそのワールド (選べない。#39)。
 */
public record CircuitRow(CircuitRecord record, int snapshots, int comments, int unread, boolean open, boolean picked, @Nullable String otherWorld)
		implements SessionRow {
}
