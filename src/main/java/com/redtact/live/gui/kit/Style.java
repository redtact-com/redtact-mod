package com.redtact.live.gui.kit;

/**
 * redtact のエレベーター UI (ダーク) の色。Web の {@code web/src/elevator/elevator.redtact.css} の変数と同じ値。
 * 画面の 1 px = GUI の 1 単位 (Web の 4 px の黒い縁は 1 単位にする)。
 */
public final class Style {
	/** 板の地 (--panel) と、明るい縁・暗い縁 (--p-l / --p-d)。 */
	public static final int PANEL = 0xFF241B15;
	public static final int PANEL_L = 0xFF3A2C22;
	public static final int PANEL_D = 0xFF120D0A;
	public static final int OUTLINE = 0xFF000000;
	/** 文字 (--txt) と控えめな文字 (--mut)。 */
	public static final int TXT = 0xFFECE2D2;
	public static final int MUT = 0xFFA8978A;
	/** 銅 (--cop) の面と縁、文字に使う銅 (--cop-tx)、銅の面に載せる文字 (--on-cop)。 */
	public static final int COP = 0xFFB5723B;
	public static final int COP_L = 0xFFE0A26A;
	public static final int COP_D = 0xFF7D4A22;
	public static final int COP_TX = 0xFFD08B54;
	public static final int ON_COP = 0xFF140C04;
	/** 赤 (--red) の面と縁、文字に使う赤 (--red-tx)。 */
	public static final int RED = 0xFFE2402F;
	public static final int RED_L = 0xFFFF7A6B;
	public static final int RED_D = 0xFF9E2418;
	public static final int RED_TX = 0xFFFE5C48;
	public static final int ON_RED = 0xFF140C04;
	/** ランプの黄色 (--lamp)・よい状態の緑・分岐の水色 (--cyan)。 */
	public static final int LAMP = 0xFFFFD24D;
	public static final int OK = 0xFF6FD08F;
	public static final int CYAN = 0xFF6FC2A8;
	/** 石のボタン (redtact の B1 Stone)。 */
	public static final int STONE = 0xFF3A2F25;
	public static final int STONE_L = 0xFF564636;
	public static final int STONE_D = 0xFF241B13;
	public static final int STONE_TX = 0xFFE7DDCD;
	/** 階のボタン (ヘッダのタブ) の地・点いたときの地・点いたときの字。 */
	public static final int FLOOR = 0xFF241A14;
	public static final int FLOOR_ON = 0xFF33211A;
	public static final int FLOOR_ON_TX = 0xFFFFE9C4;
	/** ヘッダの帯 (#120d0a の 80%)。 */
	public static final int HUD_BAR = 0xCC120D0A;
	/** 子タブの段 (#51。幕の上に薄く。redtact の .el-tabbar はページの地のまま)。 */
	public static final int SUB_BAR = 0x66120D0A;
	/** メニューの後ろに敷く暗い幕 (後ろのワールドが少し見える)。 */
	public static final int BACKDROP = 0xB0070709;
	/** 入力欄の地。 */
	public static final int FIELD = 0xFF160F0B;
	public static final int FIELD_SHADE = 0xFF0A0705;

	/** バッジの地 (色を板の地に 15% ほど混ぜたもの)。 */
	public static final int BADGE_PLAIN = 0xFF3A281B;
	public static final int BADGE_OK = 0xFF283021;
	public static final int BADGE_WARN = 0xFF47381E;
	public static final int BADGE_RED = 0xFF422119;
	public static final int BADGE_CYAN = 0xFF2A3A33;

	private Style() {
	}

	/** 色を明るくする (0..1 の割合で白を混ぜる)。不透明度は保つ。 */
	public static int lighten(int argb, float amount) {
		int a = argb >>> 24;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		r += (int) ((255 - r) * amount);
		g += (int) ((255 - g) * amount);
		b += (int) ((255 - b) * amount);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** 不透明度を掛ける (0..1)。 */
	public static int fade(int argb, float alpha) {
		int a = (int) ((argb >>> 24) * alpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
