package com.spotifyclient;

import com.spotifyclient.config.SpotifyConfig;
import com.spotifyclient.gui.SpotifyHud;
import com.spotifyclient.spotify.SpotifyService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotifyClientMod implements ClientModInitializer {
	public static final String MOD_ID = "spotify-client";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		SpotifyConfig.load();
		SpotifyKeys.register();
		SpotifyService.init();

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"), SpotifyHud::extract);

		ClientTickEvents.END_CLIENT_TICK.register(SpotifyService::handleKeys);
	}
}
