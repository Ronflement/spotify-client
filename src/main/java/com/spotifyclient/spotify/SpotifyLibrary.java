package com.spotifyclient.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotifyclient.spotify.SpotifyModels.Collection;
import com.spotifyclient.spotify.SpotifyModels.CollectionKind;
import com.spotifyclient.spotify.SpotifyModels.Device;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

public final class SpotifyLibrary {
	private static final int PAGE_SIZE = 50;
	private static final int SEARCH_PAGE_SIZE = 10;
	private static final int ARTIST_ALBUMS_PAGE_SIZE = 20;
	private static final int MAX_PLAYLISTS = 500;
	static final int LIBRARY_BATCH = 40;

	public record Page<T>(List<T> items, int nextOffset) {
		public boolean hasMore() {
			return nextOffset >= 0;
		}
	}

	public static final class ApiException extends RuntimeException {
		public final int status;
		public final @Nullable String reason;
		public final long retryAfterSeconds;

		ApiException(SpotifyApi.Response response) {
			super("Spotify API returned " + response.status() + (response.reason() != null ? " (" + response.reason() + ")" : ""));
			this.status = response.status();
			this.reason = response.reason();
			this.retryAfterSeconds = response.retryAfterSeconds();
		}
	}

	public enum SearchType {
		TRACK("track", "tracks"), ALBUM("album", "albums"), PLAYLIST("playlist", "playlists"), ARTIST("artist", "artists");

		final String param;
		final String resultKey;

		SearchType(String param, String resultKey) {
			this.param = param;
			this.resultKey = resultKey;
		}
	}

	private SpotifyLibrary() {
	}

	public static CompletableFuture<Page<Object>> search(String query, SearchType type, int offset) {
		String path = "/search?q=" + SpotifyApi.encode(query) + "&type=" + type.param + "&limit=" + SEARCH_PAGE_SIZE + "&offset=" + offset;
		return fetch(path, json -> {
			JsonObject container = SpotifyModels.obj(json, type.resultKey);
			if (container == null) {
				return new Page<>(List.of(), -1);
			}
			return page(container, offset, SEARCH_PAGE_SIZE, item -> switch (type) {
				case TRACK -> SpotifyModels.parseTrack(item, null);
				case ALBUM -> SpotifyModels.parseAlbum(item);
				case PLAYLIST -> SpotifyModels.parsePlaylist(item);
				case ARTIST -> SpotifyModels.parseArtist(item);
			});
		});
	}

	public static CompletableFuture<Page<Object>> playlists(int offset) {
		return fetch("/me/playlists?limit=" + PAGE_SIZE + "&offset=" + offset,
			json -> page(json, offset, PAGE_SIZE, SpotifyModels::parsePlaylist));
	}

	public static CompletableFuture<List<Collection>> editablePlaylists() {
		return CompletableFuture.supplyAsync(() -> {
			List<Collection> result = new ArrayList<>();
			int offset = 0;
			while (offset >= 0 && offset < MAX_PLAYLISTS) {
				int pageOffset = offset;
				Page<Object> page = fetchBlocking("/me/playlists?limit=" + PAGE_SIZE + "&offset=" + pageOffset,
					json -> page(json, pageOffset, PAGE_SIZE, SpotifyModels::parsePlaylist));
				for (Object item : page.items()) {
					if (item instanceof Collection collection && collection.canEditItems()) {
						result.add(collection);
					}
				}
				offset = page.nextOffset();
			}
			return result;
		}, SpotifyService.EXECUTOR);
	}

	public static CompletableFuture<Page<Object>> albums(int offset) {
		return fetch("/me/albums?limit=" + PAGE_SIZE + "&offset=" + offset, json -> page(json, offset, PAGE_SIZE, saved -> {
			JsonObject album = SpotifyModels.obj(saved, "album");
			return album != null ? SpotifyModels.parseAlbum(album) : null;
		}));
	}

	public static CompletableFuture<Page<Object>> likedTracks(int offset) {
		return fetch("/me/tracks?limit=" + PAGE_SIZE + "&offset=" + offset, json -> page(json, offset, PAGE_SIZE, saved -> {
			JsonObject track = SpotifyModels.obj(saved, "track");
			return track != null ? SpotifyModels.parseTrack(track, null) : null;
		}));
	}

	public static CompletableFuture<Page<Object>> recentlyPlayed(int offset) {
		return fetch("/me/player/recently-played?limit=" + PAGE_SIZE, json -> page(json, 0, PAGE_SIZE, entry -> {
			JsonObject track = SpotifyModels.obj(entry, "track");
			return track != null ? SpotifyModels.parseTrack(track, null) : null;
		})).thenApply(page -> new Page<>(page.items(), -1));
	}

