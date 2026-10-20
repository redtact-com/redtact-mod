package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.PreviewScreen;
import com.redtact.live.gui.kit.PreviewView;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.input.PointerCapture;
import com.redtact.live.preview.PreviewCircuit;
import com.redtact.live.preview.mesh.SliceMode;
import com.redtact.live.preview.view.ViewCamera;
import com.redtact.live.session.SessionManager;
import com.redtact.live.ui.ImageClipboard;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * 全画面のプレビュー (#102)。保管庫のプレビューの右上の全画面のボタン (本物のクリック) で開き、本物のマウスとキーで
 * 回す (左ドラッグ)・寄る (ホイール)・動かす (右ドラッグ)・フライ (押して閉じ込め、W と Space で進み、マウスで見回し、Esc で解く)・
 * 高さで切る (1層・下から・上から・全体)・真上から・撮影 (背景なしの PNG) を確かめ、Esc で保管庫に戻る。
 * 5,014 ブロックの回路 (統合版のエレベーターを Java に直したもの) で、組み立ての時間と、ドラッグで回す 60 tick の描く時間を計る。
 */
final class FullscreenPreviewChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	static final String FOLDER = "fullscreen";
	/** 色々なブロック (液体・半透明・葉・草・レッドストーン) の見本。 */
	static final String SHOWCASE = "g 見本.nbt";
	/** 5,014 ブロック (計測用)。 */
	static final String ELEVATOR = "h elevator.nbt";
	private static final String ELEVATOR_FIXTURE = "/structure/elevator.expected.nbt";

	private FullscreenPreviewChecks() {
	}

	/** REDTACT_LIVE_FULLSCREEN_ONLY=1 なら保管庫の全画面のプレビューだけ (REDTACT_LIVE_LIBRARY_ONLY と一緒に)。 */
	static boolean only() {
		return System.getenv("REDTACT_LIVE_FULLSCREEN_ONLY") != null;
	}

	static void library(ClientGameTestContext context) {
		Path dir = context.computeOnClient(mc -> CircuitLoader.schematicsDir()).toAbsolutePath().normalize().resolve(FOLDER);
		prepare(dir);
		context.runOnClient(mc -> {
			LibraryNav.setQuery("");
			LibraryNav.select(null);
			LibraryNav.go(dir);
		});
		context.waitFor(mc -> library(mc) != null && dir.equals(library(mc).shownDir()) && library(mc).fileCards().size() == 2, LibraryClientGameTest.TIMEOUT);

		// ---- 見本: 開く → 回す・寄る・動かす → フライ → 高さで切る → 真上 → 撮影 → Esc で戻る
		PreviewCircuit showcase = LibraryPreviewChecks.selectAndWait(context, dir.resolve(SHOWCASE));
		check(showcase.unknown() == 0, "見本に知らないブロックがある: " + showcase.unknown());
		check(context.computeOnClient(mc -> library(mc).preview().fullscreenButton().visible), "保管庫のプレビューに全画面のボタンが無い");
		LibraryFolderChecks.cleanShot(context, "redtact-live-fullscreen-0-library-button");
		open(context);
		int fullQuads = waitMesh(context);
		check(fullQuads > 50, "見本の形の面が少ない: " + fullQuads);
		// チェスト・頭・ベル・エンチャントテーブルは描き足す (ホッパーは形に入る)
		List<String> specials = context.computeOnClient(mc -> java.util.Arrays.stream(screen(mc).scene().mesh().specialStates())
				.map(st -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath()).sorted().toList());
		check(specials.equals(List.of("bell", "chest", "enchanting_table", "skeleton_skull")), "描き足すブロック: " + specials);
		LOGGER.info("[fullscreen] 描き足すブロック: {}", specials);
		String title = context.computeOnClient(mc -> screen(mc).getTitle().getString());
		check(title.equals("g 見本.nbt"), "全画面の題が違う: " + title);
		shot(context, "redtact-live-fullscreen-1-open");
		orbit(context);
		shot(context, "redtact-live-fullscreen-2-orbit-moved");
		fly(context);
		slice(context, fullQuads);
		top(context);
		capture(context);
		close(context);

		// ---- 5,014 ブロック: 組み立ての時間とドラッグで回すときの描く時間
		measure(context, dir.resolve(ELEVATOR));

		if (System.getenv("REDTACT_LIVE_FULLSCREEN_GIF") != null) {
			gifFrames(context, dir.resolve(SHOWCASE));
		}
	}

	/**
	 * PR の動く絵の素材 (REDTACT_LIVE_FULLSCREEN_GIF=1 のとき): 回す → 高さで切る (1層・下から) → 真上 → フライ を 1 コマずつ撮る
	 * (gif-000 から。ffmpeg でつなぐ)。確かめるのは上の本物の操作で、ここはカメラを直接動かす。
	 */
	private static void gifFrames(ClientGameTestContext context, Path file) {
		LibraryPreviewChecks.selectAndWait(context, file);
		open(context);
		waitMesh(context);
		int[] n = {0};
		Runnable frame = () -> {
			context.waitTicks(1);
			shot(context, String.format("gif-%03d", n[0]++));
		};
		frame.run();

		for (int i = 0; i < 30; i++) {
			context.runOnClient(mc -> {
				screen(mc).scene().camera().orbitDrag(12, 0);
				screen(mc).scene().changed();
			});
			frame.run();
		}

		for (SliceMode mode : new SliceMode[] {SliceMode.SINGLE, SliceMode.BOTTOM_UP}) {
			for (int layer = 0; layer <= 3; layer++) {
				int l = layer;
				context.runOnClient(mc -> screen(mc).setSlice(mode, l));
				context.waitTicks(2);
				waitMesh(context);
				frame.run();
				frame.run();
				frame.run();
			}
		}

		context.runOnClient(mc -> screen(mc).setSlice(SliceMode.ALL, 0));
		context.waitTicks(2);
		waitMesh(context);
		clickWidget(context, s -> s.modeButton(ViewCamera.Mode.TOP));

		for (int i = 0; i < 8; i++) {
			context.runOnClient(mc -> {
				screen(mc).scene().camera().zoom(0.15);
				screen(mc).scene().changed();
			});
			frame.run();
		}

		clickWidget(context, s -> s.modeButton(ViewCamera.Mode.ORBIT));
		clickWidget(context, s -> s.modeButton(ViewCamera.Mode.FLY));

		for (int i = 0; i < 20; i++) {
			double turn = i < 10 ? -1.5 : 1.5;
			context.runOnClient(mc -> {
				ViewCamera c = screen(mc).scene().camera();
				c.flyMove(1, 0, 0, 0.25F);
				c.flyLook(turn, 0);
				screen(mc).scene().changed();
			});
			frame.run();
		}

		close(context);
		LOGGER.info("[fullscreen] 動く絵の素材 {} コマ", n[0]);
	}

	// ---- 開く・閉じる

	/** 保管庫のプレビューの右上の全画面のボタンを本物のクリックで押す。 */
	private static void open(ClientGameTestContext context) {
		int[] at = context.computeOnClient(mc -> center(library(mc).preview().fullscreenButton()));
		LibraryClientGameTest.clickGui(context, at[0], at[1]);
		context.waitFor(mc -> screen(mc) != null, LibraryClientGameTest.TIMEOUT);
	}

	/** 形を GPU に送り終えるまで待ち、面の数を返す。 */
	private static int waitMesh(ClientGameTestContext context) {
		context.waitFor(mc -> screen(mc) != null && screen(mc).scene().mesh() != null && !screen(mc).building(), LibraryClientGameTest.TIMEOUT);
		context.waitTicks(2);
		return context.computeOnClient(mc -> screen(mc).scene().mesh().quads());
	}

	private static void close(ClientGameTestContext context) {
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> library(mc) != null, LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> LibraryNav.selected()) != null, "保管庫に戻ったら選んだファイルが外れた");
		LOGGER.info("[fullscreen] Esc で保管庫に戻る");
	}

	// ---- 回す・寄る・動かす

	private static void orbit(ClientGameTestContext context) {
		Cam c0 = camera(context);
		float yaw0 = c0.yaw();
		float pitch0 = c0.pitch();
		float distance0 = c0.distance();
		Vector3f target0 = c0.target();
		long[] stats0 = context.computeOnClient(mc -> screen(mc).scene().takeDrawStats());
		double[] build0 = context.computeOnClient(mc -> screen(mc).lastBuild());

		// 左ドラッグで回す (右へ 80・下へ 20 の GUI の画素)
		drag(context, InputConstants.MOUSE_BUTTON_LEFT, 80, 20);
		Cam c1 = camera(context);
		check(Math.abs(c1.yaw() - wrap(yaw0 - 80 * 0.4F)) < 0.5F && c1.pitch() > pitch0, "左ドラッグで回らない: yaw " + yaw0 + " -> " + c1.yaw()
				+ " pitch " + pitch0 + " -> " + c1.pitch());
		long[] stats = context.computeOnClient(mc -> screen(mc).scene().takeDrawStats());
		double[] build1 = context.computeOnClient(mc -> screen(mc).lastBuild());
		check(stats[0] > 0, "回しても描き直していない");
		check(build1[1] == build0[1], "回しただけで形を組み立て直した");
		LOGGER.info("[fullscreen] 左ドラッグで回す: 描いた {} 回 (開いてから {} 回)", stats[0], stats0[0]);

		// ホイールで寄る
		hover(context, mc -> new int[] {screen(mc).width / 2, screen(mc).height / 2});
		context.getInput().scroll(3);
		context.waitTicks(2);
		float distance1 = camera(context).distance();
		check(distance1 < distance0 * 0.8F, "ホイールで寄らない: " + distance0 + " -> " + distance1);

		// 右ドラッグで動かす (見る点が動く)
		drag(context, InputConstants.MOUSE_BUTTON_RIGHT, 40, 0);
		Vector3f target1 = camera(context).target();
		check(target1.distance(target0) > 0.5F, "右ドラッグで動かない: " + target0 + " -> " + target1);
		LOGGER.info("[fullscreen] 回す・寄る・動かす: yaw {} -> {}・距離 {} -> {}・見る点 {} -> {}", yaw0, c1.yaw(), distance0, distance1, target0, target1);
	}

	// ---- フライ

	private static void fly(ClientGameTestContext context) {
		clickWidget(context, s -> s.modeButton(ViewCamera.Mode.FLY));
		check(camera(context).mode() == ViewCamera.Mode.FLY, "「フライ」を押してもフライにならない");
		check(!context.computeOnClient(mc -> screen(mc).flyGrabbed()), "押す前からマウスを閉じ込めている");
		Vector3f eye0 = camera(context).eye();
		float yaw0 = camera(context).yaw();

		// 押すと閉じ込める
		context.waitTicks(6); // ボタンのクリックとダブルクリックにならないように
		hover(context, mc -> new int[] {screen(mc).width / 2, screen(mc).height / 2});
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> screen(mc).flyGrabbed() && PointerCapture.active()), "押してもマウスを閉じ込めない");

		// W で前へ・Space で上へ
		context.getInput().holdKeyFor(InputConstants.KEY_W, 10);
		context.waitTicks(1);
		Vector3f eye1 = camera(context).eye();
		Vector3f forward = flat(camera(context).direction());
		Vector3f moved = new Vector3f(eye1).sub(eye0);
		check(moved.dot(forward) > 1.0F, "W で前へ進まない: " + eye0 + " -> " + eye1);
		context.getInput().holdKeyFor(InputConstants.KEY_SPACE, 6);
		context.waitTicks(1);
		Vector3f eye2 = camera(context).eye();
		check(eye2.y - eye1.y > 0.5F, "Space で上がらない: " + eye1.y + " -> " + eye2.y);

		// マウスで見回す (右へ動かすと右を向く = yaw が減る)
		for (int i = 0; i < 3; i++) {
			context.getInput().moveCursor(30, 0);
			context.waitTicks(2);
		}

		float yaw1 = camera(context).yaw();
		check(wrap(yaw0 - yaw1) > 1.0F && wrap(yaw0 - yaw1) < 180.0F, "マウスで右を向かない: " + yaw0 + " -> " + yaw1);
		shot(context, "redtact-live-fullscreen-3-fly");

		// Esc は閉じ込めだけ解く (画面は閉じない)
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		check(screen(context) != null, "フライの Esc で画面まで閉じた");
		check(!context.computeOnClient(mc -> screen(mc).flyGrabbed() || PointerCapture.active()), "Esc で閉じ込めを解かない");
		LOGGER.info("[fullscreen] フライ: W {}・Space {}・見回し yaw {} -> {}・Esc で解く", moved.dot(forward), eye2.y - eye1.y, yaw0, yaw1);
		clickWidget(context, s -> s.modeButton(ViewCamera.Mode.ORBIT));
		check(camera(context).mode() == ViewCamera.Mode.ORBIT, "「回転」で戻らない");
	}

	// ---- 高さで切る

	private static void slice(ClientGameTestContext context, int fullQuads) {
		// 全体 → 1層 (高さは 0 から)
		clickWidget(context, PreviewScreen::sliceButton);
		check(context.computeOnClient(mc -> screen(mc).slice()) == SliceMode.SINGLE, "1 回押しても 1層にならない");
		check(context.computeOnClient(mc -> screen(mc).layerSlider().visible), "1層なのに高さのスライダーが無い");
		int layer0 = waitSliced(context);
		check(layer0 > 0 && layer0 < fullQuads, "1層の面の数: " + layer0 + " (全体 " + fullQuads + ")");

		// スライダーの右端を押すと一番上の段
		int[] at = context.computeOnClient(mc -> {
			var sl = screen(mc).layerSlider();
			return new int[] {sl.getX() + sl.getWidth() - 1, sl.getY() + sl.getHeight() / 2};
		});
		LibraryClientGameTest.clickGui(context, at[0], at[1]);
		int top = context.computeOnClient(mc -> screen(mc).layer());
		int maxY = context.computeOnClient(mc -> screen(mc).shownCircuit().grid().sizeY() - 1);
		check(top == maxY, "スライダーの右端で一番上にならない: " + top + " / " + maxY);
		int layerTop = waitSliced(context);
		shot(context, "redtact-live-fullscreen-4-single-top");

		// ← で 1 段下げる (キー)
		context.getInput().pressKey(InputConstants.KEY_LEFT);
		context.waitTicks(1);
		check(context.computeOnClient(mc -> screen(mc).layer()) == maxY - 1, "← で 1 段下がらない");

		// 下から (0..高さ) → 上から (高さ..上) → 全体
		clickWidget(context, PreviewScreen::sliceButton);
		check(context.computeOnClient(mc -> screen(mc).slice()) == SliceMode.BOTTOM_UP, "2 回押しても「下から」にならない");
		int bottomUp = waitSliced(context);
		check(bottomUp > layer0 && bottomUp < fullQuads, "「下から」の面の数: " + bottomUp);
		shot(context, "redtact-live-fullscreen-5-bottom-up");
		clickWidget(context, PreviewScreen::sliceButton);
		check(context.computeOnClient(mc -> screen(mc).slice()) == SliceMode.TOP_DOWN, "3 回押しても「上から」にならない");
		int topDown = waitSliced(context);
		check(topDown > 0 && topDown < fullQuads, "「上から」の面の数: " + topDown);
		clickWidget(context, PreviewScreen::sliceButton);
		check(context.computeOnClient(mc -> screen(mc).slice()) == SliceMode.ALL, "4 回押しても全体に戻らない");
		check(!context.computeOnClient(mc -> screen(mc).layerSlider().visible), "全体なのに高さのスライダーが出ている");
		int all = waitSliced(context);
		check(all == fullQuads, "全体に戻しても面の数が戻らない: " + all + " / " + fullQuads);
		LOGGER.info("[fullscreen] 高さで切る: 1層 (0) {}・1層 (上) {}・下から {}・上から {}・全体 {}", layer0, layerTop, bottomUp, topDown, all);
	}

	/** 切り方を変えたあと、組み立て終わるまで待って面の数を返す。 */
	private static int waitSliced(ClientGameTestContext context) {
		context.waitTicks(2);
		return waitMesh(context);
	}

	// ---- 真上

	private static void top(ClientGameTestContext context) {
		clickWidget(context, s -> s.modeButton(ViewCamera.Mode.TOP));
		check(camera(context).mode() == ViewCamera.Mode.TOP, "「真上」を押しても真上にならない");
		Vector3f center0 = camera(context).topCenter();
		float span0 = camera(context).topSpan();
		shot(context, "redtact-live-fullscreen-6-top");
		drag(context, InputConstants.MOUSE_BUTTON_LEFT, 40, 30);
		Vector3f center1 = camera(context).topCenter();
		check(center1.x < center0.x && center1.z < center0.z, "真上でドラッグしても動かない: " + center0 + " -> " + center1);
		hover(context, mc -> new int[] {screen(mc).width / 2, screen(mc).height / 2});
		context.getInput().scroll(2);
		context.waitTicks(2);
		check(camera(context).topSpan() < span0, "真上でホイールを回しても寄らない");
		LOGGER.info("[fullscreen] 真上: ドラッグ {} -> {}・ホイール {} -> {}", center0, center1, span0, camera(context).topSpan());
		clickWidget(context, s -> s.modeButton(ViewCamera.Mode.ORBIT));
	}

	// ---- 撮影

	private static void capture(ClientGameTestContext context) {
		clickWidget(context, PreviewScreen::captureButton);
		context.waitFor(mc -> screen(mc).lastCapture() != null, LibraryClientGameTest.TIMEOUT);
		Path file = context.computeOnClient(mc -> screen(mc).lastCapture());

		// クリップボードにも同じ PNG (ゲームの窓の SDL で写す)
		check(context.computeOnClient(mc -> screen(mc).copiedToClipboard()), "撮影をクリップボードに写せていない");
		byte[] pasted = context.computeOnClient(mc -> ImageClipboard.pastePng());

		try {
			check(pasted != null && java.util.Arrays.equals(pasted, Files.readAllBytes(file)), "クリップボードの PNG が保存したものと違う: "
					+ (pasted == null ? "なし" : pasted.length + " バイト"));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		int[] window = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
		check(file.getParent().getFileName().toString().equals("redtact") && file.getFileName().toString().startsWith("g 見本.nbt-"),
				"撮影の置き場所: " + file);

		try (InputStream in = Files.newInputStream(file); NativeImage image = NativeImage.read(in)) {
			check(image.getWidth() == window[0] && image.getHeight() == window[1], "撮影の大きさ: " + image.getWidth() + "x" + image.getHeight());
			int transparent = 0;
			int opaque = 0;

			for (int y = 0; y < image.getHeight(); y += 4) {
				for (int x = 0; x < image.getWidth(); x += 4) {
					int a = image.getPixel(x, y) >>> 24;

					if (a == 0) {
						transparent++;
					} else if (a == 255) {
						opaque++;
					}
				}
			}

			check(image.getPixel(0, 0) >>> 24 == 0 && image.getPixel(image.getWidth() - 1, image.getHeight() - 1) >>> 24 == 0, "撮影の隅が透明でない");
			check(opaque > 100, "撮影に回路が写っていない: 不透明 " + opaque);
			LOGGER.info("[fullscreen] 撮影: {} ({}x{}・4 画素おきに透明 {}・不透明 {})", file.getFileName(), image.getWidth(), image.getHeight(), transparent, opaque);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		try {
			Files.copy(file, context.computeOnClient(mc -> mc.gameDirectory.toPath()).resolve("screenshots").resolve("redtact-live-fullscreen-7-capture.png"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// ---- 5,014 ブロックで計る

	private static void measure(ClientGameTestContext context, Path file) {
		PreviewCircuit c = LibraryPreviewChecks.selectAndWait(context, file);
		int blocks = c.model().nonAir();
		check(blocks > 4_000, "計測用の回路のブロックが少ない: " + blocks);
		open(context);
		int quads = waitMesh(context);
		double[] build = context.computeOnClient(mc -> screen(mc).lastBuild());
		shot(context, "redtact-live-fullscreen-8-elevator");
		context.runOnClient(mc -> screen(mc).scene().takeDrawStats());
		hover(context, mc -> new int[] {screen(mc).width / 2, screen(mc).height / 2});
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		long t0 = System.nanoTime();

		for (int i = 0; i < 60; i++) {
			context.getInput().moveCursor(i % 20 < 10 ? 6 : -6, 0);
			context.waitTick();
		}

		double wall = (System.nanoTime() - t0) / 1.0E6 / 60;
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		long[] stats = context.computeOnClient(mc -> screen(mc).scene().takeDrawStats());
		double avg = stats[0] == 0 ? 0 : stats[1] / 1.0E6 / stats[0];
		double max = stats[2] / 1.0E6;
		double[] buildAfter = context.computeOnClient(mc -> screen(mc).lastBuild());
		check(stats[0] >= 30, "ドラッグで描き直した回数が少ない: " + stats[0]);
		check(buildAfter[1] == build[1], "ドラッグで形を組み立て直した");
		double resortAvg = stats[3] == 0 ? 0 : stats[4] / 1.0E6 / stats[3];
		LOGGER.info(String.format("[fullscreen-bench] %d blocks: quads=%d build+upload=%.1f ms | drag 60 ticks: draws=%d avg %.2f ms max %.2f ms"
				+ " (translucent resort %d times avg %.2f ms) wall %.1f ms/tick", blocks, quads, build[1], stats[0], avg, max, stats[3], resortAvg, wall));
		close(context);
	}

	// ---- 部品

	private static PreviewScreen screen(Minecraft mc) {
		return mc.gui.screen() instanceof PreviewScreen s ? s : null;
	}

	private static PreviewScreen screen(ClientGameTestContext context) {
		return context.computeOnClient(FullscreenPreviewChecks::screen);
	}

	private static LibraryScreen library(Minecraft mc) {
		return LibraryClientGameTest.screen(mc);
	}

	/** カメラの値の写し (描画のスレッドで読む)。 */
	private record Cam(ViewCamera.Mode mode, float yaw, float pitch, float distance, Vector3f target, Vector3f topCenter, float topSpan, Vector3f eye,
			Vector3f direction) {
	}

	private static Cam camera(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			ViewCamera c = screen(mc).scene().camera();
			return new Cam(c.mode(), c.yaw(), c.pitch(), c.distance(), c.target(), c.topCenter(), c.topSpan(), c.eye(), c.direction());
		});
	}

	private static int[] center(AbstractWidget w) {
		return new int[] {w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
	}

	private static void clickWidget(ClientGameTestContext context, Function<PreviewScreen, RButton> button) {
		context.waitTicks(6); // 前のクリックとダブルクリックにならないように
		int[] at = context.computeOnClient(mc -> {
			RButton b = button.apply(screen(mc));
			check(b.visible && b.active, "ボタンが押せない: " + b.getMessage().getString());
			return center(b);
		});
		LibraryClientGameTest.clickGui(context, at[0], at[1]);
		context.waitTicks(2);
	}

	private static void hover(ClientGameTestContext context, Function<Minecraft, int[]> at) {
		double[] p = context.computeOnClient(mc -> {
			int[] xy = at.apply(mc);
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {xy[0] * scale, xy[1] * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.waitTicks(1);
	}

	/** 画面の真ん中から GUI の画素で (dx, dy) だけ、数回に分けてドラッグする。 */
	private static void drag(ClientGameTestContext context, int button, int dx, int dy) {
		context.waitTicks(6);
		hover(context, mc -> new int[] {screen(mc).width / 2, screen(mc).height / 2});
		double scale = context.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		context.getInput().holdMouse(button);
		context.waitTicks(1);
		int steps = 4;

		for (int i = 0; i < steps; i++) {
			context.getInput().moveCursor(dx * scale / steps, dy * scale / steps);
			context.waitTicks(1);
		}

		context.getInput().releaseMouse(button);
		context.waitTicks(2);
	}

	/** 撮る (カーソルを隅へ・トーストを消す)。 */
	private static void shot(ClientGameTestContext context, String name) {
		LibraryFolderChecks.cleanShot(context, name);
	}

	private static Vector3f flat(Vector3f d) {
		Vector3f v = new Vector3f(d.x, 0, d.z);
		return v.lengthSquared() < 1.0E-6F ? v : v.normalize();
	}

	private static float wrap(float deg) {
		return (deg % 360.0F + 360.0F) % 360.0F;
	}

	// ---- 回路のファイル

	private static void prepare(Path dir) {
		try {
			Files.createDirectories(dir);
			NbtIo.writeCompressed(showcase(), dir.resolve(SHOWCASE));

			try (InputStream in = FullscreenPreviewChecks.class.getResourceAsStream(ELEVATOR_FIXTURE)) {
				check(in != null, "見本が無い: " + ELEVATOR_FIXTURE);
				Files.write(dir.resolve(ELEVATOR), in.readAllBytes());
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * 9×4×9 の見本の構造物: 石の床に草・水の池・色付きガラス・ガラス・葉・レッドストーンの線 (反復装置・ランプ・トーチ)・
	 * ハーフ・階段・チェスト・頭・ベル・エンチャントテーブル・ホッパー・石レンガの柱 (高さで切ったときに段ごとに違う)。
	 */
	private static CompoundTag showcase() {
		List<String> palette = new ArrayList<>();
		Map<String, Integer> ids = new LinkedHashMap<>();
		ListTag blocks = new ListTag();
		Function<String, Integer> id = s -> ids.computeIfAbsent(s, k -> {
			palette.add(k);
			return palette.size() - 1;
		});

		for (int x = 0; x < 9; x++) {
			for (int z = 0; z < 9; z++) {
				String floor = x >= 5 && z >= 5 ? "minecraft:grass_block[snowy=false]" : x <= 2 && z >= 4 && z <= 6 ? "minecraft:water[level=0]"
						: "minecraft:stone";
				blocks.add(block(x, 0, z, id.apply(floor)));
			}
		}

		// レッドストーンの線: トーチ → 粉 → 反復装置 → 粉 → ランプ
		blocks.add(block(0, 1, 1, id.apply("minecraft:redstone_torch[lit=true]")));
		blocks.add(block(1, 1, 1, id.apply("minecraft:redstone_wire[east=side,north=none,power=15,south=none,west=side]")));
		blocks.add(block(2, 1, 1, id.apply("minecraft:redstone_wire[east=side,north=none,power=14,south=none,west=side]")));
		blocks.add(block(3, 1, 1, id.apply("minecraft:repeater[delay=2,facing=west,locked=false,powered=true]")));
		blocks.add(block(4, 1, 1, id.apply("minecraft:redstone_wire[east=side,north=none,power=15,south=none,west=side]")));
		blocks.add(block(5, 1, 1, id.apply("minecraft:redstone_lamp[lit=true]")));

		// 半透明 (色付きガラス) と切り抜き (ガラス)
		for (int x = 3; x <= 5; x++) {
			blocks.add(block(x, 1, 4, id.apply("minecraft:light_blue_stained_glass")));
			blocks.add(block(x, 2, 4, id.apply("minecraft:glass")));
		}

		// 葉 (切り抜き・色) とハーフ・階段
		blocks.add(block(6, 1, 6, id.apply("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")));
		blocks.add(block(7, 1, 6, id.apply("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")));
		blocks.add(block(6, 2, 6, id.apply("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")));
		blocks.add(block(1, 1, 7, id.apply("minecraft:oak_slab[type=bottom,waterlogged=false]")));
		blocks.add(block(2, 1, 7, id.apply("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")));

		// 形に入らないブロック (チェスト・頭) と、特別な描き方の分があるブロック (ベルの本体・エンチャントテーブルの本)。ホッパーはふつう
		blocks.add(block(7, 1, 2, id.apply("minecraft:chest[facing=west,type=single,waterlogged=false]")));
		blocks.add(block(8, 1, 2, id.apply("minecraft:skeleton_skull[powered=false,rotation=4]")));
		blocks.add(block(7, 1, 3, id.apply("minecraft:bell[attachment=floor,facing=north,powered=false]")));
		blocks.add(block(6, 1, 3, id.apply("minecraft:enchanting_table")));
		blocks.add(block(6, 1, 2, id.apply("minecraft:hopper[enabled=true,facing=down]")));

		// 石レンガの柱 (高さ 3)
		for (int y = 1; y <= 3; y++) {
			blocks.add(block(8, y, 8, id.apply("minecraft:stone_bricks")));
		}

		CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", SessionManager.dataVersion());
		root.put("size", ints(9, 4, 9));
		ListTag pal = new ListTag();

		for (String s : palette) {
			pal.add(state(s));
		}

		root.put("palette", pal);
		root.put("blocks", blocks);
		root.put("entities", new ListTag());
		return root;
	}

	/** "minecraft:name[a=b,c=d]" を構造物の palette の 1 つに。 */
	private static CompoundTag state(String s) {
		CompoundTag t = new CompoundTag();
		int open = s.indexOf('[');
		t.putString("Name", open < 0 ? s : s.substring(0, open));

		if (open >= 0) {
			CompoundTag props = new CompoundTag();

			for (String kv : s.substring(open + 1, s.length() - 1).split(",")) {
				String[] p = kv.split("=");
				props.putString(p[0], p[1]);
			}

			t.put("Properties", props);
		}

		return t;
	}

	private static CompoundTag block(int x, int y, int z, int state) {
		CompoundTag b = new CompoundTag();
		b.put("pos", ints(x, y, z));
		b.putInt("state", state);
		return b;
	}

	private static ListTag ints(int... v) {
		ListTag l = new ListTag();

		for (int i : v) {
			l.add(IntTag.valueOf(i));
		}

		return l;
	}

	/** 開催中の詳細の全画面 (LiveClientGameTest から)。プレビューの右上の全画面のボタンで開き、Esc で詳細に戻る。 */
	static void openFromLive(ClientGameTestContext context, Function<Minecraft, PreviewView> preview) {
		int[] at = context.computeOnClient(mc -> {
			RButton b = preview.apply(mc).fullscreenButton();
			check(b.visible, "開催中のプレビューに全画面のボタンが無い");
			return center(b);
		});
		LibraryClientGameTest.clickGui(context, at[0], at[1]);
		context.waitFor(mc -> screen(mc) != null, LibraryClientGameTest.TIMEOUT);
	}

	/** 開いている全画面の形の面の数 (組み立て終わるまで待つ)。 */
	static int waitLiveMesh(ClientGameTestContext context) {
		return waitMesh(context);
	}

	/** 開いている全画面の回路の版 (描いている形の元)。 */
	static int shownVersion(ClientGameTestContext context) {
		return context.computeOnClient(mc -> screen(mc) == null || screen(mc).shownCircuit() == null ? -1 : screen(mc).shownCircuit().version());
	}

	/** 全画面が from より新しい版の形を描いている (組み立て終わった)。 */
	static boolean rebuiltPast(Minecraft mc, int from) {
		PreviewScreen s = screen(mc);
		return s != null && s.shownCircuit() != null && s.shownCircuit().version() > from && !s.building() && s.scene().mesh() != null;
	}

	static void shotLive(ClientGameTestContext context, String name) {
		shot(context, name);
	}
}
