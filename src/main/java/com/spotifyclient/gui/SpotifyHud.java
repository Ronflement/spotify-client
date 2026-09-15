package com.spotifyclient.gui;

import com.spotifyclient.config.SpotifyConfig;
import com.spotifyclient.spotify.SpotifyAuth;
import com.spotifyclient.spotify.SpotifyModels.Device;
import com.spotifyclient.spotify.SpotifyModels.Images;
import com.spotifyclient.spotify.SpotifyModels.PlayerState;
import com.spotifyclient.spotify.SpotifyModels.Repeat;
import com.spotifyclient.spotify.SpotifyModels.Track;
import com.spotifyclient.spotify.SpotifyService;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class SpotifyHud {
	private static final int PADDING = 4;
	private static final int LINE = 10;

	private SpotifyHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		SpotifyConfig config = SpotifyConfig.get();
		if (!config.widgetEnabled || !SpotifyAuth.isLoggedIn() || minecraft.gui.hud.isHidden()
				|| minecraft.gui.screen() instanceof SpotifyScreen || minecraft.gui.screen() instanceof SettingsScreen
				|| (config.widgetHideWithDebugScreen && minecraft.getDebugOverlay().showDebugScreen())) {
			return;
		}
		PlayerState state = SpotifyService.state();
		if (state.track() == null || (config.widgetHideWhenPaused && !state.playing())) {
			return;
		}
		draw(graphics, state);
	}

	public static void extractPreview(GuiGraphicsExtractor graphics) {
		PlayerState state = SpotifyService.state();
		if (state.track() == null) {
			Track sample = new Track("", "", Component.translatable("spotify-client.settings.preview.title").getString(),
				Component.translatable("spotify-client.settings.preview.artist").getString(), 214_000, Images.NONE, null, List.of());
			state = new PlayerState(sample, 83_000, true, false, Repeat.OFF, new Device("", "PC", "Computer", true, 70, true), null,
				System.currentTimeMillis());
		}
		draw(graphics, state);
	}

	public static int[] scaledSize() {
		SpotifyConfig config = SpotifyConfig.get();
		float scale = config.widgetScalePercent / 100f;
		return new int[]{Math.round(config.widgetWidth * scale), Math.round(height(config, SpotifyService.state()) * scale)};
	}

	private static int height(SpotifyConfig config, PlayerState state) {
		return PADDING * 2 + contentHeight(config, state);
	}

	private static int contentHeight(SpotifyConfig config, PlayerState state) {
		boolean showDevice = config.widgetShowDevice && state.device() != null;
		return LINE * (showDevice ? 3 : 2) + (config.widgetShowProgress ? LINE : 0) - 1;
	}

	private static void draw(GuiGraphicsExtractor graphics, PlayerState state) {
		Minecraft minecraft = Minecraft.getInstance();
		SpotifyConfig config = SpotifyConfig.get();
		Track track = state.track();
		Font font = minecraft.font;

		boolean showDevice = config.widgetShowDevice && state.device() != null;
		int contentHeight = contentHeight(config, state);
		int coverSize = config.widgetShowAlbumArt ? contentHeight : 0;
		int width = config.widgetWidth;
		int height = height(config, state);

		float scale = config.widgetScalePercent / 100f;
		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		int screenHeight = minecraft.getWindow().getGuiScaledHeight();
		float x = switch (config.widgetAnchor) {
			case TOP_LEFT, BOTTOM_LEFT -> config.widgetOffsetX;
			case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - width * scale - config.widgetOffsetX;
		};
		float y = switch (config.widgetAnchor) {
			case TOP_LEFT, TOP_RIGHT -> config.widgetOffsetY;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - height * scale - config.widgetOffsetY;
		};

		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);

		graphics.fill(0, 0, width, height, Ui.withAlpha(0x000000, config.widgetBackgroundOpacity * 255 / 100));
		if (coverSize > 0) {
			Ui.cover(graphics, font, track.images().large(), PADDING, PADDING, coverSize);
		}

		int textX = PADDING + (coverSize > 0 ? coverSize + 5 : 0);
		int textWidth = width - textX - PADDING;
		int accent = 0xFF000000 | config.accentColor;
		int lineY = PADDING;

		String title = (state.playing() ? "" : "⏸ ") + track.name();
		graphics.text(font, Ui.ellipsize(font, title, textWidth), textX, lineY, state.playing() ? Ui.WHITE : Ui.GRAY, true);
		lineY += LINE;
		graphics.text(font, Ui.ellipsize(font, track.subtitle(), textWidth), textX, lineY, Ui.GRAY, true);
		lineY += LINE;
		if (showDevice) {
			graphics.text(font, Ui.ellipsize(font, "▣ " + state.device().name(), textWidth), textX, lineY, Ui.DARK_GRAY, true);
			lineY += LINE;
		}

		if (config.widgetShowProgress) {
			long progress = state.currentProgressMs();
			String elapsed = Ui.time(progress);
			String total = Ui.time(track.durationMs());
			int totalX = width - PADDING - font.width(total);
			graphics.text(font, elapsed, textX, lineY, Ui.GRAY, true);
			graphics.text(font, total, totalX, lineY, Ui.GRAY, true);
			int barX0 = textX + font.width(elapsed) + 4;
			int barX1 = totalX - 4;
			if (barX1 > barX0) {
				double fraction = track.durationMs() > 0 ? (double) progress / track.durationMs() : 0;
				Ui.progressBar(graphics, barX0, lineY + 3, barX1, lineY + 5, fraction, accent);
			}
		}

		graphics.pose().popMatrix();
	}
}
