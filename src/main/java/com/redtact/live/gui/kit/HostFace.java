package com.redtact.live.gui.kit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.gui.components.PlayerFaceExtractor;
//?} else
/*import net.minecraft.client.gui.components.PlayerFaceRenderer;*/
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * ホストの顔 (#56)。確認済みのホスト (マイクラの UUID がある) はゲームのスキンの仕組みで顔 (8×8 + 帽子の層) を描く。
 * スキンは {@code PlayerSkinRenderCache} が Mojang から非同期に取って覚えておき、取れるまでは既定のスキンの顔になる
 * (プレイヤーの頭のブロックと同じ)。未確認のホストは名前の 1 文字目を銅の四角に出す (redtact の画像の無い人と同じ)。
 */
public final class HostFace {
	/** UUID ごとのプロフィール (同じものを渡し続けて、スキンの覚えを使い回す)。 */
	private static final Map<UUID, ResolvableProfile> PROFILES = new ConcurrentHashMap<>();

	private HostFace() {
	}

	/** 顔を (x, y) に size × size で描く。 */
	public static void draw(GuiGraphicsExtractor g, @Nullable String uuid, @Nullable String name, int x, int y, int size) {
		UUID id = parse(uuid);

		if (id != null) {
			g.fill(x - 1, y - 1, x + size + 1, y + size + 1, Style.OUTLINE);
			// スキンの覚えから顔の絵を引いて渡す (26.2 からはプロフィールを渡すだけでよいが、26.1 までと同じ書き方にする。#112)
			ResolvableProfile profile = PROFILES.computeIfAbsent(id, ResolvableProfile::createUnresolved);
			var skin = Minecraft.getInstance().playerSkinRenderCache().getOrDefault(profile).playerSkin();
			//? if >=26.1 {
			PlayerFaceExtractor.extractRenderState(g, skin, x, y, size);
			//?} else
			/*PlayerFaceRenderer.draw(g, skin, x, y, size);*/
			return;
		}

		g.fill(x - 1, y - 1, x + size + 1, y + size + 1, Style.OUTLINE);
		g.fill(x, y, x + size, y + size, Style.COP_D);
		String initial = initial(name);
		var font = Minecraft.getInstance().font;
		int w = font.width(initial);
		g.text(font, initial, x + (size - w + 1) / 2, y + (size - 8) / 2 + 1, Style.TXT, false);
	}

	/** 名前の 1 文字目 (サロゲートペアも 1 文字)。空なら "?"。 */
	public static String initial(@Nullable String name) {
		if (name == null || name.isBlank()) {
			return "?";
		}

		String t = name.strip();
		return t.substring(0, t.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
	}

	/** ハイフン付きの UUID を読む。読めなければ null。 */
	static @Nullable UUID parse(@Nullable String uuid) {
		if (uuid == null || uuid.isBlank()) {
			return null;
		}

		try {
			return UUID.fromString(uuid.strip());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
