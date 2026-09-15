package com.spotifyclient.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spotifyclient.SpotifyKeys;
import com.spotifyclient.SpotifyClientMod;
import com.spotifyclient.config.SpotifyConfig;
import com.spotifyclient.gui.Ui;
import com.spotifyclient.gui.SpotifyScreen;
import com.spotifyclient.spotify.SpotifyModels.Device;
import com.spotifyclient.spotify.SpotifyModels.PlayerState;
import com.spotifyclient.spotify.SpotifyModels.Repeat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SpotifyService {
	static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, daemonThreads("spotify-client"));
	private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(daemonThreads("spotify-client-poll"));

	private static final long SCREEN_POLL_MS = 1000;
	private static final long PAUSED_POLL_MS = 5000;
	private static final long ERROR_BACKOFF_MS = 5000;
	private static final long OPTIMISTIC_HOLD_MS = 1500;
	private static final long MESSAGE_DURATION_MS = 4000;

	private static volatile PlayerState state = PlayerState.EMPTY;
	private static volatile long nextPollAt;
	private static volatile boolean pollInFlight;
	private static volatile long lastCommandAt;
	private static volatile boolean screenOpen;
	private static volatile @Nullable Component message;
	private static volatile long messageAt;
	private static @Nullable String lastAnnouncedTrack;

	private SpotifyService() {
	}

	public static void init() {
		SpotifyAuth.load();
		SCHEDULER.scheduleWithFixedDelay(SpotifyService::schedulePoll, 1000, 250, TimeUnit.MILLISECONDS);
	}

	public static PlayerState state() {
		return state;
	}

	public static void setScreenOpen(boolean open) {
		screenOpen = open;
		if (open) {
			pollSoon(0);
		}
	}

	public static @Nullable Component recentMessage() {
		return System.currentTimeMillis() - messageAt < MESSAGE_DURATION_MS ? message : null;
	}

	private static void schedulePoll() {
		if (!SpotifyAuth.isLoggedIn()) {
			state = PlayerState.EMPTY;
			return;
		}
		SpotifyConfig config = SpotifyConfig.get();
		boolean inWorld = Minecraft.getInstance().level != null;
		boolean active = screenOpen || (inWorld && (config.widgetEnabled || config.announceTrackChanges));
		if (!active || pollInFlight || System.currentTimeMillis() < nextPollAt) {
			return;
		}
		pollInFlight = true;
		EXECUTOR.execute(SpotifyService::poll);
	}

	private static void poll() {
		long interval = screenOpen ? SCREEN_POLL_MS : SpotifyConfig.get().pollIntervalSeconds * 1000L;
		try {
			SpotifyApi.Response response = SpotifyApi.get("/me/player?additional_types=episode");
			if (response.status() == 429) {
				interval = Math.max(response.retryAfterSeconds(), 5) * 1000;
			} else if (response.ok()) {
				if (System.currentTimeMillis() - lastCommandAt > OPTIMISTIC_HOLD_MS) {
					state = SpotifyModels.parsePlayer(response.body());
					announceTrackChange(state);
				}
				if (!state.playing() && !screenOpen) {
					interval = Math.max(interval, PAUSED_POLL_MS);
				}
			} else {
				state = PlayerState.EMPTY;
				interval = Math.max(interval, ERROR_BACKOFF_MS);
			}
		} catch (Exception e) {
			SpotifyClientMod.LOGGER.debug("Spotify poll failed", e);
			interval = Math.max(interval, ERROR_BACKOFF_MS);
		} finally {
			nextPollAt = System.currentTimeMillis() + interval;
			pollInFlight = false;
		}
	}

	private static void pollSoon(long delayMs) {
		nextPollAt = System.currentTimeMillis() + delayMs;
	}

	private static void announceTrackChange(PlayerState current) {
		if (current.track() == null || !current.playing()) {
			return;
		}
		String uri = current.track().uri();
		if (uri.equals(lastAnnouncedTrack)) {
			return;
		}
		boolean first = lastAnnouncedTrack == null;
		lastAnnouncedTrack = uri;
		if (!first && SpotifyConfig.get().announceTrackChanges && !screenOpen) {
			Component text = Component.literal("♪ ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(current.track().name()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" — " + current.track().subtitle()).withStyle(ChatFormatting.GRAY));
			Minecraft.getInstance().execute(() -> Ui.actionBar(text));
		}
	}

	public static void handleKeys(Minecraft minecraft) {
		while (SpotifyKeys.OPEN.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new SpotifyScreen(null));
			}
		}
		while (SpotifyKeys.PLAY_PAUSE.consumeClick()) {
			if (requireLogin()) {
				togglePlayPause();
			}
		}
		while (SpotifyKeys.NEXT.consumeClick()) {
			if (requireLogin()) {
				next();
			}
		}
		while (SpotifyKeys.PREVIOUS.consumeClick()) {
			if (requireLogin()) {
				previous();
			}
		}
		while (SpotifyKeys.TOGGLE_WIDGET.consumeClick()) {
			SpotifyConfig config = SpotifyConfig.get();
			config.widgetEnabled = !config.widgetEnabled;
			SpotifyConfig.save();
			Ui.actionBar(Component.translatable(config.widgetEnabled
				? "spotify-client.widget_shown" : "spotify-client.widget_hidden"));
		}
	}

	private static boolean requireLogin() {
		if (!SpotifyAuth.isLoggedIn()) {
			notify(Component.translatable("spotify-client.error.not_logged_in"));
			return false;
		}
		return true;
	}

	public static void togglePlayPause() {
		PlayerState current = state;
		if (current.track() != null && current.playing()) {
			optimistic(current.withPlaying(false));
			command("PUT", "/me/player/pause", null);
		} else {
			if (current.track() != null) {
				optimistic(current.withPlaying(true));
			}
			command("PUT", "/me/player/play", null);
		}
	}

	public static void next() {
		command("POST", "/me/player/next", null);
	}

	public static void previous() {
		command("POST", "/me/player/previous", null);
	}

	public static void setShuffle(boolean shuffle) {
		optimistic(state.withShuffle(shuffle));
		command("PUT", "/me/player/shuffle?state=" + shuffle, null);
	}

	public static void cycleRepeat() {
		Repeat repeat = state.repeat().next();
		optimistic(state.withRepeat(repeat));
		command("PUT", "/me/player/repeat?state=" + repeat.apiValue, null);
	}

	public static void setVolume(int percent) {
		int clamped = Math.clamp(percent, 0, 100);
		optimistic(state.withVolume(clamped));
		command("PUT", "/me/player/volume?volume_percent=" + clamped, null);
	}

	public static void seek(long positionMs) {
		optimistic(state.withProgress(positionMs));
		command("PUT", "/me/player/seek?position_ms=" + Math.max(0, positionMs), null);
	}

	public static void playContext(String contextUri, @Nullable String startTrackUri) {
		JsonObject body = new JsonObject();
		body.addProperty("context_uri", contextUri);
		if (startTrackUri != null) {
			JsonObject offset = new JsonObject();
			offset.addProperty("uri", startTrackUri);
			body.add("offset", offset);
		}
		command("PUT", "/me/player/play", body);
	}

	public static void playTracks(List<String> trackUris, int startIndex) {
		JsonObject body = new JsonObject();
		JsonArray uris = new JsonArray();
		trackUris.forEach(uris::add);
		body.add("uris", uris);
		JsonObject offset = new JsonObject();
		offset.addProperty("position", Math.clamp(startIndex, 0, Math.max(0, trackUris.size() - 1)));
		body.add("offset", offset);
		command("PUT", "/me/player/play", body);
	}

	public static void transferTo(Device device) {
		JsonObject body = new JsonObject();
		JsonArray ids = new JsonArray();
		ids.add(device.id());
		body.add("device_ids", ids);
		body.addProperty("play", true);
		command("PUT", "/me/player", body);
	}

	private static void optimistic(PlayerState newState) {
		state = newState;
		lastCommandAt = System.currentTimeMillis();
	}

	private static void command(String method, String path, @Nullable JsonObject body) {
		EXECUTOR.execute(() -> {
			try {
				SpotifyApi.Response response = SpotifyApi.send(method, path, body);
				if (response.status() == 404 && path.startsWith("/me/player/")) {
					Device device = pickDevice();
					if (device == null) {
						fail(Component.translatable("spotify-client.error.no_device"));
						return;
					}
					response = SpotifyApi.send(method, path + (path.contains("?") ? "&" : "?") + "device_id=" + SpotifyApi.encode(device.id()), body);
				}
				if (response.ok()) {
					pollSoon(400);
				} else {
					fail(describeError(response.status(), response.reason(), response.retryAfterSeconds()));
				}
			} catch (Exception e) {
				SpotifyClientMod.LOGGER.warn("Spotify command {} {} failed", method, path, e);
				fail(Component.translatable("spotify-client.error.network"));
			}
		});
	}

	private static @Nullable Device pickDevice() {
		try {
			List<Device> devices = SpotifyLibrary.fetchDevicesBlocking();
			return devices.stream().filter(Device::active).findFirst()
				.orElse(devices.stream().filter(d -> d.type().equalsIgnoreCase("computer")).findFirst()
					.orElse(devices.isEmpty() ? null : devices.getFirst()));
		} catch (Exception e) {
			return null;
		}
	}

	private static void fail(Component text) {
		lastCommandAt = 0;
		pollSoon(0);
		notify(text);
	}

	public static Component describeError(int status, @Nullable String reason, long retryAfterSeconds) {
		if (status == 401) {
			return Component.translatable("spotify-client.error.not_logged_in");
		}
		if (status == 403 && "PREMIUM_REQUIRED".equals(reason)) {
			return Component.translatable("spotify-client.error.premium_required");
		}
		if (status == 403 && "INSUFFICIENT_SCOPE".equals(reason)) {
			return Component.translatable("spotify-client.error.insufficient_scope");
		}
		if (status == 403) {
			return Component.translatable("spotify-client.error.forbidden");
		}
		if (status == 404 && "NO_ACTIVE_DEVICE".equals(reason)) {
			return Component.translatable("spotify-client.error.no_device");
		}
		if (status == 429) {
			return Component.translatable("spotify-client.error.rate_limited", Math.max(retryAfterSeconds, 1));
		}
		return Component.translatable("spotify-client.error.generic", status);
	}

	public static void notify(Component text) {
		show(text.copy().withStyle(ChatFormatting.RED));
	}

	public static void info(Component text) {
		show(text.copy().withStyle(ChatFormatting.GREEN));
	}

	private static void show(Component styled) {
		message = styled;
		messageAt = System.currentTimeMillis();
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (!(minecraft.gui.screen() instanceof SpotifyScreen)) {
				Ui.actionBar(styled);
			}
		});
	}

	public static Component describe(Throwable throwable) {
		Throwable cause = throwable;
		while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)
				&& cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof SpotifyLibrary.ApiException api) {
			return describeError(api.status, api.reason, api.retryAfterSeconds);
		}
		SpotifyClientMod.LOGGER.warn("Spotify request failed", cause);
		return Component.translatable("spotify-client.error.network");
	}

	private static ThreadFactory daemonThreads(String name) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
