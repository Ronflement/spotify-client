package com.spotifyclient.spotify;

import com.spotifyclient.SpotifyClientMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ImageCache {
	private static final int MAX_TEXTURES = 96;
	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(32, 0.75f, true);
	private static int nextId;

	private static final class Entry {
		final Identifier id;
		boolean ready;

		Entry(Identifier id) {
			this.id = id;
		}
	}

	private ImageCache() {
	}

	public static @Nullable Identifier get(@Nullable String url) {
		if (url == null || url.isEmpty()) {
			return null;
		}
		Entry entry = ENTRIES.get(url);
		if (entry == null) {
			entry = new Entry(Identifier.fromNamespaceAndPath(SpotifyClientMod.MOD_ID, "spotify/cover_" + nextId++));
			ENTRIES.put(url, entry);
			load(url, entry);
			evict();
		}
		return entry.ready ? entry.id : null;
	}

	public static void clear() {
		Minecraft minecraft = Minecraft.getInstance();
		for (Entry entry : ENTRIES.values()) {
			if (entry.ready) {
				minecraft.getTextureManager().release(entry.id);
			}
		}
		ENTRIES.clear();
	}

	private static void load(String url, Entry entry) {
		CompletableFuture.supplyAsync(() -> download(url), SpotifyService.EXECUTOR).whenComplete((image, error) -> {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> {
				if (ENTRIES.get(url) != entry) {
					if (image != null) {
						image.close();
					}
					return;
				}
				if (error != null || image == null) {
					SpotifyClientMod.LOGGER.debug("Failed to load Spotify cover {}", url, error);
					return;
				}
				minecraft.getTextureManager().register(entry.id, new DynamicTexture(() -> "spotify-client cover", image));
				entry.ready = true;
			});
		});
	}

	private static NativeImage download(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "spotify-client (Minecraft mod)")
				.GET()
				.build();
			HttpResponse<byte[]> response = SpotifyAuth.HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				throw new IOException("HTTP " + response.statusCode());
			}
			BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(response.body()));
			if (decoded == null) {
				throw new IOException("Unsupported image format");
			}
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			ImageIO.write(decoded, "png", png);
			return NativeImage.read(png.toByteArray());
		} catch (Exception e) {
			throw new CompletionException(e);
		}
	}

	private static void evict() {
		Iterator<Map.Entry<String, Entry>> iterator = ENTRIES.entrySet().iterator();
		while (ENTRIES.size() > MAX_TEXTURES && iterator.hasNext()) {
			Entry eldest = iterator.next().getValue();
			iterator.remove();
			if (eldest.ready) {
				Minecraft.getInstance().getTextureManager().release(eldest.id);
			}
		}
	}
}
