package com.spotifyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.util.List;

public final class SpotifyKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SpotifyClientMod.MOD_ID, "main"));

	public static final KeyMapping OPEN = new KeyMapping("key.spotify-client.open", InputConstants.KEY_Y, CATEGORY);
	public static final KeyMapping PLAY_PAUSE = new KeyMapping("key.spotify-client.play_pause", InputConstants.KEY_NUMPAD5, CATEGORY);
	public static final KeyMapping NEXT = new KeyMapping("key.spotify-client.next", InputConstants.KEY_NUMPAD6, CATEGORY);
	public static final KeyMapping PREVIOUS = new KeyMapping("key.spotify-client.previous", InputConstants.KEY_NUMPAD4, CATEGORY);
	public static final KeyMapping TOGGLE_WIDGET = new KeyMapping("key.spotify-client.toggle_widget", InputConstants.UNKNOWN.getValue(), CATEGORY);

	public static final List<KeyMapping> ALL = List.of(OPEN, PLAY_PAUSE, NEXT, PREVIOUS, TOGGLE_WIDGET);

	private SpotifyKeys() {
	}

	public static void register() {
		ALL.forEach(KeyMappingHelper::registerKeyMapping);
	}
}
