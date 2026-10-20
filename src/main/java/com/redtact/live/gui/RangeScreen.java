package com.redtact.live.gui;

import com.redtact.live.RedtactLive;
import com.redtact.live.gui.kit.Draw;
import com.redtact.live.gui.kit.Icon;
import com.redtact.live.gui.kit.RButton;
import com.redtact.live.gui.kit.RNumberField;
import com.redtact.live.gui.kit.RTextField;
import com.redtact.live.gui.kit.Style;
import com.redtact.live.i18n.Tr;
import com.redtact.live.selection.Box;
import com.redtact.live.selection.SavedRanges;
import com.redtact.live.selection.Selection;
import com.redtact.live.session.SessionManager;
import com.redtact.live.store.CircuitRecord;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * メニューの「範囲」: 角の座標の表示と数値での編集・視線の先に合わせる・1 ブロックずつ動かす・解除・大きさと上限。
 * (Litematica の範囲エディタと同じく、± は左クリックで +1・右クリックで -1、Shift で 10・Ctrl で 100)
 * 名前を付けて保存 (セッションは開かない。同じ名前なら範囲を更新) と、選んでいる保存済みの範囲 (#39)。
 */
public final class RangeScreen extends MenuScreen {
	private static final String[] AXES = {"X", "Y", "Z"};
	private static final int FIELD_W = 46;
	private final RNumberField[][] fields = new RNumberField[2][3];
	/** 保存の名前の入力 (画面を作り直しても残す)。null か、選んでいる範囲が変わったら、選んでいる範囲の名前から始める。 */
	private static @Nullable String saveNameDraft;
	private static @Nullable String saveNameDraftFor;
	private @Nullable RTextField saveNameField;
	private int rowsY;
	private int infoY;
	/** 自分で値を入れている間は、欄を選択の値で上書きしない。 */
	private boolean refreshing;

	public RangeScreen() {
		super(MenuTab.RANGE);
	}

	private static Selection selection() {
		return RedtactLive.get().selection();
	}

	@Override
	protected void initContent() {
		rowsY = top() + 16;
		int y = rowsY;

		for (int corner = 0; corner < 2; corner++) {
			int x = MARGIN + 34;

			for (int axis = 0; axis < 3; axis++) {
				x += font.width(AXES[axis]) + 3;
				RNumberField field = addRenderableWidget(new RNumberField(font, x, y, FIELD_W, Component.literal("pos" + (corner + 1) + " " + AXES[axis])));
				field.setValue(valueText(corner, axis));
				int c = corner;
				int ax = axis;
				field.setResponder(text -> {
					if (!refreshing) {
						setCoordinate(c, ax, text);
					}
				});
				fields[corner][axis] = field;
				x += FIELD_W + 2;
				RButton nudge = addRenderableWidget(new RButton(x, y, 14, RButton.H, Component.literal("±"), RButton.Variant.SECONDARY,
						b -> nudge(c, ax, b.rightClick() ? -1 : 1)).acceptRightClick().tooltip(Tr.t("左クリックで +1・右クリックで -1 (Shift で 10・Ctrl で 100)")));
				x += nudge.getWidth() + 8;
			}

			int c = corner;
			addRenderableWidget(RButton.icon(x, y, Icon.EYE, Tr.t("視線の先へ"), RButton.Variant.SECONDARY, b -> {
				RedtactLive.get().setPosFromLook(c + 1);
				refreshFields();
			}).tooltip(Tr.t("視線の先のブロック (無ければ足元) をこの角にする")));
			y += RButton.H + 6;
		}

		int x = MARGIN;
		y += 2;
		RButton clear = addRenderableWidget(RButton.icon(x, y, Icon.CROSS, Tr.t("選択を解除"), RButton.Variant.SECONDARY, b -> {
			RedtactLive.get().clearSelection();
			refreshFields();
		}).tooltip(Tr.t("角を消す (選んだ保存済みの範囲も外す)")));
		x += clear.getWidth() + 4;
		SessionManager.Active a = RedtactLive.get().sessions().active();
		addRenderableWidget(RButton.icon(x, y, Icon.RESET, Tr.t("セッションの範囲に戻す"), RButton.Variant.SECONDARY, b -> {
			SessionManager.Active act = RedtactLive.get().sessions().active();

			if (act != null) {
				selection().set(act.region(), act.dimension());
				refreshFields();
			}
		}).disabledReason(a == null ? Tr.t("セッションを開いていません") : null));

		// 名前を付けて保存 (#39)
		String pickedId = RedtactLive.get().savedRanges().pickedId();

		if (saveNameField != null) {
			saveNameDraft = saveNameField.getValue();
			saveNameDraftFor = pickedId;
		} else if (!java.util.Objects.equals(saveNameDraftFor, pickedId)) {
			saveNameDraft = null;
		}

		y += RButton.H + 6;
		x = MARGIN + font.width(Tr.t("名前")) + 5;
		saveNameField = addRenderableWidget(new RTextField(font, x, y, 120, Component.literal(Tr.t("保存の名前"))));
		saveNameField.setMaxLength(64);
		saveNameField.setValue(saveNameDraft != null ? saveNameDraft : pickedName());
		saveNameField.hint(Tr.t("範囲の名前"));
		x += saveNameField.frameW() + 4;
		addRenderableWidget(RButton.of(font, x, y, Tr.t("範囲を保存"), RButton.Variant.PRIMARY, b -> save())
				.tooltip(Tr.t("いまの選択を名前を付けて保存する (セッションは開かない)。同じ名前があればその範囲を更新する。保存した範囲はセッションタブで選んで開始できる")));
		infoY = y + RButton.H + 8;
	}

	private static String pickedName() {
		CircuitRecord r = RedtactLive.get().savedRanges().picked();
		return r == null || r.name == null ? "" : r.name;
	}

	/** 「範囲を保存」と同じ (テストからも呼ぶ)。 */
	public void save() {
		String name = saveNameField == null ? "" : saveNameField.getValue();

		if (RedtactLive.get().saveRange(name) != null) {
			saveNameDraft = null;
			rebuildWidgets();
		}
	}

	public @Nullable RTextField saveNameField() {
		return saveNameField;
	}

	private String valueText(int corner, int axis) {
		BlockPos p = corner == 0 ? selection().pos1() : selection().pos2();
		return p == null ? "" : String.valueOf(axis == 0 ? p.getX() : axis == 1 ? p.getY() : p.getZ());
	}

	private void refreshFields() {
		refreshing = true;

		try {
			for (int c = 0; c < 2; c++) {
				for (int a = 0; a < 3; a++) {
					if (fields[c][a] != null) {
						fields[c][a].setValue(valueText(c, a));
					}
				}
			}
		} finally {
			refreshing = false;
		}
	}

	private static @Nullable String currentDimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.dimension().identifier().toString();
	}

	/** 角がまだ無ければ、もう一方の角か足元から始める。 */
	private static BlockPos base(int corner) {
		Selection s = selection();
		BlockPos p = corner == 0 ? s.pos1() : s.pos2();
		BlockPos other = corner == 0 ? s.pos2() : s.pos1();

		if (p != null) {
			return p;
		}

		if (other != null) {
			return other;
		}

		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
	}

	private static BlockPos with(BlockPos p, int axis, int value) {
		return switch (axis) {
			case 0 -> new BlockPos(value, p.getY(), p.getZ());
			case 1 -> new BlockPos(p.getX(), value, p.getZ());
			default -> new BlockPos(p.getX(), p.getY(), value);
		};
	}

	private static int get(BlockPos p, int axis) {
		return axis == 0 ? p.getX() : axis == 1 ? p.getY() : p.getZ();
	}

	private static void apply(int corner, BlockPos pos) {
		String dim = selection().dimension() != null ? selection().dimension() : currentDimension();

		if (dim == null) {
			return;
		}

		if (corner == 0) {
			selection().setPos1(pos, dim);
		} else {
			selection().setPos2(pos, dim);
		}
	}

	private void setCoordinate(int corner, int axis, String text) {
		try {
			int value = Integer.parseInt(text.trim());
			BlockPos p = base(corner);

			if (get(p, axis) != value || (corner == 0 ? selection().pos1() : selection().pos2()) == null) {
				apply(corner, with(p, axis, value));
			}
		} catch (NumberFormatException ignored) {
			// 入力途中 ("-" など) は無視する
		}
	}

	private void nudge(int corner, int axis, int sign) {
		int amount = sign;
		Minecraft mc = Minecraft.getInstance();

		if (mc.hasControlDown()) {
			amount *= 100;
		}

		if (mc.hasShiftDown()) {
			amount *= 10;
		}

		BlockPos p = base(corner);
		apply(corner, with(p, axis, get(p, axis) + amount));
		refreshFields();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Selection s = selection();
		g.text(font, Tr.f("ディメンション: {0}", s.dimension() == null ? "-" : s.dimension()), MARGIN, top() + 2, Style.MUT, false);

		for (int corner = 0; corner < 2; corner++) {
			int y = rowsY + corner * (RButton.H + 6);
			g.text(font, "pos" + (corner + 1), MARGIN, y + 4, corner == 0 ? Style.RED_TX : Style.CYAN, false);

			for (int axis = 0; axis < 3; axis++) {
				RNumberField f = fields[corner][axis];

				if (f != null) {
					g.text(font, AXES[axis], f.frameX() - font.width(AXES[axis]) - 3, y + 4, Style.MUT, false);
				}
			}
		}

		if (saveNameField != null) {
			g.text(font, Tr.t("名前"), MARGIN, saveNameField.frameY() + 4, Style.MUT, false);
		}

		// 大きさ・上限・同期する範囲・選んでいる保存済みの範囲 (板)
		int w = contentWidth();
		int h = 55;
		panel(g, MARGIN, infoY, w, h);
		int x = MARGIN + 6;
		int y = infoY + 5;
		Box box = s.box();

		if (box == null) {
			Draw.clippedText(g, font, Tr.t("大きさ: 角を 2 つ決めると出ます ([ ] キー・羽根の左右クリック・目のボタン「視線の先へ」)"), x, y, w - 12, Style.MUT, mouseX,
					mouseY);
		} else {
			String err = box.limitError();
			g.text(font, Tr.n("大きさ: {1} = {2} ブロック", box.volume(), box.sizeText(), String.format("%,d", box.volume())), x, y, Style.TXT, false);
			String limit = err == null ? Tr.f("上限の内側 (1 辺 {0}・体積 {1} まで)", Box.MAX_SIDE, String.format("%,d", Box.MAX_VOLUME)) : err;
			Draw.clippedText(g, font, limit, x, y + 11, w - 12, err == null ? Style.OK : Style.RED_TX, mouseX, mouseY);
		}

		SessionManager.Active act = RedtactLive.get().sessions().active();
		String target = act == null ? Tr.t("同期する範囲: (セッションなし)")
				: box != null ? Tr.t("同期する範囲: この選択 (次の push から)") : Tr.f("同期する範囲: セッションの範囲 {0} (次の push から)", act.region().sizeText());
		Draw.clippedText(g, font, target, x, y + 22, w - 12, Style.MUT, mouseX, mouseY);
		SavedRanges saved = RedtactLive.get().savedRanges();
		CircuitRecord picked = saved.picked();
		String pickedText = picked == null ? Tr.t("保存済みの範囲: 選んでいません (セッションタブで選ぶか、羽根を持って枠を中クリック)")
				: saved.pickedChanged() ? Tr.f("保存済みの範囲: 「{0}」を選んでいます (角を動かした。開始か保存で更新)", picked.name)
				: Tr.f("保存済みの範囲: 「{0}」を選んでいます", picked.name);
		Draw.clippedText(g, font, pickedText, x, y + 33, w - 12, picked != null && saved.pickedChanged() ? Style.LAMP : Style.MUT, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, a);
	}
}
