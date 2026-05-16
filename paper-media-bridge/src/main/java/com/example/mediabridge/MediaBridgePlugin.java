package com.example.mediabridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MediaBridgePlugin extends JavaPlugin implements PluginMessageListener, CommandExecutor, TabCompleter, Listener {
	private static final String CHANNEL = "modid:media_sync";
	private static final double MIN_RADIUS = 4.0D;
	private static final double MAX_RADIUS = 128.0D;
	private static final double DEFAULT_RADIUS = 32.0D;

	private final Gson gson = new Gson();
	private UUID activeSharer;
	private double shareRadius = DEFAULT_RADIUS;
	private final Map<UUID, String> playerGroups = new HashMap<>();

	@Override
	public void onEnable() {
		Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
		Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
		Bukkit.getPluginManager().registerEvents(this, this);

		Objects.requireNonNull(getCommand("media"), "media command missing in plugin.yml").setExecutor(this);
		Objects.requireNonNull(getCommand("media"), "media command missing in plugin.yml").setTabCompleter(this);
		getLogger().info("MediaBridge enabled.");
	}

	@Override
	public void onDisable() {
		broadcastStopToAll(activeSharer);
		activeSharer = null;
		playerGroups.clear();
		Bukkit.getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
		Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player sender, byte[] message) {
		if (!CHANNEL.equals(channel)) {
			return;
		}

		String body = new String(message, StandardCharsets.UTF_8);
		try {
			JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
			String type = payload.has("type") ? payload.get("type").getAsString() : "";

			if ("share_toggle".equals(type)) {
				handleShareToggle(sender);
				return;
			}
			if ("share_set".equals(type)) {
				boolean enabled = payload.has("enabled") && payload.get("enabled").getAsBoolean();
				handleShareSet(sender, enabled);
				return;
			}
			if ("group_create".equals(type)) {
				String code = payload.has("group") ? payload.get("group").getAsString() : "";
				handleGroupCreate(sender, code);
				return;
			}
			if ("group_join".equals(type)) {
				String code = payload.has("group") ? payload.get("group").getAsString() : "";
				handleGroupJoin(sender, code);
				return;
			}
			if ("group_leave".equals(type)) {
				handleGroupLeave(sender);
				return;
			}
			if ("group_members_request".equals(type)) {
				handleGroupMembersRequest(sender);
				return;
			}

			if (activeSharer == null || !activeSharer.equals(sender.getUniqueId())) {
				return;
			}

			if ("state".equals(type)) {
				if (!payload.has("url")) {
					return;
				}
				String url = payload.get("url").getAsString();
				if (url == null || url.isBlank()) {
					return;
				}
				forwardState(sender, url);
				return;
			}

			if ("stop".equals(type)) {
				broadcastStopToAll(activeSharer);
			}
		} catch (Exception ignored) {
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		if (activeSharer != null && activeSharer.equals(uuid)) {
			broadcastStopToAll(activeSharer);
			activeSharer = null;
		}
		String previousGroup = playerGroups.remove(uuid);
		if (previousGroup != null && !previousGroup.isBlank()) {
			broadcastGroupMembers(previousGroup);
			broadcastGroupPlayersSnapshot();
		}
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage(ChatColor.RED + "Only players can use this command.");
			return true;
		}

		if (args.length == 0) {
			sendHelp(player);
			return true;
		}

		String sub = args[0].toLowerCase(Locale.ROOT);
		if ("share".equals(sub)) {
			return handleShare(player, args);
		}
		if ("radius".equals(sub)) {
			return handleRadius(player, args);
		}
		if ("status".equals(sub)) {
			return handleStatus(player);
		}

		sendHelp(player);
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> suggestions = new ArrayList<>();
		if (args.length == 1) {
			addIfMatches(suggestions, args[0], "share");
			addIfMatches(suggestions, args[0], "radius");
			addIfMatches(suggestions, args[0], "status");
			return suggestions;
		}

		if (args.length == 2 && "share".equalsIgnoreCase(args[0])) {
			addIfMatches(suggestions, args[1], "on");
			addIfMatches(suggestions, args[1], "off");
		}
		return suggestions;
	}

	private boolean handleShare(Player player, String[] args) {
		if (args.length < 2) {
			player.sendMessage(ChatColor.YELLOW + "Usage: /media share <on|off>");
			return true;
		}

		String value = args[1].toLowerCase(Locale.ROOT);
		if ("on".equals(value)) {
			if (activeSharer != null && !activeSharer.equals(player.getUniqueId())) {
				Player previous = Bukkit.getPlayer(activeSharer);
				if (previous != null) {
					previous.sendMessage(ChatColor.RED + "Sharing stopped: another player started sharing.");
					sendShareState(previous, false);
				}
				broadcastStopToAll(activeSharer);
			}

			activeSharer = player.getUniqueId();
			sendShareState(player, true);
			player.sendMessage(ChatColor.GREEN + "Media sharing is ON. Radius: " + (int) shareRadius + " blocks.");
			return true;
		}

		if ("off".equals(value)) {
			if (activeSharer == null || !activeSharer.equals(player.getUniqueId())) {
				player.sendMessage(ChatColor.RED + "You are not the active sharing player.");
				return true;
			}

			broadcastStopToAll(activeSharer);
			activeSharer = null;
			sendShareState(player, false);
			player.sendMessage(ChatColor.YELLOW + "Media sharing is OFF.");
			return true;
		}

		player.sendMessage(ChatColor.YELLOW + "Usage: /media share <on|off>");
		return true;
	}

	private boolean handleRadius(Player player, String[] args) {
		if (activeSharer == null || !activeSharer.equals(player.getUniqueId())) {
			player.sendMessage(ChatColor.RED + "Start sharing first with /media share on.");
			return true;
		}
		if (args.length < 2) {
			player.sendMessage(ChatColor.YELLOW + "Usage: /media radius <4-128>");
			return true;
		}

		try {
			double value = Double.parseDouble(args[1]);
			shareRadius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, value));
			player.sendMessage(ChatColor.GREEN + "Share radius set to " + (int) shareRadius + " blocks.");
		} catch (NumberFormatException e) {
			player.sendMessage(ChatColor.RED + "Radius must be a number.");
		}
		return true;
	}

	private boolean handleStatus(Player player) {
		if (activeSharer == null) {
			player.sendMessage(ChatColor.YELLOW + "No active sharing player.");
			return true;
		}

		Player sharer = Bukkit.getPlayer(activeSharer);
		String sharerName = sharer == null ? "offline" : sharer.getName();
		int nearbyCount = sharer == null ? 0 : countNearbyPlayers(sharer);
		player.sendMessage(ChatColor.AQUA + "Sharer: " + sharerName + " | Radius: " + (int) shareRadius + " | Nearby: " + nearbyCount);
		return true;
	}

	private void forwardState(Player sharer, String url) {
		World world = sharer.getWorld();
		double radiusSquared = shareRadius * shareRadius;
		String sharerGroup = playerGroups.get(sharer.getUniqueId());

		for (Player target : Bukkit.getOnlinePlayers()) {
			if (target.getUniqueId().equals(sharer.getUniqueId())) {
				continue;
			}

			boolean inRange = target.getWorld().equals(world)
					&& target.getLocation().distanceSquared(sharer.getLocation()) <= radiusSquared;
			boolean sameGroup = sharerGroup != null && !sharerGroup.isBlank()
					&& sharerGroup.equals(playerGroups.get(target.getUniqueId()));

			if (inRange || sameGroup) {
				sendPlayPayload(target, sharer.getUniqueId(), url, shareRadius, sameGroup);
			} else {
				sendStopPayload(target, sharer.getUniqueId());
			}
		}
	}

	private void handleGroupCreate(Player sender, String code) {
		String previousGroup = playerGroups.get(sender.getUniqueId());
		String normalized = normalizeGroupCode(code);
		if (normalized == null) {
			sender.sendMessage(ChatColor.RED + "Group name is invalid.");
			sendGroupState(sender, previousGroup);
			sendGroupMembers(sender, previousGroup);
			return;
		}
		if (groupExists(normalized)) {
			sender.sendMessage(ChatColor.RED + "Group already exists: " + normalized);
			sendGroupState(sender, previousGroup);
			sendGroupMembers(sender, previousGroup);
			return;
		}

		playerGroups.put(sender.getUniqueId(), normalized);
		sendGroupState(sender, normalized);
		broadcastGroupMembers(normalized);
		broadcastGroupPlayersSnapshot();
		if (previousGroup != null && !previousGroup.isBlank() && !previousGroup.equals(normalized)) {
			broadcastGroupMembers(previousGroup);
		}
		sender.sendMessage(ChatColor.GREEN + "Group created: " + normalized);
	}

	private void handleGroupJoin(Player sender, String code) {
		String previousGroup = playerGroups.get(sender.getUniqueId());
		String normalized = normalizeGroupCode(code);
		if (normalized == null) {
			sender.sendMessage(ChatColor.RED + "Group code is invalid.");
			return;
		}
		if (!groupExists(normalized)) {
			sender.sendMessage(ChatColor.RED + "Group not found: " + normalized);
			String current = playerGroups.get(sender.getUniqueId());
			sendGroupState(sender, current);
			sendGroupMembers(sender, current);
			return;
		}
		playerGroups.put(sender.getUniqueId(), normalized);
		sendGroupState(sender, normalized);
		broadcastGroupMembers(normalized);
		broadcastGroupPlayersSnapshot();
		if (previousGroup != null && !previousGroup.isBlank() && !previousGroup.equals(normalized)) {
			broadcastGroupMembers(previousGroup);
		}
		sender.sendMessage(ChatColor.GREEN + "Joined group: " + normalized);
	}

	private void handleGroupLeave(Player sender) {
		String previousGroup = playerGroups.remove(sender.getUniqueId());
		sendGroupState(sender, null);
		sendGroupMembers(sender, null);
		if (previousGroup != null && !previousGroup.isBlank()) {
			broadcastGroupMembers(previousGroup);
		}
		broadcastGroupPlayersSnapshot();
		sender.sendMessage(ChatColor.YELLOW + "Left group.");
	}

	private void handleGroupMembersRequest(Player sender) {
		String code = playerGroups.get(sender.getUniqueId());
		sendGroupState(sender, code);
		sendGroupMembers(sender, code);
		sendGroupPlayersSnapshot(sender);
	}

	private void handleShareToggle(Player sender) {
		UUID senderId = sender.getUniqueId();
		if (activeSharer != null && activeSharer.equals(senderId)) {
			broadcastStopToAll(activeSharer);
			activeSharer = null;
			sendShareState(sender, false);
			sender.sendMessage(ChatColor.YELLOW + "Media sharing is OFF.");
			return;
		}

		if (activeSharer != null && !activeSharer.equals(senderId)) {
			Player previous = Bukkit.getPlayer(activeSharer);
			if (previous != null) {
				previous.sendMessage(ChatColor.RED + "Sharing stopped: another player started sharing.");
				sendShareState(previous, false);
			}
			broadcastStopToAll(activeSharer);
		}

		activeSharer = senderId;
		sendShareState(sender, true);
		sender.sendMessage(ChatColor.GREEN + "Media sharing is ON. Radius: " + (int) shareRadius + " blocks.");
	}

	private void handleShareSet(Player sender, boolean enabled) {
		if (enabled) {
			if (activeSharer != null && !activeSharer.equals(sender.getUniqueId())) {
				Player previous = Bukkit.getPlayer(activeSharer);
				if (previous != null) {
					previous.sendMessage(ChatColor.RED + "Sharing stopped: another player started sharing.");
					sendShareState(previous, false);
				}
				broadcastStopToAll(activeSharer);
			}
			activeSharer = sender.getUniqueId();
			sendShareState(sender, true);
			sender.sendMessage(ChatColor.GREEN + "Media sharing is ON. Radius: " + (int) shareRadius + " blocks.");
			return;
		}

		if (activeSharer != null && activeSharer.equals(sender.getUniqueId())) {
			broadcastStopToAll(activeSharer);
			activeSharer = null;
		}
		sendShareState(sender, false);
		sender.sendMessage(ChatColor.YELLOW + "Media sharing is OFF.");
	}

	private void broadcastStopToAll(UUID sharer) {
		for (Player target : Bukkit.getOnlinePlayers()) {
			if (sharer != null && target.getUniqueId().equals(sharer)) {
				continue;
			}
			sendStopPayload(target, sharer);
		}
	}

	private int countNearbyPlayers(Player sharer) {
		World world = sharer.getWorld();
		double radiusSquared = shareRadius * shareRadius;
		int count = 0;
		for (Player target : Bukkit.getOnlinePlayers()) {
			if (target.getUniqueId().equals(sharer.getUniqueId())) {
				continue;
			}
			if (!target.getWorld().equals(world)) {
				continue;
			}
			if (target.getLocation().distanceSquared(sharer.getLocation()) <= radiusSquared) {
				count++;
			}
		}
		return count;
	}

	private void sendPlayPayload(Player target, UUID sharer, String url, double radius, boolean globalPlayback) {
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "play");
		payload.addProperty("sharer", sharer.toString());
		payload.addProperty("url", url);
		payload.addProperty("radius", radius);
		payload.addProperty("global_playback", globalPlayback);
		sendPayload(target, payload);
	}

	private void sendStopPayload(Player target, UUID sharer) {
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "stop");
		if (sharer != null) {
			payload.addProperty("sharer", sharer.toString());
		}
		sendPayload(target, payload);
	}

	private void sendShareState(Player target, boolean enabled) {
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "share_state");
		payload.addProperty("enabled", enabled);
		sendPayload(target, payload);
	}

	private void sendGroupState(Player target, String code) {
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "group_state");
		payload.addProperty("group", code == null ? "" : code);
		payload.add("members", groupMembersArray(target, code));
		sendPayload(target, payload);
	}

	private void sendGroupMembers(Player target, String code) {
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "group_members");
		payload.addProperty("group", code == null ? "" : code);
		payload.add("members", groupMembersArray(target, code));
		sendPayload(target, payload);
	}

	private JsonArray groupMembersArray(Player target, String code) {
		JsonArray members = new JsonArray();
		if (code != null && !code.isBlank()) {
			Set<String> names = new LinkedHashSet<>();
			if (code.equals(playerGroups.get(target.getUniqueId()))) {
				names.add(target.getName());
			}
			for (Player online : Bukkit.getOnlinePlayers()) {
				String playerCode = playerGroups.get(online.getUniqueId());
				if (code.equals(playerCode)) {
					names.add(online.getName());
				}
			}
			for (String name : names) {
				members.add(name);
			}
		}
		return members;
	}

	private void broadcastGroupMembers(String groupCode) {
		if (groupCode == null || groupCode.isBlank()) {
			return;
		}
		for (Player online : Bukkit.getOnlinePlayers()) {
			String playerCode = playerGroups.get(online.getUniqueId());
			if (groupCode.equals(playerCode)) {
				sendGroupMembers(online, groupCode);
			}
		}
	}

	private void sendGroupPlayersSnapshot(Player target) {
		JsonObject payload = new JsonObject();
		payload.addProperty("type", "group_players");

		JsonArray players = new JsonArray();
		for (Player online : Bukkit.getOnlinePlayers()) {
			JsonObject player = new JsonObject();
			player.addProperty("uuid", online.getUniqueId().toString());
			player.addProperty("name", online.getName());
			player.addProperty("group", playerGroups.getOrDefault(online.getUniqueId(), ""));
			players.add(player);
		}
		payload.add("players", players);
		sendPayload(target, payload);
	}

	private void broadcastGroupPlayersSnapshot() {
		for (Player online : Bukkit.getOnlinePlayers()) {
			sendGroupPlayersSnapshot(online);
		}
	}

	private static String normalizeGroupCode(String code) {
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

	private boolean groupExists(String code) {
		return playerGroups.containsValue(code);
	}

	private void sendPayload(Player target, JsonObject payload) {
		byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
		target.sendPluginMessage(this, CHANNEL, bytes);
	}

	private void sendHelp(Player player) {
		player.sendMessage(ChatColor.YELLOW + "/media share on" + ChatColor.GRAY + " - start sharing");
		player.sendMessage(ChatColor.YELLOW + "/media share off" + ChatColor.GRAY + " - stop sharing");
		player.sendMessage(ChatColor.YELLOW + "/media radius <4-128>" + ChatColor.GRAY + " - set range");
		player.sendMessage(ChatColor.YELLOW + "/media status" + ChatColor.GRAY + " - show current bridge state");
	}

	private static void addIfMatches(List<String> suggestions, String userInput, String value) {
		if (value.startsWith(userInput.toLowerCase(Locale.ROOT))) {
			suggestions.add(value);
		}
	}
}
