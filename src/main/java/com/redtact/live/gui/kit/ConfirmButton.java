package com.redtact.live.gui.kit;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * 2 回押して確かめるアイコンのボタン (#77。保存済みの範囲の削除・設定の段の一括リセット・保管庫の削除)。
 * 1 回目で赤くなり、名前 (ツールチップの先頭) が「もう一度押すと…」に変わる。{@link #CONFIRM_MS} 以内にもう一度押すと動き、
 * 過ぎたら元に戻る。
 */
public final class ConfirmButton extends RButton {
	/** 1 回目を押してから、2 回目を受ける時間。 */
	public static final long CONFIRM_MS = 3_000;
	private final Variant normal;
	private final String name;
	private final String armedName;
	private final Action confirmed;
	private long armedAt;

	private ConfirmButton(int x, int y, Icon icon, String name, String armedName, Variant variant, Action confirmed) {
		super(x, y, H, H, Component.literal(name), variant, b -> {
		});
		this.normal = variant;
		this.name = name;
		this.armedName = armedName;
		this.confirmed = confirmed;
		useIcon(icon);
	}

	/**
	 * @param name      ふだんの名前 (例「削除」)
	 * @param armedName 1 回目を押したあとの名前 (例「もう一度押すと削除」)
	 * @param confirmed 2 回目を押したときの動き
	 */
	public static ConfirmButton icon(int x, int y, Icon icon, String name, String armedName, Variant variant, Action confirmed) {
		return new ConfirmButton(x, y, icon, name, armedName, variant, confirmed);
	}

	@Override
	public ConfirmButton tooltip(@Nullable String text) {
		super.tooltip(text);
		return this;
	}

	@Override
	public ConfirmButton disabledReason(@Nullable String reason) {
		super.disabledReason(reason);
		return this;
	}

	/** 1 回目を押したあとで、2 回目を待っているか。 */
	public boolean armed() {
		return armedAt != 0 && System.currentTimeMillis() - armedAt <= CONFIRM_MS;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		if (!armed()) {
			armedAt = System.currentTimeMillis();
			variant(Variant.DANGER).label(armedName);
			return;
		}

		disarm();
		confirmed.onPress(this);
	}

	/** 待つのをやめて元に戻す。 */
	public void disarm() {
		armedAt = 0;
		variant(normal).label(name);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (armedAt != 0 && !armed()) {
			disarm(); // 時間切れ
		}

		super.extractContents(g, mouseX, mouseY, a);
	}
}
