package com.example.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class MediaGroupMembersScreen extends Screen {
	private static final int PANEL_WIDTH = 260;
	private static final int PANEL_HEIGHT = 178;
	private static final int LIST_FILL_COLOR = 0xEE151515;
	private static final int REFRESH_TICKS = 20;

	private final Screen parentScreen;
	private int refreshCooldown = REFRESH_TICKS;
	private String statusMessage = "";
	private boolean leaveRequested;

	public MediaGroupMembersScreen(Screen parentScreen) {
		super(Component.literal("Group Members"));
		this.parentScreen = parentScreen;
	}

	private void closeToParent() {
		if (minecraft != null) {
			minecraft.setScreen(parentScreen);
		}
	}

	private void openJoinPage() {
		if (minecraft != null) {
			minecraft.setScreen(new MediaGroupScreen(parentScreen));
		}
	}

	private List<String> visibleMembers() {
		List<String> members = new ArrayList<>(MediaBridgeClient.getLocalGroupMembers());
		if (minecraft == null || minecraft.player == null) {
			return members;
		}

		String ownName = minecraft.player.getName().getString();
		if (ownName == null || ownName.isBlank()) {
			return members;
		}

		for (String member : members) {
			if (ownName.equalsIgnoreCase(member)) {
				return members;
			}
		}
		members.add(0, ownName);
		return members;
	}

	@Override
	protected void init() {
		super.init();
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;

		addRenderableWidget(
				Button.builder(Component.literal("Leave"), button -> {
							if (MediaBridgeClient.requestGroupLeave()) {
								statusMessage = "Leaving group...";
								leaveRequested = true;
							} else {
								statusMessage = "Join a server first.";
							}
						})
						.bounds(left + 8, top + PANEL_HEIGHT - 28, PANEL_WIDTH - 16, 20)
						.build()
		);

		MediaBridgeClient.requestGroupMembersRefresh();
	}

	@Override
	public void tick() {
		super.tick();
		if (MediaBridgeClient.getLocalGroupCode().isBlank()) {
			if (leaveRequested) {
				openJoinPage();
			}
			return;
		}

		refreshCooldown--;
		if (refreshCooldown <= 0) {
			MediaBridgeClient.requestGroupMembersRefresh();
			refreshCooldown = REFRESH_TICKS;
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

		String code = MediaBridgeClient.getLocalGroupCode();
		String header = code.isBlank() ? "Group" : "Group: " + code;
		guiGraphics.drawCenteredString(font, Component.literal(header), left + (PANEL_WIDTH / 2), top + 7, MediaUi.TITLE);

		int listLeft = left + 8;
		int listTop = top + 24;
		int listRight = left + PANEL_WIDTH - 8;
		int listBottom = top + PANEL_HEIGHT - 38;
		guiGraphics.fill(listLeft, listTop, listRight, listBottom, LIST_FILL_COLOR);
		MediaUi.border(guiGraphics, listLeft, listTop, listRight - listLeft, listBottom - listTop, MediaUi.PANEL_BORDER);

		List<String> members = visibleMembers();
		int listY = listTop + 10;
		int maxRows = Math.max(1, (listBottom - listTop - 20) / 12);
		if (members.isEmpty()) {
			guiGraphics.drawCenteredString(font, Component.literal("No members loaded yet."), left + (PANEL_WIDTH / 2), (listTop + listBottom) / 2 - 4, MediaUi.MUTED_TEXT);
		} else {
			for (int i = 0; i < Math.min(maxRows, members.size()); i++) {
				String line = (i + 1) + ". " + members.get(i);
				guiGraphics.drawString(font, Component.literal(line), listLeft + 10, listY + (i * 12), MediaUi.TEXT, false);
			}
			if (members.size() > maxRows) {
				int more = members.size() - maxRows;
				guiGraphics.drawString(font, Component.literal("+" + more + " more"), listLeft + 10, listY + (maxRows * 12), MediaUi.MUTED_TEXT, false);
			}
		}

		if (!statusMessage.isBlank()) {
			guiGraphics.drawString(font, Component.literal(statusMessage), left + 10, top + PANEL_HEIGHT - 36, MediaUi.MUTED_TEXT, false);
		}

		super.render(guiGraphics, mouseX, mouseY, delta);
	}
}
