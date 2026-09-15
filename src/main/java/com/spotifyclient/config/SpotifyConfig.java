package com.spotifyclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spotifyclient.SpotifyClientMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SpotifyConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(SpotifyClientMod.MOD_ID + ".json");

	private static SpotifyConfig instance = new SpotifyConfig();

	public String clientId = "";
	public int redirectPort = 43127;
	public int pollIntervalSeconds = 3;
	public boolean announceTrackChanges = false;
	public int accentColor = 0x1DB954;

	public boolean widgetEnabled = true;
	public WidgetAnchor widgetAnchor = WidgetAnchor.TOP_LEFT;
	public int widgetOffsetX = 4;
	public int widgetOffsetY = 4;
	public int widgetScalePercent = 100;
	public int widgetWidth = 170;
	public int widgetBackgroundOpacity = 65;
	public boolean widgetShowAlbumArt = true;
	public boolean widgetShowProgress = true;
	public boolean widgetShowDevice = false;
	public boolean widgetHideWhenPaused = false;
	public boolean widgetHideWithDebugScreen = true;

	public enum WidgetAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	public static SpotifyConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				SpotifyConfig loaded = GSON.fromJson(reader, SpotifyConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				SpotifyClientMod.LOGGER.error("Failed to read config {}, using defaults", PATH, e);
			}
		}
		instance.sanitize();
		save();
	}

	private void sanitize() {
		if (clientId == null) {
			clientId = "";
		}
		clientId = clientId.trim();
		if (redirectPort < 1024 || redirectPort > 65535) {
			redirectPort = 43127;
		}
		if (widgetAnchor == null) {
			widgetAnchor = WidgetAnchor.TOP_LEFT;
		}
		pollIntervalSeconds = Math.clamp(pollIntervalSeconds, 1, 15);
		widgetScalePercent = Math.clamp(widgetScalePercent, 50, 200);
		widgetWidth = Math.clamp(widgetWidth, 110, 320);
		widgetBackgroundOpacity = Math.clamp(widgetBackgroundOpacity, 0, 100);
		widgetOffsetX = Math.max(0, widgetOffsetX);
		widgetOffsetY = Math.max(0, widgetOffsetY);
		accentColor &= 0xFFFFFF;
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			SpotifyClientMod.LOGGER.error("Failed to write config {}", PATH, e);
		}
	}
}
