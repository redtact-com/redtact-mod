package com.redtact.live.structure;

import com.redtact.live.i18n.Tr;
import org.jspecify.annotations.Nullable;

/**
 * 回路のファイルを読むときの上限 (#70)。細工したファイルや大きすぎる回路で、ゲームのメモリを使い切らないようにする。
 *
 * @param maxFileBytes ファイルの大きさ (圧縮したまま)
 * @param maxNbtBytes  NBT を読んで持つ量 (NbtAccounter の上限。gzip を解いたあとの中身もこれで止まる)
 * @param maxVolume    範囲の体積 (ブロック数)。これより大きい回路は中身を並べない
 */
public record StructureLimits(long maxFileBytes, long maxNbtBytes, long maxVolume) {
	/** 保管庫のプレビュー。体積は開催中のプレビュー (PreviewStore.MAX_VOLUME) と同じ。 */
	public static final StructureLimits PREVIEW = new StructureLimits(32L << 20, 128L << 20, 4L << 20);

	/** 体積が上限を超えていれば理由 (超えていなければ null)。 */
	public @Nullable String volumeReason(long sizeX, long sizeY, long sizeZ) {
		if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
			return Tr.f("回路の大きさがありません ({0}×{1}×{2})", sizeX, sizeY, sizeZ);
		}

		// 3 辺をそのまま掛けると、辺が大きいときに long でも桁あふれして通るので、1 辺ずつ上限で割って比べる
		if (sizeX > maxVolume || sizeY > maxVolume / sizeX || sizeZ > maxVolume / (sizeX * sizeY)) {
			return Tr.f("範囲が大きすぎるので描きません ({0}×{1}×{2})", sizeX, sizeY, sizeZ);
		}

		return null;
	}
}
