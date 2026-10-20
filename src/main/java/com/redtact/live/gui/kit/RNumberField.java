package com.redtact.live.gui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;

/** 整数だけを入れる入力欄 (数字と先頭の - 。貼り付けた文字からも数字以外を外す)。 */
public class RNumberField extends RTextField {
	public RNumberField(Font font, int x, int y, int width, Component narration) {
		super(font, x, y, width, narration);
		setMaxLength(11);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		String s = event.codepointAsString();

		if (!s.matches("[0-9-]")) {
			return this.canConsumeInput(); // 数字以外は受けて捨てる (ゲームのキーにしない)
		}

		return super.charTyped(event);
	}

	@Override
	public void insertText(String input) {
		super.insertText(input.replaceAll("[^0-9-]", ""));
	}
}
