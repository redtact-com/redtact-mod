package com.redtact.live.gametest;

import static com.redtact.live.gametest.LibraryClientGameTest.check;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mojang.blaze3d.platform.InputConstants;
import com.redtact.live.copy.CircuitLoader;
import com.redtact.live.gui.LibraryNav;
import com.redtact.live.gui.LibraryScreen;
import com.redtact.live.gui.cards.FileCard;
import com.redtact.live.gui.cards.FolderCard;
import com.redtact.live.gui.cards.LibraryCard;
import com.redtact.live.gui.cards.MenuCard;
import com.redtact.live.gui.cards.SelectableCard;
import com.redtact.live.litematic.BlockStateEntry;
import com.redtact.live.litematic.LitematicWriter;
import com.redtact.live.litematic.RegionBlocks;
import com.redtact.live.preview.PreviewCircuit;
import com.redtact.live.session.SessionManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * 保管庫タブの立体のプレビュー・検索・場所と検索の段 (#70)。schematics/previews/ に形式ごとのファイルを置き、本物のマウスで行を押すと
 * 右に描けること (大きさ・旧形式の .schematic の名前の直し・古い版の名前の直し・統合版の .mcstructure)、ヘッダの「3D」のトグルで
 * プレビューを隠すと一覧が全幅になること、↑↓ で選べること、.mcworld は理由を出すこと、ヘッダの右端の ▲ で段を隠すと
 * 一覧が上へ広がること、検索欄 (本物のキー) でいまのフォルダとその下から名前・保管庫の題で探せることを見る。
 */
final class LibraryPreviewChecks {
	private static final Logger LOGGER = LoggerFactory.getLogger("redtact-live-gametest");
	static final String FOLDER = "previews";
	static final String LITEMATIC = "a 回路.litematic";
	static final String SCHEM = "b.schem";
	static final String LEGACY = "c.schematic";
	static final String STRUCTURE = "d.nbt";
	static final String WORLD = "e.mcworld";
	/** 統合版 26.52 の BDS が書いた版 2 の .mcstructure (mc-nbt-converter の見本。単体テストと同じもの)。 */
	static final String BEDROCK = "f 統合版.mcstructure";
	private static final String BEDROCK_FIXTURE = "/structure/bds-26.52-v2-small.mcstructure";

	private LibraryPreviewChecks() {
	}

	static void run(ClientGameTestContext context) {
		Path root = context.computeOnClient(mc -> CircuitLoader.schematicsDir()).toAbsolutePath().normalize();
		Path dir = root.resolve(FOLDER);
		prepare(dir);
		context.runOnClient(mc -> {
			LibraryNav.setQuery("");
			LibraryNav.select(null);
			LibraryNav.go(dir);
		});
		context.waitFor(mc -> screen(mc) != null && dir.equals(screen(mc).shownDir()) && screen(mc).fileCards().size() == 6, LibraryClientGameTest.TIMEOUT);
		String empty = context.computeOnClient(mc -> screen(mc).preview().message());
		check(empty != null && empty.contains("押すと"), "選ぶ前のプレビューの案内が違う: " + empty);

		// ---- 1. 行を押すと右に立体 (形式ごと)
		PreviewCircuit lit = selectAndWait(context, dir.resolve(LITEMATIC));
		check(lit.model().sizeX() == 5 && lit.model().sizeY() == 2 && lit.model().sizeZ() == 3, "litematic の大きさが違う");
		check(lit.unknown() == 0, "litematic に知らないブロックがある: " + lit.unknown());
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5a-preview");

		PreviewCircuit schem = selectAndWait(context, dir.resolve(SCHEM));
		check(schem.model().sizeX() == 2 && schem.model().sizeZ() == 2 && schem.model().nonAir() == 3, "schem の中身が違う: " + schem.model().nonAir());

		PreviewCircuit legacy = selectAndWait(context, dir.resolve(LEGACY));
		check(legacy.model().palette().contains("minecraft:granite"), "旧形式の .schematic の名前を直していない: " + legacy.model().palette());

		PreviewCircuit structure = selectAndWait(context, dir.resolve(STRUCTURE));
		check(structure.model().nonAir() == 2, "nbt のブロックの数が違う: " + structure.model().nonAir());
		check(structure.model().palette().stream().anyMatch(s -> s.startsWith("minecraft:short_grass")), "古い版の grass を short_grass に直していない: "
				+ structure.model().palette());

		PreviewCircuit bedrock = selectAndWait(context, dir.resolve(BEDROCK));
		check(bedrock.unknown() == 0 && bedrock.model().nonAir() > 0, "統合版の .mcstructure を描けない: 知らないブロック " + bedrock.unknown());
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5e-bedrock");

		// ---- 1b. 止まった絵と右上の回すボタン (#93)
		PreviewButtonChecks.library(context, dir.resolve(LITEMATIC));

		clickCard(context, dir.resolve(WORLD));
		context.waitTicks(3);
		String world = context.computeOnClient(mc -> screen(mc).preview().message());
		check(world != null && world.contains(".mcworld"), ".mcworld の理由が出ていない: " + world);

		// ---- 2. ↑↓ で選ぶ行を動かす (行を押したあとのまま。一覧が選ばれている)
		clickCard(context, dir.resolve(LITEMATIC));
		context.getInput().pressKey(InputConstants.KEY_DOWN);
		context.waitTicks(2);
		check(dir.resolve(SCHEM).equals(context.computeOnClient(mc -> LibraryNav.selected())), "↓ で次の行に移らない: " + context.computeOnClient(mc -> LibraryNav.selected()));
		context.getInput().pressKey(InputConstants.KEY_UP);
		context.waitTicks(2);
		check(dir.resolve(LITEMATIC).equals(context.computeOnClient(mc -> LibraryNav.selected())), "↑ で前の行に戻らない");

		// ---- 3. ヘッダの右端の ▲ で場所と検索の段を隠すと一覧が上へ広がり、▼ で戻る
		int shownTop = context.computeOnClient(mc -> screen(mc).list().getY());
		check(context.computeOnClient(mc -> screen(mc).searchField() != null && screen(mc).subBarToggle() != null), "段か ▲ が無い");
		LibraryClientGameTest.clickSubBarToggle(context);
		context.waitFor(mc -> screen(mc) != null && screen(mc).pathField() == null, LibraryClientGameTest.TIMEOUT);
		int hiddenTop = context.computeOnClient(mc -> screen(mc).list().getY());
		check(hiddenTop < shownTop, "段を隠しても一覧が上へ広がらない: " + shownTop + " → " + hiddenTop);
		check(context.computeOnClient(mc -> screen(mc).searchField() == null && screen(mc).upButton() == null), "段を隠しても検索欄・上へが残る");
		check(context.computeOnClient(mc -> screen(mc).preview().shownCircuit() != null), "段を隠したらプレビューが消えた");
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5b-bar-hidden");
		LibraryClientGameTest.clickSubBarToggle(context);
		context.waitFor(mc -> screen(mc) != null && screen(mc).pathField() != null, LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> screen(mc).list().getY()) == shownTop, "段を出し直しても一覧の位置が戻らない");

		// ---- 3b. 段の ▲ の左の「3D」のトグルでプレビューを隠すと一覧が全幅になり、もう一度押すと選んだファイルの立体が戻る
		int splitW = context.computeOnClient(mc -> screen(mc).list().getWidth());
		int[] at = context.computeOnClient(mc -> {
			LibraryScreen s = screen(mc);
			var t = s.headerToggle(LibraryScreen.PREVIEW_TOGGLE);
			check(t != null, "プレビューのトグルが無い");
			return new int[] {t.getX(), t.getWidth(), s.subBarToggle().getX()};
		});
		check(at[0] + at[1] <= at[2] && at[2] - (at[0] + at[1]) <= 4, "プレビューのトグルが ▲ のすぐ左にない: " + at[0] + "+" + at[1] + " / ▲ " + at[2]);
		LibraryClientGameTest.clickHeaderToggle(context, LibraryScreen.PREVIEW_TOGGLE);
		context.waitFor(mc -> screen(mc) != null && screen(mc).preview() == null, LibraryClientGameTest.TIMEOUT);
		int fullW = context.computeOnClient(mc -> screen(mc).list().getWidth());
		int contentW = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth() - 16);
		check(fullW == contentW && fullW > splitW, "プレビューを隠しても一覧が全幅にならない: " + splitW + " → " + fullW + " (中身 " + contentW + ")");
		check(context.computeOnClient(mc -> !LibraryNav.previewShown()), "隠したことを覚えていない");
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5b2-preview-hidden");
		clickCard(context, dir.resolve(SCHEM)); // 隠したままでも行は選べる (描かない)
		check(dir.resolve(SCHEM).equals(context.computeOnClient(mc -> LibraryNav.selected())), "隠したままでは行を選べない");
		clickCard(context, dir.resolve(LITEMATIC));
		LibraryClientGameTest.clickHeaderToggle(context, LibraryScreen.PREVIEW_TOGGLE);
		context.waitFor(mc -> screen(mc) != null && screen(mc).preview() != null && screen(mc).preview().shownCircuit() != null,
				LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> screen(mc).list().getWidth()) == splitW, "プレビューを出し直しても一覧の幅が戻らない");
		check(context.computeOnClient(mc -> screen(mc).preview().shownCircuit().model().sizeX()) == 5, "出し直したプレビューが選んだファイルでない");

		// ---- 4. 検索欄 (本物のキー): schematics のいちばん上から、下のフォルダのファイルを場所付きで
		context.runOnClient(mc -> LibraryNav.go(root));
		context.waitFor(mc -> root.equals(screen(mc).shownDir()), LibraryClientGameTest.TIMEOUT);
		typeSearch(context, "回路");
		context.waitFor(mc -> screen(mc).showingSearch() && names(screen(mc)).contains(LibraryFolderChecks.SMALL), LibraryClientGameTest.TIMEOUT);
		List<String> found = context.computeOnClient(mc -> names(screen(mc)));
		check(found.containsAll(List.of(LibraryFolderChecks.SMALL, LibraryFolderChecks.LARGE, LITEMATIC)), "検索で下のフォルダのファイルが出ない: " + found);
		String where = context.computeOnClient(mc -> screen(mc).fileCards().stream().filter(c -> c.item().name().equals(LibraryFolderChecks.SMALL))
				.findFirst().map(FileCard::location).orElse(null));
		check((LibraryFolderChecks.BUILDS + "/" + LibraryFolderChecks.YEAR + "/").equals(where), "検索の結果に場所が出ていない: " + where);
		// 検索の結果からも選べる
		PreviewCircuit fromSearch = selectAndWait(context, dir.resolve(LITEMATIC));
		check(fromSearch.model().sizeX() == 5, "検索の結果から選んだプレビューが違う");
		LibraryFolderChecks.cleanShot(context, "redtact-live-library-5c-search");

		// 保管庫の回路は redtact の題でも引ける
		context.runOnClient(mc -> LibraryClientGameTest.save("zz.litematic", LibraryClientGameTest.CIRCUIT_ID + "/zz.litematic",
				LibraryClientGameTest.litematic("zz"), "題で探す回路"));
		typeSearch(context, "題で探す");
		context.waitFor(mc -> screen(mc).showingSearch() && screen(mc).libraryCards().size() == 1, LibraryClientGameTest.TIMEOUT);

		// 見つからない字
		typeSearch(context, "どこにもない名前");
		context.waitFor(mc -> screen(mc).showingSearch() && screen(mc).headers().getFirst().contains("見つかりません"), LibraryClientGameTest.TIMEOUT);

		// 検索の結果のフォルダを押すと中へ入り、検索をやめる
		typeSearch(context, FOLDER);
		context.waitFor(mc -> screen(mc).showingSearch() && screen(mc).folderCards().stream().anyMatch(c -> c.item().name().equals(FOLDER)),
				LibraryClientGameTest.TIMEOUT);
		LibraryClientGameTest.clickOnTab(context, s -> folder(s, FOLDER).openButton());
		context.waitFor(mc -> dir.equals(screen(mc).shownDir()) && !screen(mc).showingSearch(), LibraryClientGameTest.TIMEOUT);
		check(context.computeOnClient(mc -> screen(mc).searchField().getValue()).isEmpty(), "フォルダを開いても検索欄が空にならない");
		check(context.computeOnClient(mc -> screen(mc).fileCards().size()) == 6, "検索をやめたあとの一覧が違う");

		// ---- 5. 1080p (GUI 4 = 480×270) は左右に、いちばん狭い画面 (320×240。マイクラは GUI をこれより小さくしない) はプレビューを一覧の下に置く
		clickCard(context, dir.resolve(LITEMATIC));
		sizes(context);

		// 後の確かめのために保管庫へ戻し、題で探すために入れた行を消す (McefChecks は保管庫の行が取ってきた 1 件だけだと見る)
		context.runOnClient(mc -> {
			LibraryNav.select(null);
			LibraryNav.go(LibraryNav.libraryDir());

			try {
				LibraryClientGameTest.library().remove("zz.litematic");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		LOGGER.info("[library] 立体のプレビュー (litematic・schem・旧形式の schematic・nbt・統合版の mcstructure)・↑↓・段を隠す・プレビューを隠す・検索 (下のフォルダ・題・フォルダを開く) を確かめた");
	}

	/** 窓と GUI の倍率を変えて並びを見て撮り、元に戻す。 */
	private static void sizes(ClientGameTestContext context) {
		int[] orig = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});

		try {
			for (int[] size : new int[][] {{1920, 1080, 4}, {960, 720, 3}}) {
				context.getInput().resizeWindow(size[0], size[1]);
				context.runOnClient(mc -> {
					mc.options.guiScale().set(size[2]);
					mc.resizeGui();
				});
				context.waitTicks(5);
				context.waitFor(mc -> screen(mc) != null && screen(mc).preview() != null && screen(mc).preview().shownCircuit() != null, LibraryClientGameTest.TIMEOUT);
				String tag = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
				int[] r = context.computeOnClient(mc -> {
					LibraryScreen s = screen(mc);
					return new int[] {s.list().getX(), s.list().getRight(), s.list().getBottom(), s.preview().getX(), s.preview().getY(),
							s.preview().getY() + s.preview().getHeight(), mc.getWindow().getGuiScaledHeight()};
				});
				LOGGER.info("[library] {}: 一覧 x={}..{} 下端 {}・プレビュー x={} y={}..{}", tag, r[0], r[1], r[2], r[3], r[4], r[5]);
				check(r[5] <= r[6], tag + " でプレビューが画面の下にはみ出す");

				if (size[2] == 4) {
					check(r[3] > r[1], tag + " でプレビューが一覧の右にない");
					// ファイルの行の投稿は字を「投稿」だけにしてファイル名の幅を空け、名前はツールチップの先頭 (#77)
					String bad = context.computeOnClient(mc -> screen(mc).fileCards().stream()
							.map(c -> c.postButton())
							.filter(b -> b != null && !(b.getMessage().getString().equals(LibraryCard.POST_LABEL) && b.tooltipText() != null
									&& b.tooltipText().startsWith(LibraryCard.POST_NAME + "\n")))
							.map(b -> b.getMessage().getString() + " / " + b.tooltipText())
							.findFirst().orElse(null));
					check(bad == null, "ファイルの行の投稿の字と名前: " + bad);
					check(context.computeOnClient(mc -> screen(mc).fileCards().stream().anyMatch(c -> c.postButton() != null)), "投稿を出すファイルの行が無い");
				} else {
					check(r[4] > r[2], tag + " でプレビューが一覧の下にない");
				}

				LibraryFolderChecks.cleanShot(context, "redtact-live-library-5d-" + tag);
			}
		} finally {
			context.getInput().resizeWindow(orig[0], orig[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(orig[2]);
				mc.resizeGui();
			});
			context.waitTicks(5);
		}
	}

	/** 行 (本物のクリック) を押して選び、描けるまで待つ。 */
	static PreviewCircuit selectAndWait(ClientGameTestContext context, Path file) {
		clickCard(context, file);
		check(file.equals(context.computeOnClient(mc -> LibraryNav.selected())), "押した行が選ばれていない: " + file.getFileName());
		context.waitFor(mc -> screen(mc).preview().shownCircuit() != null || screen(mc).preview().message() != null
				&& !screen(mc).preview().message().startsWith("読み込み中"), LibraryClientGameTest.TIMEOUT);
		PreviewCircuit c = context.computeOnClient(mc -> screen(mc).preview().shownCircuit());
		check(c != null, file.getFileName() + " を描けない: " + context.computeOnClient(mc -> screen(mc).preview().message()));
		return c;
	}

	/** その行の題のあたり (ボタンの無いところ) を本物のマウスで押す。見えていなければ一覧を送ってから。 */
	private static void clickCard(ClientGameTestContext context, Path file) {
		context.waitTicks(6); // 前のクリックとダブルクリックにならないように
		context.runOnClient(mc -> {
			SelectableCard c = card(screen(mc), file);

			if (!visible(screen(mc), c)) {
				screen(mc).list().scrollToTop(c);
			}
		});
		context.waitTicks(2);
		double[] p = context.computeOnClient(mc -> {
			SelectableCard c = card(screen(mc), file);
			check(visible(screen(mc), c), "行が見えていない: " + file.getFileName());
			return new double[] {c.getX() + 20, c.getY() + c.height() / 2.0};
		});
		LibraryClientGameTest.clickGui(context, p[0], p[1]);
	}

	private static SelectableCard card(LibraryScreen s, Path file) {
		for (MenuCard c : s.list().cards()) {
			if (c instanceof SelectableCard sc && sc.file().equals(file)) {
				return sc;
			}
		}

		throw new AssertionError("行が無い: " + file.getFileName());
	}

	private static boolean visible(LibraryScreen s, MenuCard c) {
		return c.getY() >= s.list().getY() && c.getY() + c.height() <= s.list().getBottom();
	}

	private static void typeSearch(ClientGameTestContext context, String text) {
		context.waitTicks(6);
		LibraryClientGameTest.clickOnTab(context, LibraryScreen::searchField);
		LibraryFolderChecks.clearAndType(context, mc -> screen(mc).searchField(), text);
	}

	private static List<String> names(LibraryScreen s) {
		return s.fileCards().stream().map(c -> c.item().name()).toList();
	}

	private static FolderCard folder(LibraryScreen s, String name) {
		return s.folderCards().stream().filter(c -> c.item().name().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError("フォルダの行が無い: " + name));
	}

	private static LibraryScreen screen(Minecraft mc) {
		return LibraryClientGameTest.screen(mc);
	}

	// ---- 形式ごとのファイル

	private static void prepare(Path dir) {
		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(LITEMATIC), circuitLitematic());
			NbtIo.writeCompressed(spongeV2(), dir.resolve(SCHEM));
			NbtIo.writeCompressed(legacySchematic(), dir.resolve(LEGACY));
			NbtIo.writeCompressed(structureNbt(), dir.resolve(STRUCTURE));
			Files.write(dir.resolve(WORLD), new byte[] {'P', 'K', 3, 4});

			try (InputStream in = LibraryPreviewChecks.class.getResourceAsStream(BEDROCK_FIXTURE)) {
				check(in != null, "見本が無い: " + BEDROCK_FIXTURE);
				Files.write(dir.resolve(BEDROCK), in.readAllBytes());
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** 5×2×3: 石の床にリピーター・導線・トーチ・ランプ。 */
	private static byte[] circuitLitematic() {
		Map<String, String> none = new TreeMap<>();
		RegionBlocks.Builder b = new RegionBlocks.Builder(5, 2, 3);

		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 3; z++) {
				b.set(x, 0, z, BlockStateEntry.of("minecraft:smooth_stone", none));
			}
		}

		b.set(0, 1, 1, BlockStateEntry.of("minecraft:redstone_torch", Map.of("lit", "true")));
		b.set(1, 1, 1, BlockStateEntry.of("minecraft:redstone_wire", Map.of("power", "15", "east", "side", "west", "side", "north", "none", "south", "none")));
		b.set(2, 1, 1, BlockStateEntry.of("minecraft:repeater", Map.of("facing", "west", "delay", "2", "locked", "false", "powered", "true")));
		b.set(3, 1, 1, BlockStateEntry.of("minecraft:redstone_wire", Map.of("power", "15", "east", "side", "west", "side", "north", "none", "south", "none")));
		b.set(4, 1, 1, BlockStateEntry.of("minecraft:redstone_lamp", Map.of("lit", "true")));
		return LitematicWriter.toBytes(b.build(), List.of(), new LitematicWriter.Metadata("preview", "gametest", "#70", SessionManager.dataVersion(), 0L));
	}

	/** Sponge の版 2 の 2×1×2 (石・金・空気・レッドストーンブロック)。 */
	private static CompoundTag spongeV2() {
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:air", 0);
		palette.putInt("minecraft:stone", 1);
		palette.putInt("minecraft:gold_block", 2);
		palette.putInt("minecraft:redstone_block", 3);
		CompoundTag root = new CompoundTag();
		root.putInt("Version", 2);
		root.putInt("DataVersion", 3953);
		root.putShort("Width", (short) 2);
		root.putShort("Height", (short) 1);
		root.putShort("Length", (short) 2);
		root.put("Palette", palette);
		root.putByteArray("BlockData", new byte[] {1, 2, 0, 3});
		return root;
	}

	/** MCEdit の旧形式の 3×1×1 (石・花崗岩・導線)。 */
	private static CompoundTag legacySchematic() {
		CompoundTag root = new CompoundTag();
		root.putShort("Width", (short) 3);
		root.putShort("Height", (short) 1);
		root.putShort("Length", (short) 1);
		root.putString("Materials", "Alpha");
		root.putByteArray("Blocks", new byte[] {1, 1, 55});
		root.putByteArray("Data", new byte[] {0, 1, 0});
		return root;
	}

	/** 構造ブロックの .nbt (1.20.1 の版) の 2×2×1。草 (grass → short_grass に直る) と土。 */
	private static CompoundTag structureNbt() {
		CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", 3465);
		root.put("size", ints(2, 2, 1));
		ListTag palette = new ListTag();
		CompoundTag dirt = new CompoundTag();
		dirt.putString("Name", "minecraft:dirt");
		CompoundTag grass = new CompoundTag();
		grass.putString("Name", "minecraft:grass");
		palette.add(dirt);
		palette.add(grass);
		root.put("palette", palette);
		ListTag blocks = new ListTag();
		blocks.add(block(0, 0, 0, 0));
		blocks.add(block(0, 1, 0, 1));
		root.put("blocks", blocks);
		return root;
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
}
