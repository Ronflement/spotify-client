package com.spotifyclient.gui;

import com.spotifyclient.spotify.ImageCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class Ui {
	public static final int WHITE = 0xFFFFFFFF;
	public static final int GRAY = 0xFFB3B3B3;
	public static final int DARK_GRAY = 0xFF7A7A7A;
	public static final int SPOTIFY_GREEN = 0xFF1DB954;

	private Ui() {
	}

	public static String ellipsize(Font font, String text, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis))).stripTrailing() + ellipsis;
	}

	public static String time(long millis) {
		long totalSeconds = Math.max(0, millis) / 1000;
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, seconds) : String.format("%d:%02d", minutes, seconds);
	}

	public static void cover(GuiGraphicsExtractor graphics, Font font, @Nullable String url, int x, int y, int size) {
		Identifier texture = ImageCache.get(url);
		if (texture != null) {
			graphics.blit(texture, x, y, x + size, y + size, 0, 1, 0, 1);
			return;
		}
		graphics.fill(x, y, x + size, y + size, 0xFF333333);
		String note = "♪";
		graphics.text(font, note, x + (size - font.width(note)) / 2, y + (size - 8) / 2, DARK_GRAY, false);
	}

	public static void progressBar(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, double fraction, int fillColor) {
		graphics.fill(x0, y0, x1, y1, 0xFF4D4D4D);
		int filled = x0 + (int) Math.round((x1 - x0) * Math.clamp(fraction, 0, 1));
		if (filled > x0) {
			graphics.fill(x0, y0, filled, y1, fillColor);
		}
	}

	public static boolean inside(double mouseX, double mouseY, int x0, int y0, int x1, int y1) {
		return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
	}

	public static void actionBar(Component message) {
		Minecraft.getInstance().gui.hud.setOverlayMessage(message, false);
	}

	public static int withAlpha(int rgb, int alpha) {
		return (Math.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
	}
}
