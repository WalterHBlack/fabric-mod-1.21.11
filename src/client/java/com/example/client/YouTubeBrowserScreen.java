package com.example.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class YouTubeBrowserScreen extends Screen {
	private static final int FRAME_MARGIN = 20;
	private static final int NAV_BAR_HEIGHT = 20;
	private static final int NAV_BAR_GAP = 6;
	private static final int NAV_BUTTON_WIDTH = 24;
	private static final int MEDIA_BUTTON_WIDTH = 48;
	private static final int MEDIA_OPTION_WIDTH = 84;
	private static final int MEDIA_OPTION_HEIGHT = 18;
	private static final int MENU_BUTTON_WIDTH = 52;
	private static final int POPUP_BUTTON_WIDTH = 34;
	private static final int POPUP_DEFAULT_WIDTH = 360;
	private static final int POPUP_DEFAULT_HEIGHT = 203;
	private static final int POPUP_MIN_WIDTH = 220;
	private static final int POPUP_MAX_WIDTH = 620;
	private static final float POPUP_ASPECT_RATIO = 16.0F / 9.0F;
	private static final int NAV_SPACING = 4;
	private static final String YOUTUBE_URL = "https://www.youtube.com";
	private static final String YOUTUBE_MUSIC_URL = "https://music.youtube.com";
	private static final String SPOTIFY_URL = "https://open.spotify.com";
	private static final String APPLE_MUSIC_URL = "https://music.apple.com";
	private static final String DEFAULT_URL = YOUTUBE_URL;
	private static final String BLANK_URL = "about:blank";
	private static final int BACKGROUND_KEEP_ALIVE_INTERVAL_TICKS = 40;
	private static final int BACKGROUND_LOW_POWER_WIDTH = 480;
	private static final int BACKGROUND_LOW_POWER_HEIGHT = 270;
	private static final long MEDIA_CHANGE_POPUP_DURATION_MS = 3500L;
	private static final int MEDIA_CHANGE_POPUP_WIDTH = 292;
	private static final int MEDIA_CHANGE_POPUP_HEIGHT = 58;
	private static final HttpClient MEDIA_META_HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static MCEFBrowser sharedBrowser;
	private static String lastClosedUrl = DEFAULT_URL;
	private static boolean sharedBrowserSoftClosed;
	private static int backgroundTickCounter;
	private static boolean popupModeEnabled;
	private static int popupX = Integer.MIN_VALUE;
	private static int popupY = Integer.MIN_VALUE;
	private static int popupWidth = POPUP_DEFAULT_WIDTH;
	private static int popupHeight = POPUP_DEFAULT_HEIGHT;
	private static int popupBrowserPixelWidth = -1;
	private static int popupBrowserPixelHeight = -1;
	private static boolean mediaTrackPrimed;
	private static String lastMediaTrackKey = "";
	private static String mediaChangePopupServiceLine = "";
	private static String mediaChangePopupTitleLine = "";
	private static final Map<String, String> mediaTitleCache = new HashMap<>();
	private static long mediaChangePopupHideAtMs;
	private static long spotifyCompatLastInjectMs;
	private static boolean backgroundLowPowerApplied;
	private static int backgroundLowPowerPixelWidth = -1;
	private static int backgroundLowPowerPixelHeight = -1;
	private static String lastSecureNavigationUrl = "";
	private static boolean secureErrorRetryDone;
	private static long secureErrorFirstSeenAtMs;
	private static boolean googleRejectedAutoHandled;
	private static boolean sessionLoaded;

	private final boolean showMainMenuButton;
	private final String launchUrl;
	private MCEFBrowser browser;
	private EditBox urlBox;
	private Button closeButton;
	private Button backButton;
	private Button forwardButton;
	private Button mediaDropdownButton;
	private Button mediaYouTubeButton;
	private Button mediaMusicButton;
	private Button mediaSpotifyButton;
	private Button mediaAppleMusicButton;
	private Button popupModeButton;
	private Button chooseYouTubeButton;
	private Button chooseMusicButton;
	private Button chooseSpotifyButton;
	private Button chooseAppleMusicButton;
	private Button spotifyWarningContinueButton;
	private Button spotifyWarningDontShowAgainButton;
	private Button spotifyWarningCancelButton;
	private boolean mediaDropdownOpen;
	private boolean servicePickerVisible;
	private boolean spotifyWarningVisible;
	private static boolean spotifyWarningSuppressed;
	private String spotifyWarningTargetUrl;
	private int spotifyCompatForegroundTicker;

	public YouTubeBrowserScreen() {
		this(false, null);
	}

	public YouTubeBrowserScreen(boolean showMainMenuButton) {
		this(showMainMenuButton, null);
	}

	public YouTubeBrowserScreen(boolean showMainMenuButton, String launchUrl) {
		super(Component.literal("Media Browser"));
		this.showMainMenuButton = showMainMenuButton;
		this.launchUrl = launchUrl;
	}

	@Override
	protected void init() {
		super.init();
		if (!MCEF.isInitialized()) {
			onClose();
			return;
		}
		boolean reopenedFromSoftClose = sharedBrowserSoftClosed || sharedBrowser == null;
		boolean showSpotifyWarningOnInit = false;
		browser = getOrCreateSharedBrowser();
		if (browser == null) {
			onClose();
			return;
		}
		if (launchUrl != null && !launchUrl.isBlank()) {
			String targetUrl = normalizeServiceUrl(launchUrl);
			if (isSpotifyUrl(targetUrl) && !spotifyWarningSuppressed) {
				spotifyWarningTargetUrl = targetUrl;
				showSpotifyWarningOnInit = true;
			} else {
				browser.loadURL(targetUrl);
				lastClosedUrl = targetUrl;
				saveSession();
			}
			servicePickerVisible = false;
		} else if (reopenedFromSoftClose) {
			browser.loadURL(BLANK_URL);
			servicePickerVisible = true;
		} else {
			servicePickerVisible = false;
		}
		initNavigationWidgets();
		resizeBrowser();
		refreshNavigationState();
		if (showSpotifyWarningOnInit) {
			setSpotifyWarningVisible(true, spotifyWarningTargetUrl);
		}
	}

	public static String getYoutubeUrl() {
		return YOUTUBE_URL;
	}

	public static String getYouTubeMusicUrl() {
		return YOUTUBE_MUSIC_URL;
	}

	public static String getSpotifyUrl() {
		return SPOTIFY_URL;
	}

	public static String getAppleMusicUrl() {
		return APPLE_MUSIC_URL;
	}

	public static boolean hasManagedBrowserForSync() {
		return hasManagedBrowser();
	}

	public static String getManagedBrowserUrl() {
		if (!hasManagedBrowser()) {
			return null;
		}
		return sharedBrowser.getURL();
	}

	public static void loadSharedUrl(String url) {
		if (!hasManagedBrowser() || url == null || url.isBlank()) {
			return;
		}
		url = normalizeServiceUrl(url);
		recordSecureNavigationTarget(url);
		sharedBrowser.loadURL(url);
		persistLastUrlIfValid(url);
		sharedBrowser.setFocus(true);
	}

	private void navigateToServiceInternal(String url) {
		if (browser == null || url == null || url.isBlank()) {
			return;
		}
		url = normalizeServiceUrl(url);
		recordSecureNavigationTarget(url);
		browser.loadURL(url);
		persistLastUrlIfValid(url);
		servicePickerVisible = false;
		setServicePickerVisible(false);
		setMediaDropdownVisible(false);
		if (urlBox != null) {
			urlBox.setValue(url);
		}
		browser.setFocus(true);
	}

	private void navigateToService(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		String normalized = normalizeServiceUrl(url);
		if (isSpotifyUrl(normalized) && !spotifyWarningSuppressed) {
			setSpotifyWarningVisible(true, normalized);
			return;
		}
		navigateToServiceInternal(normalized);
	}

	private static int getPopupMinHeight() {
		return Math.max(124, Math.round(POPUP_MIN_WIDTH / POPUP_ASPECT_RATIO));
	}

	private static int getPopupMaxHeight() {
		return Math.max(200, Math.round(POPUP_MAX_WIDTH / POPUP_ASPECT_RATIO));
	}

	private static void clampPopupLayoutToScreen(Minecraft client) {
		if (client == null) {
			return;
		}

		int screenWidth = client.getWindow().getGuiScaledWidth();
		int screenHeight = client.getWindow().getGuiScaledHeight();
		int minWidth = Math.min(POPUP_MIN_WIDTH, Math.max(120, screenWidth));
		int maxWidth = Math.min(POPUP_MAX_WIDTH, Math.max(minWidth, screenWidth));
		int minHeight = Math.min(getPopupMinHeight(), Math.max(68, screenHeight));
		int maxHeight = Math.min(getPopupMaxHeight(), Math.max(minHeight, screenHeight));

		popupWidth = Math.max(minWidth, Math.min(popupWidth, maxWidth));
		popupHeight = Math.max(minHeight, Math.min(Math.round(popupWidth / POPUP_ASPECT_RATIO), maxHeight));
		popupWidth = Math.max(minWidth, Math.min(Math.round(popupHeight * POPUP_ASPECT_RATIO), maxWidth));

		int minX = 0;
		int minY = 0;
		int maxX = Math.max(minX, screenWidth - popupWidth);
		int maxY = Math.max(minY, screenHeight - popupHeight);
		popupX = Math.max(minX, Math.min(popupX, maxX));
		popupY = Math.max(minY, Math.min(popupY, maxY));
	}

	private static void loadSessionIfNeeded() {
		if (sessionLoaded) {
			return;
		}
		sessionLoaded = true;
		MediaSessionStore.Session session = MediaSessionStore.load();
		if (shouldPersistLastUrl(session.lastUrl)) {
			lastClosedUrl = session.lastUrl;
		}
		spotifyWarningSuppressed = session.spotifyWarningSuppressed;
	}

	private static void saveSession() {
		MediaSessionStore.Session session = new MediaSessionStore.Session();
		session.lastUrl = lastClosedUrl == null || lastClosedUrl.isBlank() ? DEFAULT_URL : lastClosedUrl;
		session.spotifyWarningSuppressed = spotifyWarningSuppressed;
		MediaSessionStore.save(session);
	}

	public static void loadSavedSession() {
		loadSessionIfNeeded();
	}

	private static void ensurePopupLayoutInitialized(Minecraft client) {
		if (client == null) {
			return;
		}
		loadSessionIfNeeded();
		if (popupX != Integer.MIN_VALUE && popupY != Integer.MIN_VALUE) {
			clampPopupLayoutToScreen(client);
			saveSession();
			return;
		}

		popupWidth = POPUP_DEFAULT_WIDTH;
		popupHeight = POPUP_DEFAULT_HEIGHT;
		int screenWidth = client.getWindow().getGuiScaledWidth();
		int screenHeight = client.getWindow().getGuiScaledHeight();
		popupX = screenWidth - popupWidth;
		popupY = screenHeight - popupHeight;
		clampPopupLayoutToScreen(client);
		saveSession();
	}

	private static void resizeSharedBrowserForPopup(Minecraft client) {
		if (sharedBrowser == null || client == null) {
			return;
		}
		ensurePopupLayoutInitialized(client);
		int targetWidth = (int) (popupWidth * client.getWindow().getGuiScale());
		int targetHeight = (int) (popupHeight * client.getWindow().getGuiScale());
		if (targetWidth == popupBrowserPixelWidth && targetHeight == popupBrowserPixelHeight) {
			return;
		}
		sharedBrowser.resize(targetWidth, targetHeight);
		popupBrowserPixelWidth = targetWidth;
		popupBrowserPixelHeight = targetHeight;
	}

	private static void movePopupTo(Minecraft client, int x, int y) {
		popupX = x;
		popupY = y;
		clampPopupLayoutToScreen(client);
		saveSession();
	}

	private static void resizePopupByWidth(Minecraft client, int widthDelta) {
		ensurePopupLayoutInitialized(client);
		popupWidth += widthDelta;
		popupHeight = Math.round(popupWidth / POPUP_ASPECT_RATIO);
		clampPopupLayoutToScreen(client);
		resizeSharedBrowserForPopup(client);
		saveSession();
	}

	private static boolean isInsidePopup(double mouseX, double mouseY) {
		return mouseX >= popupX && mouseY >= popupY
				&& mouseX < popupX + popupWidth
				&& mouseY < popupY + popupHeight;
	}

	private static void renderPopupTexture(GuiGraphics guiGraphics, int x, int y, int width, int height) {
		if (!hasManagedBrowser() || !sharedBrowser.isTextureReady()) {
			return;
		}

		Identifier texture = sharedBrowser.getTextureIdentifier();
		if (texture == null) {
			return;
		}

		guiGraphics.blit(
				RenderPipelines.GUI_TEXTURED,
				texture,
				x,
				y,
				0.0F,
				0.0F,
				width,
				height,
				width,
				height
		);
	}

	public static void renderPopupOverlay(GuiGraphics guiGraphics, Minecraft client) {
		if (client == null) {
			return;
		}
		renderMediaChangePopup(guiGraphics, client);
		if (!popupModeEnabled || client.level == null || !hasManagedBrowser()) {
			return;
		}
		if (client.screen instanceof YouTubeBrowserScreen) {
			return;
		}
		ensurePopupLayoutInitialized(client);

		guiGraphics.fill(
				Math.max(0, popupX - 1),
				Math.max(0, popupY - 1),
				popupX + popupWidth + 1,
				popupY + popupHeight + 1,
				0xAA000000
		);
		renderPopupTexture(guiGraphics, popupX, popupY, popupWidth, popupHeight);
	}

	private static void renderMediaChangePopup(GuiGraphics guiGraphics, Minecraft client) {
		if (client.level == null || client.screen instanceof YouTubeBrowserScreen) {
			return;
		}
		long now = System.currentTimeMillis();
		if (mediaChangePopupHideAtMs <= now) {
			return;
		}
		int screenWidth = client.getWindow().getGuiScaledWidth();
		int popupLeft = (screenWidth - MEDIA_CHANGE_POPUP_WIDTH) / 2;
		int popupTop = 8;
		int popupRight = popupLeft + MEDIA_CHANGE_POPUP_WIDTH;
		int popupBottom = popupTop + MEDIA_CHANGE_POPUP_HEIGHT;

		guiGraphics.fill(popupLeft - 1, popupTop - 1, popupRight + 1, popupBottom + 1, 0xC0000000);
		guiGraphics.fill(popupLeft, popupTop, popupRight, popupBottom, 0xEE0D0D11);
		guiGraphics.fill(popupLeft + 1, popupTop + 1, popupRight - 1, popupTop + 2, 0x66FFFFFF);

		int coverSize = 44;
		int coverX = popupLeft + 7;
		int coverY = popupTop + 7;
		guiGraphics.fill(coverX - 1, coverY - 1, coverX + coverSize + 1, coverY + coverSize + 1, 0xCC000000);
		guiGraphics.fill(coverX, coverY, coverX + coverSize, coverY + coverSize, 0xFF2A2A31);
		guiGraphics.fill(coverX + 2, coverY + 2, coverX + coverSize - 2, coverY + coverSize - 2, 0xFF1B1B22);
		guiGraphics.drawCenteredString(
				client.font,
				Component.literal(serviceBadgeText(mediaChangePopupServiceLine)),
				coverX + (coverSize / 2),
				coverY + (coverSize / 2) - 4,
				0xFFE7C29D
		);

		int textX = coverX + coverSize + 10;
		guiGraphics.drawString(
				client.font,
				Component.literal(trimPopupText(mediaChangePopupTitleLine, 28)),
				textX,
				popupTop + 10,
				0xFFF4F5F7,
				false
		);
		guiGraphics.drawString(
				client.font,
				Component.literal(trimPopupText(mediaChangePopupServiceLine, 32)),
				textX,
				popupTop + 23,
				0xFFA5A9B4,
				false
		);

		int controlsY = popupTop + 37;
		int playButtonX = popupRight - 92;
		guiGraphics.drawString(client.font, Component.literal("<<"), playButtonX - 24, controlsY, 0xFF8E94A1, false);
		guiGraphics.fill(playButtonX, controlsY - 2, playButtonX + 18, controlsY + 10, 0xFFE7C29D);
		guiGraphics.drawCenteredString(client.font, Component.literal(">"), playButtonX + 9, controlsY, 0xFF101012);
		guiGraphics.drawString(client.font, Component.literal(">>"), playButtonX + 26, controlsY, 0xFF8E94A1, false);
	}

	private static String serviceBadgeText(String service) {
		if (service == null || service.isBlank()) {
			return "MD";
		}
		String cleaned = service.trim();
		String[] parts = cleaned.split("\\s+");
		if (parts.length >= 2) {
			char a = Character.toUpperCase(parts[0].charAt(0));
			char b = Character.toUpperCase(parts[1].charAt(0));
			return "" + a + b;
		}
		if (cleaned.length() >= 2) {
			return cleaned.substring(0, 2).toUpperCase(Locale.ROOT);
		}
		return cleaned.toUpperCase(Locale.ROOT);
	}

	private static MCEFBrowser getOrCreateSharedBrowser() {
		loadSessionIfNeeded();
		if (!MCEF.isInitialized()) {
			return null;
		}
		if (sharedBrowser == null) {
			String startUrl = getSafeStartupUrl();
			sharedBrowser = MCEF.createBrowser(startUrl, true);
			sharedBrowserSoftClosed = false;
		} else if (sharedBrowserSoftClosed) {
			sharedBrowserSoftClosed = false;
			sharedBrowser.setWindowVisibility(true);
			String restoreUrl = getSafeStartupUrl();
			sharedBrowser.loadURL(restoreUrl);
		}
		return sharedBrowser;
	}

	private static String normalizeServiceUrl(String url) {
		if (url == null || url.isBlank()) {
			return url;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		if (lower.contains("open.spotify.com") || lower.contains("play.spotify.com")) {
			return SPOTIFY_URL;
		}
		return url;
	}

	private static boolean isSpotifyUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		return lower.contains("open.spotify.com") || lower.contains("play.spotify.com");
	}

	private static boolean isGoogleSigninRejectedUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		return lower.contains("accounts.google.com")
				&& (lower.contains("/v3/signin/rejected") || lower.contains("signin/rejected"));
	}

	private static boolean isSecurityInterstitialUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		return lower.startsWith("chrome-error://chromewebdata")
				|| lower.contains("chromewebdata")
				|| lower.contains("err_cert");
	}

	private static boolean shouldPersistLastUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		if (BLANK_URL.equalsIgnoreCase(url)) {
			return false;
		}
		return !isSecurityInterstitialUrl(url) && !isGoogleSigninRejectedUrl(url);
	}

	private static void persistLastUrlIfValid(String url) {
		if (!shouldPersistLastUrl(url)) {
			return;
		}
		lastClosedUrl = url;
		saveSession();
	}

	private static String getSafeStartupUrl() {
		String candidate = normalizeServiceUrl(lastClosedUrl == null || lastClosedUrl.isBlank() ? DEFAULT_URL : lastClosedUrl);
		return shouldPersistLastUrl(candidate) ? candidate : DEFAULT_URL;
	}

	private static void recordSecureNavigationTarget(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("https://")) {
			return;
		}
		lastSecureNavigationUrl = url;
		secureErrorRetryDone = false;
		secureErrorFirstSeenAtMs = 0L;
		googleRejectedAutoHandled = false;
	}

	private static void executeSpotifyCompatScript(MCEFBrowser targetBrowser, String currentUrl) {
		if (targetBrowser == null || currentUrl == null || currentUrl.isBlank()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - spotifyCompatLastInjectMs < 900L) {
			return;
		}
		spotifyCompatLastInjectMs = now;
		targetBrowser.executeJavaScript(
				"""
				(() => {
				  const looksInteresting = (raw) => {
				    const url = (raw || "").toString().toLowerCase();
				    return url.includes("widevine") || url.includes("license") || url.includes("spclient");
				  };
				
				  if (!window.__mcSpotifyCompat) {
				    window.__mcSpotifyCompat = true;
				    console.warn("[MC-Spotify] compat init");
				
				    const installMediaWatch = (media) => {
				      if (!media || media.__mcSpotifyWatch) return;
				      media.__mcSpotifyWatch = true;
				      const log = (name) => {
				        const t = Number.isFinite(media.currentTime) ? media.currentTime.toFixed(2) : "n/a";
				        const err = media.error ? media.error.code : 0;
				        console.warn(`[MC-Spotify] media:${name} t=${t} err=${err}`);
				      };
				      media.addEventListener("error", () => log("error"), true);
				      media.addEventListener("stalled", () => log("stalled"), true);
				      media.addEventListener("suspend", () => log("suspend"), true);
				      media.addEventListener("waiting", () => log("waiting"), true);
				      media.addEventListener("pause", () => log("pause"), true);
				    };
				
				    const scanMedia = () => {
				      for (const media of document.querySelectorAll("video, audio")) installMediaWatch(media);
				    };
				    scanMedia();
				    new MutationObserver(scanMedia).observe(document.documentElement || document, { childList: true, subtree: true });
				
				    if (window.fetch && !window.__mcSpotifyFetchWrapped) {
				      const originalFetch = window.fetch.bind(window);
				      window.fetch = (...args) => {
				        const req = args && args[0];
				        const requestUrl = (typeof req === "string") ? req : (req && req.url ? req.url : "");
				        return originalFetch(...args).then((res) => {
				          if (looksInteresting(requestUrl)) {
				            console.warn(`[MC-Spotify] fetch ${res.status} ${requestUrl}`);
				          }
				          return res;
				        }).catch((err) => {
				          if (looksInteresting(requestUrl)) {
				            console.error(`[MC-Spotify] fetch error ${requestUrl}`, err);
				          }
				          throw err;
				        });
				      };
				      window.__mcSpotifyFetchWrapped = true;
				    }
				
				    if (window.XMLHttpRequest && !window.__mcSpotifyXhrWrapped) {
				      const open = XMLHttpRequest.prototype.open;
				      const send = XMLHttpRequest.prototype.send;
				      XMLHttpRequest.prototype.open = function(method, url, ...rest) {
				        this.__mcUrl = url;
				        return open.call(this, method, url, ...rest);
				      };
				      XMLHttpRequest.prototype.send = function(...args) {
				        this.addEventListener("loadend", () => {
				          if (looksInteresting(this.__mcUrl)) {
				            console.warn(`[MC-Spotify] xhr ${this.status} ${this.__mcUrl}`);
				          }
				        });
				        return send.apply(this, args);
				      };
				      window.__mcSpotifyXhrWrapped = true;
				    }
				  }
				
				  try {
				    Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
				    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
				    if (typeof document.hasFocus === "function") document.hasFocus = () => true;
				  } catch (e) {}
				
				  if (navigator.requestMediaKeySystemAccess && !window.__mcSpotifyWidevineProbe) {
				    window.__mcSpotifyWidevineProbe = true;
				    navigator.requestMediaKeySystemAccess("com.widevine.alpha", [{
				      initDataTypes: ["cenc"],
				      audioCapabilities: [{ contentType: "audio/mp4; codecs=\\"mp4a.40.2\\"" }]
				    }]).then(() => {
				      console.warn("[MC-Spotify] widevine probe ok");
				    }).catch((err) => {
				      console.error("[MC-Spotify] widevine probe fail", err && err.message ? err.message : err);
				    });
				  }
				})();
				""",
				currentUrl,
				0
		);
	}

	private static void applyBackgroundLowPowerResize(Minecraft client) {
		if (sharedBrowser == null || client == null) {
			return;
		}
		if (popupModeEnabled) {
			if (backgroundLowPowerApplied) {
				resizeSharedBrowserForPopup(client);
				backgroundLowPowerApplied = false;
				backgroundLowPowerPixelWidth = -1;
				backgroundLowPowerPixelHeight = -1;
			}
			return;
		}

		int targetWidth = Math.max(32, (int) (BACKGROUND_LOW_POWER_WIDTH * client.getWindow().getGuiScale()));
		int targetHeight = Math.max(32, (int) (BACKGROUND_LOW_POWER_HEIGHT * client.getWindow().getGuiScale()));
		if (backgroundLowPowerApplied
				&& targetWidth == backgroundLowPowerPixelWidth
				&& targetHeight == backgroundLowPowerPixelHeight) {
			return;
		}

		sharedBrowser.resize(targetWidth, targetHeight);
		backgroundLowPowerApplied = true;
		backgroundLowPowerPixelWidth = targetWidth;
		backgroundLowPowerPixelHeight = targetHeight;
	}

	private static boolean hasManagedBrowser() {
		if (!MCEF.isInitialized() || sharedBrowser == null || sharedBrowserSoftClosed) {
			return false;
		}
		String currentUrl = sharedBrowser.getURL();
		return currentUrl != null && !currentUrl.isBlank() && !BLANK_URL.equalsIgnoreCase(currentUrl);
	}

	private static void trackMediaChange(Minecraft client, String url) {
		if (client == null || url == null || url.isBlank() || BLANK_URL.equalsIgnoreCase(url)) {
			return;
		}
		String key = buildMediaTrackKey(url);
		if (key.isBlank()) {
			return;
		}
		if (!mediaTrackPrimed) {
			mediaTrackPrimed = true;
			lastMediaTrackKey = key;
			return;
		}
		if (key.equals(lastMediaTrackKey)) {
			return;
		}
		lastMediaTrackKey = key;
		mediaChangePopupServiceLine = serviceNameFromUrl(url);
		mediaChangePopupTitleLine = fallbackTitleFromUrl(url);
		String cachedTitle = mediaTitleCache.get(key);
		if (cachedTitle != null && !cachedTitle.isBlank()) {
			mediaChangePopupTitleLine = cachedTitle;
		} else {
			requestMediaTitleAsync(key, url);
		}
		mediaChangePopupHideAtMs = System.currentTimeMillis() + MEDIA_CHANGE_POPUP_DURATION_MS;
	}

	private static String trimPopupText(String text, int maxChars) {
		if (text == null || text.isBlank()) {
			return "Media changed";
		}
		String cleaned = text.trim();
		if (cleaned.length() <= maxChars) {
			return cleaned;
		}
		return cleaned.substring(0, Math.max(1, maxChars - 3)) + "...";
	}

	private static String serviceNameFromUrl(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		if (lower.contains("music.youtube.com")) {
			return "Yt Music";
		}
		if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
			return "YouTube";
		}
		if (lower.contains("spotify.com")) {
			return "Spotify";
		}
		if (lower.contains("music.apple.com")) {
			return "Apple Music";
		}
		return "Media";
	}

	private static String buildMediaTrackKey(String url) {
		try {
			URI uri = new URI(url);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			String path = uri.getPath() == null ? "" : uri.getPath();
			String query = uri.getQuery() == null ? "" : uri.getQuery();
			if (host.contains("music.youtube.com")) {
				String videoId = queryParam(query, "v");
				if (!videoId.isBlank()) {
					return "ytm:" + videoId;
				}
				return "ytm:" + path;
			}
			if (host.contains("youtube.com")) {
				String videoId = queryParam(query, "v");
				if (!videoId.isBlank()) {
					return "yt:" + videoId;
				}
				return "yt:" + path;
			}
			if (host.contains("youtu.be")) {
				String videoId = path.startsWith("/") ? path.substring(1) : path;
				return "yt:" + videoId;
			}
			if (host.contains("spotify.com")) {
				String[] parts = path.split("/");
				if (parts.length >= 3) {
					return "sp:" + parts[1] + ":" + parts[2];
				}
				return "sp:" + path;
			}
			if (host.contains("music.apple.com")) {
				String trackId = queryParam(query, "i");
				if (!trackId.isBlank()) {
					return "am:" + trackId;
				}
				return "am:" + path;
			}
			return host + path + "?" + query;
		} catch (URISyntaxException ignored) {
			return url;
		}
	}

	private static void requestMediaTitleAsync(String key, String url) {
		if (key == null || key.isBlank() || url == null || url.isBlank()) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			String title = resolveMediaTitle(url);
			if (title == null || title.isBlank()) {
				return;
			}
			Minecraft client = Minecraft.getInstance();
			if (client == null) {
				return;
			}
			client.execute(() -> {
				mediaTitleCache.put(key, title);
				if (key.equals(lastMediaTrackKey)) {
					mediaChangePopupTitleLine = title;
					mediaChangePopupHideAtMs = System.currentTimeMillis() + MEDIA_CHANGE_POPUP_DURATION_MS;
				}
			});
		});
	}

	private static String resolveMediaTitle(String mediaUrl) {
		String service = serviceNameFromUrl(mediaUrl);
		return switch (service) {
			case "YouTube", "Yt Music" -> fetchYouTubeTitle(mediaUrl);
			case "Spotify" -> fetchSpotifyTitle(mediaUrl);
			case "Apple Music" -> fetchAppleMusicTitle(mediaUrl);
			default -> fallbackTitleFromUrl(mediaUrl);
		};
	}

	private static String fetchYouTubeTitle(String mediaUrl) {
		String id = extractYouTubeVideoId(mediaUrl);
		String lookupUrl = mediaUrl;
		if (id != null && !id.isBlank()) {
			lookupUrl = "https://www.youtube.com/watch?v=" + id;
		}
		String api = "https://www.youtube.com/oembed?url=" + URLEncoder.encode(lookupUrl, StandardCharsets.UTF_8) + "&format=json";
		String title = fetchJsonField(api, "title");
		if (title == null || title.isBlank()) {
			title = fetchHtmlTitle(lookupUrl);
		}
		return title == null || title.isBlank() ? fallbackTitleFromUrl(mediaUrl) : title;
	}

	private static String fetchSpotifyTitle(String mediaUrl) {
		String api = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(mediaUrl, StandardCharsets.UTF_8);
		String title = fetchJsonField(api, "title");
		if (title == null || title.isBlank()) {
			title = fetchHtmlTitle(mediaUrl);
		}
		return title == null || title.isBlank() ? fallbackTitleFromUrl(mediaUrl) : title;
	}

	private static String fetchAppleMusicTitle(String mediaUrl) {
		String title = null;
		try {
			URI uri = new URI(mediaUrl);
			String trackId = queryParam(uri.getQuery(), "i");
			if (!trackId.isBlank()) {
				String api = "https://itunes.apple.com/lookup?id=" + URLEncoder.encode(trackId, StandardCharsets.UTF_8);
				title = fetchAppleLookupTrackName(api);
			}
		} catch (URISyntaxException ignored) {
		}
		if (title == null || title.isBlank()) {
			title = fetchHtmlTitle(mediaUrl);
		}
		if (title == null || title.isBlank()) {
			title = fallbackTitleFromUrl(mediaUrl);
		}
		return title;
	}

	private static String fetchHtmlTitle(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(4))
					.header("User-Agent", "Mozilla/5.0")
					.GET()
					.build();
			HttpResponse<String> response = MEDIA_META_HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				return "";
			}
			String body = response.body();
			if (body == null || body.isBlank()) {
				return "";
			}
			String lower = body.toLowerCase(Locale.ROOT);
			int start = lower.indexOf("<title>");
			int end = lower.indexOf("</title>");
			if (start < 0 || end < 0 || end <= start + 7) {
				return "";
			}
			String raw = body.substring(start + 7, end).trim();
			String cleaned = raw
					.replace("&amp;", "&")
					.replace("&#39;", "'")
					.replace("&quot;", "\"")
					.replace("&lt;", "<")
					.replace("&gt;", ">");
			return normalizeFetchedTitle(cleaned.trim());
		} catch (Exception ignored) {
			return "";
		}
	}

	private static String fetchJsonField(String url, String field) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(3))
					.header("User-Agent", "Mozilla/5.0")
					.GET()
					.build();
			HttpResponse<String> response = MEDIA_META_HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				return "";
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!root.has(field) || root.get(field).isJsonNull()) {
				return "";
			}
			return normalizeFetchedTitle(root.get(field).getAsString().trim());
		} catch (Exception ignored) {
			return "";
		}
	}

	private static String fetchAppleLookupTrackName(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(3))
					.header("User-Agent", "Mozilla/5.0")
					.GET()
					.build();
			HttpResponse<String> response = MEDIA_META_HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				return "";
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!root.has("results") || !root.get("results").isJsonArray()) {
				return "";
			}
			JsonArray results = root.getAsJsonArray("results");
			if (results.isEmpty()) {
				return "";
			}
			JsonObject first = results.get(0).getAsJsonObject();
			if (!first.has("trackName") || first.get("trackName").isJsonNull()) {
				return "";
			}
			return normalizeFetchedTitle(first.get("trackName").getAsString().trim());
		} catch (Exception ignored) {
			return "";
		}
	}

	private static String normalizeFetchedTitle(String title) {
		if (title == null) {
			return "";
		}
		String cleaned = title.trim();
		cleaned = cleaned.replace(" - YouTube Music", "");
		cleaned = cleaned.replace(" - YouTube", "");
		cleaned = cleaned.replace(" | Spotify", "");
		cleaned = cleaned.replace(" - Apple Music", "");
		return cleaned.trim();
	}

	private static String extractYouTubeVideoId(String url) {
		try {
			URI uri = new URI(url);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			if (host.contains("youtu.be")) {
				String path = uri.getPath() == null ? "" : uri.getPath();
				return path.startsWith("/") ? path.substring(1) : path;
			}
			if (host.contains("youtube.com")) {
				return queryParam(uri.getQuery(), "v");
			}
			return "";
		} catch (Exception ignored) {
			return "";
		}
	}

	private static String fallbackTitleFromUrl(String url) {
		try {
			String ytId = extractYouTubeVideoId(url);
			if (ytId != null && !ytId.isBlank()) {
				return "Video " + ytId;
			}
			URI uri = new URI(url);
			String path = uri.getPath() == null ? "" : uri.getPath();
			String[] parts = path.split("/");
			for (int i = parts.length - 1; i >= 0; i--) {
				String part = parts[i];
				if (part != null && !part.isBlank()) {
					String decoded = java.net.URLDecoder.decode(part, StandardCharsets.UTF_8);
					return decoded.replace('-', ' ').trim();
				}
			}
		} catch (Exception ignored) {
		}
		return serviceNameFromUrl(url);
	}

	private static String queryParam(String query, String key) {
		if (query == null || query.isBlank() || key == null || key.isBlank()) {
			return "";
		}
		String target = key + "=";
		for (String part : query.split("&")) {
			if (part.startsWith(target)) {
				return part.substring(target.length());
			}
		}
		return "";
	}

	public boolean canHandleMediaHotkeys() {
		return urlBox == null || !urlBox.isFocused();
	}

	private static void executeScript(String script) {
		if (!hasManagedBrowser()) {
			return;
		}
		String currentUrl = sharedBrowser.getURL();
		sharedBrowser.executeJavaScript(script, currentUrl == null ? DEFAULT_URL : currentUrl, 0);
	}

	private static void performMediaAction(String action) {
		if (hasManagedBrowser()) {
			String currentUrl = sharedBrowser.getURL();
			if (isSpotifyUrl(currentUrl) && ExampleModClient.triggerSystemMediaAction(action)) {
				return;
			}
		}
		executeScript(
				("""
				(() => {
				  const action = "%s";
				  const isDisabled = (element) =>
				    !element ||
				    element.disabled === true ||
				    element.getAttribute("disabled") !== null ||
				    element.getAttribute("aria-disabled") === "true" ||
				    element.classList?.contains("ytp-button-disabled");
				
				  const isVisible = (element) => {
				    if (!element) return false;
				    const style = window.getComputedStyle(element);
				    if (!style) return false;
				    if (style.display === "none" || style.visibility === "hidden" || style.pointerEvents === "none") return false;
				    if (Number(style.opacity) === 0) return false;
				    const rect = element.getBoundingClientRect();
				    return rect.width > 0 && rect.height > 0;
				  };
				
				  const normalizeText = (value) =>
				    (value || "")
				      .toString()
				      .toLowerCase()
				      .normalize("NFD")
				      .replace(/[\u0300-\u036f]/g, "");
				
				  const collectRoots = (root, roots) => {
				    if (!root || roots.includes(root)) return;
				    roots.push(root);
				    if (!root.querySelectorAll) return;
				    const nodes = root.querySelectorAll("*");
				    for (const node of nodes) {
				      if (node && node.shadowRoot) {
				        collectRoots(node.shadowRoot, roots);
				      }
				    }
				  };
				
				  const getAllRoots = () => {
				    const roots = [];
				    collectRoots(document, roots);
				    return roots;
				  };
				
				  const queryAllDeep = (selector, root) => {
				    const roots = root ? [root] : getAllRoots();
				    const results = [];
				    for (const currentRoot of roots) {
				      if (!currentRoot || !currentRoot.querySelectorAll) continue;
				      try {
				        for (const element of currentRoot.querySelectorAll(selector)) {
				          results.push(element);
				        }
				      } catch (e) {}
				    }
				    return results;
				  };
				
				  const queryFirstDeep = (selector, root) => {
				    const matches = queryAllDeep(selector, root);
				    return matches.length > 0 ? matches[0] : null;
				  };
				
				  const findMediaElement = () => {
				    const candidates = queryAllDeep("video, audio");
				    for (const media of candidates) {
				      if (media) {
				        return media;
				      }
				    }
				    return null;
				  };
				
				  const pressElement = (element) => {
				    if (!element || isDisabled(element) || !isVisible(element)) return false;
				    const targets = [
				      element,
				      element.querySelector?.("button"),
				      element.shadowRoot?.querySelector?.("button"),
				      element.shadowRoot?.querySelector?.("[role='button']")
				    ].filter(Boolean);
				
				    for (const target of targets) {
				      if (isDisabled(target) || !isVisible(target)) continue;
				      try {
				        target.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true, view: window }));
				        target.dispatchEvent(new MouseEvent("mouseup", { bubbles: true, cancelable: true, view: window }));
				        target.click();
				        return true;
				      } catch (e) {}
				    }
				    return false;
				  };
				
				  const click = (selectors) => {
				    for (const selector of selectors) {
				      const elements = queryAllDeep(selector);
				      for (const element of elements) {
				        if (pressElement(element)) {
				          return true;
				        }
				      }
				    }
				    return false;
				  };
				
				  const clickByKeywords = (keywords, root) => {
				    const normalizedKeywords = keywords.map(normalizeText);
				    const elements = queryAllDeep("button, tp-yt-paper-icon-button, a[role='button']", root);
				    for (const element of elements) {
				      const haystack = normalizeText(
				        (element.getAttribute("aria-label") || "") + " "
				          + (element.getAttribute("title") || "") + " "
				          + (element.textContent || "")
				      );
				      if (!haystack) continue;
				      if (normalizedKeywords.some((keyword) => haystack.includes(keyword))) {
				        if (pressElement(element)) {
				          return true;
				        }
				      }
				    }
				    return false;
				  };
				
				  const invokeYouTubeMusicAction = (kind) => {
				    const bar = queryFirstDeep("ytmusic-player-bar");
				    if (!bar) return false;
				
				    const methodNames = kind === "next"
				      ? ["nextVideo", "next", "onNextTap", "handleNextTap", "skipNext"]
				      : kind === "previous"
				        ? ["previousVideo", "previous", "onPreviousTap", "handlePreviousTap", "skipPrevious", "previousSong"]
				        : ["togglePlayPause", "togglePause", "playPause", "onPlayPauseTap", "handlePlayPauseTap", "togglePlay"];
				
				    const targets = [bar, bar.playerApi_, bar.playerApi, bar.api_, bar.api, bar.player_];
				    for (const target of targets) {
				      if (!target) continue;
				      for (const methodName of methodNames) {
				        const fn = target[methodName];
				        if (typeof fn !== "function") continue;
				        try {
				          fn.call(target);
				          return true;
				        } catch (e) {}
				      }
				    }
				    return false;
				  };
				
				  const sendKey = (key, code, withShift = false) => {
				    const target = document.activeElement || document.body || document.documentElement;
				    if (!target) return false;
				    const down = new KeyboardEvent("keydown", { key, code, bubbles: true, cancelable: true, shiftKey: withShift });
				    const up = new KeyboardEvent("keyup", { key, code, bubbles: true, cancelable: true, shiftKey: withShift });
				    target.dispatchEvent(down);
				    target.dispatchEvent(up);
				    return true;
				  };
				
				  const getYouTubePlayer = () => document.getElementById("movie_player");
				
				  const host = location.hostname;
				  const isMusic = host.includes("music.youtube.com");
				  const isYoutube = host.includes("youtube.com") && !isMusic;
				  const isSpotify = host.includes("spotify.com");
				  const isAppleMusic = host.includes("music.apple.com");
				
				  if (action === "playpause") {
				    const musicBar = queryFirstDeep("ytmusic-player-bar");
				    if (isMusic && click([
				      "tp-yt-paper-icon-button.play-pause-button",
				      "ytmusic-player-bar tp-yt-paper-icon-button.play-pause-button",
				      "ytmusic-player-bar .play-pause-button",
				      "ytmusic-player-bar #play-pause-button",
				      "button[aria-label*='Play']",
				      "button[aria-label*='Pause']",
				      "button[aria-label*='Oynat']",
				      "button[aria-label*='Duraklat']"
				    ])) return;
				    if (isMusic && clickByKeywords(["play", "pause", "oynat", "duraklat"], musicBar)) return;
				    if (isMusic && invokeYouTubeMusicAction("playpause")) return;
				    if (isYoutube) {
				      const player = getYouTubePlayer();
				      if (player && typeof player.playVideo === "function" && typeof player.pauseVideo === "function") {
				        try {
				          const state = typeof player.getPlayerState === "function" ? player.getPlayerState() : null;
				          if (state === 1) player.pauseVideo();
				          else player.playVideo();
				          return;
				        } catch (e) {}
				      }
				      if (click(["button.ytp-play-button", "button[aria-label*='Play']", "button[aria-label*='Pause']"])) return;
				    }
				    if (isSpotify && click([
				      "button[data-testid='control-button-playpause']",
				      "button[data-testid='play-button']",
				      "button[data-testid='pause-button']",
				      "button[aria-label*='Play']",
				      "button[aria-label*='Pause']"
				    ])) return;
				    if (isAppleMusic && click([
				      "button[aria-label*='Play']",
				      "button[aria-label*='Pause']",
				      "button[aria-label*='Oynat']",
				      "button[aria-label*='Duraklat']",
				      "button[title*='Play']",
				      "button[title*='Pause']"
				    ])) return;
				
				    const media = findMediaElement();
				    if (media) {
				      if (media.paused) {
				        media.play().catch(() => {});
				      } else {
				        media.pause();
				      }
				    }
				    return;
				  }
				
				  if (action === "next") {
				    if (isMusic && click([
				      "ytmusic-player-bar tp-yt-paper-icon-button.next-button",
				      "ytmusic-player-bar .next-button",
				      "ytmusic-player-bar #next-button",
				      "ytmusic-player-bar button[aria-label*='Next']",
				      "ytmusic-player-bar button[aria-label*='Sonraki']",
				      "tp-yt-paper-icon-button.next-button",
				      "button[aria-label*='Next']",
				      "button[aria-label*='Sonraki']",
				      "button[title*='Next']"
				    ])) return;
				    if (isMusic && invokeYouTubeMusicAction("next")) return;
				    if (isYoutube) {
				      window.__mcCanHistoryBack = true;
				      if (window.__mcCanHistoryForward === true) {
				        window.__mcCanHistoryForward = false;
				        history.forward();
				      }
				      const player = getYouTubePlayer();
				      if (player && typeof player.nextVideo === "function") {
				        try {
				          player.nextVideo();
				          return;
				        } catch (e) {}
				      }
				      if (click(["button.ytp-next-button", "button[aria-label*='Next']", "button[aria-label*='Sonraki']"])) return;
				      // Keep synthetic key as a best-effort fallback, but do not stop here.
				      // Some YouTube builds ignore untrusted keyboard events.
				      sendKey("N", "KeyN", true);
				      const recommendation =
				        queryFirstDeep("ytd-watch-next-secondary-results-renderer a#thumbnail[href*='/watch']") ||
				        queryFirstDeep("ytd-watch-next-secondary-results-renderer a.yt-simple-endpoint[href*='/watch']") ||
				        queryFirstDeep("ytd-compact-video-renderer a#thumbnail[href*='/watch']") ||
				        queryFirstDeep("ytd-compact-video-renderer a.yt-simple-endpoint[href*='/watch']") ||
				        queryFirstDeep("ytd-compact-video-renderer yt-formatted-string a[href*='/watch']") ||
				        queryFirstDeep("a.ytp-next-button[href*='/watch']") ||
				        queryFirstDeep("a.ytp-ce-covering-overlay[href*='/watch']");
				      if (recommendation) {
				        const href = recommendation.getAttribute("href");
				        if (href) {
				          location.href = new URL(href, location.origin).toString();
				          return;
				        }
				      }
				      history.forward();
				      return;
				    }
				    if (isSpotify && click([
				      "button[data-testid='control-button-skip-forward']",
				      "button[aria-label*='Next']",
				      "button[aria-label*='Sonraki']"
				    ])) return;
				    if (isAppleMusic && click([
				      "button[aria-label*='Next']",
				      "button[aria-label*='Sonraki']",
				      "button[title*='Next']"
				    ])) return;
				    if (isMusic && (sendKey("N", "KeyN", true) || sendKey("L", "KeyL"))) return;
				  }
				
				  if (action === "previous") {
				    if (isMusic && click([
				      "ytmusic-player-bar tp-yt-paper-icon-button.previous-button",
				      "ytmusic-player-bar .previous-button",
				      "ytmusic-player-bar #previous-button",
				      "ytmusic-player-bar button[aria-label*='Previous']",
				      "ytmusic-player-bar button[aria-label*='Onceki']",
				      "tp-yt-paper-icon-button.previous-button",
				      "button[aria-label*='Previous']",
				      "button[aria-label*='Onceki']",
				      "button[title*='Previous']"
				    ])) return;
				    if (isMusic && invokeYouTubeMusicAction("previous")) return;
				    if (isYoutube) {
				      if (history.length > 1 || window.__mcCanHistoryBack === true) {
				        window.__mcCanHistoryForward = true;
				        history.back();
				        return;
				      }
				      const inPlaylist = (() => {
				        try {
				          const url = new URL(location.href);
				          return url.searchParams.has("list") || !!queryFirstDeep("ytd-playlist-panel-renderer");
				        } catch (e) {
				          return !!queryFirstDeep("ytd-playlist-panel-renderer");
				        }
				      })();
				      if (!inPlaylist) {
				        window.__mcCanHistoryForward = true;
				        history.back();
				        return;
				      }
				      const player = getYouTubePlayer();
				      if (player && typeof player.previousVideo === "function") {
				        try {
				          player.previousVideo();
				          return;
				        } catch (e) {}
				      }
				      if (click(["button.ytp-prev-button", "button[aria-label*='Previous']", "button[aria-label*='Onceki']"])) return;
				      if (clickByKeywords(["previous", "onceki"], queryFirstDeep(".ytp-chrome-controls"))) return;
				      // Same reason as next: do not early-return on synthetic key dispatch.
				      sendKey("P", "KeyP", true);
				      const playlistPrevious =
				        queryFirstDeep("a.ytp-prev-button[href*='/watch']") ||
				        queryFirstDeep("#playlist #previous-button a[href*='/watch']") ||
				        queryFirstDeep("ytd-playlist-panel-renderer #previous-button a[href*='/watch']");
				      if (playlistPrevious) {
				        const href = playlistPrevious.getAttribute("href");
				        if (href) {
				          location.href = new URL(href, location.origin).toString();
				          return;
				        }
				      }
				      window.__mcCanHistoryForward = true;
				      history.back();
				      return;
				    }
				    if (isSpotify && click([
				      "button[data-testid='control-button-skip-back']",
				      "button[aria-label*='Previous']",
				      "button[aria-label*='Onceki']"
				    ])) return;
				    if (isAppleMusic && click([
				      "button[aria-label*='Previous']",
				      "button[aria-label*='Onceki']",
				      "button[title*='Previous']"
				    ])) return;
				    if (isMusic && (sendKey("P", "KeyP", true) || sendKey("J", "KeyJ"))) return;
				  }
				})();
				""").formatted(action)
		);
	}

	public static void playPauseVideo() {
		performMediaAction("playpause");
	}

	public static void nextVideo() {
		performMediaAction("next");
	}

	public static void previousVideo() {
		performMediaAction("previous");
	}

	public static void tickBackgroundPlayback() {
		if (!hasManagedBrowser()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return;
		}
		if (client.screen instanceof YouTubeBrowserScreen) {
			// Keep-alive script is only needed while browser is in background.
			return;
		}
		String currentUrl = sharedBrowser.getURL();
		trackMediaChange(client, currentUrl);
		boolean spotifyPage = isSpotifyUrl(currentUrl);
		applyBackgroundLowPowerResize(client);

		backgroundTickCounter++;
		if (backgroundTickCounter < BACKGROUND_KEEP_ALIVE_INTERVAL_TICKS) {
			return;
		}
		backgroundTickCounter = 0;

		sharedBrowser.setWindowVisibility(true);
		sharedBrowser.setFocus(false);
		if (spotifyPage) {
			executeSpotifyCompatScript(sharedBrowser, currentUrl);
		} else {
			sharedBrowser.executeJavaScript(
					"""
					(() => {
					  const forceVisibleState = () => {
					    try {
					      Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
					      Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
					      Object.defineProperty(document, "webkitHidden", { configurable: true, get: () => false });
					      Object.defineProperty(document, "msHidden", { configurable: true, get: () => false });
					      if (typeof document.hasFocus === "function") {
					        document.hasFocus = () => true;
					      }
					    } catch (e) {}
					    try { window.focus(); } catch (e) {}
					    try { window.dispatchEvent(new Event("focus")); } catch (e) {}
					    try { window.dispatchEvent(new Event("pageshow")); } catch (e) {}
					    try { document.dispatchEvent(new Event("visibilitychange")); } catch (e) {}
					  };
					
					  if (!window.__mcKeepAlive) {
					    window.__mcKeepAlive = { lastPulse: 0 };
					    forceVisibleState();
					  }
					
					  const now = Date.now();
					  if (now - window.__mcKeepAlive.lastPulse > 900) {
					    window.__mcKeepAlive.lastPulse = now;
					    forceVisibleState();
					  }
					
					  const roots = [];
					  const collectRoots = (root) => {
					    if (!root || roots.includes(root) || !root.querySelectorAll) return;
					    roots.push(root);
					    for (const node of root.querySelectorAll("*")) {
					      if (node && node.shadowRoot) collectRoots(node.shadowRoot);
					    }
					  };
					  collectRoots(document);
					
					  const medias = [];
					  for (const root of roots) {
					    for (const media of root.querySelectorAll("video, audio")) {
					      if (media) medias.push(media);
					    }
					  }
					  if (!medias.length) return;
					
					  for (const media of medias) {
					    try {
					      if (typeof media.__mcKeepAliveBaseVolume !== "number" && media.volume > 0.001) {
					        media.__mcKeepAliveBaseVolume = media.volume;
					      }
					      media.muted = false;
					      if (media.volume <= 0.001) {
					        media.volume = typeof media.__mcKeepAliveBaseVolume === "number"
					          ? Math.max(0.05, media.__mcKeepAliveBaseVolume)
					          : 1.0;
					      }
					      if (media.paused && !media.ended && media.readyState >= 2) {
					        media.play().catch(() => {});
					      }
					    } catch (e) {}
					  }
					})();
					""",
					currentUrl,
					0
			);
		}
	}

	public static void shutdownSharedBrowser() {
		loadSessionIfNeeded();
		if (sharedBrowser != null) {
			String currentUrl = sharedBrowser.getURL();
			persistLastUrlIfValid(currentUrl);
			sharedBrowser.executeJavaScript(
					"""
					for (const media of document.querySelectorAll('video, audio')) {
					  try {
					    media.pause();
					    media.currentTime = 0;
					    media.src = '';
					    media.load();
					  } catch (e) {}
					}
					""",
					currentUrl == null || currentUrl.isBlank() ? DEFAULT_URL : currentUrl,
					0
			);
			sharedBrowser.setFocus(false);
			sharedBrowser.setWindowVisibility(false);
			sharedBrowser.loadURL(BLANK_URL);
			sharedBrowserSoftClosed = true;
		}
		backgroundTickCounter = 0;
		backgroundLowPowerApplied = false;
		backgroundLowPowerPixelWidth = -1;
		backgroundLowPowerPixelHeight = -1;
		popupModeEnabled = false;
		popupBrowserPixelWidth = -1;
		popupBrowserPixelHeight = -1;
		mediaTrackPrimed = false;
		lastMediaTrackKey = "";
		mediaChangePopupServiceLine = "";
		mediaChangePopupTitleLine = "";
		mediaTitleCache.clear();
		mediaChangePopupHideAtMs = 0L;
		saveSession();
	}

	public static void destroySharedBrowser() {
		loadSessionIfNeeded();
		if (sharedBrowser != null) {
			String currentUrl = sharedBrowser.getURL();
			persistLastUrlIfValid(currentUrl);
			sharedBrowser.setFocus(false);
			sharedBrowser.setWindowVisibility(false);
			sharedBrowser.close();
			sharedBrowser = null;
		}
		sharedBrowserSoftClosed = false;
		backgroundTickCounter = 0;
		backgroundLowPowerApplied = false;
		backgroundLowPowerPixelWidth = -1;
		backgroundLowPowerPixelHeight = -1;
		popupBrowserPixelWidth = -1;
		popupBrowserPixelHeight = -1;
		mediaTrackPrimed = false;
		lastMediaTrackKey = "";
		mediaChangePopupServiceLine = "";
		mediaChangePopupTitleLine = "";
		mediaTitleCache.clear();
		mediaChangePopupHideAtMs = 0L;
		saveSession();
	}

	private void openPopupEditor() {
		if (browser == null || minecraft == null || minecraft.level == null) {
			return;
		}
		popupModeEnabled = false;
		setMediaDropdownVisible(false);
		ensurePopupLayoutInitialized(minecraft);
		resizeSharedBrowserForPopup(minecraft);
		browser.setWindowVisibility(true);
		browser.setFocus(true);
		minecraft.setScreen(new PopupEditorScreen());
	}

	private void initNavigationWidgets() {
		int navX = FRAME_MARGIN;
		int navY = FRAME_MARGIN;

		closeButton = addRenderableWidget(
				Button.builder(Component.literal("X"), button -> {
					if (minecraft != null) {
						String currentUrl = browser == null ? null : browser.getURL();
						persistLastUrlIfValid(currentUrl);
						minecraft.setScreen(null);
						ExampleModClient.requestInGameMouseRestore(minecraft);
					}
				})
						.bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += NAV_BUTTON_WIDTH + NAV_SPACING;

		backButton = addRenderableWidget(
				Button.builder(Component.literal("<"), button -> browser.goBack())
						.bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += NAV_BUTTON_WIDTH + NAV_SPACING;

		forwardButton = addRenderableWidget(
				Button.builder(Component.literal(">"), button -> browser.goForward())
						.bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += NAV_BUTTON_WIDTH + NAV_SPACING;

		addRenderableWidget(
				Button.builder(Component.literal("R"), button -> browser.reload())
						.bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += NAV_BUTTON_WIDTH + NAV_SPACING;

		mediaDropdownButton = addRenderableWidget(
				Button.builder(Component.literal("Media"), button -> toggleMediaDropdown())
						.bounds(navX, navY, MEDIA_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);

		int mediaOptionX = navX + (MEDIA_BUTTON_WIDTH - MEDIA_OPTION_WIDTH) / 2;
		int mediaOptionY = navY + NAV_BAR_HEIGHT + NAV_SPACING;
		mediaYouTubeButton = addRenderableWidget(
				Button.builder(Component.literal("Youtube"), button -> {
							navigateToService(YOUTUBE_URL);
						})
						.bounds(mediaOptionX, mediaOptionY, MEDIA_OPTION_WIDTH, MEDIA_OPTION_HEIGHT)
						.build()
		);
		mediaMusicButton = addRenderableWidget(
				Button.builder(Component.literal("Yt Music"), button -> {
							navigateToService(YOUTUBE_MUSIC_URL);
						})
						.bounds(mediaOptionX, mediaOptionY + MEDIA_OPTION_HEIGHT + 1, MEDIA_OPTION_WIDTH, MEDIA_OPTION_HEIGHT)
						.build()
		);
		mediaSpotifyButton = addRenderableWidget(
				Button.builder(Component.literal("Spotify"), button -> {
							navigateToService(SPOTIFY_URL);
						})
						.bounds(mediaOptionX, mediaOptionY + (MEDIA_OPTION_HEIGHT + 1) * 2, MEDIA_OPTION_WIDTH, MEDIA_OPTION_HEIGHT)
						.build()
		);
		mediaAppleMusicButton = addRenderableWidget(
				Button.builder(Component.literal("Apple Music"), button -> {
							navigateToService(APPLE_MUSIC_URL);
						})
						.bounds(mediaOptionX, mediaOptionY + (MEDIA_OPTION_HEIGHT + 1) * 3, MEDIA_OPTION_WIDTH, MEDIA_OPTION_HEIGHT)
						.build()
		);
		setMediaDropdownVisible(false);
		navX += MEDIA_BUTTON_WIDTH + NAV_SPACING;

		popupModeButton = addRenderableWidget(
				Button.builder(Component.literal("POP"), button -> openPopupEditor())
						.bounds(navX, navY, POPUP_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += POPUP_BUTTON_WIDTH + NAV_SPACING;

		if (showMainMenuButton) {
			addRenderableWidget(
					Button.builder(Component.literal("MENU"), button -> {
						if (minecraft != null) {
							minecraft.setScreen(new TitleScreen());
						}
					})
							.bounds(navX, navY, MENU_BUTTON_WIDTH, NAV_BAR_HEIGHT)
							.build()
			);
			navX += MENU_BUTTON_WIDTH + NAV_SPACING;
		}

		addRenderableWidget(
				Button.builder(Component.literal("\u23EE"), button -> previousVideo())
						.bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += NAV_BUTTON_WIDTH + NAV_SPACING;

		addRenderableWidget(
				Button.builder(Component.literal("\u23EF"), button -> playPauseVideo())
						.bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += NAV_BUTTON_WIDTH + NAV_SPACING;

		addRenderableWidget(
				Button.builder(Component.literal("\u23ED"), button -> nextVideo())
						.bounds(navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT)
						.build()
		);
		navX += NAV_BUTTON_WIDTH + NAV_SPACING;

		int urlWidth = Math.max(60, width - FRAME_MARGIN - navX);
		urlBox = addRenderableWidget(new EditBox(font, navX, navY, urlWidth, NAV_BAR_HEIGHT, Component.literal("URL")));
		urlBox.setMaxLength(2048);
		String currentUrl = browser.getURL();
		urlBox.setValue(currentUrl == null || currentUrl.isBlank() ? DEFAULT_URL : currentUrl);

		initServicePickerWidgets();
		initSpotifyWarningWidgets();
	}

	private void initServicePickerWidgets() {
		int buttonWidth = 128;
		int buttonHeight = 22;
		int buttonGap = 8;
		int gridWidth = (buttonWidth * 2) + buttonGap;
		int gridHeight = (buttonHeight * 2) + buttonGap;
		int left = (width - gridWidth) / 2;
		int top = getBrowserY() + Math.max(24, (getBrowserHeight() - gridHeight) / 2);

		chooseYouTubeButton = addRenderableWidget(
				Button.builder(Component.literal("YouTube"), button -> navigateToService(YOUTUBE_URL))
						.bounds(left, top, buttonWidth, buttonHeight)
						.build()
		);
		chooseMusicButton = addRenderableWidget(
				Button.builder(Component.literal("Youtube Music"), button -> navigateToService(YOUTUBE_MUSIC_URL))
						.bounds(left + buttonWidth + buttonGap, top, buttonWidth, buttonHeight)
						.build()
		);
		chooseSpotifyButton = addRenderableWidget(
				Button.builder(Component.literal("Spotify"), button -> navigateToService(SPOTIFY_URL))
						.bounds(left, top + buttonHeight + buttonGap, buttonWidth, buttonHeight)
						.build()
		);
		chooseAppleMusicButton = addRenderableWidget(
				Button.builder(Component.literal("Apple Music"), button -> navigateToService(APPLE_MUSIC_URL))
						.bounds(left + buttonWidth + buttonGap, top + buttonHeight + buttonGap, buttonWidth, buttonHeight)
						.build()
		);
		setServicePickerVisible(servicePickerVisible);
	}

	private void initSpotifyWarningWidgets() {
		int panelWidth = 380;
		int panelLeft = (width - panelWidth) / 2;
		int panelTop = getBrowserY() + Math.max(8, (getBrowserHeight() - 148) / 2 - 10);

		spotifyWarningContinueButton = addRenderableWidget(
				Button.builder(Component.literal("Continue"), button -> {
					String target = spotifyWarningTargetUrl == null || spotifyWarningTargetUrl.isBlank() ? SPOTIFY_URL : spotifyWarningTargetUrl;
					setSpotifyWarningVisible(false, null);
					navigateToServiceInternal(target);
				})
						.bounds(panelLeft + 12, panelTop + 112, 114, 20)
						.build()
		);
		spotifyWarningDontShowAgainButton = addRenderableWidget(
				Button.builder(Component.literal("Don't show again"), button -> {
					spotifyWarningSuppressed = true;
					saveSession();
					String target = spotifyWarningTargetUrl == null || spotifyWarningTargetUrl.isBlank() ? SPOTIFY_URL : spotifyWarningTargetUrl;
					setSpotifyWarningVisible(false, null);
					navigateToServiceInternal(target);
				})
						.bounds(panelLeft + 133, panelTop + 112, 114, 20)
						.build()
		);
		spotifyWarningCancelButton = addRenderableWidget(
				Button.builder(Component.literal("Cancel"), button -> {
					setSpotifyWarningVisible(false, null);
					if (browser != null) {
						String current = browser.getURL();
						if (current == null || current.isBlank() || BLANK_URL.equalsIgnoreCase(current)) {
							setServicePickerVisible(true);
						}
					}
				})
						.bounds(panelLeft + panelWidth - 126, panelTop + 112, 114, 20)
						.build()
		);
		setSpotifyWarningVisible(spotifyWarningVisible, spotifyWarningTargetUrl);
	}

	private void setMediaDropdownVisible(boolean visible) {
		mediaDropdownOpen = visible;
		if (mediaYouTubeButton != null) {
			mediaYouTubeButton.visible = visible;
		}
		if (mediaMusicButton != null) {
			mediaMusicButton.visible = visible;
		}
		if (mediaSpotifyButton != null) {
			mediaSpotifyButton.visible = visible;
		}
		if (mediaAppleMusicButton != null) {
			mediaAppleMusicButton.visible = visible;
		}
	}

	private void setServicePickerVisible(boolean visible) {
		servicePickerVisible = visible;
		if (chooseYouTubeButton != null) {
			chooseYouTubeButton.visible = visible;
		}
		if (chooseMusicButton != null) {
			chooseMusicButton.visible = visible;
		}
		if (chooseSpotifyButton != null) {
			chooseSpotifyButton.visible = visible;
		}
		if (chooseAppleMusicButton != null) {
			chooseAppleMusicButton.visible = visible;
		}
	}

	private void setSpotifyWarningVisible(boolean visible, String targetUrl) {
		spotifyWarningVisible = visible;
		if (targetUrl != null && !targetUrl.isBlank()) {
			spotifyWarningTargetUrl = targetUrl;
		} else if (!visible) {
			spotifyWarningTargetUrl = null;
		}
		if (visible) {
			setMediaDropdownVisible(false);
			setServicePickerVisible(false);
		}
		if (spotifyWarningContinueButton != null) {
			spotifyWarningContinueButton.visible = visible;
		}
		if (spotifyWarningDontShowAgainButton != null) {
			spotifyWarningDontShowAgainButton.visible = visible;
		}
		if (spotifyWarningCancelButton != null) {
			spotifyWarningCancelButton.visible = visible;
		}
	}

	private void toggleMediaDropdown() {
		setMediaDropdownVisible(!mediaDropdownOpen);
	}

	private static boolean isInsideButton(Button button, double x, double y) {
		if (button == null || !button.visible) {
			return false;
		}
		return x >= button.getX() && y >= button.getY()
				&& x < button.getX() + button.getWidth()
				&& y < button.getY() + button.getHeight();
	}

	private int getBrowserX() {
		return FRAME_MARGIN;
	}

	private int getBrowserY() {
		return FRAME_MARGIN + NAV_BAR_HEIGHT + NAV_BAR_GAP;
	}

	private int getBrowserWidth() {
		return Math.max(1, width - FRAME_MARGIN * 2);
	}

	private int getBrowserHeight() {
		return Math.max(1, height - getBrowserY() - FRAME_MARGIN);
	}

	private boolean isInBrowserBounds(double x, double y) {
		int browserX = getBrowserX();
		int browserY = getBrowserY();
		return x >= browserX && y >= browserY && x < (browserX + getBrowserWidth()) && y < (browserY + getBrowserHeight());
	}

	private int mouseToBrowserX(double x) {
		return (int) ((x - getBrowserX()) * minecraft.getWindow().getGuiScale());
	}

	private int mouseToBrowserY(double y) {
		return (int) ((y - getBrowserY()) * minecraft.getWindow().getGuiScale());
	}

	private void resizeBrowser() {
		if (browser != null) {
			browser.resize((int) (getBrowserWidth() * minecraft.getWindow().getGuiScale()), (int) (getBrowserHeight() * minecraft.getWindow().getGuiScale()));
			backgroundLowPowerApplied = false;
			backgroundLowPowerPixelWidth = -1;
			backgroundLowPowerPixelHeight = -1;
			popupBrowserPixelWidth = -1;
			popupBrowserPixelHeight = -1;
		}
	}

	private void refreshNavigationState() {
		if (browser == null) {
			return;
		}

		if (backButton != null) {
			backButton.active = browser.canGoBack();
		}
		if (forwardButton != null) {
			forwardButton.active = browser.canGoForward();
		}
		if (popupModeButton != null) {
			popupModeButton.active = minecraft != null && minecraft.level != null;
		}

		if (urlBox != null && !urlBox.isFocused()) {
			String currentUrl = browser.getURL();
			if (servicePickerVisible) {
				urlBox.setValue("Choose a service");
			} else if (currentUrl != null && !currentUrl.isBlank() && !currentUrl.equals(urlBox.getValue())) {
				urlBox.setValue(currentUrl);
				persistLastUrlIfValid(currentUrl);
			}
		}
	}

	private void navigateFromUrlField() {
		if (urlBox == null || browser == null) {
			return;
		}

		String input = urlBox.getValue();
		if (input == null) {
			return;
		}
		input = input.trim();
		if (input.isEmpty()) {
			return;
		}

		if (!input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
			input = "https://" + input;
		}

		urlBox.setValue(input);
		recordSecureNavigationTarget(input);
		browser.loadURL(input);
		persistLastUrlIfValid(input);
		browser.setFocus(true);
	}

	@Override
	public void tick() {
		super.tick();
		refreshNavigationState();
		if (browser != null) {
			String currentUrl = browser.getURL();
			if (isGoogleSigninRejectedUrl(currentUrl)) {
				if (!googleRejectedAutoHandled) {
					googleRejectedAutoHandled = true;
					browser.loadURL(DEFAULT_URL);
				}
				return;
			}
			googleRejectedAutoHandled = false;
			if (isSecurityInterstitialUrl(currentUrl)) {
				if (!secureErrorRetryDone && !lastSecureNavigationUrl.isBlank()) {
					long now = System.currentTimeMillis();
					if (secureErrorFirstSeenAtMs == 0L) {
						secureErrorFirstSeenAtMs = now;
					} else if (now - secureErrorFirstSeenAtMs >= 900L) {
						secureErrorRetryDone = true;
						browser.loadURL(lastSecureNavigationUrl);
					}
				}
			} else {
				secureErrorFirstSeenAtMs = 0L;
			}
		}
		spotifyCompatForegroundTicker++;
		if (spotifyCompatForegroundTicker >= 20 && browser != null) {
			spotifyCompatForegroundTicker = 0;
			String currentUrl = browser.getURL();
			if (isSpotifyUrl(currentUrl)) {
				executeSpotifyCompatScript(browser, currentUrl);
			}
		}
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		resizeBrowser();
	}

	@Override
	public void onClose() {
		super.onClose();
		if (popupModeEnabled && minecraft != null) {
			resizeSharedBrowserForPopup(minecraft);
		}
	}

	@Override
	public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		super.render(guiGraphics, mouseX, mouseY, delta);
		if (browser == null || !browser.isTextureReady()) {
			return;
		}

		Identifier texture = browser.getTextureIdentifier();
		if (texture == null) {
			return;
		}

		int browserWidth = getBrowserWidth();
		int browserHeight = getBrowserHeight();
		guiGraphics.blit(
				RenderPipelines.GUI_TEXTURED,
				texture,
				getBrowserX(),
				getBrowserY(),
				0.0F,
				0.0F,
				browserWidth,
				browserHeight,
				browserWidth,
				browserHeight
		);

		if (servicePickerVisible) {
			int panelWidth = 300;
			int panelHeight = 104;
			int panelLeft = (width - panelWidth) / 2;
			int panelTop = getBrowserY() + Math.max(8, (getBrowserHeight() - panelHeight) / 2 - 14);
			guiGraphics.fill(getBrowserX(), getBrowserY(), getBrowserX() + browserWidth, getBrowserY() + browserHeight, 0xCC101010);
			MediaUi.panel(guiGraphics, panelLeft, panelTop, panelWidth, panelHeight);
			guiGraphics.drawCenteredString(font, Component.literal("Choose a media"), panelLeft + (panelWidth / 2), panelTop + 10, MediaUi.TITLE);
			if (chooseYouTubeButton != null) {
				chooseYouTubeButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (chooseMusicButton != null) {
				chooseMusicButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (chooseSpotifyButton != null) {
				chooseSpotifyButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (chooseAppleMusicButton != null) {
				chooseAppleMusicButton.render(guiGraphics, mouseX, mouseY, delta);
			}
		}

		if (spotifyWarningVisible) {
			int panelWidth = 380;
			int panelHeight = 148;
			int panelLeft = (width - panelWidth) / 2;
			int panelTop = getBrowserY() + Math.max(8, (getBrowserHeight() - panelHeight) / 2 - 10);
			guiGraphics.fill(getBrowserX(), getBrowserY(), getBrowserX() + browserWidth, getBrowserY() + browserHeight, 0xCC101010);
			MediaUi.panel(guiGraphics, panelLeft, panelTop, panelWidth, panelHeight);
			guiGraphics.drawCenteredString(font, Component.literal("Spotify Warning"), panelLeft + (panelWidth / 2), panelTop + 10, MediaUi.TITLE);
			guiGraphics.drawCenteredString(font, Component.literal("Unfortunately, this browser has a licensing issue with Spotify."), panelLeft + (panelWidth / 2), panelTop + 31, MediaUi.TEXT);
			guiGraphics.drawCenteredString(font, Component.literal("If you want to use Spotify, you need to connect an"), panelLeft + (panelWidth / 2), panelTop + 47, MediaUi.MUTED_TEXT);
			guiGraphics.drawCenteredString(font, Component.literal("external Spotify app."), panelLeft + (panelWidth / 2), panelTop + 61, MediaUi.MUTED_TEXT);
			guiGraphics.drawCenteredString(font, Component.literal("Shortcut keys will continue to work."), panelLeft + (panelWidth / 2), panelTop + 75, MediaUi.MUTED_TEXT);
			if (spotifyWarningContinueButton != null) {
				spotifyWarningContinueButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (spotifyWarningDontShowAgainButton != null) {
				spotifyWarningDontShowAgainButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (spotifyWarningCancelButton != null) {
				spotifyWarningCancelButton.render(guiGraphics, mouseX, mouseY, delta);
			}
		}

		// Draw dropdown options again above browser texture.
		if (mediaDropdownOpen) {
			if (mediaYouTubeButton != null) {
				mediaYouTubeButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (mediaMusicButton != null) {
				mediaMusicButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (mediaSpotifyButton != null) {
				mediaSpotifyButton.render(guiGraphics, mouseX, mouseY, delta);
			}
			if (mediaAppleMusicButton != null) {
				mediaAppleMusicButton.render(guiGraphics, mouseX, mouseY, delta);
			}
		}
		MediaUi.coloredButton(guiGraphics, font, closeButton, "X", MediaUi.BUTTON_RED, MediaUi.BUTTON_RED_HOVER);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
		if (mediaDropdownOpen
				&& !isInsideButton(mediaDropdownButton, event.x(), event.y())
				&& !isInsideButton(mediaYouTubeButton, event.x(), event.y())
				&& !isInsideButton(mediaMusicButton, event.x(), event.y())
				&& !isInsideButton(mediaSpotifyButton, event.x(), event.y())
				&& !isInsideButton(mediaAppleMusicButton, event.x(), event.y())) {
			setMediaDropdownVisible(false);
		}

		boolean handled = super.mouseClicked(event, isDoubleClick);
		if (handled) {
			return true;
		}

		if (servicePickerVisible) {
			return true;
		}
		if (spotifyWarningVisible) {
			return true;
		}

		if (!isInBrowserBounds(event.x(), event.y())) {
			return false;
		}

		browser.sendMousePress(mouseToBrowserX(event.x()), mouseToBrowserY(event.y()), event.button());
		browser.setFocus(true);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = super.mouseReleased(event);
		if (handled) {
			return true;
		}

		browser.sendMouseRelease(mouseToBrowserX(event.x()), mouseToBrowserY(event.y()), event.button());
		browser.setFocus(true);
		return true;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (browser != null && isInBrowserBounds(mouseX, mouseY)) {
			browser.sendMouseMove(mouseToBrowserX(mouseX), mouseToBrowserY(mouseY));
		}
		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		boolean handled = super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		if (handled) {
			return true;
		}

		if (!isInBrowserBounds(mouseX, mouseY)) {
			return false;
		}
		if (servicePickerVisible || spotifyWarningVisible) {
			return true;
		}

		browser.sendMouseWheel(mouseToBrowserX(mouseX), mouseToBrowserY(mouseY), verticalAmount, 0);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (canHandleMediaHotkeys() && ExampleModClient.triggerMediaActionFromEvent(event)) {
			return true;
		}

		boolean arrowKey = event.key() == GLFW.GLFW_KEY_LEFT
				|| event.key() == GLFW.GLFW_KEY_RIGHT
				|| event.key() == GLFW.GLFW_KEY_UP
				|| event.key() == GLFW.GLFW_KEY_DOWN;
		if (arrowKey && (urlBox == null || !urlBox.isFocused())) {
			return true;
		}

		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (spotifyWarningVisible) {
				setSpotifyWarningVisible(false, null);
				return true;
			}
			onClose();
			return true;
		}

		if (urlBox != null && urlBox.isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			navigateFromUrlField();
			setFocused(null);
			browser.setFocus(true);
			return true;
		}

		if (urlBox != null && urlBox.isFocused()) {
			return super.keyPressed(event);
		}

		browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
		browser.setFocus(true);
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (canHandleMediaHotkeys() && ExampleModClient.isMediaControlKey(event)) {
			return true;
		}

		boolean arrowKey = event.key() == GLFW.GLFW_KEY_LEFT
				|| event.key() == GLFW.GLFW_KEY_RIGHT
				|| event.key() == GLFW.GLFW_KEY_UP
				|| event.key() == GLFW.GLFW_KEY_DOWN;
		if (arrowKey && (urlBox == null || !urlBox.isFocused())) {
			return true;
		}

		if (urlBox != null && urlBox.isFocused()) {
			return super.keyReleased(event);
		}

		browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
		browser.setFocus(true);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (super.charTyped(event)) {
			return true;
		}

		if (urlBox != null && urlBox.isFocused()) {
			return true;
		}

		if (event.codepoint() == (char) 0) {
			return false;
		}
		browser.sendKeyTyped((char) event.codepoint(), event.modifiers());
		browser.setFocus(true);
		return true;
	}

	private static final class PopupEditorScreen extends Screen {
		private static final int SIZE_STEP = 24;
		private static final int RESIZE_HIT_SIZE = 8;
		private boolean dragging;
		private boolean resizing;
		private int dragOffsetX;
		private int dragOffsetY;
		private ResizeHandle resizeHandle = ResizeHandle.NONE;
		private int resizeStartMouseX;
		private int resizeStartMouseY;
		private int resizeStartX;
		private int resizeStartY;
		private int resizeStartWidth;
		private int resizeStartHeight;
		private Button shrinkButton;
		private Button growButton;
		private Button okButton;
		private Button backToBrowserButton;
		private Button removeButton;

		private enum ResizeHandle {
			NONE,
			LEFT,
			RIGHT,
			TOP,
			BOTTOM,
			TOP_LEFT,
			TOP_RIGHT,
			BOTTOM_LEFT,
			BOTTOM_RIGHT
		}

		private PopupEditorScreen() {
			super(Component.literal("Popup Setup"));
		}

		private void goBackToBrowser(boolean keepPopup) {
			popupModeEnabled = keepPopup;
			saveSession();
			if (minecraft != null) {
				minecraft.setScreen(new YouTubeBrowserScreen(false, null));
			}
		}

		@Override
		protected void init() {
			super.init();
			if (minecraft != null) {
				ensurePopupLayoutInitialized(minecraft);
				resizeSharedBrowserForPopup(minecraft);
			}

			int controlsY = height - 28;
			int startX = (width - 222) / 2;
			shrinkButton = addRenderableWidget(
					Button.builder(Component.literal("-"), button -> {
						if (minecraft != null) {
							resizePopupByWidth(minecraft, -SIZE_STEP);
						}
					})
							.bounds(startX, controlsY, 20, 20)
							.build()
			);
			growButton = addRenderableWidget(
					Button.builder(Component.literal("+"), button -> {
						if (minecraft != null) {
							resizePopupByWidth(minecraft, SIZE_STEP);
						}
					})
							.bounds(startX + 24, controlsY, 20, 20)
							.build()
			);
			okButton = addRenderableWidget(
					Button.builder(Component.empty(), button -> {
						popupModeEnabled = true;
						saveSession();
						if (minecraft != null) {
							ExampleModClient.showActionMessage(minecraft, "Popup saved");
							minecraft.setScreen(null);
							ExampleModClient.requestInGameMouseRestore(minecraft);
						}
					})
							.bounds(startX + 52, controlsY, 40, 20)
							.build()
			);
			backToBrowserButton = addRenderableWidget(
					Button.builder(Component.literal("BACK"), button -> goBackToBrowser(false))
							.bounds(startX + 96, controlsY, 52, 20)
							.build()
			);
			removeButton = addRenderableWidget(
					Button.builder(Component.empty(), button -> {
						popupModeEnabled = false;
						saveSession();
						ExampleModClient.showActionMessage(minecraft, "Popup removed");
						goBackToBrowser(false);
					})
							.bounds(startX + 152, controlsY, 70, 20)
							.build()
			);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}

		@Override
		public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
			renderTransparentBackground(guiGraphics);
			if (minecraft != null) {
				ensurePopupLayoutInitialized(minecraft);
			}
			guiGraphics.fill(
					Math.max(0, popupX - 1),
					Math.max(0, popupY - 1),
					popupX + popupWidth + 1,
					popupY + popupHeight + 1,
					0xCC000000
			);
			renderPopupTexture(guiGraphics, popupX, popupY, popupWidth, popupHeight);
			MediaUi.border(guiGraphics, Math.max(0, popupX - 2), Math.max(0, popupY - 2), popupWidth + 4, popupHeight + 4, 0xFF78F29A);
			guiGraphics.fill(8, 8, 264, 36, 0x99000000);
			MediaUi.border(guiGraphics, 8, 8, 256, 28, MediaUi.PANEL_BORDER);
			guiGraphics.drawString(font, Component.literal("Drag popup. Resize from edges, wheel or +/-."), 14, 12, MediaUi.TEXT, false);
			guiGraphics.drawString(font, Component.literal("Select OK to apply."), 14, 25, MediaUi.MUTED_TEXT, false);
			super.render(guiGraphics, mouseX, mouseY, delta);
			MediaUi.coloredButton(guiGraphics, font, shrinkButton, "-", MediaUi.BUTTON_DARK, MediaUi.BUTTON_DARK_HOVER);
			MediaUi.coloredButton(guiGraphics, font, growButton, "+", MediaUi.BUTTON_DARK, MediaUi.BUTTON_DARK_HOVER);
			MediaUi.coloredButton(guiGraphics, font, okButton, "OK", MediaUi.BUTTON_GREEN, MediaUi.BUTTON_GREEN_HOVER);
			MediaUi.coloredButton(guiGraphics, font, backToBrowserButton, "BACK", MediaUi.BUTTON_DARK, MediaUi.BUTTON_DARK_HOVER);
			MediaUi.coloredButton(guiGraphics, font, removeButton, "REMOVE", MediaUi.BUTTON_RED, MediaUi.BUTTON_RED_HOVER);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
			if (super.mouseClicked(event, isDoubleClick)) {
				return true;
			}

			if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				return false;
			}

			ResizeHandle handle = getResizeHandle(event.x(), event.y());
			if (handle != ResizeHandle.NONE) {
				resizing = true;
				resizeHandle = handle;
				resizeStartMouseX = (int) event.x();
				resizeStartMouseY = (int) event.y();
				resizeStartX = popupX;
				resizeStartY = popupY;
				resizeStartWidth = popupWidth;
				resizeStartHeight = popupHeight;
				return true;
			}

			if (isInsidePopup(event.x(), event.y())) {
				dragging = true;
				dragOffsetX = (int) event.x() - popupX;
				dragOffsetY = (int) event.y() - popupY;
				return true;
			}
			return false;
		}

		@Override
		public void mouseMoved(double mouseX, double mouseY) {
			if (resizing && minecraft != null) {
				applyResizeDrag((int) mouseX, (int) mouseY);
			} else if (dragging && minecraft != null) {
				movePopupTo(minecraft, (int) mouseX - dragOffsetX, (int) mouseY - dragOffsetY);
			}
			super.mouseMoved(mouseX, mouseY);
		}

		@Override
		public boolean mouseReleased(MouseButtonEvent event) {
			dragging = false;
			resizing = false;
			resizeHandle = ResizeHandle.NONE;
			return super.mouseReleased(event);
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
			if (verticalAmount != 0.0 && minecraft != null) {
				resizePopupByWidth(minecraft, verticalAmount > 0 ? SIZE_STEP : -SIZE_STEP);
				return true;
			}
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE && minecraft != null) {
				goBackToBrowser(false);
				return true;
			}
			return super.keyPressed(event);
		}

		private ResizeHandle getResizeHandle(double mouseX, double mouseY) {
			double left = popupX;
			double right = popupX + popupWidth;
			double top = popupY;
			double bottom = popupY + popupHeight;
			if (mouseX < left - RESIZE_HIT_SIZE || mouseX > right + RESIZE_HIT_SIZE
					|| mouseY < top - RESIZE_HIT_SIZE || mouseY > bottom + RESIZE_HIT_SIZE) {
				return ResizeHandle.NONE;
			}

			boolean nearLeft = Math.abs(mouseX - left) <= RESIZE_HIT_SIZE;
			boolean nearRight = Math.abs(mouseX - right) <= RESIZE_HIT_SIZE;
			boolean nearTop = Math.abs(mouseY - top) <= RESIZE_HIT_SIZE;
			boolean nearBottom = Math.abs(mouseY - bottom) <= RESIZE_HIT_SIZE;

			if (nearLeft && nearTop) {
				return ResizeHandle.TOP_LEFT;
			}
			if (nearRight && nearTop) {
				return ResizeHandle.TOP_RIGHT;
			}
			if (nearLeft && nearBottom) {
				return ResizeHandle.BOTTOM_LEFT;
			}
			if (nearRight && nearBottom) {
				return ResizeHandle.BOTTOM_RIGHT;
			}
			if (nearLeft) {
				return ResizeHandle.LEFT;
			}
			if (nearRight) {
				return ResizeHandle.RIGHT;
			}
			if (nearTop) {
				return ResizeHandle.TOP;
			}
			if (nearBottom) {
				return ResizeHandle.BOTTOM;
			}
			return ResizeHandle.NONE;
		}

		private void applyResizeDrag(int mouseX, int mouseY) {
			int dx = mouseX - resizeStartMouseX;
			int dy = mouseY - resizeStartMouseY;
			int widthFromX;
			int widthFromY;
			int desiredWidth = resizeStartWidth;

			switch (resizeHandle) {
				case RIGHT -> desiredWidth = resizeStartWidth + dx;
				case LEFT -> desiredWidth = resizeStartWidth - dx;
				case BOTTOM -> desiredWidth = resizeStartWidth + Math.round(dy * POPUP_ASPECT_RATIO);
				case TOP -> desiredWidth = resizeStartWidth - Math.round(dy * POPUP_ASPECT_RATIO);
				case BOTTOM_RIGHT -> {
					widthFromX = resizeStartWidth + dx;
					widthFromY = resizeStartWidth + Math.round(dy * POPUP_ASPECT_RATIO);
					desiredWidth = pickDominantWidth(widthFromX, widthFromY);
				}
				case TOP_RIGHT -> {
					widthFromX = resizeStartWidth + dx;
					widthFromY = resizeStartWidth - Math.round(dy * POPUP_ASPECT_RATIO);
					desiredWidth = pickDominantWidth(widthFromX, widthFromY);
				}
				case BOTTOM_LEFT -> {
					widthFromX = resizeStartWidth - dx;
					widthFromY = resizeStartWidth + Math.round(dy * POPUP_ASPECT_RATIO);
					desiredWidth = pickDominantWidth(widthFromX, widthFromY);
				}
				case TOP_LEFT -> {
					widthFromX = resizeStartWidth - dx;
					widthFromY = resizeStartWidth - Math.round(dy * POPUP_ASPECT_RATIO);
					desiredWidth = pickDominantWidth(widthFromX, widthFromY);
				}
				default -> {
					return;
				}
			}

			popupWidth = desiredWidth;
			popupHeight = Math.round(popupWidth / POPUP_ASPECT_RATIO);

			int anchorRight = resizeStartX + resizeStartWidth;
			int anchorBottom = resizeStartY + resizeStartHeight;
			boolean anchorLeftEdge = resizeHandle == ResizeHandle.LEFT || resizeHandle == ResizeHandle.TOP_LEFT || resizeHandle == ResizeHandle.BOTTOM_LEFT;
			boolean anchorTopEdge = resizeHandle == ResizeHandle.TOP || resizeHandle == ResizeHandle.TOP_LEFT || resizeHandle == ResizeHandle.TOP_RIGHT;

			popupX = anchorLeftEdge ? anchorRight - popupWidth : resizeStartX;
			popupY = anchorTopEdge ? anchorBottom - popupHeight : resizeStartY;

			clampPopupLayoutToScreen(minecraft);
			resizeSharedBrowserForPopup(minecraft);
			saveSession();
		}

		private int pickDominantWidth(int widthFromX, int widthFromY) {
			int deltaX = Math.abs(widthFromX - resizeStartWidth);
			int deltaY = Math.abs(widthFromY - resizeStartWidth);
			return deltaX >= deltaY ? widthFromX : widthFromY;
		}
	}
}

