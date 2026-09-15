package com.spotifyclient.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.spotifyclient.SpotifyKeys;
import com.spotifyclient.config.SpotifyConfig;
import com.spotifyclient.config.SpotifyConfig.WidgetAnchor;
import com.spotifyclient.spotify.ImageCache;
import com.spotifyclient.spotify.SavedCache;
import com.spotifyclient.spotify.SpotifyAuth;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class SettingsScreen extends Screen {
	private static final int BACKGROUND = 0xF2121212;
	private static final int SIDEBAR = 0xFF000000;
	private static final int DIVIDER = 0xFF2A2A2A;
	private static final int HOVER = 0x22FFFFFF;
	private static final int SELECTED = 0xFF282828;
	private static final int CONTROL = 0xFF2A2A2A;
	private static final int CONTROL_HOVER = 0xFF3E3E3E;
	private static final int ERROR = 0xFFE9565C;
	private static final int YELLOW = 0xFFE9C46A;

	private static final int SIDEBAR_WIDTH = 104;
	private static final int ROW = 22;
	private static final int CONTROL_WIDTH = 130;
	private static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";
	private static final int[] ACCENT_COLORS = {0x1DB954, 0x3B82F6, 0xA855F7, 0xEC4899, 0xF97316, 0xEF4444, 0xEAB308, 0xFFFFFF};

	private enum Tab {
		ACCOUNT("●"), WIDGET("▣"), PLAYBACK("♪"), KEYS("#");

		final String icon;

		Tab(String icon) {
			this.icon = icon;
		}
	}

	private static Tab lastTab = Tab.ACCOUNT;

	private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
	}

	private record Slider(int x0, int x1, int y0, int y1, int min, int max, IntConsumer setter) {
		int valueAt(double mouseX) {
			double fraction = Math.clamp((mouseX - x0) / (double) (x1 - x0), 0, 1);
			return min + (int) Math.round(fraction * (max - min));
		}
	}

	private final @Nullable Screen parent;
	private final SpotifyConfig config = SpotifyConfig.get();
	private final List<Hit> hits = new ArrayList<>();
	private final List<Slider> sliders = new ArrayList<>();
	private Tab tab = lastTab;
	private @Nullable EditBox clientIdBox;
	private @Nullable EditBox portBox;
	private @Nullable Slider activeSlider;
	private @Nullable KeyMapping waitingKey;
	private @Nullable Component notice;
	private long noticeAt;
	private double scroll;
	private int contentHeight;

	private int left;
	private int top;
	private int right;
	private int bottom;
	private int contentLeft;
	private int viewTop;
	private int viewBottom;

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("spotify-client.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int margin = width < 520 ? 4 : 12;
		left = margin;
		top = margin;
		right = width - margin;
		bottom = height - margin;
		contentLeft = left + SIDEBAR_WIDTH;
		viewTop = top + 30;
		viewBottom = bottom;

		EditBox previousClientId = clientIdBox;
		clientIdBox = new EditBox(font, 0, 0, 180, 16, previousClientId, text("account.client_id"));
		clientIdBox.setMaxLength(64);
		if (previousClientId == null) {
			clientIdBox.setValue(config.clientId);
		}
		clientIdBox.setHint(text("account.client_id.hint"));
		clientIdBox.setResponder(value -> {
			String trimmed = value.trim();
			if (trimmed.isEmpty() || isValidClientId(trimmed)) {
				config.clientId = trimmed;
				SpotifyConfig.save();
			}
		});
		addRenderableWidget(clientIdBox);

		EditBox previousPort = portBox;
		portBox = new EditBox(font, 0, 0, 60, 16, previousPort, text("account.port"));
		portBox.setMaxLength(5);
		if (previousPort == null) {
			portBox.setValue(String.valueOf(config.redirectPort));
		}
		portBox.setResponder(value -> {
			Integer port = parsePort(value);
			if (port != null) {
				config.redirectPort = port;
				SpotifyConfig.save();
			}
		});
		addRenderableWidget(portBox);
	}

	@Override
	public void removed() {
		SpotifyConfig.save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void selectTab(Tab newTab) {
		tab = newTab;
		lastTab = newTab;
		scroll = 0;
		waitingKey = null;
		setFocused(null);
	}

	private void notice(Component message) {
		notice = message;
		noticeAt = System.currentTimeMillis();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		hits.clear();
		sliders.clear();
		if (clientIdBox != null && portBox != null) {
			clientIdBox.visible = false;
			portBox.visible = false;
		}

		graphics.fill(left, top, right, bottom, BACKGROUND);
		extractSidebar(graphics, mouseX, mouseY);

		graphics.text(font, text("tab." + tab.name().toLowerCase(Locale.ROOT)), contentLeft + 12, top + 11, Ui.WHITE, false);
		graphics.fill(contentLeft, viewTop - 1, right, viewTop, DIVIDER);

		int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
		scroll = Math.clamp(scroll, 0, maxScroll);
		graphics.enableScissor(contentLeft, viewTop, right, viewBottom);
		int y = viewTop + 6 - (int) scroll;
		int startY = y;
		y = switch (tab) {
			case ACCOUNT -> extractAccount(graphics, y, mouseX, mouseY);
			case WIDGET -> extractWidget(graphics, y, mouseX, mouseY);
			case PLAYBACK -> extractPlayback(graphics, y, mouseX, mouseY);
			case KEYS -> extractKeys(graphics, y, mouseX, mouseY);
		};
		contentHeight = y - startY + 10;
		graphics.disableScissor();

		if (maxScroll > 0) {
			int viewHeight = viewBottom - viewTop;
			int thumbHeight = Math.max(12, viewHeight * viewHeight / contentHeight);
			int thumbY = viewTop + (int) ((viewHeight - thumbHeight) * (scroll / maxScroll));
			graphics.fill(right - 5, viewTop, right - 3, viewBottom, 0xFF202020);
			graphics.fill(right - 5, thumbY, right - 3, thumbY + thumbHeight, 0xFF7A7A7A);
		}

		if (notice != null && System.currentTimeMillis() - noticeAt < 3000) {
			int textWidth = font.width(notice);
			int centerX = (contentLeft + right) / 2;
			graphics.fill(centerX - textWidth / 2 - 6, bottom - 22, centerX + textWidth / 2 + 6, bottom - 8, 0xF0282828);
			graphics.text(font, notice, centerX - textWidth / 2, bottom - 19, Ui.SPOTIFY_GREEN, false);
		}

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (tab == Tab.WIDGET && config.widgetEnabled) {
			graphics.nextStratum();
			SpotifyHud.extractPreview(graphics);
		}
	}

	private void extractSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x1 = left + SIDEBAR_WIDTH;
		graphics.fill(left, top, x1, bottom, SIDEBAR);
		graphics.text(font, "♪ Spotify", left + 8, top + 8, Ui.SPOTIFY_GREEN, false);
		graphics.text(font, text("title_short"), left + 8, top + 18, Ui.GRAY, false);

		int y = top + 36;
		for (Tab entry : Tab.values()) {
			boolean selected = entry == tab;
			boolean hovered = Ui.inside(mouseX, mouseY, left + 4, y, x1 - 4, y + 16);
			if (selected || hovered) {
				graphics.fill(left + 4, y, x1 - 4, y + 16, selected ? SELECTED : HOVER);
			}
			graphics.text(font, entry.icon, left + 9, y + 4, selected ? Ui.SPOTIFY_GREEN : Ui.GRAY, false);
			graphics.text(font, Ui.ellipsize(font, text("tab." + entry.name().toLowerCase(Locale.ROOT)).getString(), SIDEBAR_WIDTH - 32),
				left + 22, y + 4, selected || hovered ? Ui.WHITE : Ui.GRAY, false);
			hits.add(new Hit(left + 4, y, x1 - 4, y + 16, () -> selectTab(entry)));
			y += 17;
		}

		int backY = bottom - 20;
		boolean clientHovered = Ui.inside(mouseX, mouseY, left + 4, backY - 16, x1 - 4, backY - 2);
		graphics.text(font, "♫ " + text("open_client").getString(), left + 8, backY - 12, clientHovered ? Ui.WHITE : Ui.GRAY, false);
		hits.add(new Hit(left + 4, backY - 16, x1 - 4, backY - 2, () -> {
			if (parent instanceof SpotifyScreen) {
				onClose();
			} else {
				minecraft.gui.setScreen(new SpotifyScreen(this));
			}
		}));
		boolean backHovered = Ui.inside(mouseX, mouseY, left + 4, backY, x1 - 4, backY + 14);
		graphics.text(font, "◀ " + text("back").getString(), left + 8, backY + 3, backHovered ? Ui.WHITE : Ui.GRAY, false);
		hits.add(new Hit(left + 4, backY, x1 - 4, backY + 14, this::onClose));
	}

	private int extractAccount(GuiGraphicsExtractor graphics, int y, int mouseX, int mouseY) {
		y = heading(graphics, text("account.section"), y);

		Component status = switch (SpotifyAuth.status()) {
			case LOGGED_IN -> Component.translatable("spotify-client.settings.account.logged_in",
				SpotifyAuth.displayName() != null ? SpotifyAuth.displayName() : "?");
			case WAITING_FOR_BROWSER -> text("account.waiting");
			case LOGGED_OUT -> text("account.logged_out");
		};
		int statusColor = switch (SpotifyAuth.status()) {
			case LOGGED_IN -> Ui.SPOTIFY_GREEN;
			case WAITING_FOR_BROWSER -> YELLOW;
			case LOGGED_OUT -> Ui.GRAY;
		};
		Component buttonLabel = text("account.button." + SpotifyAuth.status().name().toLowerCase(Locale.ROOT));
		boolean primary = SpotifyAuth.status() == SpotifyAuth.Status.LOGGED_OUT;
		y = buttonRow(graphics, status, statusColor, buttonLabel, primary, y, mouseX, mouseY, this::accountAction);
		if (SpotifyAuth.lastError() != null && SpotifyAuth.status() != SpotifyAuth.Status.LOGGED_IN) {
			graphics.text(font, text("error." + SpotifyAuth.lastError()), contentLeft + 12, y, ERROR, false);
			y += 14;
		}

		y = heading(graphics, text("app.section"), y + 6);
		String clientId = clientIdBox != null ? clientIdBox.getValue().trim() : config.clientId;
		y = fieldRow(graphics, text("account.client_id"), clientIdBox, y,
			!clientId.isEmpty() && !isValidClientId(clientId) ? text("account.client_id.invalid") : null);
		String port = portBox != null ? portBox.getValue() : String.valueOf(config.redirectPort);
		y = fieldRow(graphics, text("account.port"), portBox, y, parsePort(port) == null ? text("account.port.invalid") : null);

		String redirectUri = SpotifyAuth.redirectUri(config.redirectPort);
		y = buttonRow(graphics, Component.literal(redirectUri), YELLOW, text("account.copy"), false, y, mouseX, mouseY, () -> {
			minecraft.keyboardHandler.setClipboard(redirectUri);
			notice(text("account.copied"));
		});
		y = buttonRow(graphics, text("account.dashboard"), Ui.WHITE, text("account.open"), false, y, mouseX, mouseY,
			() -> Util.getPlatform().openUri(URI.create(DASHBOARD_URL)));

		y = heading(graphics, text("steps.section"), y + 6);
		y = paragraph(graphics, Component.translatable("spotify-client.settings.steps", redirectUri), y, Ui.GRAY);
		return paragraph(graphics, text("steps.note"), y + 4, Ui.DARK_GRAY);
	}

	private void accountAction() {
		switch (SpotifyAuth.status()) {
			case LOGGED_OUT -> {
				if (!isValidClientId(config.clientId)) {
					notice(text("account.client_id.invalid"));
					return;
				}
				SpotifyAuth.startLogin(config.clientId, config.redirectPort);
			}
			case WAITING_FOR_BROWSER -> SpotifyAuth.cancelLogin();
			case LOGGED_IN -> {
				SpotifyAuth.logout();
				ImageCache.clear();
				SavedCache.clear();
			}
		}
	}

	private int extractWidget(GuiGraphicsExtractor graphics, int y, int mouseX, int mouseY) {
		y = heading(graphics, text("widget.section"), y);
		y = toggleRow(graphics, text("widget.enabled"), config.widgetEnabled, y, mouseX, mouseY, v -> config.widgetEnabled = v);
		y = cycleRow(graphics, text("widget.anchor"), text("anchor." + config.widgetAnchor.name().toLowerCase(Locale.ROOT)), y, mouseX, mouseY,
			() -> config.widgetAnchor = WidgetAnchor.values()[(config.widgetAnchor.ordinal() + 1) % WidgetAnchor.values().length]);
		int maxX = Math.max(0, minecraft.getWindow().getGuiScaledWidth() - SpotifyHud.scaledSize()[0]);
		int maxY = Math.max(0, minecraft.getWindow().getGuiScaledHeight() - SpotifyHud.scaledSize()[1]);
		y = sliderRow(graphics, text("widget.offset_x"), config.widgetOffsetX, 0, maxX, String::valueOf, y, mouseX, mouseY, v -> config.widgetOffsetX = v);
		y = sliderRow(graphics, text("widget.offset_y"), config.widgetOffsetY, 0, maxY, String::valueOf, y, mouseX, mouseY, v -> config.widgetOffsetY = v);
		y = sliderRow(graphics, text("widget.scale"), config.widgetScalePercent, 50, 200, v -> v + "%", y, mouseX, mouseY, v -> config.widgetScalePercent = v);
		y = sliderRow(graphics, text("widget.width"), config.widgetWidth, 110, 320, String::valueOf, y, mouseX, mouseY, v -> config.widgetWidth = v);
		y = sliderRow(graphics, text("widget.opacity"), config.widgetBackgroundOpacity, 0, 100, v -> v + "%", y, mouseX, mouseY,
			v -> config.widgetBackgroundOpacity = v);

		y = heading(graphics, text("widget.content"), y + 6);
		y = toggleRow(graphics, text("widget.cover"), config.widgetShowAlbumArt, y, mouseX, mouseY, v -> config.widgetShowAlbumArt = v);
		y = toggleRow(graphics, text("widget.progress"), config.widgetShowProgress, y, mouseX, mouseY, v -> config.widgetShowProgress = v);
		y = toggleRow(graphics, text("widget.device"), config.widgetShowDevice, y, mouseX, mouseY, v -> config.widgetShowDevice = v);
		y = toggleRow(graphics, text("widget.hide_paused"), config.widgetHideWhenPaused, y, mouseX, mouseY, v -> config.widgetHideWhenPaused = v);
		y = toggleRow(graphics, text("widget.hide_debug"), config.widgetHideWithDebugScreen, y, mouseX, mouseY, v -> config.widgetHideWithDebugScreen = v);

		y = heading(graphics, text("widget.accent"), y + 6);
		int swatchX = contentLeft + 12;
		for (int color : ACCENT_COLORS) {
			boolean selected = (config.accentColor & 0xFFFFFF) == color;
			boolean hovered = Ui.inside(mouseX, mouseY, swatchX, y, swatchX + 16, y + 16);
			if (selected || hovered) {
				graphics.fill(swatchX - 2, y - 2, swatchX + 18, y + 18, selected ? Ui.WHITE : Ui.GRAY);
			}
			graphics.fill(swatchX, y, swatchX + 16, y + 16, 0xFF000000 | color);
			addHit(swatchX, y, swatchX + 16, y + 16, () -> {
				config.accentColor = color;
				SpotifyConfig.save();
			});
			swatchX += 22;
		}
		return y + 22;
	}

	private int extractPlayback(GuiGraphicsExtractor graphics, int y, int mouseX, int mouseY) {
		y = heading(graphics, text("playback.section"), y);
		y = toggleRow(graphics, text("playback.announce"), config.announceTrackChanges, y, mouseX, mouseY, v -> config.announceTrackChanges = v);
		y = sliderRow(graphics, text("playback.poll"), config.pollIntervalSeconds, 1, 15, v -> v + " s", y, mouseX, mouseY,
			v -> config.pollIntervalSeconds = v);
		return paragraph(graphics, text("playback.poll.note"), y + 2, Ui.DARK_GRAY);
	}

	private int extractKeys(GuiGraphicsExtractor graphics, int y, int mouseX, int mouseY) {
		y = heading(graphics, text("keys.section"), y);
		for (KeyMapping mapping : SpotifyKeys.ALL) {
			y = keyRow(graphics, mapping, y, mouseX, mouseY);
		}
		return paragraph(graphics, text("keys.note"), y + 4, Ui.DARK_GRAY);
	}

	private int heading(GuiGraphicsExtractor graphics, Component title, int y) {
		graphics.text(font, title, contentLeft + 12, y + 4, Ui.SPOTIFY_GREEN, false);
		return y + 18;
	}

	private int paragraph(GuiGraphicsExtractor graphics, Component text, int y, int color) {
		for (FormattedCharSequence line : font.split(text, right - contentLeft - 30)) {
			graphics.text(font, line, contentLeft + 12, y, color, false);
			y += 10;
		}
		return y;
	}

	private int controlX() {
		return right - 16 - CONTROL_WIDTH;
	}

	private void label(GuiGraphicsExtractor graphics, Component label, int y, int color) {
		graphics.text(font, Ui.ellipsize(font, label.getString(), controlX() - contentLeft - 24), contentLeft + 12, y + 7, color, false);
	}

	private int toggleRow(GuiGraphicsExtractor graphics, Component label, boolean value, int y, int mouseX, int mouseY, Consumer<Boolean> setter) {
		label(graphics, label, y, Ui.WHITE);
		int x1 = right - 16;
		int x0 = x1 - 24;
		int accent = 0xFF000000 | config.accentColor;
		boolean hovered = inView(mouseY) && Ui.inside(mouseX, mouseY, x0, y + 5, x1, y + 17);
		graphics.fill(x0, y + 5, x1, y + 17, value ? accent : hovered ? CONTROL_HOVER : 0xFF4D4D4D);
		int knobX = value ? x1 - 11 : x0 + 1;
		graphics.fill(knobX, y + 6, knobX + 10, y + 16, Ui.WHITE);
		addHit(contentLeft + 8, y, x1, y + ROW, () -> {
			setter.accept(!value);
			SpotifyConfig.save();
		});
		return y + ROW;
	}

	private int cycleRow(GuiGraphicsExtractor graphics, Component label, Component value, int y, int mouseX, int mouseY, Runnable next) {
		label(graphics, label, y, Ui.WHITE);
		int x0 = controlX();
		int x1 = right - 16;
		boolean hovered = inView(mouseY) && Ui.inside(mouseX, mouseY, x0, y + 2, x1, y + 20);
		graphics.fill(x0, y + 2, x1, y + 20, hovered ? CONTROL_HOVER : CONTROL);
		String shown = Ui.ellipsize(font, value.getString(), CONTROL_WIDTH - 20);
		graphics.text(font, shown, (x0 + x1 - font.width(shown)) / 2, y + 7, Ui.WHITE, false);
		graphics.text(font, "›", x1 - 8, y + 7, Ui.GRAY, false);
		addHit(x0, y + 2, x1, y + 20, () -> {
			next.run();
			SpotifyConfig.save();
		});
		return y + ROW;
	}

	private int sliderRow(GuiGraphicsExtractor graphics, Component label, int value, int min, int max, IntFunction<String> format,
			int y, int mouseX, int mouseY, IntConsumer setter) {
		label(graphics, label, y, Ui.WHITE);
		int x1 = right - 16;
		int x0 = x1 - CONTROL_WIDTH + 40;
		String shown = format.apply(value);
		graphics.text(font, shown, x0 - 8 - font.width(shown), y + 7, Ui.GRAY, false);
		boolean active = activeSlider != null && activeSlider.y0() == y + 2;
		boolean hovered = active || (inView(mouseY) && Ui.inside(mouseX, mouseY, x0 - 4, y + 2, x1 + 4, y + 20));
		double fraction = max > min ? (double) (Math.clamp(value, min, max) - min) / (max - min) : 0;
		int accent = 0xFF000000 | config.accentColor;
		Ui.progressBar(graphics, x0, y + 10, x1, y + 12, fraction, hovered ? accent : Ui.WHITE);
		int knobX = x0 + (int) Math.round((x1 - x0) * fraction);
		if (hovered) {
			graphics.fill(knobX - 3, y + 8, knobX + 3, y + 14, Ui.WHITE);
		}
		if (y + 2 >= viewTop && y + 20 <= viewBottom) {
			sliders.add(new Slider(x0, x1, y + 2, y + 20, min, max, setter));
		}
		return y + ROW;
	}

	private int fieldRow(GuiGraphicsExtractor graphics, Component label, @Nullable EditBox box, int y, @Nullable Component error) {
		label(graphics, label, y, Ui.WHITE);
		if (box != null) {
			int boxWidth = box == portBox ? 60 : Math.min(200, right - 16 - (contentLeft + 12 + font.width(label) + 12));
			box.setWidth(boxWidth);
			box.setX(right - 16 - boxWidth);
			box.setY(y + 3);
			box.visible = y + 3 >= viewTop && y + 19 <= viewBottom;
		}
		if (error != null) {
			graphics.text(font, error, contentLeft + 12, y + ROW, ERROR, false);
			return y + ROW + 12;
		}
		return y + ROW;
	}

	private int buttonRow(GuiGraphicsExtractor graphics, Component label, int labelColor, Component button, boolean primary,
			int y, int mouseX, int mouseY, Runnable action) {
		int buttonWidth = Math.max(70, font.width(button) + 16);
		int x1 = right - 16;
		int x0 = x1 - buttonWidth;
		graphics.text(font, Ui.ellipsize(font, label.getString(), x0 - contentLeft - 24), contentLeft + 12, y + 7, labelColor, false);
		boolean hovered = inView(mouseY) && Ui.inside(mouseX, mouseY, x0, y + 2, x1, y + 20);
		int background = primary ? (hovered ? 0xFF1ED760 : Ui.SPOTIFY_GREEN) : (hovered ? CONTROL_HOVER : CONTROL);
		graphics.fill(x0, y + 2, x1, y + 20, background);
		graphics.text(font, button, (x0 + x1 - font.width(button)) / 2, y + 7, primary ? 0xFF000000 : Ui.WHITE, false);
		addHit(x0, y + 2, x1, y + 20, action);
		return y + ROW;
	}

	private int keyRow(GuiGraphicsExtractor graphics, KeyMapping mapping, int y, int mouseX, int mouseY) {
		label(graphics, Component.translatable(mapping.getName()), y, Ui.WHITE);
		Component resetLabel = text("keys.reset");
		int resetX1 = right - 16;
		int resetX0 = resetX1 - font.width(resetLabel) - 12;
		int x1 = resetX0 - 4;
		int x0 = x1 - CONTROL_WIDTH + 22;
		boolean waiting = waitingKey == mapping;
		boolean hovered = inView(mouseY) && Ui.inside(mouseX, mouseY, x0, y + 2, x1, y + 20);
		graphics.fill(x0, y + 2, x1, y + 20, waiting ? 0xFF3E3E3E : hovered ? CONTROL_HOVER : CONTROL);
		Component keyText = waiting ? Component.literal("> ").append(text("keys.press")).append(" <") : mapping.getTranslatedKeyMessage();
		String shown = Ui.ellipsize(font, keyText.getString(), x1 - x0 - 8);
		graphics.text(font, shown, (x0 + x1 - font.width(shown)) / 2, y + 7, waiting ? YELLOW : mapping.isUnbound() ? Ui.DARK_GRAY : Ui.WHITE, false);
		addHit(x0, y + 2, x1, y + 20, () -> waitingKey = mapping);

		boolean canReset = !mapping.isDefault();
		boolean resetHovered = canReset && inView(mouseY) && Ui.inside(mouseX, mouseY, resetX0, y + 2, resetX1, y + 20);
		graphics.fill(resetX0, y + 2, resetX1, y + 20, resetHovered ? CONTROL_HOVER : CONTROL);
		graphics.text(font, resetLabel, resetX0 + 6, y + 7, canReset ? Ui.WHITE : Ui.DARK_GRAY, false);
		if (canReset) {
			addHit(resetX0, y + 2, resetX1, y + 20, () -> bind(mapping, mapping.getDefaultKey()));
		}
		return y + ROW;
	}

	private void addHit(int x0, int y0, int x1, int y1, Runnable action) {
		int clippedTop = Math.max(y0, viewTop);
		int clippedBottom = Math.min(y1, viewBottom);
		if (clippedBottom > clippedTop) {
			hits.add(new Hit(x0, clippedTop, x1, clippedBottom, action));
		}
	}

	private boolean inView(int mouseY) {
		return mouseY >= viewTop && mouseY < viewBottom;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();

		if (waitingKey != null) {
			if (event.input() > InputConstants.MOUSE_BUTTON_RIGHT) {
				bind(waitingKey, InputConstants.Type.MOUSE.getOrCreate(event.input()));
			}
			waitingKey = null;
			return true;
		}

		for (EditBox box : new EditBox[]{clientIdBox, portBox}) {
			if (box != null && box.visible && box.isMouseOver(mouseX, mouseY)) {
				return super.mouseClicked(event, doubleClick);
			}
		}
		setFocused(null);

		if (event.input() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(event, doubleClick);
		}
		for (Slider slider : sliders) {
			if (Ui.inside(mouseX, mouseY, slider.x0() - 4, slider.y0(), slider.x1() + 4, slider.y1())) {
				activeSlider = slider;
				slider.setter().accept(slider.valueAt(mouseX));
				return true;
			}
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (Ui.inside(mouseX, mouseY, hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (activeSlider != null) {
			activeSlider.setter().accept(activeSlider.valueAt(event.x()));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (activeSlider != null) {
			activeSlider = null;
			SpotifyConfig.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (Ui.inside(mouseX, mouseY, contentLeft, viewTop, right, viewBottom)) {
			scroll -= scrollY * ROW;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (waitingKey != null) {
			if (event.isEscape()) {
				waitingKey = null;
			} else if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE) {
				bind(waitingKey, InputConstants.UNKNOWN);
			} else {
				bind(waitingKey, InputConstants.getKey(event));
			}
			return true;
		}
		return super.keyPressed(event);
	}

	private void bind(KeyMapping mapping, InputConstants.Key key) {
		mapping.setKey(key);
		KeyMapping.resetMapping();
		minecraft.options.save();
		waitingKey = null;
	}

	private static boolean isValidClientId(String value) {
		return value.matches("[0-9a-fA-F]{32}");
	}

	private static @Nullable Integer parsePort(String value) {
		try {
			int port = Integer.parseInt(value);
			return port >= 1024 && port <= 65535 ? port : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Component text(String key) {
		return Component.translatable("spotify-client.settings." + key);
	}
}
