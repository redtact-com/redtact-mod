package com.redtact.live.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.redtact.live.compat.GameInput;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RSlider;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.input.PointerCapture;
import com.redtact.live.preview.PreviewCircuit;
import com.redtact.live.preview.mesh.CircuitGrid;
import com.redtact.live.preview.mesh.CircuitMesh;
import com.redtact.live.preview.mesh.GpuMesh;
import com.redtact.live.preview.mesh.SliceMode;
import com.redtact.live.preview.view.FullscreenPreviewRenderState;
import com.redtact.live.preview.view.PreviewScene;
import com.redtact.live.preview.view.ViewCamera;
import com.redtact.live.ui.ImageClipboard;
import com.redtact.live.ui.Messages;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 全画面のプレビュー (#102)。保管庫と開催中の詳細のプレビューの右上のボタンから開き、Esc で元の画面に戻る。
 * redtact の web の 3D プレビューと同じく、回す (左ドラッグ)・動かす (右ドラッグ)・寄る (ホイール)・フライ (押すとマウスを閉じ込め、
 * WASD・Space/Shift・Ctrl・マウスで見回す。Esc で解く)・真上から (2D)・高さで切る (全体・1層・下から・上から)・撮影 (背景なしの PNG) ができる。
 *
 * <p>回路の形は 1 度だけ組み立てて GPU に置き ({@link CircuitMesh}・{@link GpuMesh})、動かすときは描くだけ。組み立ては裏のスレッドで、
 * 回路が変わったとき (開催中の版が進んだ) と高さの切り方を変えたときだけやり直す (その間は前の形を見せる)。
 */
public final class PreviewScreen extends Screen {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live");
	private static final int BAR_H = 22;
	private static final int MARGIN = 6;
	/** 背景 (redtact の web のビューアーの既定の背景と同じ色)。 */
	private static final int BACKGROUND = 0xFF1A1A1F;

	private final Screen parent;
	private final String name;
	private final Supplier<@Nullable PreviewCircuit> source;
	private final @Nullable Runnable refresher;
	/** 初めの向き (オービット。開いたときと「向きを戻す」)。 */
	private final float initialYaw;
	private final float initialPitch;
	private final PreviewScene scene = new PreviewScene();
	// 形
	private @Nullable PreviewCircuit shown;
	private int @Nullable [] shownRange;
	private @Nullable PreviewCircuit building;
	private int @Nullable [] buildingRange;
	private @Nullable CompletableFuture<CircuitMesh> pending;
	private @Nullable String failure;
	/** 組み立てられなかった回路と高さ (同じものは頼み直さない。ログが毎 tick 出ないように)。 */
	private @Nullable PreviewCircuit failedCircuit;
	private int @Nullable [] failedRange;
	private boolean cameraReady;
	// 高さで切る
	private SliceMode slice = SliceMode.ALL;
	private int layer;
	// 入力
	private int dragButton = -1;
	private boolean flyGrabbed;
	private double grabX;
	private double grabY;
	private long lastFrameNanos;
	private boolean captureRequested;
	private int ticks;
	// 組み立ての計測 (テストと計測用)
	private long buildStarted;
	private double builtMillis;
	private int builtQuads;
	// 部品
	private @Nullable RButton orbitButton;
	private @Nullable RButton flyButton;
	private @Nullable RButton topButton;
	private @Nullable RButton sliceButton;
	private @Nullable RSlider layerSlider;
	private @Nullable RButton captureButton;
	private @Nullable RButton closeButton;
	/** 最後に保存した撮影と、クリップボードにも写せたか (テスト用)。 */
	private volatile @Nullable Path lastCapture;
	private volatile boolean copiedToClipboard;

	/**
	 * @param previewYaw   開いた元の止まったプレビューの向き (同じ向きから見始める)
	 * @param previewPitch 同じく見下ろす角度
	 * @param source       描く回路 (読み直したら新しいものを返す。まだ無ければ null)
	 * @param refresher    1 秒ごとに呼ぶ (ファイルが書き換わった・開催中の版が進んだら読み直す。要らなければ null)
	 */
	public PreviewScreen(Screen parent, String name, float previewYaw, float previewPitch, Supplier<@Nullable PreviewCircuit> source,
			@Nullable Runnable refresher) {
		super(Component.literal(name));
		this.parent = parent;
		this.name = name;
		this.initialYaw = ViewCamera.yawFromPreview(previewYaw);
		this.initialPitch = previewPitch;
		this.source = source;
		this.refresher = refresher;
	}

	// ---- 部品

	@Override
	protected void init() {
		int right = width - MARGIN;
		RButton close = RButton.icon(right - RButton.H, (BAR_H - RButton.H) / 2, Icon.CROSS, Tr.t("閉じる"), RButton.Variant.SECONDARY,
				b -> onClose()).tooltip(Tr.t("元の画面に戻る (Esc でも)"));
		closeButton = addRenderableWidget(close);
		int x = close.getX() - 6;
		RButton capture = RButton.of(font, 0, (BAR_H - RButton.H) / 2, Tr.t("撮影"), RButton.Variant.SECONDARY, b -> captureRequested = true)
				.tooltip(Tr.t("いまの向きで背景なしの PNG を撮る (screenshots/redtact/ に保存し、クリップボードにも写す)"));
		x -= capture.getWidth();
		capture.setX(x);
		captureButton = addRenderableWidget(capture);
		RButton reset = RButton.icon(0, (BAR_H - RButton.H) / 2, Icon.RESET, Tr.t("向きを戻す"), RButton.Variant.SECONDARY, b -> resetCamera())
				.tooltip(Tr.t("回路が画面に収まる初めの向きに戻す"));
		x -= RButton.H + 6;
		reset.setX(x);
		addRenderableWidget(reset);
		topButton = modeButton(Tr.t("真上"), ViewCamera.Mode.TOP, Tr.t("真上から見る (ドラッグで動かす・ホイールで寄る)"));
		flyButton = modeButton(Tr.t("フライ"), ViewCamera.Mode.FLY, Tr.t("押すと見回す (WASD で移動・Space/Shift で上下・Ctrl で速く・Esc で解く)"));
		orbitButton = modeButton(Tr.t("回転"), ViewCamera.Mode.ORBIT, Tr.t("左ドラッグで回す・右ドラッグで動かす・ホイールで寄る"));
		x -= 10;

		for (RButton b : new RButton[] {topButton, flyButton, orbitButton}) {
			x -= b.getWidth();
			b.setX(x);
			x -= 2;
		}

		int by = height - BAR_H + (BAR_H - RButton.H) / 2;
		sliceButton = addRenderableWidget(RButton.of(font, MARGIN, by, widestSliceLabel(), RButton.Variant.SECONDARY, b -> cycleSlice())
				.tooltip(Tr.t("高さで切って見る (全体・1層・下から・上から)")));
		layerSlider = addRenderableWidget(new RSlider(sliceButton.getX() + sliceButton.getWidth() + 6, by, Math.min(200, width / 3), 0, 0, layer,
				Component.literal(Tr.t("高さ")), v -> {
					layer = v;
					requestMesh();
				}));
		syncWidgets();
	}

	private RButton modeButton(String label, ViewCamera.Mode mode, String tooltip) {
		return addRenderableWidget(RButton.of(font, 0, (BAR_H - RButton.H) / 2, label, RButton.Variant.SECONDARY, b -> setMode(mode)).tooltip(tooltip));
	}

	private String widestSliceLabel() {
		String widest = "";

		for (SliceMode m : SliceMode.values()) {
			if (font.width(sliceText(m)) > font.width(widest)) {
				widest = sliceText(m);
			}
		}

		return widest;
	}

	private static String sliceText(SliceMode mode) {
		return Tr.f("高さ: {0}", mode.label());
	}

	/** 部品の字と押せるかを、いまの見方・切り方に合わせる。 */
	private void syncWidgets() {
		ViewCamera.Mode mode = scene.camera().mode();

		if (orbitButton != null) {
			orbitButton.variant(mode == ViewCamera.Mode.ORBIT ? RButton.Variant.PRIMARY : RButton.Variant.SECONDARY);
			flyButton.variant(mode == ViewCamera.Mode.FLY ? RButton.Variant.PRIMARY : RButton.Variant.SECONDARY);
			topButton.variant(mode == ViewCamera.Mode.TOP ? RButton.Variant.PRIMARY : RButton.Variant.SECONDARY);
		}

		if (sliceButton != null) {
			sliceButton.label(sliceText(slice));
			int maxY = maxY();
			layerSlider.setRange(0, maxY);
			layerSlider.setValue(layer);
			layerSlider.visible = slice != SliceMode.ALL && maxY > 0;
		}
	}

	private int maxY() {
		PreviewCircuit c = shown != null ? shown : source.get();
		return c == null ? 0 : Math.max(0, c.grid().sizeY() - 1);
	}

	// ---- 見方・切り方

	private void setMode(ViewCamera.Mode mode) {
		release();
		scene.camera().setMode(mode);
		scene.changed();
		syncWidgets();
	}

	private void resetCamera() {
		PreviewCircuit c = shown;

		if (c != null) {
			CircuitGrid g = c.grid();
			ViewCamera.Mode mode = scene.camera().mode();
			scene.camera().reset(g.sizeX(), g.sizeY(), g.sizeZ(), width / (float) Math.max(1, height), initialYaw, initialPitch);
			scene.camera().setMode(mode);
			scene.changed();
		}
	}

	private void cycleSlice() {
		SliceMode[] all = SliceMode.values();
		slice = all[(slice.ordinal() + 1) % all.length];

		if (slice == SliceMode.SINGLE || slice == SliceMode.BOTTOM_UP) {
			layer = Math.min(layer, maxY());
		}

		syncWidgets();
		requestMesh();
	}

	// ---- 形の組み立て

	/** いまの回路と切り方の形が無ければ、裏で組み立てる (組み立て中なら待つ)。 */
	private void requestMesh() {
		PreviewCircuit c = source.get();

		if (c == null) {
			return;
		}

		int[] range = slice.range(layer, Math.max(0, c.grid().sizeY() - 1));

		if (c == shown && Arrays.equals(range, shownRange) || c == building && Arrays.equals(range, buildingRange)
				|| c == failedCircuit && Arrays.equals(range, failedRange)) {
			return;
		}

		if (pending != null) {
			pending.thenAccept(CircuitMesh::close); // 古い頼み: できたら捨てる
		}

		building = c;
		buildingRange = range;
		CircuitGrid grid = c.grid();
		Biome biome = plains();
		Vector3f eye = cameraReady ? scene.camera().eye() : new Vector3f(grid.sizeX() / 2.0F, grid.sizeY() * 2.0F, grid.sizeZ() * 2.0F);
		buildStarted = System.nanoTime();
		Set<BlockState> specials = scene.specials().pick(grid.palette()); // 形に入らないブロック (チェストなど。描画のスレッドで見分ける)
		pending = CompletableFuture.supplyAsync(() -> CircuitMesh.build(grid, range[0], range[1], biome, eye.x, eye.y, eye.z, specials),
				Util.backgroundExecutor());
	}

	/** 組み立て終わった形を GPU に送って差し替える (描画のスレッドで毎コマ)。 */
	private void collectMesh() {
		CompletableFuture<CircuitMesh> f = pending;

		if (f == null || !f.isDone()) {
			return;
		}

		pending = null;
		CircuitMesh built;

		try {
			built = f.join();
		} catch (RuntimeException e) {
			Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;

			if (cause instanceof CircuitMesh.TooManyQuadsException) {
				LOGGER.info("[redtact-live] 全画面のプレビュー: 面が {} を超えるので組み立てない", CircuitMesh.MAX_QUADS);
				failure = Tr.f("面が多すぎるので描きません ({0} 面まで)", String.format("%,d", CircuitMesh.MAX_QUADS));
			} else {
				LOGGER.warn("[redtact-live] 全画面のプレビューの形を組み立てられません", cause);
				failure = Tr.f("形を組み立てられません ({0})", cause.getClass().getSimpleName());
			}

			failedCircuit = building;
			failedRange = buildingRange;
			building = null;
			return;
		}

		try {
			scene.setMesh(GpuMesh.upload(built));
		} finally {
			built.close();
		}

		builtMillis = (System.nanoTime() - buildStarted) / 1.0E6;
		builtQuads = built.quads();

		shown = building;
		shownRange = buildingRange;
		building = null;
		failure = null;

		if (!cameraReady && shown != null) {
			CircuitGrid g = shown.grid();
			scene.camera().reset(g.sizeX(), g.sizeY(), g.sizeZ(), width / (float) Math.max(1, height), initialYaw, initialPitch);
			cameraReady = true;
		}

		syncWidgets();
	}

	private @Nullable Biome plains() {
		if (minecraft == null || minecraft.level == null) {
			return null;
		}

		return minecraft.level.registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS).map(h -> h.value()).orElse(null);
	}

	// ---- 毎 tick・毎コマ

	@Override
	public void tick() {
		if (refresher != null && ticks++ % 20 == 0) {
			refresher.run();
		}

		if (flyGrabbed && minecraft != null && !minecraft.isWindowActive()) {
			release(); // ほかの窓へ移った
		}

		requestMesh();
	}

	/** フライ: 閉じ込めているあいだのマウスとキーで動く (コマの時間で)。 */
	private void fly(float seconds) {
		if (!flyGrabbed || minecraft == null) {
			return;
		}

		ViewCamera camera = scene.camera();
		double[] d = PointerCapture.drain();

		if (d != null) {
			// ゲームの視点と同じ速さ (設定のマウス感度)
			double s = minecraft.options.sensitivity().get() * 0.6 + 0.2;
			double perPixel = s * s * s * 8.0 * 0.15;
			double invert = minecraft.options.invertMouseY().get() ? -1 : 1;
			camera.flyLook(d[0] * perPixel, d[1] * perPixel * invert);
			scene.changed();
		}

		// 置いてある場所のキー (SDL のスキャンコード。キー配列によらず左手の位置)
		float forward = (down(InputConstants.KEY_W) ? 1 : 0) - (down(InputConstants.KEY_S) ? 1 : 0);
		float right = (down(InputConstants.KEY_D) ? 1 : 0) - (down(InputConstants.KEY_A) ? 1 : 0);
		float up = (down(InputConstants.KEY_SPACE) ? 1 : 0) - (down(InputConstants.KEY_LSHIFT) ? 1 : 0);

		if (forward != 0 || right != 0 || up != 0) {
			boolean fast = down(InputConstants.KEY_LCONTROL) || down(InputConstants.KEY_RCONTROL);
			camera.flyMove(forward, right, up, camera.flySpeed() * (fast ? 3.0F : 1.0F) * seconds);
			scene.changed();
		}
	}

	private static boolean down(int scancode) {
		return GameInput.isKeyDown(scancode);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, BACKGROUND);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		long now = System.nanoTime();
		float seconds = lastFrameNanos == 0 ? 0 : Math.min(0.1F, (now - lastFrameNanos) / 1.0E9F);
		lastFrameNanos = now;
		collectMesh();
		fly(seconds);

		if (captureRequested) {
			captureRequested = false;
			capture();
		}

		extractView(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, a); // 部品 (帯の上に)
	}

	/** 回路の絵と上下の帯 (後から足したものほど上に重なる)。 */
	private void extractView(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (scene.mesh() != null) {
			g.guiRenderState.addPicturesInPictureState(new FullscreenPreviewRenderState(scene, 0, 0, width, height, g.scissorStack.peek()));
		}

		String center = centerMessage();

		if (center != null) {
			int w = font.width(center);
			g.fill(width / 2 - w / 2 - 8, height / 2 - 10, width / 2 + w / 2 + 8, height / 2 + 10, Style.HUD_BAR);
			g.text(font, center, width / 2 - w / 2, height / 2 - 4, Style.TXT, false);
		}

		// 上の帯: 名前と大きさ
		g.fill(0, 0, width, BAR_H, Style.HUD_BAR);
		g.fill(0, BAR_H, width, BAR_H + 1, Style.OUTLINE);
		int textRight = orbitButton == null ? width - MARGIN : orbitButton.getX() - 8;
		Draw.clippedText(g, font, name, MARGIN, (BAR_H - 8) / 2, Math.max(20, textRight - MARGIN) * 2 / 3, Style.TXT, mouseX, mouseY);
		String info = info();
		int infoX = MARGIN + Math.min(font.width(name), Math.max(20, textRight - MARGIN) * 2 / 3) + 10;

		if (info != null && infoX < textRight) {
			Draw.clippedText(g, font, info, infoX, (BAR_H - 8) / 2, textRight - infoX, Style.MUT, mouseX, mouseY);
		}

		// 下の帯: 高さで切る・操作の案内
		g.fill(0, height - BAR_H - 1, width, height - BAR_H, Style.OUTLINE);
		g.fill(0, height - BAR_H, width, height, Style.HUD_BAR);
		int hintX = MARGIN;

		if (layerSlider != null && layerSlider.visible) {
			String label = Tr.f("高さ {0} / {1}", layer, maxY());
			int lx = layerSlider.getX() + layerSlider.getWidth() + 6;
			g.text(font, label, lx, height - BAR_H + (BAR_H - 8) / 2, Style.TXT, false);
			hintX = lx + font.width(label) + 12;
		} else if (sliceButton != null) {
			hintX = sliceButton.getX() + sliceButton.getWidth() + 12;
		}

		String hint = hint();
		int hw = font.width(hint);
		int hx = Math.max(hintX, width - MARGIN - hw);
		Draw.clippedText(g, font, hint, hx, height - BAR_H + (BAR_H - 8) / 2, width - MARGIN - hx, Style.MUT, mouseX, mouseY);
	}

	private @Nullable String centerMessage() {
		if (failure != null) {
			return failure;
		}

		if (scene.mesh() == null) {
			return source.get() == null ? Tr.t("読み込み中…") : Tr.t("形を組み立て中…");
		}

		if (scene.mesh().isEmpty()) {
			return Tr.t("この高さにはブロックがありません");
		}

		if (scene.camera().mode() == ViewCamera.Mode.FLY && !flyGrabbed) {
			return Tr.t("クリックで視点操作を開始");
		}

		return null;
	}

	private @Nullable String info() {
		PreviewCircuit c = shown != null ? shown : source.get();

		if (c == null) {
			return null;
		}

		CircuitGrid g = c.grid();
		String size = g.sizeX() + "×" + g.sizeY() + "×" + g.sizeZ();
		String text = Tr.n("{1}・{0} ブロック", c.model().nonAir(), size);
		return pending != null && shown != null ? Tr.f("{0}・{1}", text, Tr.t("組み立て中…")) : text;
	}

	private String hint() {
		return switch (scene.camera().mode()) {
			case ORBIT -> Tr.t("左ドラッグで回す・右ドラッグで動かす・ホイールで寄る");
			case FLY -> flyGrabbed ? Tr.t("WASD で移動・Space/Shift で上下・Ctrl で速く・Esc で解く") : Tr.t("押すと見回す");
			case TOP -> Tr.t("ドラッグで動かす・ホイールで寄る");
		};
	}

	// ---- 撮影

	private void capture() {
		if (minecraft == null || scene.mesh() == null || scene.mesh().isEmpty()) {
			return;
		}

		Window window = minecraft.getWindow();
		Path dir = minecraft.gameDirectory.toPath().resolve("screenshots").resolve("redtact");
		String file = fileName(name) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".png";
		scene.capture(window.getWidth(), window.getHeight(), image -> Util.ioPool().execute(() -> save(image, dir.resolve(file))));
	}

	/** 撮った絵を保存し (裏のスレッド)、描画のスレッドでクリップボードにも写す (写せなければ保存だけ)。 */
	private void save(NativeImage image, Path file) {
		byte[] png;

		try (image) {
			Files.createDirectories(file.getParent());
			image.writeToFile(file);
			png = Files.readAllBytes(file);
		} catch (Exception e) {
			LOGGER.warn("[redtact-live] 撮った絵を保存できません: {}", file, e);
			Messages.error(Tr.f("撮った絵を保存できません ({0})", e.getClass().getSimpleName()));
			return;
		}

		LOGGER.info("[redtact-live] 全画面のプレビューを撮った: {}", file);
		Minecraft.getInstance().execute(() -> {
			boolean copied = ImageClipboard.copyPng(png);
			copiedToClipboard = copied;
			lastCapture = file;
			String name = file.getFileName().toString();
			Messages.notice(Tr.t("撮影しました"), copied ? Tr.f("{0}・クリップボードにも写しました", name) : name);
		});
	}

	/** テスト用: 最後の撮影をクリップボードにも写せたか。 */
	public boolean copiedToClipboard() {
		return copiedToClipboard;
	}

	/** ファイル名 (Windows で使えない字は _)。 */
	static String fileName(String name) {
		String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();

		while (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1);
		}

		if (s.isEmpty()) {
			s = "circuit";
		}

		return s.length() > 60 ? s.substring(0, 60) : s;
	}

	/** テスト用: 最後に保存した撮影 (まだなら null)。 */
	public @Nullable Path lastCapture() {
		return lastCapture;
	}

	// ---- 入力

	private boolean inView(double x, double y) {
		return y > BAR_H && y < height - BAR_H;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (flyGrabbed) {
			return true;
		}

		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		if (!inView(event.x(), event.y()) || scene.mesh() == null) {
			return false;
		}

		if (scene.camera().mode() == ViewCamera.Mode.FLY) {
			grab();
			return true;
		}

		dragButton = event.button();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragButton < 0) {
			return super.mouseDragged(event, dx, dy);
		}

		ViewCamera camera = scene.camera();

		if (camera.mode() == ViewCamera.Mode.ORBIT && dragButton == InputConstants.MOUSE_BUTTON_LEFT) {
			camera.orbitDrag(dx, dy);
		} else if (camera.mode() != ViewCamera.Mode.FLY) {
			camera.pan(dx, dy, height);
		}

		scene.changed();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragButton >= 0) {
			dragButton = -1;
			return true;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!inView(mouseX, mouseY) || vertical == 0) {
			return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
		}

		if (scene.camera().mode() != ViewCamera.Mode.FLY) {
			scene.camera().zoom(vertical);
			scene.changed();
		}

		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (flyGrabbed) {
			if (event.isEscape()) {
				release(); // 閉じ込めだけ解く (画面は閉じない。web と同じ)
			}

			return true; // 動かすキーは毎コマ見る。部品へは渡さない
		}

		return super.keyPressed(event);
	}

	// ---- フライのマウスの閉じ込め (#88 と同じく SDL の相対モード)

	private void grab() {
		if (flyGrabbed || minecraft == null) {
			return;
		}

		grabX = minecraft.mouseHandler.xpos();
		grabY = minecraft.mouseHandler.ypos();
		PointerCapture.begin(grabX, grabY);
		GameInput.grabMouse(minecraft.getWindow(), grabX, grabY);
		flyGrabbed = true;
	}

	private void release() {
		if (!flyGrabbed || minecraft == null) {
			return;
		}

		flyGrabbed = false;
		PointerCapture.end();
		GameInput.releaseMouse(minecraft.getWindow(), grabX, grabY);
	}

	/** フライでマウスを閉じ込めているか (テスト用)。 */
	public boolean flyGrabbed() {
		return flyGrabbed;
	}

	/** テスト用。 */
	public PreviewScene scene() {
		return scene;
	}

	/** テスト用: 高さの切り方を変える。 */
	public void setSlice(SliceMode mode, int layer) {
		this.slice = mode;
		this.layer = layer;
		syncWidgets();
		requestMesh();
	}

	/** テスト用: いま描いている形の元 (組み立て中なら前のもの)。 */
	public @Nullable PreviewCircuit shownCircuit() {
		return shown;
	}

	/** テスト用: 組み立て中か。 */
	public boolean building() {
		return pending != null;
	}

	/** テスト用: 最後に組み立てた形の面の数と、頼んでから GPU に送り終えるまでの時間 (ミリ秒)。 */
	public double[] lastBuild() {
		return new double[] {builtQuads, builtMillis};
	}

	/** テスト用: 見方のボタン。 */
	public RButton modeButton(ViewCamera.Mode mode) {
		return switch (mode) {
			case ORBIT -> orbitButton;
			case FLY -> flyButton;
			case TOP -> topButton;
		};
	}

	/** テスト用。 */
	public RButton sliceButton() {
		return sliceButton;
	}

	public RSlider layerSlider() {
		return layerSlider;
	}

	public RButton captureButton() {
		return captureButton;
	}

	public RButton closeButton() {
		return closeButton;
	}

	/** テスト用: いまの高さの切り方と高さ。 */
	public SliceMode slice() {
		return slice;
	}

	public int layer() {
		return layer;
	}

	// ---- 閉じる

	@Override
	public void onClose() {
		release();

		if (minecraft != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	@Override
	public void removed() {
		release();

		if (pending != null) {
			pending.thenAccept(CircuitMesh::close);
			pending = null;
		}

		scene.close();
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
