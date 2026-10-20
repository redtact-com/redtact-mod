package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.browser.BrowserSupport;
import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.cards.FileCard;
import com.redtact.live.gui.cards.FolderCard;
import com.redtact.live.library.LibraryActions;
import com.redtact.live.library.RedtactLinks;
import com.redtact.live.library.RedtactUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 保管庫タブでの schematics の下のフォルダの行き来 (#60)。本物のマウスとキーで「上へ」・フォルダの行・パス欄 (Enter) を使い、
 * ほかのフォルダのファイルの行に「配置」「redtact に投稿」が出ること、50MB を超えるファイルは投稿できないことを見る。
 * MCEF Modern が無ければ「redtact に投稿」は外のブラウザで投稿の画面とフォルダを開く (ある場合のドロップは {@link DropChecks})。
 */
final class LibraryFolderChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	static final String BUILDS = "builds";
	static final String YEAR = "2026";
	static final String SMALL = "回路2.litematic";
	static final String LARGE = "回路10.litematic";

	private LibraryFolderChecks() {
	}

	/** schematics/builds/2026/ に 2 つの回路のファイル (回路2・回路10) を置き、そのフォルダを返す。 */
	static Path prepareFiles(ClientGameTestContext context) {
		Path root = context.computeOnClient(mc -> CircuitLoader.schematicsDir());
		Path dir = root.resolve(BUILDS).resolve(YEAR);

		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(SMALL), LibraryClientGameTest.litematic("small"));
			Files.write(dir.resolve(LARGE), LibraryClientGameTest.litematic("large circuit"));
			Files.writeString(root.resolve(BUILDS).resolve("readme.txt"), "not a circuit");
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		return dir.toAbsolutePath().normalize();
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext sp, boolean litematica, boolean mcef) {
		Path dir = prepareFiles(context);
		Path root = dir.getParent().getParent();
		Path big = root.resolve(BUILDS).resolve("big.nbt");

		try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
			f.setLength(RedtactUpload.MAX_BYTES + 1); // 中身は空 (疎なファイル)
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		// 保管庫タブは保管庫 (schematics/redtact/) から
		context.runOnClient(mc -> LibraryNav.go(LibraryNav.libraryDir()));
		context.waitFor(mc -> screen(mc) != null && LibraryNav.libraryDir().equals(screen(mc).shownDir()), LibraryClientGameTest.TIMEOUT);

		// ---- 「上へ」(本物のクリック) で schematics のいちばん上。builds と redtact のフォルダが出る
		LibraryClientGameTest.clickOnTab(context, LibraryScreen::upButton);
		context.waitFor(mc -> root.equals(screen(mc).shownDir()), LibraryClientGameTest.TIMEOUT);
		List<String> top = context.computeOnClient(mc -> screen(mc).folderCards().stream().map(c -> c.item().name()).toList());
		check(top.contains(BUILDS) && top.contains("redtact"), "schematics のいちばん上のフォルダが違う: " + top);
		check(context.computeOnClient(mc -> screen(mc).upButton().disabledReason()) != null, "いちばん上なのに「上へ」が押せる");
		check(context.computeOnClient(mc -> screen(mc).pathField().getValue()).isEmpty(), "いちばん上なのにパス欄が空でない");
		cleanShot(context, "redtact-live-library-3b-schematics-root");

		// ---- フォルダの行を押すと中へ (builds)。回路でないファイルは出ず、50MB を超える .nbt は投稿できない
		LibraryClientGameTest.clickOnTab(context, s -> folder(s, BUILDS).openButton());
		context.waitFor(mc -> root.resolve(BUILDS).equals(screen(mc).shownDir()), LibraryClientGameTest.TIMEOUT);
		List<FileCard> inBuilds = context.computeOnClient(mc -> screen(mc).fileCards());
		check(inBuilds.size() == 1 && inBuilds.getFirst().item().name().equals("big.nbt"), "builds のファイルが違う: "
				+ inBuilds.stream().map(c -> c.item().name()).toList());
		String bigReason = inBuilds.getFirst().postButton().disabledReason();
		check(bigReason != null && bigReason.contains("50.0 MB"), "50MB を超えるのに投稿できる: " + bigReason);
		check("builds".equals(context.computeOnClient(mc -> screen(mc).pathField().getValue())), "パス欄が builds でない");

		// ---- パス欄 (本物のキー) に builds/2026 と打って Enter
		typePath(context, BUILDS + "/" + YEAR);
		context.waitFor(mc -> dir.equals(screen(mc).shownDir()), LibraryClientGameTest.TIMEOUT);
		List<String> names = context.computeOnClient(mc -> screen(mc).fileCards().stream().map(c -> c.item().name()).toList());
		check(names.equals(List.of(SMALL, LARGE)), "builds/2026 のファイルが違う (数の順のはず): " + names);
		FileCard small = context.computeOnClient(mc -> screen(mc).fileCards().getFirst());
		check(small.postButton() != null && small.postButton().disabledReason() == null, "投稿できるのに押せない: " + small.postButton());
		cleanShot(context, "redtact-live-library-3c-folder");

		// ---- 外へは出られない (知らせて移らない)
		typePath(context, "../..");
		context.waitTicks(5);
		Path afterUp = context.computeOnClient(mc -> screen(mc).shownDir());
		check(dir.equals(afterUp), "schematics の外へ移った: " + afterUp + " (パス欄 " + context.computeOnClient(mc -> screen(mc).pathField().getValue()) + ")");

		// ---- 配置 (本物のクリック。Litematica があるとき)
		if (litematica) {
			sp.getServer().runCommand("tp @a 3.5 -60 9.5 0 0");
			context.waitTicks(5);
			LibraryClientGameTest.clickOnTab(context, s -> s.fileCards().getFirst().placeButton());
			String key = LibraryActions.FILE_KEY_PREFIX + dir.resolve(SMALL);
			context.waitFor(mc -> LitematicaChecks.libraryPlacement(key) != null, LibraryClientGameTest.TIMEOUT);
			LitematicaChecks.LibraryPlacement p = context.computeOnClient(mc -> LitematicaChecks.libraryPlacement(key));
			check(p.origin().equals(new BlockPos(3, -60, 9)), "配置の原点が足元でない: " + p.origin());
			check(p.file().equals(dir.resolve(SMALL)), "配置のファイルが違う: " + p.file());
		} else {
			String reason = context.computeOnClient(mc -> screen(mc).fileCards().getFirst().placeButton().disabledReason());
			check(reason != null && reason.contains("Litematica"), "Litematica が無いのに配置できる: " + reason);
		}

		// ---- MCEF Modern が無ければ「redtact に投稿」は外のブラウザで投稿の画面と、ファイルのあるフォルダを開く
		if (!mcef) {
			List<String> external = new CopyOnWriteArrayList<>();
			List<Path> folders = new CopyOnWriteArrayList<>();
			context.runOnClient(mc -> {
				BrowserSupport.setExternalOpener(external::add);
				LibraryActions.setFolderOpener(folders::add);
			});

			try {
				LibraryClientGameTest.clickOnTab(context, s -> s.fileCards().getFirst().postButton());
				context.waitFor(mc -> !external.isEmpty() && !folders.isEmpty(), LibraryClientGameTest.TIMEOUT);
				String want = context.computeOnClient(mc -> RedtactLinks.newCircuitPage(BrowserSupport.home()));
				check(external.equals(List.of(want)), "外のブラウザで投稿の画面を開いていない: " + external);
				check(folders.equals(List.of(dir)), "ファイルのあるフォルダを開いていない: " + folders);

				// 「フォルダを開く」はいま見ているフォルダ
				LibraryClientGameTest.clickOnTab(context, LibraryScreen::openFolderButton);
				context.waitFor(mc -> folders.size() == 2, LibraryClientGameTest.TIMEOUT);
				check(folders.get(1).equals(dir), "「フォルダを開く」がいまのフォルダでない: " + folders);
			} finally {
				context.runOnClient(mc -> {
					BrowserSupport.setExternalOpener(null);
					LibraryActions.setFolderOpener(null);
				});
			}
		}

		// ---- パス欄で保管庫へ戻る
		typePath(context, "redtact");
		context.waitFor(mc -> LibraryNav.libraryDir().equals(screen(mc).shownDir()), LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> screen(mc).headers().getFirst()).startsWith("保管庫 schematics/redtact/"), "保管庫の見出しでない");

		try {
			Files.deleteIfExists(big);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		LOGGER.info("[library] フォルダの行き来 (上へ・フォルダ・パス欄・外へ出ない)・配置・投稿できる条件を確かめた");
	}

	/** トーストを消し、マウスを左下へ外して (ツールチップを出さない) から撮る。確かめには関わらない。 */
	static void cleanShot(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		double h = context.computeOnClient(mc -> (double) mc.getWindow().getScreenHeight());
		context.getInput().setCursorPos(2, h - 2);
		context.waitTicks(3);
		context.takeScreenshot(name);
	}

	/** パス欄を本物のクリックで選び、消して打ち直し、Enter。 */
	static void typePath(ClientGameTestContext context, String text) {
		context.waitTicks(6); // 直前のクリックとダブルクリック (単語の選択) にならないように
		LibraryClientGameTest.clickOnTab(context, LibraryScreen::pathField);
		clearAndType(context, mc -> LibraryClientGameTest.screen(mc).pathField(), text);
	}

	/**
	 * 入力欄の字を End と Backspace で消して打ち、Enter (本物のキー)。gametest のキーは修飾キーを付けないので Ctrl+A では選べない
	 * (Fabric の TestInput は KeyEvent の modifiers を 0 で送る)。
	 */
	static void clearAndType(ClientGameTestContext context, java.util.function.Function<net.minecraft.client.Minecraft, EditBox> field, String text) {
		int length = context.computeOnClient(mc -> field.apply(mc).getValue().length());
		context.getInput().pressKey(InputConstants.KEY_END);

		for (int i = 0; i < length; i++) {
			context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		}

		check(context.computeOnClient(mc -> field.apply(mc).getValue()).isEmpty(), "入力欄を消せない: " + context.computeOnClient(mc -> field.apply(mc).getValue()));
		context.getInput().typeChars(text);
		context.waitTicks(1);
		check(context.computeOnClient(mc -> field.apply(mc).getValue()).equals(text), "入力欄の字が違う: " + context.computeOnClient(mc -> field.apply(mc).getValue()));
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(3);
	}

	private static FolderCard folder(LibraryScreen s, String name) {
		return s.folderCards().stream().filter(c -> c.item().name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError("フォルダの行が無い: " + name));
	}

	private static LibraryScreen screen(net.minecraft.client.Minecraft mc) {
		return LibraryClientGameTest.screen(mc);
	}
}
