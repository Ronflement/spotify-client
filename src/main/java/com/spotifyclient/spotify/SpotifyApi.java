package com.spotifyclient.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class SpotifyApi {
	static final String BASE_URL = "https://api.spotify.com/v1";

	record Response(int status, @Nullable JsonElement json, long retryAfterSeconds) {
		static final Response NOT_LOGGED_IN = new Response(401, null, 0);

		boolean ok() {
			return status >= 200 && status < 300;
		}

		@Nullable JsonObject body() {
			return json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
		}

		@Nullable String reason() {
			JsonObject body = body();
			JsonObject error = body != null ? SpotifyModels.obj(body, "error") : null;
			return error != null && error.has("reason") ? error.get("reason").getAsString() : null;
		}
	}

	private SpotifyApi() {
	}

	static Response get(String path) throws IOException, InterruptedException {
		return send("GET", path, null, true);
	}

	static Response send(String method, String path, @Nullable JsonObject body) throws IOException, InterruptedException {
		return send(method, path, body, true);
	}

	static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static Response send(String method, String path, @Nullable JsonObject body, boolean retryOnUnauthorized)
			throws IOException, InterruptedException {
		String token = SpotifyAuth.accessToken();
		if (token == null) {
			return Response.NOT_LOGGED_IN;
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + token);
		if (method.equals("GET")) {
			builder.GET();
		} else {
			builder.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body != null ? body.toString() : ""));
		}
		HttpResponse<String> response = SpotifyAuth.HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() == 401 && retryOnUnauthorized) {
			SpotifyAuth.invalidateAccessToken();
			return send(method, path, body, false);
		}

		JsonElement json = null;
		String text = response.body();
		if (text != null && !text.isBlank()) {
			try {
				json = JsonParser.parseString(text);
			} catch (RuntimeException ignored) {
			}
		}
		long retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(0);
		return new Response(response.statusCode(), json, retryAfter);
	}
}
