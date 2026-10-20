package com.redtact.live.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.redtact.live.RedtactLive;
import com.redtact.live.gui.Menu;
import com.redtact.live.gui.MenuTab;
import com.redtact.live.i18n.Tr;
import com.redtact.live.store.CircuitRecord;
import com.redtact.live.ui.Messages;

import net.minecraft.commands.SharedSuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * {@code /rlive ...} (クライアントコマンド)。結果はチャットに出さず、メニュー・トースト・アクションバーへ。
 */
public final class RliveCommand {
	private RliveCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, RedtactLive mod) {
		dispatcher.register(literal("rlive")
				.executes(ctx -> openMenu(MenuTab.SESSION))
				.then(literal("help").executes(ctx -> openMenu(MenuTab.SETTINGS)))
				.then(literal("menu").executes(ctx -> {
					Menu.openLater(Menu::open);
					return 1;
				}))
				.then(literal("pos1").executes(ctx -> {
					mod.setPosFromLook(1);
					return 1;
				}))
				.then(literal("pos2").executes(ctx -> {
					mod.setPosFromLook(2);
					return 1;
				}))
				.then(literal("sel")
						.executes(ctx -> openMenu(MenuTab.RANGE))
						.then(literal("clear").executes(ctx -> {
							mod.clearSelection();
							Messages.info(Tr.t("選択を解除しました"));
							return 1;
						}))
						// いまの選択を名前を付けて保存する (セッションは開かない。#39)
						.then(literal("save")
								.then(argument("name", StringArgumentType.greedyString())
										.suggests((ctx, b) -> SharedSuggestionProvider.suggest(mod.sessions().circuitNames(), b))
										.executes(ctx -> mod.saveRange(StringArgumentType.getString(ctx, "name")) != null ? 1 : 0))))
				.then(literal("open")
						.then(argument("name", StringArgumentType.greedyString())
								.suggests((ctx, b) -> SharedSuggestionProvider.suggest(mod.sessions().circuitNames(), b))
								.executes(ctx -> {
									mod.sessions().open(StringArgumentType.getString(ctx, "name"));
									return 1;
								})))
				// 開催中の回路を .litematic で取ってきて Litematica に読み込む (無ければ保存だけ)
				.then(literal("load")
						.executes(ctx -> openMenu(MenuTab.LIVE_LIST))
						.then(argument("link", StringArgumentType.greedyString())
								.suggests((ctx, b) -> SharedSuggestionProvider.suggest(
										mod.liveDirectory().state().sessions().stream().map(s -> s.sessionId).toList(), b))
								.executes(ctx -> {
									mod.circuitLoader().load(StringArgumentType.getString(ctx, "link"));
									return 1;
								})))
				// 分岐 (#13): /rlive branch <リンクかID> で準備、start [名前] で開始、cancel でやめる
				.then(literal("branch")
						.executes(ctx -> openMenu(MenuTab.SESSION))
						.then(literal("start")
								.executes(ctx -> {
									mod.branches().start(null, () -> false, mod.config()::defaultAllowCopy);
									return 1;
								})
								.then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
									mod.branches().start(StringArgumentType.getString(ctx, "name"), () -> false, mod.config()::defaultAllowCopy);
									return 1;
								})))
						.then(literal("cancel").executes(ctx -> {
							mod.branches().cancel();
							return 1;
						}))
						.then(argument("link", StringArgumentType.greedyString())
								.suggests((ctx, b) -> SharedSuggestionProvider.suggest(
										mod.liveDirectory().state().sessions().stream().map(s -> s.sessionId).toList(), b))
								.executes(ctx -> {
									mod.branches().prepare(StringArgumentType.getString(ctx, "link"));
									return 1;
								})))
				.then(literal("close").executes(ctx -> {
					mod.sessions().close();
					return 1;
				}))
				.then(literal("push").executes(ctx -> {
					mod.sessions().push();
					return 1;
				}))
				.then(literal("auto")
						.executes(ctx -> openMenu(MenuTab.SETTINGS))
						.then(literal("on").executes(ctx -> {
							mod.setAutoSync(true);
							return 1;
						}))
						.then(literal("off").executes(ctx -> {
							mod.setAutoSync(false);
							return 1;
						})))
				.then(literal("status").executes(ctx -> openMenu(MenuTab.SESSION)))
				.then(literal("list").executes(ctx -> openMenu(MenuTab.SESSION)))
				.then(literal("link").executes(ctx -> {
					mod.sessions().copyShareLink();
					return 1;
				}))
				.then(literal("comments")
						.executes(ctx -> {
							Menu.openLater(() -> Menu.openComments(null));
							return 1;
						})
						.then(argument("name", StringArgumentType.greedyString())
								.suggests((ctx, b) -> SharedSuggestionProvider.suggest(mod.sessions().circuitNames(), b))
								.executes(ctx -> {
									String name = StringArgumentType.getString(ctx, "name").trim();
									String id = mod.sessions().listCircuits().stream()
											.filter(r -> name.equals(r.name))
											.map((CircuitRecord r) -> r.circuitId)
											.findFirst()
											.orElse(null);

									if (id == null) {
										Messages.error(Tr.f("回路「{0}」はありません", name));
										return 0;
									}

									Menu.openLater(() -> Menu.openComments(id));
									return 1;
								}))));
	}

	/** コマンドを打ったチャット画面が閉じてから開く。 */
	private static int openMenu(MenuTab tab) {
		Menu.openLater(() -> Menu.open(tab));
		return 1;
	}
}
