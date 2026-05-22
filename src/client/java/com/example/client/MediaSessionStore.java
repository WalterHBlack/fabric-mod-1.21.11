package com.example.client;

import com.example.ExampleMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class MediaSessionStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path SESSION_PATH = FabricLoader.getInstance().getConfigDir().resolve("modid-media-session.json");

	private MediaSessionStore() {
	}

	static Session load() {
		if (!Files.isRegularFile(SESSION_PATH)) {
			return new Session();
		}
		try (Reader reader = Files.newBufferedReader(SESSION_PATH)) {
			Session session = GSON.fromJson(reader, Session.class);
			return session == null ? new Session() : session;
		} catch (Exception exception) {
			ExampleMod.LOGGER.warn("Failed to load media session: {}", exception.toString());
			return new Session();
		}
	}

	static void save(Session session) {
		if (session == null) {
			return;
		}
		try {
			Files.createDirectories(SESSION_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(SESSION_PATH)) {
				GSON.toJson(session, writer);
			}
		} catch (IOException exception) {
			ExampleMod.LOGGER.warn("Failed to save media session: {}", exception.toString());
		}
	}

	static final class Session {
		String lastUrl = "https://www.youtube.com";
		boolean spotifyWarningSuppressed = false;
		Map<String, Double> siteZoomLevels = new HashMap<>();
	}
}
