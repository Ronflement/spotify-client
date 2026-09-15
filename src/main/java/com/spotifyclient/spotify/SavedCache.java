package com.spotifyclient.spotify;

import com.spotifyclient.SpotifyClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SavedCache {
	private static final Map<String, Boolean> SAVED = new HashMap<>();
	private static final Set<String> PENDING = new HashSet<>();
	private static final List<String> QUEUE = new ArrayList<>();

	private SavedCache() {
	}

	public static @Nullable Boolean get(String uri) {
		if (uri.isEmpty()) {
			return null;
		}
		Boolean saved = SAVED.get(uri);
		if (saved == null && PENDING.add(uri)) {
			QUEUE.add(uri);
		}
		return saved;
	}

	public static void markSaved(String uri, boolean saved) {
		SAVED.put(uri, saved);
	}

	public static void flush() {
		if (QUEUE.isEmpty() || !SpotifyAuth.isLoggedIn()) {
			return;
		}
		List<String> batch = List.copyOf(QUEUE);
		QUEUE.clear();
		SpotifyLibrary.contains(batch).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			batch.forEach(PENDING::remove);
			if (error != null) {
				SpotifyClientMod.LOGGER.debug("Spotify library lookup failed", error);
				return;
			}
			SAVED.putAll(result);
		}));
	}

	public static void toggle(String uri) {
		boolean saved = Boolean.TRUE.equals(SAVED.get(uri));
		SAVED.put(uri, !saved);
		var request = saved ? SpotifyActions.removeFromLibrary(List.of(uri)) : SpotifyActions.saveToLibrary(List.of(uri));
		request.whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null) {
				SAVED.put(uri, saved);
				SpotifyService.notify(SpotifyService.describe(error));
			} else {
				SpotifyService.info(Component.translatable(saved ? "spotify-client.info.removed_from_library" : "spotify-client.info.saved_to_library"));
			}
		}));
	}

	public static void clear() {
		SAVED.clear();
		PENDING.clear();
		QUEUE.clear();
	}
}
