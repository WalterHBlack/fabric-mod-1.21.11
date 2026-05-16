package com.example.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public final class MediaShareControlScreen extends Screen {
	private static final int PANEL_WIDTH = 200;
	private static final int PANEL_HEIGHT = 80;
	private static final int BUTTON_WIDTH = 58;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 5;

	private final Screen parentScreen;
	private Button shareButton;
	private Button spatialButton;
	private Button groupButton;

	public MediaShareControlScreen(Screen parentScreen) {
		super(Component.literal("Media Share"));
		this.parentScreen = parentScreen;
	}

	private Component shareLabel() {
		return Component.literal(MediaBridgeClient.isLocalShareEnabled() ? "S:ON" : "S:OFF");
	}

	private Component spatialLabel() {
		return Component.literal(MediaBridgeClient.isSpatialAudioEnabled() ? "3D:ON" : "3D:OFF");
	}

	private void closeToParent() {
		if (minecraft != null) {
			minecraft.setScreen(parentScreen);
		}
	}

	@Override
	protected void init() {
		super.init();
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;
		int rowY = top + 30;

		shareButton = addRenderableWidget(
				Button.builder(shareLabel(), button -> {
							boolean enabled = MediaBridgeClient.requestShareToggle();
							ExampleModClient.showActionMessage(minecraft, "Media share " + (enabled ? "ON" : "OFF"));
						})
						.bounds(left + 6, rowY, BUTTON_WIDTH, BUTTON_HEIGHT)
						.build()
		);
		spatialButton = addRenderableWidget(
				Button.builder(spatialLabel(), button -> MediaBridgeClient.toggleSpatialAudio())
						.bounds(left + 6 + BUTTON_WIDTH + BUTTON_GAP, rowY, BUTTON_WIDTH, BUTTON_HEIGHT)
						.build()
		);
		groupButton = addRenderableWidget(
				Button.builder(Component.literal("Group"), button -> {
							if (minecraft != null) {
								if (MediaBridgeClient.getLocalGroupCode().isBlank()) {
									minecraft.setScreen(new MediaGroupScreen(this));
								} else {
									minecraft.setScreen(new MediaGroupMembersScreen(this));
								}
							}
						})
						.bounds(left + 6 + (BUTTON_WIDTH + BUTTON_GAP) * 2, rowY, BUTTON_WIDTH, BUTTON_HEIGHT)
						.build()
		);
		addRenderableWidget(
				Button.builder(Component.literal("X"), button -> closeToParent())
						.bounds(left + PANEL_WIDTH - 22, top + 6, 16, 16)
						.build()
		);
	}

	@Override
	public void tick() {
		super.tick();
		if (shareButton != null) {
			shareButton.setMessage(shareLabel());
		}
		if (spatialButton != null) {
			spatialButton.setMessage(spatialLabel());
		}
		if (groupButton != null) {
			String code = MediaBridgeClient.getLocalGroupCode();
			if (code == null || code.isBlank()) {
				groupButton.setMessage(Component.literal("Group"));
			} else {
				String shortCode = code.length() > 4 ? code.substring(0, 4) : code;
				groupButton.setMessage(Component.literal("G:" + shortCode));
			}
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			closeToParent();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		renderTransparentBackground(guiGraphics);
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;

		MediaUi.panel(guiGraphics, left, top, PANEL_WIDTH, PANEL_HEIGHT);
		guiGraphics.drawCenteredString(font, Component.literal("Media Controls"), left + (PANEL_WIDTH / 2), top + 9, MediaUi.TITLE);

		super.render(guiGraphics, mouseX, mouseY, delta);
	}
}
