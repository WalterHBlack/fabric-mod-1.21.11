package com.example.client;

import com.cinemamod.mcef.MCEF;
import com.example.ExampleMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ExampleModClient implements ClientModInitializer {
	private static final KeyMapping OPEN_BROWSER_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.modid.open_browser", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KeyMapping.Category.MISC)
	);
	private static final KeyMapping PREVIOUS_VIDEO_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.modid.previous_video", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KeyMapping.Category.MISC)
	);
	private static final KeyMapping PLAY_PAUSE_VIDEO_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.modid.play_pause_video", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC)
	);
	private static final KeyMapping NEXT_VIDEO_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.modid.next_video", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, KeyMapping.Category.MISC)
	);
	private static final KeyMapping CLOSE_BACKGROUND_BROWSER_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.modid.close_background_browser", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KeyMapping.Category.MISC)
	);
	private static final KeyMapping TOGGLE_MEDIA_SHARE_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.modid.toggle_media_share", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC)
	);
	private static boolean previousMediaDown;
	private static boolean playPauseMediaDown;
	private static boolean nextMediaDown;
	private static boolean openBrowserDown;
	private static boolean closeBrowserDown;
	private static boolean toggleMediaShareDown;
	private static int pendingMouseRestoreTicks;
	private static final int MENU_TAB_WIDTH = 52;
	private static final int MENU_TAB_HEIGHT = 20;
	private static final int MENU_TAB_OPTION_WIDTH = 84;
	private static final int MENU_TAB_OPTION_HEIGHT = 18;
	private static final int MENU_TAB_MARGIN = 6;
	private static final int MENU_TAB_SPACING = 3;
	private static final String WIDEVINE_DLL_NAME = "widevinecdm.dll";
	private static final String WIDEVINE_HINT_FILE_NAME = "latest-component-updated-widevine-cdm";

	public static void showActionMessage(Minecraft client, String message) {
		if (client != null && client.player != null && message != null && !message.isBlank()) {
			client.player.displayClientMessage(Component.literal(message), true);
		}
	}

	public static boolean triggerSystemMediaAction(String action) {
		if (action == null || action.isBlank()) {
			return false;
		}
		int keyCode = switch (action) {
			case "previous" -> 0xB1; // VK_MEDIA_PREV_TRACK
			case "playpause" -> 0xB3; // VK_MEDIA_PLAY_PAUSE
			case "next" -> 0xB0; // VK_MEDIA_NEXT_TRACK
			default -> -1;
		};
		if (keyCode < 0) {
			return false;
		}

		try {
			java.awt.Robot robot = new java.awt.Robot();
			robot.keyPress(keyCode);
			robot.keyRelease(keyCode);
			return true;
		} catch (Throwable throwable) {
			ExampleMod.LOGGER.warn("Failed to trigger system media action '{}': {}", action, throwable.toString());
			return false;
		}
	}

	public static void restoreInGameMouse(Minecraft client) {
		if (client == null || client.level == null || client.screen != null) {
			return;
		}
		GLFW.glfwSetInputMode(client.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
		client.mouseHandler.grabMouse();
	}

	public static void requestInGameMouseRestore(Minecraft client) {
		pendingMouseRestoreTicks = 2;
	}

	private static void openShareControl(Minecraft client) {
		if (client.screen instanceof MediaShareControlScreen) {
			return;
		}
		client.setScreen(new MediaShareControlScreen(client.screen));
	}

	private static void openBrowser(Minecraft client, boolean openedFromMenu, String targetUrl) {
		if (!MCEF.isInitialized()) {
			ExampleMod.LOGGER.warn("MCEF is not initialized yet. Wait for Chromium download to finish and try again.");
			return;
		}

		if (client.screen instanceof YouTubeBrowserScreen) {
			if (targetUrl != null && !targetUrl.isBlank()) {
				YouTubeBrowserScreen.loadSharedUrl(targetUrl);
			}
			return;
		}

		client.setScreen(new YouTubeBrowserScreen(openedFromMenu, targetUrl));
	}

	private static void closeBackgroundBrowser(Minecraft client) {
		boolean hadBrowser = YouTubeBrowserScreen.hasManagedBrowserForSync();
		if (client.screen instanceof YouTubeBrowserScreen) {
			client.setScreen(null);
		}
		YouTubeBrowserScreen.shutdownSharedBrowser();
		requestInGameMouseRestore(client);
		showActionMessage(client, hadBrowser ? "Background browser closed" : "No background browser");
	}

	private static boolean isBindingDown(Minecraft client, KeyMapping keyMapping) {
		if (keyMapping.isUnbound()) {
			return false;
		}

		InputConstants.Key boundKey = InputConstants.getKey(keyMapping.saveString());
		if (boundKey == InputConstants.UNKNOWN) {
			return false;
		}

		if (boundKey.getType() == InputConstants.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(client.getWindow().handle(), boundKey.getValue()) == GLFW.GLFW_PRESS;
		}

		return InputConstants.isKeyDown(client.getWindow(), boundKey.getValue());
	}

	private static boolean isPreviousPressed(Minecraft client) {
		return PREVIOUS_VIDEO_KEY.isDown() || isBindingDown(client, PREVIOUS_VIDEO_KEY);
	}

	private static boolean isPlayPausePressed(Minecraft client) {
		return PLAY_PAUSE_VIDEO_KEY.isDown() || isBindingDown(client, PLAY_PAUSE_VIDEO_KEY);
	}

	private static boolean isNextPressed(Minecraft client) {
		return NEXT_VIDEO_KEY.isDown() || isBindingDown(client, NEXT_VIDEO_KEY);
	}

	private static boolean isOpenBrowserPressed(Minecraft client) {
		return OPEN_BROWSER_KEY.isDown() || isBindingDown(client, OPEN_BROWSER_KEY);
	}

	private static boolean isCloseBrowserPressed(Minecraft client) {
		return CLOSE_BACKGROUND_BROWSER_KEY.isDown() || isBindingDown(client, CLOSE_BACKGROUND_BROWSER_KEY);
	}

	private static boolean isToggleMediaSharePressed(Minecraft client) {
		return TOGGLE_MEDIA_SHARE_KEY.isDown() || isBindingDown(client, TOGGLE_MEDIA_SHARE_KEY);
	}

	public static boolean isMediaControlKey(KeyEvent event) {
		return PREVIOUS_VIDEO_KEY.matches(event)
				|| PLAY_PAUSE_VIDEO_KEY.matches(event)
				|| NEXT_VIDEO_KEY.matches(event);
	}

	public static boolean triggerMediaActionFromEvent(KeyEvent event) {
		if (PREVIOUS_VIDEO_KEY.matches(event)) {
			YouTubeBrowserScreen.previousVideo();
			return true;
		}
		if (PLAY_PAUSE_VIDEO_KEY.matches(event)) {
			YouTubeBrowserScreen.playPauseVideo();
			return true;
		}
		if (NEXT_VIDEO_KEY.matches(event)) {
			YouTubeBrowserScreen.nextVideo();
			return true;
		}
		return false;
	}

	private static void processMediaHotkeys(Minecraft client, boolean enabled) {
		boolean previousPressed = isPreviousPressed(client);
		boolean playPausePressed = isPlayPausePressed(client);
		boolean nextPressed = isNextPressed(client);
		boolean previousClicked = PREVIOUS_VIDEO_KEY.consumeClick();
		boolean playPauseClicked = PLAY_PAUSE_VIDEO_KEY.consumeClick();
		boolean nextClicked = NEXT_VIDEO_KEY.consumeClick();

		if (enabled && (previousClicked || (previousPressed && !previousMediaDown))) {
			YouTubeBrowserScreen.previousVideo();
		}
		if (enabled && (playPauseClicked || (playPausePressed && !playPauseMediaDown))) {
			YouTubeBrowserScreen.playPauseVideo();
		}
		if (enabled && (nextClicked || (nextPressed && !nextMediaDown))) {
			YouTubeBrowserScreen.nextVideo();
		}

		previousMediaDown = previousPressed;
		playPauseMediaDown = playPausePressed;
		nextMediaDown = nextPressed;
	}

	private static void processBrowserHotkeys(Minecraft client) {
		boolean openPressed = isOpenBrowserPressed(client);
		boolean closePressed = isCloseBrowserPressed(client);
		boolean openClicked = OPEN_BROWSER_KEY.consumeClick();
		boolean closeClicked = CLOSE_BACKGROUND_BROWSER_KEY.consumeClick();
		boolean allowOpenBrowserHotkey = client.screen == null || client.screen instanceof TitleScreen;
		boolean allowCloseBrowserHotkey = client.screen == null || client.screen instanceof TitleScreen;

		if (allowOpenBrowserHotkey && (openClicked || (openPressed && !openBrowserDown))) {
			openBrowser(client, client.level == null, null);
		}
		if (allowCloseBrowserHotkey && (closeClicked || (closePressed && !closeBrowserDown))) {
			closeBackgroundBrowser(client);
		}

		openBrowserDown = openPressed;
		closeBrowserDown = closePressed;
	}

	private static void processShareHotkeys(Minecraft client) {
		boolean togglePressed = isToggleMediaSharePressed(client);
		boolean toggleClicked = TOGGLE_MEDIA_SHARE_KEY.consumeClick();
		boolean allowShareHotkey = client.level != null && client.screen == null;

		if (allowShareHotkey && (toggleClicked || (togglePressed && !toggleMediaShareDown))) {
			openShareControl(client);
		}

		toggleMediaShareDown = togglePressed;
	}

	private static void tickMouseRestore(Minecraft client) {
		if (pendingMouseRestoreTicks <= 0) {
			return;
		}
		pendingMouseRestoreTicks--;
		if (pendingMouseRestoreTicks == 0) {
			restoreInGameMouse(client);
		}
	}

	private static Path resolveLocalAppData() {
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData == null || localAppData.isBlank()) {
			return null;
		}
		try {
			return Path.of(localAppData);
		} catch (Exception exception) {
			return null;
		}
	}

	private static Path resolveMcefWidevineRoot() {
		Path localAppData = resolveLocalAppData();
		if (localAppData == null) {
			return null;
		}
		return localAppData.resolve("MCEF").resolve("cef-cache").resolve("WidevineCdm");
	}

	private static boolean hasWidevineDll(Path root) {
		if (root == null || !Files.isDirectory(root)) {
			return false;
		}
		try (Stream<Path> stream = Files.find(root, 8, (path, attrs) ->
				attrs.isRegularFile() && WIDEVINE_DLL_NAME.equalsIgnoreCase(path.getFileName().toString()))) {
			return stream.findFirst().isPresent();
		} catch (IOException exception) {
			return false;
		}
	}

	private static int parseVersionToken(String token) {
		try {
			return Integer.parseInt(token);
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private static int compareVersionNames(String left, String right) {
		String[] leftParts = left.split("\\.");
		String[] rightParts = right.split("\\.");
		int max = Math.max(leftParts.length, rightParts.length);
		for (int i = 0; i < max; i++) {
			int leftValue = i < leftParts.length ? parseVersionToken(leftParts[i]) : 0;
			int rightValue = i < rightParts.length ? parseVersionToken(rightParts[i]) : 0;
			if (leftValue != rightValue) {
				return Integer.compare(leftValue, rightValue);
			}
		}
		return left.compareTo(right);
	}

	private static boolean looksLikeVersionDirectory(Path directory) {
		String name = directory.getFileName().toString();
		return name.matches("[0-9]+(\\.[0-9]+)+");
	}

	private static boolean isWidevinePayloadDir(Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return false;
		}
		Path manifest = directory.resolve("manifest.json");
		Path platformSpecific = directory.resolve("_platform_specific");
		return Files.isRegularFile(manifest) && Files.isDirectory(platformSpecific);
	}

	private static Path findWidevineVersionDirectory(Path widevineRoot) {
		if (widevineRoot == null || !Files.isDirectory(widevineRoot)) {
			return null;
		}

		List<Path> candidates = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(widevineRoot)) {
			for (Path entry : stream) {
				if (!Files.isDirectory(entry) || !looksLikeVersionDirectory(entry)) {
					continue;
				}
				if (isWidevinePayloadDir(entry)) {
					candidates.add(entry);
				}
			}
		} catch (IOException ignored) {
			return null;
		}

		candidates.sort((a, b) -> compareVersionNames(
				b.getFileName().toString(),
				a.getFileName().toString()
		));
		return candidates.isEmpty() ? null : candidates.getFirst();
	}

	private static List<Path> collectBrowserApplicationDirectories() {
		List<Path> directories = new ArrayList<>();
		String programFiles = System.getenv("ProgramFiles");
		String programFilesX86 = System.getenv("ProgramFiles(x86)");
		Path localAppData = resolveLocalAppData();

		if (programFiles != null && !programFiles.isBlank()) {
			directories.add(Path.of(programFiles, "Google", "Chrome", "Application"));
			directories.add(Path.of(programFiles, "Microsoft", "Edge", "Application"));
		}
		if (programFilesX86 != null && !programFilesX86.isBlank()) {
			directories.add(Path.of(programFilesX86, "Google", "Chrome", "Application"));
			directories.add(Path.of(programFilesX86, "Microsoft", "Edge", "Application"));
			directories.add(Path.of(programFilesX86, "Microsoft", "EdgeCore"));
			directories.add(Path.of(programFilesX86, "Microsoft", "EdgeWebView", "Application"));
		}
		if (localAppData != null) {
			directories.add(localAppData.resolve("Google").resolve("Chrome").resolve("Application"));
			directories.add(localAppData.resolve("Microsoft").resolve("Edge").resolve("Application"));
			directories.add(localAppData.resolve("ModrinthApp").resolve("EBWebView").resolve("WidevineCdm"));
			directories.add(localAppData.resolve("Microsoft").resolve("EdgeWebView").resolve("WidevineCdm"));
		}
		if (programFiles != null && !programFiles.isBlank()) {
			directories.add(Path.of(programFiles, "Common Files", "Adobe", "Microsoft", "EdgeWebView", "WidevineCdm"));
		}
		return directories;
	}

	private static Path findSystemWidevineVersionDirectory() {
		for (Path applicationDirectory : collectBrowserApplicationDirectories()) {
			if (applicationDirectory == null || !Files.isDirectory(applicationDirectory)) {
				continue;
			}
			Path directVersionPayload = findWidevineVersionDirectory(applicationDirectory);
			if (directVersionPayload != null) {
				return directVersionPayload;
			}
			if (isWidevinePayloadDir(applicationDirectory)) {
				return applicationDirectory;
			}
			try (DirectoryStream<Path> versions = Files.newDirectoryStream(applicationDirectory)) {
				List<Path> versionDirectories = new ArrayList<>();
				for (Path versionDir : versions) {
					if (!Files.isDirectory(versionDir) || !looksLikeVersionDirectory(versionDir)) {
						continue;
					}
					versionDirectories.add(versionDir);
				}
				versionDirectories.sort((a, b) -> compareVersionNames(
						b.getFileName().toString(),
						a.getFileName().toString()
				));
				for (Path versionDirectory : versionDirectories) {
					Path widevineRoot = versionDirectory.resolve("WidevineCdm");
					Path payload = findWidevineVersionDirectory(widevineRoot);
					if (payload != null) {
						return payload;
					}
					// Some Chromium distributions store payload files directly under WidevineCdm.
					if (isWidevinePayloadDir(widevineRoot)) {
						return widevineRoot;
					}
				}
			} catch (IOException ignored) {
			}
		}
		return null;
	}

	private static void copyWidevineDirectory(Path source, Path target) throws IOException {
		Files.createDirectories(target);
		try (Stream<Path> stream = Files.walk(source)) {
			stream.forEach(path -> {
				try {
					Path relative = source.relativize(path);
					Path destination = target.resolve(relative.toString());
					if (Files.isDirectory(path)) {
						Files.createDirectories(destination);
					} else {
						Files.createDirectories(destination.getParent());
						Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					}
				} catch (IOException exception) {
					throw new RuntimeException(exception);
				}
			});
		} catch (RuntimeException runtimeException) {
			if (runtimeException.getCause() instanceof IOException ioException) {
				throw ioException;
			}
			throw runtimeException;
		}
	}

	private static void writeWidevineHintFile(Path widevineRoot, Path versionDirectory) {
		if (widevineRoot == null || versionDirectory == null) {
			return;
		}
		Path hintFile = widevineRoot.resolve(WIDEVINE_HINT_FILE_NAME);
		String normalizedPath = versionDirectory.toAbsolutePath().toString().replace("\\", "\\\\");
		String json = "{\"Path\":\"" + normalizedPath + "\"}";
		try {
			Files.createDirectories(widevineRoot);
			Files.writeString(hintFile, json, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			ExampleMod.LOGGER.warn("MCEF: failed to write Widevine hint file: {}", exception.toString());
		}
	}

	private static void bootstrapWidevineForMcef() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			return;
		}
		Path targetRoot = resolveMcefWidevineRoot();
		if (targetRoot == null) {
			return;
		}
		Path sourceVersionDirectory = findSystemWidevineVersionDirectory();
		if (sourceVersionDirectory == null) {
			ExampleMod.LOGGER.warn("MCEF: no system Widevine installation was found (Chrome/Edge).");
			return;
		}
		Path existingVersionDirectory = findWidevineVersionDirectory(targetRoot);
		String sourceVersion = sourceVersionDirectory.getFileName().toString();
		String existingVersion = existingVersionDirectory == null ? "" : existingVersionDirectory.getFileName().toString();
		boolean shouldCopyFromSystem = existingVersionDirectory == null
				|| compareVersionNames(existingVersion, sourceVersion) < 0
				|| !hasWidevineDll(existingVersionDirectory);

		Path targetVersionDirectory = targetRoot.resolve(sourceVersion);
		Path selectedVersionDirectory = existingVersionDirectory;
		try {
			if (shouldCopyFromSystem) {
				copyWidevineDirectory(sourceVersionDirectory, targetVersionDirectory);
				selectedVersionDirectory = targetVersionDirectory;
				ExampleMod.LOGGER.info("MCEF: updated Widevine CDM from {} to {}",
						sourceVersionDirectory.toAbsolutePath(),
						targetVersionDirectory.toAbsolutePath());
			} else {
				ExampleMod.LOGGER.info("MCEF: keeping existing Widevine CDM version {} (system version: {})",
						existingVersion,
						sourceVersion);
			}
			if (selectedVersionDirectory != null) {
				writeWidevineHintFile(targetRoot, selectedVersionDirectory);
			}
		} catch (IOException exception) {
			ExampleMod.LOGGER.warn("MCEF: failed to bootstrap Widevine CDM: {}", exception.toString());
		}
	}

	private static void applyMcefPlaybackCompatibilitySettings() {
		try {
			bootstrapWidevineForMcef();
		} catch (Exception exception) {
			ExampleMod.LOGGER.warn("Failed to apply MCEF playback compatibility settings: {}", exception.toString());
		}
	}

	@Override
	public void onInitializeClient() {
		applyMcefPlaybackCompatibilitySettings();
		YouTubeBrowserScreen.loadSavedSession();
		MediaBridgeClient.initialize();

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof TitleScreen)) {
				return;
			}

			int tabY = MENU_TAB_MARGIN;
			int tabX = scaledWidth - MENU_TAB_WIDTH - MENU_TAB_MARGIN;
			int optionX = tabX + (MENU_TAB_WIDTH - MENU_TAB_OPTION_WIDTH);
			boolean[] dropdownOpen = {false};

			Button openYouTubeTab = Button.builder(Component.literal("Youtube"), button -> {
						openBrowser(client, true, YouTubeBrowserScreen.getYoutubeUrl());
						dropdownOpen[0] = false;
					})
					.bounds(optionX, tabY + MENU_TAB_HEIGHT + MENU_TAB_SPACING, MENU_TAB_OPTION_WIDTH, MENU_TAB_OPTION_HEIGHT)
					.build();
			openYouTubeTab.visible = false;

			Button openMusicTab = Button.builder(Component.literal("Yt Music"), button -> {
						openBrowser(client, true, YouTubeBrowserScreen.getYouTubeMusicUrl());
						dropdownOpen[0] = false;
					})
					.bounds(optionX, tabY + MENU_TAB_HEIGHT + MENU_TAB_SPACING + MENU_TAB_OPTION_HEIGHT + 1, MENU_TAB_OPTION_WIDTH, MENU_TAB_OPTION_HEIGHT)
					.build();
			openMusicTab.visible = false;

			Button openSpotifyTab = Button.builder(Component.literal("Spotify"), button -> {
						openBrowser(client, true, YouTubeBrowserScreen.getSpotifyUrl());
						dropdownOpen[0] = false;
					})
					.bounds(optionX, tabY + MENU_TAB_HEIGHT + MENU_TAB_SPACING + (MENU_TAB_OPTION_HEIGHT + 1) * 2, MENU_TAB_OPTION_WIDTH, MENU_TAB_OPTION_HEIGHT)
					.build();
			openSpotifyTab.visible = false;

			Button openAppleMusicTab = Button.builder(Component.literal("Apple Music"), button -> {
						openBrowser(client, true, YouTubeBrowserScreen.getAppleMusicUrl());
						dropdownOpen[0] = false;
					})
					.bounds(optionX, tabY + MENU_TAB_HEIGHT + MENU_TAB_SPACING + (MENU_TAB_OPTION_HEIGHT + 1) * 3, MENU_TAB_OPTION_WIDTH, MENU_TAB_OPTION_HEIGHT)
					.build();
			openAppleMusicTab.visible = false;

			Button mediaTab = Button.builder(Component.literal("Media"), button -> {
						dropdownOpen[0] = !dropdownOpen[0];
						boolean visible = dropdownOpen[0];
						openYouTubeTab.visible = visible;
						openMusicTab.visible = visible;
						openSpotifyTab.visible = visible;
						openAppleMusicTab.visible = visible;
					})
					.bounds(tabX, tabY, MENU_TAB_WIDTH, MENU_TAB_HEIGHT)
					.build();

			Screens.getButtons(screen).add(mediaTab);
			Screens.getButtons(screen).add(openYouTubeTab);
			Screens.getButtons(screen).add(openMusicTab);
			Screens.getButtons(screen).add(openSpotifyTab);
			Screens.getButtons(screen).add(openAppleMusicTab);

			ScreenEvents.afterTick(screen).register(currentScreen -> {
				if (!dropdownOpen[0]) {
					openYouTubeTab.visible = false;
					openMusicTab.visible = false;
					openSpotifyTab.visible = false;
					openAppleMusicTab.visible = false;
				}
			});
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			YouTubeBrowserScreen.tickBackgroundPlayback();
			MediaBridgeClient.tick(client);
			processBrowserHotkeys(client);
			processShareHotkeys(client);
			tickMouseRestore(client);

			boolean hotkeysEnabled = client.screen == null || client.level == null;
			if (client.screen instanceof YouTubeBrowserScreen browserScreen) {
				hotkeysEnabled = browserScreen.canHandleMediaHotkeys();
			}
			processMediaHotkeys(client, hotkeysEnabled);
		});
		HudRenderCallback.EVENT.register((guiGraphics, tickCounter) -> {
			Minecraft client = Minecraft.getInstance();
			YouTubeBrowserScreen.renderPopupOverlay(guiGraphics, client);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			MediaBridgeClient.shutdown();
			YouTubeBrowserScreen.destroySharedBrowser();
		});
	}
}
