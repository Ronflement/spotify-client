package com.spotifyclient.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spotifyclient.spotify.SpotifyLibrary.ApiException;
import com.spotifyclient.spotify.SpotifyModels.Collection;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SpotifyActions {
	private SpotifyActions() {
	}

	public static CompletableFuture<Void> saveToLibrary(List<String> uris) {
		return libraryRequest("PUT", uris);
	}

	public static CompletableFuture<Void> removeFromLibrary(List<String> uris) {
		return libraryRequest("DELETE", uris);
	}

	public static CompletableFuture<Collection> createPlaylist(String name, String description, boolean isPublic, boolean collaborative) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("description", description);
		body.addProperty("public", isPublic && !collaborative);
		body.addProperty("collaborative", collaborative);
		return run("POST", "/me/playlists", body).thenApply(response -> {
			JsonObject json = response.body();
			if (json == null) {
				throw new ApiException(response);
			}
			return SpotifyModels.parsePlaylist(json);
		});
	}

	public static CompletableFuture<Void> updatePlaylist(Collection playlist, String name, String description, boolean isPublic, boolean collaborative) {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("description", description);
		body.addProperty("public", isPublic && !collaborative);
		body.addProperty("collaborative", collaborative);
		return run("PUT", "/playlists/" + playlist.id(), body).thenApply(response -> null);
	}

	public static CompletableFuture<Void> addToPlaylist(Collection playlist, List<String> trackUris) {
		JsonObject body = new JsonObject();
		body.add("uris", array(trackUris));
		return run("POST", "/playlists/" + playlist.id() + "/items", body).thenApply(response -> null);
	}

	public static CompletableFuture<Void> removeFromPlaylist(Collection playlist, String trackUri) {
		JsonObject item = new JsonObject();
		item.addProperty("uri", trackUri);
		JsonArray items = new JsonArray();
		items.add(item);
		JsonObject body = new JsonObject();
		body.add("items", items);
		return run("DELETE", "/playlists/" + playlist.id() + "/items", body).thenApply(response -> null);
	}

	public static CompletableFuture<Void> movePlaylistItem(Collection playlist, int from, int to) {
		JsonObject body = new JsonObject();
		body.addProperty("range_start", from);
		body.addProperty("insert_before", to > from ? to + 1 : to);
		return run("PUT", "/playlists/" + playlist.id() + "/items", body).thenApply(response -> null);
	}

	public static CompletableFuture<Void> addToQueue(String uri) {
		return run("POST", "/me/player/queue?uri=" + SpotifyApi.encode(uri), null).thenApply(response -> null);
	}

	private static CompletableFuture<Void> libraryRequest(String method, List<String> uris) {
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (int start = 0; start < uris.size(); start += SpotifyLibrary.LIBRARY_BATCH) {
			List<String> batch = uris.subList(start, Math.min(uris.size(), start + SpotifyLibrary.LIBRARY_BATCH));
			String path = "/me/library?uris=" + SpotifyApi.encode(String.join(",", batch));
			chain = chain.thenCompose(ignored -> run(method, path, null)).thenApply(response -> null);
		}
		return chain;
	}

	private static CompletableFuture<SpotifyApi.Response> run(String method, String path, @Nullable JsonObject body) {
		return CompletableFuture.supplyAsync(() -> {
			SpotifyApi.Response response = SpotifyLibrary.call(() -> SpotifyApi.send(method, path, body));
			if (!response.ok()) {
				throw new ApiException(response);
			}
			return response;
		}, SpotifyService.EXECUTOR);
	}

	private static JsonArray array(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}
}