	public static CompletableFuture<Page<Object>> queue(int offset) {
		return fetch("/me/player/queue", json -> {
			List<Object> items = new ArrayList<>();
			JsonObject current = SpotifyModels.obj(json, "currently_playing");
			if (current != null) {
				items.add(SpotifyModels.parseTrack(current, null));
			}
			JsonArray queue = SpotifyModels.arr(json, "queue");
			if (queue != null) {
				for (JsonElement element : queue) {
					if (element.isJsonObject()) {
						items.add(SpotifyModels.parseTrack(element.getAsJsonObject(), null));
					}
				}
			}
			return new Page<>(items, -1);
		});
	}

	public static CompletableFuture<Page<Object>> collectionTracks(Collection collection, int offset) {
		return switch (collection.kind()) {
			case ALBUM -> fetch("/albums/" + collection.id() + "/tracks?limit=" + PAGE_SIZE + "&offset=" + offset,
				json -> page(json, offset, PAGE_SIZE, item -> SpotifyModels.parseTrack(item, collection)));
			case ARTIST -> fetch("/artists/" + collection.id() + "/albums?include_groups=album,single&limit=" + ARTIST_ALBUMS_PAGE_SIZE + "&offset=" + offset,
				json -> page(json, offset, ARTIST_ALBUMS_PAGE_SIZE, SpotifyModels::parseAlbum));
			case PLAYLIST -> fetch("/playlists/" + collection.id() + "/items?limit=" + PAGE_SIZE + "&offset=" + offset + "&additional_types=track,episode",
				json -> page(json, offset, PAGE_SIZE, entry -> {
					JsonObject item = SpotifyModels.obj(entry, "item") != null ? SpotifyModels.obj(entry, "item") : SpotifyModels.obj(entry, "track");
					return item != null && !SpotifyModels.str(item, "uri").isEmpty() ? SpotifyModels.parseTrack(item, null) : null;
				}));
		};
	}

	public static CompletableFuture<Map<String, Boolean>> contains(List<String> uris) {
		return CompletableFuture.supplyAsync(() -> {
			Map<String, Boolean> result = new HashMap<>();
			for (int start = 0; start < uris.size(); start += LIBRARY_BATCH) {
				List<String> batch = uris.subList(start, Math.min(uris.size(), start + LIBRARY_BATCH));
				SpotifyApi.Response response = call(() -> SpotifyApi.get("/me/library/contains?uris=" + SpotifyApi.encode(String.join(",", batch))));
				if (!response.ok() || response.json() == null || !response.json().isJsonArray()) {
					throw new ApiException(response);
				}
				JsonArray flags = response.json().getAsJsonArray();
				for (int i = 0; i < batch.size() && i < flags.size(); i++) {
					result.put(batch.get(i), flags.get(i).getAsBoolean());
				}
			}
			return result;
		}, SpotifyService.EXECUTOR);
	}

	public static CompletableFuture<List<Device>> devices() {
		return CompletableFuture.supplyAsync(SpotifyLibrary::fetchDevicesBlocking, SpotifyService.EXECUTOR);
	}

	static List<Device> fetchDevicesBlocking() {
		SpotifyApi.Response response = call(() -> SpotifyApi.get("/me/player/devices"));
		if (!response.ok() || response.body() == null) {
			throw new ApiException(response);
		}
		List<Device> devices = new ArrayList<>();
		JsonArray array = SpotifyModels.arr(response.body(), "devices");
		if (array != null) {
			for (JsonElement element : array) {
				if (element.isJsonObject() && !SpotifyModels.str(element.getAsJsonObject(), "id").isEmpty()) {
					devices.add(SpotifyModels.parseDevice(element.getAsJsonObject()));
				}
			}
		}
		return devices;
	}

	interface ApiCall {
		SpotifyApi.Response run() throws Exception;
	}

	static SpotifyApi.Response call(ApiCall call) {
		try {
			return call.run();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new CompletionException(e);
		}
	}

	private static <T> CompletableFuture<T> fetch(String path, Function<JsonObject, T> parser) {
		return CompletableFuture.supplyAsync(() -> fetchBlocking(path, parser), SpotifyService.EXECUTOR);
	}

	private static <T> T fetchBlocking(String path, Function<JsonObject, T> parser) {
		SpotifyApi.Response response = call(() -> SpotifyApi.get(path));
		if (!response.ok() || response.body() == null) {
			throw new ApiException(response);
		}
		return parser.apply(response.body());
	}

	private static Page<Object> page(JsonObject paging, int offset, int limit, Function<JsonObject, @Nullable Object> parser) {
		List<Object> items = new ArrayList<>();
		JsonArray array = SpotifyModels.arr(paging, "items");
		if (array != null) {
			for (JsonElement element : array) {
				if (element.isJsonObject()) {
					Object parsed = parser.apply(element.getAsJsonObject());
					if (parsed != null) {
						items.add(parsed);
					}
				}
			}
		}
		boolean hasNext = paging.has("next") && !paging.get("next").isJsonNull();
		return new Page<>(items, hasNext ? offset + limit : -1);
	}

	static boolean isKind(Object row, CollectionKind kind) {
		return row instanceof Collection collection && collection.kind() == kind;
	}
}
