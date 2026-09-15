package com.spotifyclient.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class SpotifyModels {
	private static final int SMALL_IMAGE = 64;
	private static final int LARGE_IMAGE = 200;

	private SpotifyModels() {
	}

	public record Images(@Nullable String small, @Nullable String large) {
		public static final Images NONE = new Images(null, null);
	}

	public record Ref(String uri, String id, String name) {
	}

	public record Track(String uri, String id, String name, String subtitle, long durationMs, Images images,
			@Nullable Ref album, List<Ref> artists) {
		public boolean isEpisode() {
			return uri.startsWith("spotify:episode:");
		}
	}

	public enum CollectionKind { PLAYLIST, ALBUM, ARTIST }

	public record Collection(CollectionKind kind, String uri, String id, String name, String subtitle, Images images, int total,
			String ownerId, String description, boolean isPublic, boolean collaborative, String snapshotId) {

		public static Collection album(Ref ref, String artists, Images images) {
			return new Collection(CollectionKind.ALBUM, ref.uri(), ref.id(), ref.name(), artists, images, 0, "", "", false, false, "");
		}

		public static Collection artist(Ref ref) {
			return new Collection(CollectionKind.ARTIST, ref.uri(), ref.id(), ref.name(), "", Images.NONE, 0, "", "", false, false, "");
		}

		public boolean isOwnedByMe() {
			return kind == CollectionKind.PLAYLIST && !ownerId.isEmpty() && ownerId.equals(SpotifyAuth.userId());
		}

		public boolean itemsReadable() {
			return kind != CollectionKind.PLAYLIST || collaborative || isOwnedByMe();
		}

		public boolean canEditItems() {
			return kind == CollectionKind.PLAYLIST && (collaborative || isOwnedByMe());
		}

		public Collection withDetails(String newName, String newDescription, boolean newPublic, boolean newCollaborative) {
			return new Collection(kind, uri, id, newName, subtitle, images, total, ownerId, newDescription, newPublic, newCollaborative, snapshotId);
		}
	}

	public record Device(String id, String name, String type, boolean active, int volume, boolean supportsVolume) {
	}

	public enum Repeat {
		OFF("off"), CONTEXT("context"), TRACK("track");

		public final String apiValue;

		Repeat(String apiValue) {
			this.apiValue = apiValue;
		}

		public Repeat next() {
			return values()[(ordinal() + 1) % values().length];
		}

		static Repeat of(String value) {
			for (Repeat repeat : values()) {
				if (repeat.apiValue.equals(value)) {
					return repeat;
				}
			}
			return OFF;
		}
	}

	public record PlayerState(
		@Nullable Track track,
		long progressMs,
		boolean playing,
		boolean shuffle,
		Repeat repeat,
		@Nullable Device device,
		@Nullable String contextUri,
		long fetchedAtMillis
	) {
		public static final PlayerState EMPTY = new PlayerState(null, 0, false, false, Repeat.OFF, null, null, 0);

		public long currentProgressMs() {
			if (track == null) {
				return 0;
			}
			if (!playing) {
				return progressMs;
			}
			return Math.min(track.durationMs(), progressMs + (System.currentTimeMillis() - fetchedAtMillis));
		}

		public PlayerState withPlaying(boolean value) {
			return new PlayerState(track, currentProgressMs(), value, shuffle, repeat, device, contextUri, System.currentTimeMillis());
		}

		public PlayerState withShuffle(boolean value) {
			return new PlayerState(track, currentProgressMs(), playing, value, repeat, device, contextUri, System.currentTimeMillis());
		}

		public PlayerState withRepeat(Repeat value) {
			return new PlayerState(track, currentProgressMs(), playing, shuffle, value, device, contextUri, System.currentTimeMillis());
		}

		public PlayerState withProgress(long value) {
			return new PlayerState(track, value, playing, shuffle, repeat, device, contextUri, System.currentTimeMillis());
		}

		public PlayerState withVolume(int value) {
			Device d = device == null ? null : new Device(device.id(), device.name(), device.type(), device.active(), value, device.supportsVolume());
			return new PlayerState(track, currentProgressMs(), playing, shuffle, repeat, d, contextUri, System.currentTimeMillis());
		}
	}

	static PlayerState parsePlayer(@Nullable JsonObject json) {
		if (json == null) {
			return PlayerState.EMPTY;
		}
		JsonObject item = obj(json, "item");
		JsonObject context = obj(json, "context");
		JsonObject device = obj(json, "device");
		return new PlayerState(
			item != null ? parseTrack(item, null) : null,
			longOf(json, "progress_ms"),
			bool(json, "is_playing"),
			bool(json, "shuffle_state"),
			Repeat.of(str(json, "repeat_state")),
			device != null ? parseDevice(device) : null,
			context != null ? str(context, "uri") : null,
			System.currentTimeMillis()
		);
	}

	static Track parseTrack(JsonObject item, @Nullable Collection albumContext) {
		if ("episode".equals(str(item, "type"))) {
			JsonObject show = obj(item, "show");
			Images images = parseImages(arr(item, "images"));
			if (images.small() == null && show != null) {
				images = parseImages(arr(show, "images"));
			}
			return new Track(str(item, "uri"), str(item, "id"), str(item, "name"), show != null ? str(show, "name") : "",
				longOf(item, "duration_ms"), images, null, List.of());
		}

		List<Ref> artists = refs(arr(item, "artists"));
		JsonObject album = obj(item, "album");
		Ref albumRef;
		Images images;
		if (album != null) {
			albumRef = ref(album);
			images = parseImages(arr(album, "images"));
		} else if (albumContext != null) {
			albumRef = new Ref(albumContext.uri(), albumContext.id(), albumContext.name());
			images = albumContext.images();
		} else {
			albumRef = null;
			images = Images.NONE;
		}
		return new Track(str(item, "uri"), str(item, "id"), str(item, "name"),
			String.join(", ", artists.stream().map(Ref::name).toList()), longOf(item, "duration_ms"), images, albumRef, artists);
	}

	static Collection parsePlaylist(JsonObject json) {
		JsonObject owner = obj(json, "owner");
		String ownerId = owner != null ? str(owner, "id") : "";
		String ownerName = owner != null ? str(owner, "display_name") : "";
		JsonObject items = obj(json, "items") != null ? obj(json, "items") : obj(json, "tracks");
		return new Collection(CollectionKind.PLAYLIST, str(json, "uri"), str(json, "id"), str(json, "name"),
			ownerName.isEmpty() ? ownerId : ownerName, parseImages(arr(json, "images")),
			items != null ? (int) longOf(items, "total") : 0,
			ownerId, str(json, "description"), bool(json, "public"), bool(json, "collaborative"), str(json, "snapshot_id"));
	}

	static Collection parseAlbum(JsonObject json) {
		String artists = String.join(", ", refs(arr(json, "artists")).stream().map(Ref::name).toList());
		return new Collection(CollectionKind.ALBUM, str(json, "uri"), str(json, "id"), str(json, "name"), artists,
			parseImages(arr(json, "images")), (int) longOf(json, "total_tracks"), "", "", false, false, "");
	}

	static Collection parseArtist(JsonObject json) {
		JsonArray genres = arr(json, "genres");
		String subtitle = genres != null && !genres.isEmpty() ? genres.get(0).getAsString() : "";
		return new Collection(CollectionKind.ARTIST, str(json, "uri"), str(json, "id"), str(json, "name"), subtitle,
			parseImages(arr(json, "images")), 0, "", "", false, false, "");
	}

	static Device parseDevice(JsonObject json) {
		return new Device(str(json, "id"), str(json, "name"), str(json, "type"), bool(json, "is_active"),
			(int) longOf(json, "volume_percent"), bool(json, "supports_volume"));
	}

	static Images parseImages(@Nullable JsonArray images) {
		if (images == null || images.isEmpty()) {
			return Images.NONE;
		}
		String small = null;
		String large = null;
		for (JsonElement element : images) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject image = element.getAsJsonObject();
			String url = str(image, "url");
			int width = (int) longOf(image, "width");
			if (large == null || width >= LARGE_IMAGE || width == 0) {
				large = url;
			}
			if (small == null || width >= SMALL_IMAGE || width == 0) {
				small = url;
			}
		}
		return new Images(small, large);
	}

	private static List<Ref> refs(@Nullable JsonArray array) {
		List<Ref> result = new ArrayList<>();
		if (array != null) {
			for (JsonElement element : array) {
				if (element.isJsonObject()) {
					result.add(ref(element.getAsJsonObject()));
				}
			}
		}
		return result;
	}

	private static Ref ref(JsonObject json) {
		return new Ref(str(json, "uri"), str(json, "id"), str(json, "name"));
	}

	static @Nullable JsonObject obj(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	static @Nullable JsonArray arr(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	static String str(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
	}

	static long longOf(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0;
	}

	static boolean bool(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsBoolean();
	}
}
