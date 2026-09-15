package com.spotifyclient.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotifyclient.SpotifyClientMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class SpotifyAuth {
	public static final String SCOPES = String.join(" ",
		"user-read-playback-state",
		"user-read-currently-playing",
		"user-modify-playback-state",
		"user-read-recently-played",
		"user-library-read",
		"user-library-modify",
		"user-follow-read",
		"user-follow-modify",
		"playlist-read-private",
		"playlist-read-collaborative",
		"playlist-modify-public",
		"playlist-modify-private");
	private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
	private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);
	private static final long REFRESH_MARGIN_MS = 60_000;

	private static final Path TOKENS_PATH = FabricLoader.getInstance().getConfigDir().resolve(SpotifyClientMod.MOD_ID + "-spotify-tokens.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public enum Status { LOGGED_OUT, WAITING_FOR_BROWSER, LOGGED_IN }

	private static final class Tokens {
		String clientId;
		String accessToken;
		String refreshToken;
		long expiresAtMillis;
		@Nullable String displayName;
		@Nullable String userId;
		@Nullable String scopes;
	}

	private static volatile @Nullable Tokens tokens;
	private static volatile Status status = Status.LOGGED_OUT;
	private static volatile @Nullable String lastError;
	private static volatile @Nullable ServerSocket pendingServer;

	private SpotifyAuth() {
	}

	public static String redirectUri(int port) {
		return "http://127.0.0.1:" + port + "/callback";
	}

	public static Status status() {
		return status;
	}

	public static @Nullable String lastError() {
		return lastError;
	}

	public static @Nullable String displayName() {
		Tokens current = tokens;
		return current != null ? current.displayName : null;
	}

	public static @Nullable String userId() {
		Tokens current = tokens;
		return current != null ? current.userId : null;
	}

	public static boolean isLoggedIn() {
		return status == Status.LOGGED_IN;
	}

	public static void load() {
		if (!Files.exists(TOKENS_PATH)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(TOKENS_PATH)) {
			Tokens loaded = GSON.fromJson(reader, Tokens.class);
			if (loaded != null && loaded.refreshToken != null && loaded.clientId != null && SCOPES.equals(loaded.scopes)) {
				tokens = loaded;
				status = Status.LOGGED_IN;
			}
		} catch (Exception e) {
			SpotifyClientMod.LOGGER.error("Failed to read Spotify tokens", e);
		}
	}

	public static synchronized void startLogin(String clientId, int port) {
		cancelLogin();
		lastError = null;
		if (clientId.isBlank()) {
			lastError = "missing_client_id";
			return;
		}

		ServerSocket server;
		try {
			server = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
			server.setSoTimeout((int) LOGIN_TIMEOUT.toMillis());
		} catch (IOException e) {
			lastError = "port_in_use";
			SpotifyClientMod.LOGGER.warn("Spotify login: can't listen on port {}", port, e);
			return;
		}

		String verifier = randomUrlSafe(64);
		String state = randomUrlSafe(16);
		String redirectUri = redirectUri(port);
		String authorizeUrl = AUTHORIZE_URL + "?" + form(Map.of(
			"client_id", clientId.trim(),
			"response_type", "code",
			"redirect_uri", redirectUri,
			"code_challenge_method", "S256",
			"code_challenge", challenge(verifier),
			"scope", SCOPES,
			"state", state
		));

		pendingServer = server;
		status = Status.WAITING_FOR_BROWSER;
		Thread thread = new Thread(() -> awaitCallback(server, clientId.trim(), redirectUri, verifier, state), "spotify-client-login");
		thread.setDaemon(true);
		thread.start();

		Util.getPlatform().openUri(URI.create(authorizeUrl));
	}

	public static synchronized void cancelLogin() {
		ServerSocket server = pendingServer;
		pendingServer = null;
		if (server != null) {
			closeQuietly(server);
			if (status == Status.WAITING_FOR_BROWSER) {
				status = tokens != null ? Status.LOGGED_IN : Status.LOGGED_OUT;
			}
		}
	}

	public static synchronized void logout() {
		cancelLogin();
		tokens = null;
		status = Status.LOGGED_OUT;
		lastError = null;
		try {
			Files.deleteIfExists(TOKENS_PATH);
		} catch (IOException e) {
			SpotifyClientMod.LOGGER.error("Failed to delete Spotify tokens", e);
		}
	}

	private static void awaitCallback(ServerSocket server, String clientId, String redirectUri, String verifier, String expectedState) {
		try (server) {
			Map<String, String> query = null;
			while (query == null) {
				Socket socket = server.accept();
				try (socket) {
					socket.setSoTimeout(10_000);
					BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
					String requestLine = reader.readLine();
					if (requestLine != null && requestLine.startsWith("GET /callback")) {
						query = parseQuery(requestLine);
						respond(socket.getOutputStream(), !query.containsKey("error")
							&& expectedState.equals(query.get("state")) && query.containsKey("code"));
					} else {
						socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
							.getBytes(StandardCharsets.US_ASCII));
					}
				} catch (SocketTimeoutException stalledConnection) {
				}
			}

			String error = null;
			if (query.containsKey("error")) {
				error = "denied";
			} else if (!expectedState.equals(query.get("state")) || !query.containsKey("code")) {
				error = "invalid_callback";
			}

			if (error != null) {
				fail(error);
				return;
			}
			Tokens newTokens = exchange(clientId, Map.of(
				"grant_type", "authorization_code",
				"code", query.get("code"),
				"redirect_uri", redirectUri,
				"client_id", clientId,
				"code_verifier", verifier
			), null);
			fetchProfile(newTokens);
			newTokens.scopes = SCOPES;
			tokens = newTokens;
			save(newTokens);
			status = Status.LOGGED_IN;
			lastError = null;
		} catch (SocketTimeoutException e) {
			fail("timeout");
		} catch (Exception e) {
			if (pendingServer == server) {
				SpotifyClientMod.LOGGER.error("Spotify login failed", e);
				fail("token_exchange");
			}
		} finally {
			if (pendingServer == server) {
				pendingServer = null;
			}
		}
	}

	private static void fail(String error) {
		lastError = error;
		status = tokens != null ? Status.LOGGED_IN : Status.LOGGED_OUT;
	}

	private static void respond(OutputStream out, boolean success) throws IOException {
		String message = success
			? "Connexion Spotify r&eacute;ussie, tu peux fermer cet onglet et retourner sur Minecraft.<br>Spotify login successful, you can close this tab."
			: "Connexion Spotify annul&eacute;e ou invalide.<br>Spotify login cancelled or invalid.";
		String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Spotify Client</title></head>"
			+ "<body style=\"font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding-top:15vh\">"
			+ "<h1 style=\"color:#1DB954\">Spotify Client</h1><p>" + message + "</p></body></html>";
		byte[] body = html.getBytes(StandardCharsets.UTF_8);
		out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
			.getBytes(StandardCharsets.US_ASCII));
		out.write(body);
		out.flush();
	}

	public static synchronized @Nullable String accessToken() {
		Tokens current = tokens;
		if (current == null) {
			return null;
		}
		if (System.currentTimeMillis() < current.expiresAtMillis - REFRESH_MARGIN_MS) {
			return current.accessToken;
		}
		try {
			Tokens refreshed = exchange(current.clientId, Map.of(
				"grant_type", "refresh_token",
				"refresh_token", current.refreshToken,
				"client_id", current.clientId
			), current);
			tokens = refreshed;
			save(refreshed);
			return refreshed.accessToken;
		} catch (RevokedException e) {
			SpotifyClientMod.LOGGER.warn("Spotify refresh token rejected, logging out");
			logout();
			lastError = "session_expired";
			return null;
		} catch (Exception e) {
			SpotifyClientMod.LOGGER.warn("Spotify token refresh failed, will retry", e);
			return null;
		}
	}

	public static synchronized void invalidateAccessToken() {
		Tokens current = tokens;
		if (current != null) {
			current.expiresAtMillis = 0;
		}
	}

	private static final class RevokedException extends IOException {
		RevokedException(String message) {
			super(message);
		}
	}

	private static Tokens exchange(String clientId, Map<String, String> params, @Nullable Tokens previous) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
			.timeout(Duration.ofSeconds(15))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form(params)))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 400 || response.statusCode() == 401) {
			throw new RevokedException("Token endpoint returned " + response.statusCode() + ": " + response.body());
		}
		if (response.statusCode() != 200) {
			throw new IOException("Token endpoint returned " + response.statusCode() + ": " + response.body());
		}

		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		Tokens result = new Tokens();
		result.clientId = clientId;
		result.accessToken = json.get("access_token").getAsString();
		result.expiresAtMillis = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000;
		result.refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : previous != null ? previous.refreshToken : null;
		if (previous != null) {
			result.displayName = previous.displayName;
			result.userId = previous.userId;
			result.scopes = previous.scopes;
		}
		if (result.refreshToken == null) {
			throw new IOException("No refresh token in response");
		}
		return result;
	}

	private static void fetchProfile(Tokens target) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(SpotifyApi.BASE_URL + "/me"))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + target.accessToken)
				.GET()
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
				target.userId = SpotifyModels.str(json, "id");
				String name = SpotifyModels.str(json, "display_name");
				target.displayName = name.isEmpty() ? target.userId : name;
			}
		} catch (Exception e) {
			SpotifyClientMod.LOGGER.warn("Failed to fetch Spotify profile", e);
		}
	}

	private static void save(Tokens value) {
		try {
			Files.createDirectories(TOKENS_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(TOKENS_PATH)) {
				GSON.toJson(value, writer);
			}
		} catch (IOException e) {
			SpotifyClientMod.LOGGER.error("Failed to write Spotify tokens", e);
		}
	}

	private static Map<String, String> parseQuery(@Nullable String requestLine) {
		Map<String, String> result = new HashMap<>();
		if (requestLine == null) {
			return result;
		}
		String[] parts = requestLine.split(" ");
		if (parts.length < 2 || !parts[1].contains("?")) {
			return result;
		}
		for (String pair : parts[1].substring(parts[1].indexOf('?') + 1).split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	static String form(Map<String, String> params) {
		StringJoiner joiner = new StringJoiner("&");
		params.forEach((key, value) -> joiner.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
		return joiner.toString();
	}

	private static String randomUrlSafe(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private static String challenge(String verifier) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void closeQuietly(ServerSocket server) {
		try {
			server.close();
		} catch (IOException ignored) {
		}
	}
}
