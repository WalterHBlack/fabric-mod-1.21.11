package com.example.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public final class MediaGroupScreen extends Screen {
	private static final int PANEL_WIDTH = 220;
	private static final int PANEL_HEIGHT = 98;

	private final Screen parentScreen;
	private EditBox groupCodeBox;
	private Button createButton;
	private Button joinButton;
	private String statusMessage = "";

	public MediaGroupScreen(Screen parentScreen) {
		super(Component.literal("Group"));
		this.parentScreen = parentScreen;
	}

	private void closeToParent() {
		if (minecraft != null) {
			minecraft.setScreen(parentScreen);
		}
	}

	private void openMembersPage() {
		if (minecraft != null) {
			minecraft.setScreen(new MediaGroupMembersScreen(parentScreen));
		}
	}

	@Override
	protected void init() {
		super.init();
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;

		groupCodeBox = addRenderableWidget(new EditBox(font, left + 10, top + 38, 92, 18, Component.literal("Name")));
		groupCodeBox.setMaxLength(12);
		groupCodeBox.setValue("");

		createButton = addRenderableWidget(
				Button.builder(Component.literal("Create"), button -> {
					if (MediaBridgeClient.requestGroupCreate(groupCodeBox.getValue())) {
						statusMessage = "Creating group...";
						openMembersPage();
					} else {
						statusMessage = "Type a name and join server.";
					}
						})
						.bounds(left + 108, top + 38, 48, 18)
						.build()
		);
		joinButton = addRenderableWidget(
				Button.builder(Component.literal("Join"), button -> {
					if (MediaBridgeClient.requestGroupJoin(groupCodeBox.getValue())) {
						statusMessage = "Joining group...";
						openMembersPage();
					} else {
						statusMessage = "Type a code and join server.";
					}
						})
						.bounds(left + 160, top + 38, 50, 18)
						.build()
		);
		addRenderableWidget(
				Button.builder(Component.literal("Back"), button -> closeToParent())
						.bounds(left + PANEL_WIDTH - 58, top + 64, 48, 18)
						.build()
		);
	}

	@Override
	public void tick() {
		super.tick();
		if (!MediaBridgeClient.getLocalGroupCode().isBlank()) {
			if (minecraft != null) {
				minecraft.setScreen(new MediaGroupMembersScreen(parentScreen));
			}
			return;
		}

		String code = groupCodeBox == null ? "" : groupCodeBox.getValue().trim();
		boolean hasTypedCode = !code.isEmpty();
		if (createButton != null) {
			createButton.active = hasTypedCode;
		}
		if (joinButton != null) {
			joinButton.active = hasTypedCode;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			closeToParent();
			return true;
		}
		if (groupCodeBox != null && groupCodeBox.isFocused()
				&& (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			if (MediaBridgeClient.requestGroupJoin(groupCodeBox.getValue())) {
				statusMessage = "Joining group...";
				openMembersPage();
			} else {
				statusMessage = "Type a code and join server.";
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (super.charTyped(event)) {
			return true;
		}
		return groupCodeBox != null && groupCodeBox.isFocused();
	}

	@Override
	public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		renderTransparentBackground(guiGraphics);
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;

		MediaUi.panel(guiGraphics, left, top, PANEL_WIDTH, PANEL_HEIGHT);

		guiGraphics.drawCenteredString(font, Component.literal("Group"), left + (PANEL_WIDTH / 2), top + 8, MediaUi.TITLE);
		String code = MediaBridgeClient.getLocalGroupCode();
		String status = (code == null || code.isBlank()) ? "Current: NONE" : "Current: " + code;
		guiGraphics.drawString(font, Component.literal(status), left + 10, top + 24, MediaUi.TEXT, false);
		guiGraphics.drawString(font, Component.literal("Create or join by name"), left + 10, top + 64, MediaUi.MUTED_TEXT, false);
		if (!statusMessage.isBlank()) {
			guiGraphics.drawString(font, Component.literal(statusMessage), left + 10, top + PANEL_HEIGHT - 14, MediaUi.MUTED_TEXT, false);
		}

		super.render(guiGraphics, mouseX, mouseY, delta);
	}
}
