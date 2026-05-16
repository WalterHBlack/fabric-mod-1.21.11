package com.example.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.example.ExampleMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MediaBridgeClient {
	private static final Gson GSON = new Gson();
	private static final long STATE_SEND_INTERVAL_MS = 1000L;
	private static final long REMOTE_TIMEOUT_MS = 5000L;
	private static final long SEND_LOG_INTERVAL_MS = 5000L;
	private static final float VOLUME_SMOOTHING = 0.30F;
	private static final float PAN_SMOOTHING = 0.40F;
	private static final float DEFAULT_RADIUS = 32.0F;
	private static final float MIN_RADIUS = 4.0F;
	private static final float MAX_RADIUS = 128.0F;

	private static MCEFBrowser remoteBrowser;
	private static String remoteUrl;
	private static UUID remoteSharer;
	private static float remoteRadius = DEFAULT_RADIUS;
	private static boolean remoteGlobalPlayback;
	private static float remoteVolume = -1.0F;
	private static float remotePan;
	private static boolean spatialAudioEnabled = true;
	private static long lastStateSendMs;
	private static long lastRemoteStateMs;
	private static long lastRemoteVolumeUpdateMs;
	private static long lastSendFailureLogMs;
	private static long lastCapabilityLogMs;
	private static boolean localStateSent;
	private static boolean localShareEnabled;
	private static boolean shareToggleRequested;
	private static Boolean shareSetRequested;
	private static String groupCreateRequested;
	private static boolean groupLeaveRequested;
	private static boolean groupMembersRefreshRequested;
	private static boolean groupMutationPending;
	private static long groupMutationStartedMs;
	private static String groupJoinRequested;
	private static String localGroupCode = "";
	private static long lastGroupMembersRequestMs;
	private static final List<String> localGroupMembers = new ArrayList<>();
	private static final Map<UUID, GroupPlayerState> groupPlayerStates = new HashMap<>();
	private static boolean initialized;

	private record GroupPlayerState(UUID uuid, String name, String group) {
	}

	private MediaBridgeClient() {
	}

	public static void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;

		PayloadTypeRegistry.playC2S().register(MediaSyncPayload.TYPE, MediaSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(MediaSyncPayload.TYPE, MediaSyncPayload.STREAM_CODEC);

		ClientPlayNetworking.registerGlobalReceiver(MediaSyncPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> handleServerMessage(context.client(), payload.json()));
		});
	}

	public static void tick(Minecraft client) {
		if (client == null) {
			return;
		}
		sendLocalStateIfNeeded(client);
		tickRemotePlayback(client);
	}

	public static void shutdown() {
		stopRemotePlayback();
		localStateSent = false;
		localShareEnabled = false;
		shareToggleRequested = false;
		shareSetRequested = null;
		groupCreateRequested = null;
		groupLeaveRequested = false;
		groupMembersRefreshRequested = false;
		groupMutationPending = false;
		groupMutationStartedMs = 0L;
		groupJoinRequested = null;
		localGroupCode = "";
		localGroupMembers.clear();
		groupPlayerStates.clear();
		lastGroupMembersRequestMs = 0L;
	}

	public static boolean isSpatialAudioEnabled() {
		return spatialAudioEnabled;
	}

	public static boolean toggleSpatialAudio() {
		spatialAudioEnabled = !spatialAudioEnabled;
		return spatialAudioEnabled;
	}

	public static boolean isLocalShareEnabled() {
		return localShareEnabled;
	}

	public static boolean requestShareToggle() {
		localShareEnabled = !localShareEnabled;
		shareToggleRequested = true;
		shareSetRequested = null;
		if (!localShareEnabled) {
			localStateSent = false;
		}
		return localShareEnabled;
	}

	public static boolean requestShareSet(boolean enabled) {
		localShareEnabled = enabled;
		shareSetRequested = enabled;
		shareToggleRequested = false;
		if (!enabled) {
			localStateSent = false;
		}
		return localShareEnabled;
	}

	public static String getLocalGroupCode() {
		return localGroupCode;
	}

	public static List<String> getLocalGroupMembers() {
		if (localGroupCode.isBlank()) {
			return List.copyOf(localGroupMembers);
		}

		List<String> members = new ArrayList<>();
		for (GroupPlayerState state : groupPlayerStates.values()) {
			if (localGroupCode.equalsIgnoreCase(state.group())) {
				addNameIfMissing(members, state.name());
			}
		}
		for (String member : localGroupMembers) {
			addNameIfMissing(members, member);
		}

		Minecraft client = Minecraft.getInstance();
		if (client != null && client.player != null) {
			addNameIfMissing(members, client.player.getName().getString());
		}
		members.sort(Comparator.comparing(String::toLowerCase));
		return members;
	}

	public static boolean requestGroupCreate(String code) {
		if (!canQueueServerRequest()) {
			return false;
		}
		if (code == null) {
			return false;
		}
		String normalized = normalizeGroupName(code);
		if (normalized == null) {
			return false;
		}
		groupCreateRequested = normalized;
		groupLeaveRequested = false;
		groupMembersRefreshRequested = false;
		groupMutationPending = true;
		groupMutationStartedMs = System.currentTimeMillis();
		groupJoinRequested = null;
		localGroupCode = normalized;
		localGroupMembers.clear();
		ensureLocalPlayerListed(Minecraft.getInstance());
		updateOwnGroupState(Minecraft.getInstance());
		ExampleMod.LOGGER.info("MediaBridge: queued group create '{}', local members now {}", normalized, localGroupMembers.size());
		return true;
	}

	public static boolean requestGroupJoin(String code) {
		if (!canQueueServerRequest()) {
			return false;
		}
		if (code == null) {
			return false;
		}
		String normalized = code.trim();
		if (normalized.isEmpty()) {
			return false;
		}
		groupJoinRequested = normalized;
		groupCreateRequested = null;
		groupLeaveRequested = false;
		groupMembersRefreshRequested = false;
		groupMutationPending = true;
		groupMutationStartedMs = System.currentTimeMillis();
		localGroupCode = normalized;
		localGroupMembers.clear();
		ensureLocalPlayerListed(Minecraft.getInstance());
		updateOwnGroupState(Minecraft.getInstance());
		ExampleMod.LOGGER.info("MediaBridge: queued group join '{}', local members now {}", normalized, localGroupMembers.size());
		return true;
	}

	public static boolean requestGroupLeave() {
		if (!canQueueServerRequest()) {
			return false;
		}
		groupLeaveRequested = true;
		groupCreateRequested = null;
		groupJoinRequested = null;
		groupMembersRefreshRequested = false;
		groupMutationPending = true;
		groupMutationStartedMs = System.currentTimeMillis();
		localGroupCode = "";
		localGroupMembers.clear();
		groupPlayerStates.clear();
		ExampleMod.LOGGER.info("MediaBridge: queued group leave");
		return true;
	}

	public static boolean requestGroupMembersRefresh() {
		if (!canQueueServerRequest()) {
			return false;
		}
		groupMembersRefreshRequested = true;
		return true;
	}

	private static void sendLocalStateIfNeeded(Minecraft client) {
		if (client.player == null || client.getConnection() == null) {
			groupCreateRequested = null;
			groupLeaveRequested = false;
			groupMembersRefreshRequested = false;
			groupMutationPending = false;
			groupMutationStartedMs = 0L;
			groupJoinRequested = null;
			return;
		}

		String url = YouTubeBrowserScreen.getManagedBrowserUrl();
		boolean hasLocalMedia = YouTubeBrowserScreen.hasManagedBrowserForSync() && url != null && !url.isBlank();
		long now = System.currentTimeMillis();
		if (groupMutationPending && groupMutationStartedMs > 0L && now - groupMutationStartedMs > 2500L) {
			groupMutationPending = false;
			groupMembersRefreshRequested = true;
		}
		if (!ClientPlayNetworking.canSend(MediaSyncPayload.TYPE) && now - lastCapabilityLogMs >= SEND_LOG_INTERVAL_MS) {
			ExampleMod.LOGGER.info("MediaBridge: server did not advertise channel support; trying direct send fallback.");
			lastCapabilityLogMs = now;
		}

		if (shareToggleRequested) {
			if (sendClientMessage("share_toggle", null, null, null)) {
				shareToggleRequested = false;
			}
		}
		if (shareSetRequested != null) {
			if (sendClientMessage("share_set", null, shareSetRequested, null)) {
				shareSetRequested = null;
			}
		}
		if (groupCreateRequested != null) {
			if (sendClientMessage("group_create", null, null, groupCreateRequested)) {
				groupCreateRequested = null;
			}
		}
		if (groupJoinRequested != null) {
			if (sendClientMessage("group_join", null, null, groupJoinRequested)) {
				groupJoinRequested = null;
			}
		}
		if (groupLeaveRequested) {
			if (sendClientMessage("group_leave", null, null, null)) {
				groupLeaveRequested = false;
			}
		}
		if (!groupMutationPending && groupMembersRefreshRequested && now - lastGroupMembersRequestMs >= 350L) {
			if (sendClientMessage("group_members_request", null, null, null)) {
				groupMembersRefreshRequested = false;
				lastGroupMembersRequestMs = now;
			}
		}

		if (!localShareEnabled) {
			if (localStateSent && now - lastStateSendMs >= 400L) {
				if (sendClientMessage("stop", null, null, null)) {
					lastStateSendMs = now;
					localStateSent = false;
				}
			}
			return;
		}

		if (!hasLocalMedia) {
			if (localStateSent && now - lastStateSendMs >= 400L) {
				if (sendClientMessage("stop", null, null, null)) {
					lastStateSendMs = now;
					localStateSent = false;
				}
			}
			return;
		}

		if (now - lastStateSendMs < STATE_SEND_INTERVAL_MS) {
			return;
		}

		if (sendClientMessage("state", url, null, null)) {
			lastStateSendMs = now;
			localStateSent = true;
		}
	}

	private static boolean sendClientMessage(String type, String url, Boolean enabled, String group) {
		JsonObject payload = new JsonObject();
		payload.addProperty("type", type);
		if (url != null && !url.isBlank()) {
			payload.addProperty("url", url);
		}
		if (enabled != null) {
			payload.addProperty("enabled", enabled);
		}
		if (group != null && !group.isBlank()) {
			payload.addProperty("group", group);
		}
		try {
			ClientPlayNetworking.send(new MediaSyncPayload(GSON.toJson(payload)));
			return true;
		} catch (Exception exception) {
			long now = System.currentTimeMillis();
			if (now - lastSendFailureLogMs >= SEND_LOG_INTERVAL_MS) {
				ExampleMod.LOGGER.warn("MediaBridge: failed to send {} payload: {}", type, exception.toString());
				lastSendFailureLogMs = now;
			}
			return false;
		}
	}

	private static void handleServerMessage(Minecraft client, String json) {
		try {
			JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
			String type = payload.has("type") ? payload.get("type").getAsString() : "";

			if ("play".equals(type)) {
				String sharerValue = payload.has("sharer") ? payload.get("sharer").getAsString() : "";
				String url = payload.has("url") ? payload.get("url").getAsString() : "";
				float radius = payload.has("radius") ? payload.get("radius").getAsFloat() : DEFAULT_RADIUS;
				boolean globalPlayback = payload.has("global_playback") && payload.get("global_playback").getAsBoolean();
				UUID sharer = UUID.fromString(sharerValue);

				if (client.player != null && sharer.equals(client.player.getUUID())) {
					return;
				}
				if (url == null || url.isBlank()) {
					return;
				}

				ensureRemoteBrowser(url);
				remoteSharer = sharer;
				remoteRadius = clamp(radius, MIN_RADIUS, MAX_RADIUS);
				remoteGlobalPlayback = globalPlayback;
				lastRemoteStateMs = System.currentTimeMillis();
				return;
			}

			if ("share_state".equals(type)) {
				boolean enabled = payload.has("enabled") && payload.get("enabled").getAsBoolean();
				localShareEnabled = enabled;
				if (!enabled) {
					localStateSent = false;
				}
				if (client.player != null) {
					client.player.displayClientMessage(
							net.minecraft.network.chat.Component.literal("Media Share: " + (enabled ? "ON" : "OFF")),
							true
					);
				}
				return;
			}

			if ("group_state".equals(type)) {
				boolean wasPending = groupMutationPending;
				String previousLocalGroup = localGroupCode;
				groupMutationPending = false;
				groupMutationStartedMs = 0L;
				String code = payload.has("group") ? payload.get("group").getAsString() : "";
				String serverGroupCode = code == null ? "" : code.trim();
				if (serverGroupCode.isBlank() && wasPending && !previousLocalGroup.isBlank()) {
					localGroupCode = previousLocalGroup;
					ensureLocalPlayerListed(client);
					updateOwnGroupState(client);
					groupMembersRefreshRequested = true;
					ExampleMod.LOGGER.info("MediaBridge: ignored blank group_state while pending; keeping '{}'", localGroupCode);
					return;
				}
				localGroupCode = serverGroupCode;
				if (localGroupCode.isBlank()) {
					localGroupMembers.clear();
					groupPlayerStates.clear();
				} else {
					replaceMembersFromPayload(client, payload);
					updateOwnGroupState(client);
					ensureLocalPlayerListed(client);
					groupMembersRefreshRequested = true;
				}
				ExampleMod.LOGGER.info("MediaBridge: received group_state '{}', members {}", localGroupCode, localGroupMembers.size());
				if (client.player != null) {
					String text = localGroupCode.isBlank() ? "Group: NONE" : "Group: " + localGroupCode;
					client.player.displayClientMessage(net.minecraft.network.chat.Component.literal(text), true);
				}
				return;
			}

			if ("group_members".equals(type)) {
				String code = payload.has("group") ? payload.get("group").getAsString() : "";
				String normalizedCode = code == null ? "" : code.trim();
				if (normalizedCode.isBlank()) {
					if (!groupMutationPending) {
						localGroupCode = "";
						localGroupMembers.clear();
					}
					return;
				}
				if (localGroupCode.isBlank()) {
					localGroupCode = normalizedCode;
				} else if (!normalizedCode.equalsIgnoreCase(localGroupCode)) {
					return;
				}
				groupMutationPending = false;
				groupMutationStartedMs = 0L;
				replaceMembersFromPayload(client, payload);
				ensureLocalPlayerListed(client);
				ExampleMod.LOGGER.info("MediaBridge: received group_members '{}', members {}", localGroupCode, localGroupMembers.size());
				return;
			}

			if ("group_players".equals(type)) {
				groupPlayerStates.clear();
				if (payload.has("players") && payload.get("players").isJsonArray()) {
					JsonArray players = payload.getAsJsonArray("players");
					for (JsonElement element : players) {
						if (element == null || !element.isJsonObject()) {
							continue;
						}
						JsonObject player = element.getAsJsonObject();
						if (!player.has("uuid") || !player.has("name")) {
							continue;
						}
						UUID uuid = UUID.fromString(player.get("uuid").getAsString());
						String name = player.get("name").getAsString();
						String group = player.has("group") ? player.get("group").getAsString() : "";
						groupPlayerStates.put(uuid, new GroupPlayerState(uuid, name == null ? "" : name.trim(), group == null ? "" : group.trim()));
					}
				}
				updateOwnGroupState(client);
				ExampleMod.LOGGER.info("MediaBridge: received group_players snapshot with {} players", groupPlayerStates.size());
				return;
			}

			if ("stop".equals(type)) {
				UUID sharer = null;
				if (payload.has("sharer")) {
					sharer = UUID.fromString(payload.get("sharer").getAsString());
				}
				if (sharer == null || (remoteSharer != null && remoteSharer.equals(sharer))) {
					stopRemotePlayback();
				}
			}
		} catch (Exception exception) {
			ExampleMod.LOGGER.warn("MediaBridge: failed to handle server payload '{}': {}", json, exception.toString());
		}
	}

	private static void tickRemotePlayback(Minecraft client) {
		if (remoteBrowser == null) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastRemoteStateMs > REMOTE_TIMEOUT_MS) {
			stopRemotePlayback();
			return;
		}

		Player sharerPlayer = findSharerPlayer(client);
		float targetVolume = computeProximityVolume(client, sharerPlayer);
		float targetPan = spatialAudioEnabled ? computeStereoPan(client, sharerPlayer) : 0.0F;

		if (remoteVolume < 0.0F) {
			remoteVolume = targetVolume;
		} else {
			remoteVolume += (targetVolume - remoteVolume) * VOLUME_SMOOTHING;
			remoteVolume = clamp(remoteVolume, 0.0F, 1.0F);
		}

		remotePan += (targetPan - remotePan) * PAN_SMOOTHING;
		remotePan = clamp(remotePan, -1.0F, 1.0F);

		if (Math.abs(targetVolume - remoteVolume) < 0.005F
				&& Math.abs(targetPan - remotePan) < 0.01F
				&& now - lastRemoteVolumeUpdateMs < 90L) {
			return;
		}

		lastRemoteVolumeUpdateMs = now;
		String url = remoteBrowser.getURL();
		if (url == null || url.isBlank()) {
			url = remoteUrl;
		}

		String volumeText = String.format(Locale.US, "%.3f", remoteVolume);
		String panText = String.format(Locale.US, "%.3f", remotePan);
		remoteBrowser.executeJavaScript(
				"""
				(() => {
				  const findMedia = () => {
				    const direct = document.querySelector("video, audio");
				    if (direct) return direct;
				
				    const roots = [];
				    const collectRoots = (root) => {
				      if (!root || roots.includes(root) || !root.querySelectorAll) return;
				      roots.push(root);
				      for (const node of root.querySelectorAll("*")) {
				        if (node && node.shadowRoot) {
				          collectRoots(node.shadowRoot);
				        }
				      }
				    };
				
				    collectRoots(document);
				    for (const root of roots) {
				      const media = root.querySelector("video, audio");
				      if (media) return media;
				    }
				    return null;
				  };
				
				  const media = findMedia();
				  if (!media) return;
				
				  const targetVolume = %s;
				  const targetPan = %s;
				  const AudioCtx = window.AudioContext || window.webkitAudioContext;
				
				  if (AudioCtx) {
				    if (!media.__mcBridgeAudioSetup) {
				      try {
				        const ctx = new AudioCtx();
				        const source = ctx.createMediaElementSource(media);
				        const gain = ctx.createGain();
				        const panner = ctx.createStereoPanner ? ctx.createStereoPanner() : null;
				
				        if (panner) {
				          source.connect(panner);
				          panner.connect(gain);
				        } else {
				          source.connect(gain);
				        }
				        gain.connect(ctx.destination);
				
				        media.__mcBridgeCtx = ctx;
				        media.__mcBridgeGain = gain;
				        media.__mcBridgePanner = panner;
				        media.__mcBridgeAudioSetup = true;
				      } catch (e) {
				        media.__mcBridgeAudioSetup = false;
				      }
				    }
				
				    if (media.__mcBridgeCtx && media.__mcBridgeCtx.state === "suspended") {
				      media.__mcBridgeCtx.resume().catch(() => {});
				    }
				
				    if (media.__mcBridgeGain) {
				      media.muted = true;
				      media.__mcBridgeGain.gain.value = targetVolume;
				      if (media.__mcBridgePanner) {
				        media.__mcBridgePanner.pan.value = targetPan;
				      }
				    } else {
				      media.muted = false;
				      media.volume = targetVolume;
				    }
				  } else {
				    media.muted = false;
				    media.volume = targetVolume;
				  }
				
				  if (media.paused && targetVolume > 0.01) {
				    media.play().catch(() => {});
				  }
				})();
				""".formatted(volumeText, panText),
				url == null || url.isBlank() ? "https://www.youtube.com" : url,
				0
		);
	}

	private static Player findSharerPlayer(Minecraft client) {
		if (client.player == null || client.level == null || remoteSharer == null) {
			return null;
		}
		for (Player player : client.level.players()) {
			if (remoteSharer.equals(player.getUUID())) {
				return player;
			}
		}
		return null;
	}

	private static float computeProximityVolume(Minecraft client, Player sharerPlayer) {
		if (remoteGlobalPlayback) {
			return 1.0F;
		}
		if (client.player == null || sharerPlayer == null) {
			return 0.0F;
		}

		float distance = client.player.distanceTo(sharerPlayer);
		float ratio = 1.0F - (distance / Math.max(MIN_RADIUS, remoteRadius));
		return clamp(ratio, 0.0F, 1.0F);
	}

	private static float computeStereoPan(Minecraft client, Player sharerPlayer) {
		if (remoteGlobalPlayback) {
			return 0.0F;
		}
		if (client.player == null || sharerPlayer == null) {
			return 0.0F;
		}

		Vec3 listenerPos = client.player.position();
		Vec3 sourcePos = sharerPlayer.position();
		Vec3 toSourceFlat = new Vec3(sourcePos.x - listenerPos.x, 0.0D, sourcePos.z - listenerPos.z);
		double distance = toSourceFlat.length();
		if (distance < 0.001D) {
			return 0.0F;
		}

		Vec3 forward = client.player.getViewVector(1.0F);
		Vec3 forwardFlat = new Vec3(forward.x, 0.0D, forward.z);
		if (forwardFlat.lengthSqr() < 1.0e-6D) {
			forwardFlat = new Vec3(0.0D, 0.0D, 1.0D);
		} else {
			forwardFlat = forwardFlat.normalize();
		}

		Vec3 right = new Vec3(-forwardFlat.z, 0.0D, forwardFlat.x);
		Vec3 direction = toSourceFlat.normalize();
		float pan = (float) direction.dot(right);

		float distanceWeight = clamp((float) (distance / Math.max(1.0D, remoteRadius * 0.35D)), 0.0F, 1.0F);
		return clamp(pan * distanceWeight, -1.0F, 1.0F);
	}

	private static void ensureRemoteBrowser(String url) {
		if (!MCEF.isInitialized()) {
			return;
		}

		if (remoteBrowser == null) {
			remoteBrowser = MCEF.createBrowser(url, true);
			remoteUrl = url;
			remoteVolume = -1.0F;
			remotePan = 0.0F;
			lastRemoteVolumeUpdateMs = 0L;
		} else if (!url.equals(remoteUrl)) {
			remoteBrowser.loadURL(url);
			remoteUrl = url;
		}

		remoteBrowser.setWindowVisibility(true);
		remoteBrowser.setFocus(false);
	}

	private static void stopRemotePlayback() {
		if (remoteBrowser != null) {
			remoteBrowser.close();
		}

		remoteBrowser = null;
		remoteUrl = null;
		remoteSharer = null;
		remoteRadius = DEFAULT_RADIUS;
		remoteGlobalPlayback = false;
		remoteVolume = -1.0F;
		remotePan = 0.0F;
		lastRemoteStateMs = 0L;
		lastRemoteVolumeUpdateMs = 0L;
	}

	private static boolean canQueueServerRequest() {
		Minecraft client = Minecraft.getInstance();
		return client != null && client.player != null && client.getConnection() != null;
	}

	private static String normalizeGroupName(String code) {
		if (code == null) {
			return null;
		}
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		if (normalized.length() < 3 || normalized.length() > 12) {
			return null;
		}
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
			if (!ok) {
				return null;
			}
		}
		return normalized;
	}

	private static void replaceMembersFromPayload(Minecraft client, JsonObject payload) {
		localGroupMembers.clear();
		if (payload.has("members") && payload.get("members").isJsonArray()) {
			JsonArray members = payload.getAsJsonArray("members");
			for (JsonElement element : members) {
				if (element != null && element.isJsonPrimitive()) {
					String name = element.getAsString();
					if (name != null && !name.isBlank()) {
						localGroupMembers.add(name.trim());
					}
				}
			}
		}
		ensureLocalPlayerListed(client);
	}

	private static void ensureLocalPlayerListed(Minecraft client) {
		if (client == null || client.player == null || localGroupCode.isBlank()) {
			return;
		}

		String ownName = client.player.getName().getString();
		if (ownName == null || ownName.isBlank()) {
			return;
		}

		for (String member : localGroupMembers) {
			if (ownName.equalsIgnoreCase(member)) {
				return;
			}
		}
		localGroupMembers.add(0, ownName);
	}

	private static void updateOwnGroupState(Minecraft client) {
		if (client == null || client.player == null || localGroupCode.isBlank()) {
			return;
		}
		UUID uuid = client.player.getUUID();
		String name = client.player.getName().getString();
		groupPlayerStates.put(uuid, new GroupPlayerState(uuid, name, localGroupCode));
	}

	private static void addNameIfMissing(List<String> names, String name) {
		if (name == null || name.isBlank()) {
			return;
		}
		for (String existing : names) {
			if (name.equalsIgnoreCase(existing)) {
				return;
			}
		}
		names.add(name);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
