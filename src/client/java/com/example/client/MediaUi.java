package com.example.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class MediaUi {
	static final int PANEL_FILL = 0xCC4A4A4A;
	static final int PANEL_BORDER = 0xFF000000;
	static final int TITLE = 0xFFE0E0E0;
	static final int TEXT = 0xFFFFFFFF;
	static final int MUTED_TEXT = 0xFFD8D8D8;
	static final int BUTTON_GREEN = 0xFF2F9A3D;
	static final int BUTTON_GREEN_HOVER = 0xFF46B454;
	static final int BUTTON_RED = 0xFFB73636;
	static final int BUTTON_RED_HOVER = 0xFFD14545;
	static final int BUTTON_DARK = 0xFF3A3A3A;
	static final int BUTTON_DARK_HOVER = 0xFF545454;

	private MediaUi() {
	}

	static void panel(GuiGraphics guiGraphics, int left, int top, int width, int height) {
		guiGraphics.fill(left, top, left + width, top + height, PANEL_FILL);
		border(guiGraphics, left, top, width, height, PANEL_BORDER);
	}

	static void border(GuiGraphics guiGraphics, int left, int top, int width, int height, int color) {
		guiGraphics.fill(left, top, left + width, top + 1, color);
		guiGraphics.fill(left, top + height - 1, left + width, top + height, color);
		guiGraphics.fill(left, top, left + 1, top + height, color);
		guiGraphics.fill(left + width - 1, top, left + width, top + height, color);
	}

	static void coloredButton(GuiGraphics guiGraphics, Font font, Button button, String label, int normalColor, int hoverColor) {
		if (button == null || !button.visible) {
			return;
		}
		int x = button.getX();
		int y = button.getY();
		int w = button.getWidth();
		int h = button.getHeight();
		int fillColor = button.isHoveredOrFocused() ? hoverColor : normalColor;
		guiGraphics.fill(x, y, x + w, y + h, fillColor);
		border(guiGraphics, x, y, w, h, PANEL_BORDER);
		guiGraphics.drawCenteredString(font, Component.literal(label), x + (w / 2), y + ((h - 8) / 2), TEXT);
	}
}
