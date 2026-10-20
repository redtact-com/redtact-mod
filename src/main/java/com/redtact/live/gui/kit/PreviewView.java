package com.redtact.live.gui.kit;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.redtact.live.RedtactLive;
import com.redtact.live.i18n.Tr;
import com.redtact.live.preview.CircuitPreviewRenderState;
import com.redtact.live.preview.PreviewCircuit;
import com.redtact.live.preview.PreviewStore;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * 回路の立体のプレビュー (開催中のセッション #34・保管庫のファイル #70)。止まった絵で、右上のボタンで左右に 15° ずつ回す (#93)。
 * 画面が {@link #onFullscreen} で開き方を渡すと、右端に全画面のボタンを出す (#102。見回すのは全画面で)。
 * 描き直すのは回路・向き・枠の大きさが変わったときだけ (ドラッグで回すと、毎フレーム全部のブロックを組み立て直して重かった。
 * 5,000 ブロックで 1 回 約 25 ms)。
 * 中身は出どころ ({@link Content} を返すもの。開催中は {@link PreviewStore}) が用意したものを描く (用意できていなければ説明の文字)。
 *
 * <p>ボタンは画面が {@link #controls()} を足す (プレビューより後に足すと上に描かれる)。拡大した詳細のように枠の外へ置くときは
 * {@link #placeControlsOutside} で置き場を決める。
 */
public final class PreviewView extends AbstractWidget {
	public static final float DEFAULT_YAW = 225.0F;
	public static final float DEFAULT_PITCH = 30.0F;
	/** ボタン 1 回で回す角度。 */
	public static final float STEP = 15.0F;
	/** ボタンの間。 */
	private static final int GAP = 2;
	/** 回すボタンと全画面のボタンの間。 */
	private static final int GROUP_GAP = 5;
	/** 枠の中に置くときの、枠の内側からの余白。 */
	private static final int INSET = 3;

	/**
	 * 描くものの今の状態。
	 *
	 * @param circuit 描くもの (読み直している間・失敗したときは前のもの)
	 * @param message ERROR のときの説明 (そのまま出す)
	 */
	public record Content(PreviewStore.Status status, @Nullable PreviewCircuit circuit, @Nullable String message) {
	}

	/** 見せているもの (変わったら向きを戻す)。 */
	private @Nullable Object key;
	private Supplier<@Nullable Content> source = () -> null;
	private @Nullable String placeholder;
	/** 見えるブロックが多すぎるときの案内。 */
	private String tooLargeHint = Tr.t("「ブラウザで開く」で見られます");
	private float yaw = DEFAULT_YAW;
	private final RButton rotateLeft;
	private final RButton rotateRight;
	private final RButton fullscreen;
	/** 全画面のボタンを押したとき (null なら出さない)。 */
	private @Nullable Runnable openFullscreen;
	/** 枠の外に置くときの右端と上端 (null なら枠の中の右上)。 */
	private int @Nullable [] outside;

	public PreviewView(int x, int y, int width, int height) {
		super(x, y, width, height, Component.literal(Tr.t("回路のプレビュー")));
		// 絵は押せない (押して何かするのは右上のボタン)。押せると、画面は先に足したこの部品にクリックを渡すので、枠の中のボタンに届かない
		active = false;
		rotateLeft = RButton.icon(0, 0, Icon.ROTATE_LEFT, Tr.t("左に回す"), RButton.Variant.SECONDARY, b -> rotate(-1))
				.tooltip(Tr.t("上から見て反時計回りに 15° 回す"));
		rotateRight = RButton.icon(0, 0, Icon.ROTATE_RIGHT, Tr.t("右に回す"), RButton.Variant.SECONDARY, b -> rotate(1))
				.tooltip(Tr.t("上から見て時計回りに 15° 回す"));
		fullscreen = RButton.icon(0, 0, Icon.FULLSCREEN, Tr.t("全画面で見る"), RButton.Variant.SECONDARY, b -> {
			if (openFullscreen != null && fullscreenCircuit() != null) {
				openFullscreen.run();
			}
		}).tooltip(Tr.t("回す・寄る・フライ・高さで切る・真上から・撮影 (Esc で戻る)"));
		syncControls();
	}

	/**
	 * 見せるセッション。placeholder があれば立体の代わりにそれを出す (未同期・大きすぎるなど、取りにいかないとき)。
	 * セッションが変わったら向きを戻す。
	 */
	public void show(@Nullable String sessionId, @Nullable String placeholder) {
		show(sessionId, sessionId == null ? () -> null : () -> session(sessionId), placeholder);
	}

	/**
	 * 見せるもの。key が変わったら向きを戻す。placeholder があれば立体の代わりにそれを出す。
	 */
	public void show(@Nullable Object key, Supplier<@Nullable Content> source, @Nullable String placeholder) {
		if (!Objects.equals(this.key, key)) {
			resetView();
		}

		this.key = key;
		this.source = source;
		this.placeholder = placeholder;
		syncControls();
	}

	/** 見えるブロックが多すぎて描かないときの案内 (「…で見られます」)。 */
	public PreviewView tooLargeHint(String hint) {
		this.tooLargeHint = hint;
		return this;
	}

	/** 全画面のボタンを押したときに開くもの (null なら全画面のボタンを出さない。#102)。 */
	public PreviewView onFullscreen(@Nullable Runnable open) {
		this.openFullscreen = open;
		syncControls();
		return this;
	}

	/** 右上のボタン (左に回す・右に回す・全画面)。画面がプレビューより後に addRenderableWidget する。 */
	public List<RButton> controls() {
		return List.of(rotateLeft, rotateRight, fullscreen);
	}

	/** 全画面のボタン (テスト用)。 */
	public RButton fullscreenButton() {
		return fullscreen;
	}

	/** ボタンを枠の外に置く: 右端 right・上端 top から左へ並べる (拡大した詳細の操作の行。#77 の「拡大時のボタンはプレビューの外」)。 */
	public void placeControlsOutside(int right, int top) {
		outside = new int[] {right, top};
		syncControls();
	}

	/** 枠の外に置くボタンの幅 ({@link #onFullscreen} のあとで)。 */
	public int outsideWidth() {
		return 2 * RButton.H + GAP + (openFullscreen == null ? 0 : GROUP_GAP + RButton.H);
	}

	private static @Nullable Content session(String sessionId) {
		PreviewStore.Entry e = RedtactLive.get().previews().get(sessionId);
		return e == null ? null : new Content(e.status(), e.circuit(), e.message() == null ? null : Tr.f("プレビューを取れません: {0}", e.message()));
	}

	public void resetView() {
		yaw = DEFAULT_YAW;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return DEFAULT_PITCH;
	}

	/**
	 * 15° ずつ回す。clockwise が正なら右回り (上から見て時計回り。手前が左へ動く。アイコンの ↷ と同じ)、負なら左回り。
	 * 描くときは yaw を足して回すので、右回りは yaw を減らす (ボタン。テストからも呼ぶ)。
	 */
	public void rotate(int clockwise) {
		yaw = ((yaw - clockwise * STEP) % 360.0F + 360.0F) % 360.0F;
	}

	/** いま描いている回路 (描けていなければ null)。 */
	public @Nullable PreviewCircuit shownCircuit() {
		Content e = content();
		PreviewCircuit c = e == null ? null : e.circuit();
		return placeholder != null || c == null || c.model().tooLarge() || c.model().drawCount() == 0 ? null : c;
	}

	/**
	 * 全画面で見られる回路 (全画面のボタンを出すとき。null なら出さない)。止まった絵には多すぎる回路も、全画面なら形を 1 度だけ
	 * 組み立てるので見られる。
	 */
	public @Nullable PreviewCircuit fullscreenCircuit() {
		Content e = content();
		PreviewCircuit c = e == null ? null : e.circuit();
		return openFullscreen == null || placeholder != null || c == null || c.model().nonAir() == 0 ? null : c;
	}

	/** 立体の代わりに出している説明 (描けていれば null)。 */
	public @Nullable String message() {
		if (placeholder != null) {
			return placeholder;
		}

		Content e = content();
		PreviewCircuit c = e == null ? null : e.circuit();

		if (c != null) {
			if (c.model().tooLarge()) {
				return Tr.n("見えるブロックが {0} 個あり、大きすぎるので描きません ({1})", c.model().visibleCount(),
						openFullscreen != null ? Tr.t("全画面のボタンで見られます") : tooLargeHint);
			}

			if (c.model().drawCount() == 0) {
				return Tr.t("空気だけの範囲です");
			}

			return null;
		}

		if (e == null || e.status() == PreviewStore.Status.LOADING) {
			return Tr.t("読み込み中…");
		}

		return e.message() == null ? Tr.t("プレビューを作れません") : e.message();
	}

	private @Nullable Content content() {
		return source.get();
	}

	/** ボタンを出す・隠す・並べる (描くたび。描けているかが変わるため)。右から 全画面・右に回す・左に回す。 */
	private void syncControls() {
		boolean shown = shownCircuit() != null;
		rotateLeft.visible = shown;
		rotateRight.visible = shown;
		fullscreen.visible = fullscreenCircuit() != null;
		int right = outside == null ? getX() + getWidth() - INSET : outside[0];
		int top = outside == null ? getY() + INSET : outside[1];

		if (openFullscreen != null) {
			fullscreen.setPosition(right - RButton.H, top);
			right -= RButton.H + GROUP_GAP;
		}

		rotateRight.setPosition(right - RButton.H, top);
		rotateLeft.setPosition(right - 2 * RButton.H - GAP, top);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		syncControls();
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		Font font = Minecraft.getInstance().font;
		// へこんだ黒っぽい窓 (redtact のビューアーの地)
		Draw.box(g, x, y, w, h, Style.FIELD, Style.PANEL_D, Style.PANEL_L);
		PreviewCircuit c = shownCircuit();

		if (c != null) {
			float scale = c.model().fitScale(yaw, DEFAULT_PITCH, w - 10, h - 10);
			g.guiRenderState.addPicturesInPictureState(new CircuitPreviewRenderState(c, yaw, DEFAULT_PITCH, x + 2, y + 2, x + w - 2, y + h - 2, scale,
					g.scissorStack.peek()));
			Content e = content();

			// 右上はボタンなので、更新の様子は左上に
			if (e != null && e.status() == PreviewStore.Status.LOADING) {
				g.text(font, Tr.t("更新中…"), x + 4, y + 4, Style.MUT, false);
			} else if (e != null && e.status() == PreviewStore.Status.ERROR) {
				g.text(font, Tr.t("更新できません"), x + 4, y + 4, Style.RED_TX, false);
			}

			if (c.unknown() > 0) {
				Draw.clippedText(g, font, Tr.n("知らないブロック {0} 種は描いていません", c.unknown()), x + 4, y + h - 12, w - 8, Style.LAMP, mouseX, mouseY);
			}

			return;
		}

		String text = message();
		Content e = content();
		int color = placeholder == null && e != null && e.status() == PreviewStore.Status.ERROR ? Style.RED_TX : Style.MUT;
		centered(g, font, text == null ? "" : text, x + w / 2, y + h / 2, w - 16, color);
	}

	/** 折り返して真ん中に置く。 */
	private static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int cy, int maxWidth, int color) {
		var lines = font.split(Component.literal(text), Math.max(20, maxWidth));
		int top = cy - lines.size() * 10 / 2;

		for (int i = 0; i < lines.size(); i++) {
			var line = lines.get(i);
			g.text(font, line, cx - font.width(line) / 2, top + i * 10, color, false);
		}
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
		// 絵を押しても音を鳴らさない (押して何かするのは右上のボタン)
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
	}
}
