package com.spotifyclient.gui;

import com.spotifyclient.spotify.SpotifyActions;
import com.spotifyclient.spotify.SpotifyModels.Collection;
import com.spotifyclient.spotify.SpotifyService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

public class PlaylistEditScreen extends Screen {
	private static final int PANEL_WIDTH = 280;
	private static final int PANEL_HEIGHT = 170;

	private final Screen parent;
	private final @Nullable Collection playlist;
	private final Consumer<Collection> onSaved;

	private @Nullable EditBox name;
	private @Nullable EditBox description;
	private boolean isPublic;
	private boolean collaborative;
	private @Nullable Button save;
	private @Nullable Component error;
	private boolean saving;

	public PlaylistEditScreen(Screen parent, @Nullable Collection playlist, Consumer<Collection> onSaved) {
		super(Component.translatable(playlist == null ? "spotify-client.playlist.create" : "spotify-client.playlist.edit"));
		this.parent = parent;
		this.playlist = playlist;
		this.onSaved = onSaved;
		this.isPublic = playlist != null && playlist.isPublic();
		this.collaborative = playlist != null && playlist.collaborative();
	}

	@Override
	protected void init() {
		int left = (width - PANEL_WIDTH) / 2 + 12;
		int top = (height - PANEL_HEIGHT) / 2;
		int fieldWidth = PANEL_WIDTH - 24;

		EditBox previousName = name;
		name = new EditBox(font, left, top + 34, fieldWidth, 18, previousName, Component.translatable("spotify-client.playlist.name"));
		name.setMaxLength(100);
		name.setHint(Component.translatable("spotify-client.playlist.name"));
		if (previousName == null && playlist != null) {
			name.setValue(playlist.name());
		}
		name.setResponder(value -> updateSaveButton());
		addRenderableWidget(name);

		EditBox previousDescription = description;
		description = new EditBox(font, left, top + 70, fieldWidth, 18, previousDescription, Component.translatable("spotify-client.playlist.description"));
		description.setMaxLength(300);
		description.setHint(Component.translatable("spotify-client.playlist.description"));
		if (previousDescription == null && playlist != null) {
			description.setValue(playlist.description());
		}
		addRenderableWidget(description);

		int halfWidth = (fieldWidth - 4) / 2;
		addRenderableWidget(CycleButton.onOffBuilder(isPublic)
			.create(left, top + 98, halfWidth, 20, Component.translatable("spotify-client.playlist.public"), (button, value) -> isPublic = value));
		addRenderableWidget(CycleButton.onOffBuilder(collaborative)
			.create(left + halfWidth + 4, top + 98, halfWidth, 20, Component.translatable("spotify-client.playlist.collaborative"), (button, value) -> collaborative = value));

		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left, top + PANEL_HEIGHT - 28, halfWidth, 20).build());
		save = addRenderableWidget(Button.builder(Component.translatable("spotify-client.playlist.save"), button -> submit())
			.bounds(left + halfWidth + 4, top + PANEL_HEIGHT - 28, halfWidth, 20).build());

		setInitialFocus(name);
		updateSaveButton();
	}

	private void updateSaveButton() {
		if (save != null && name != null) {
			save.active = !saving && !name.getValue().isBlank();
		}
	}

	private void submit() {
		if (name == null || description == null || name.getValue().isBlank()) {
			return;
		}
		saving = true;
		error = null;
		updateSaveButton();

		String newName = name.getValue().trim();
		String newDescription = description.getValue().trim();
		var request = playlist == null
			? SpotifyActions.createPlaylist(newName, newDescription, isPublic, collaborative)
			: SpotifyActions.updatePlaylist(playlist, newName, newDescription, isPublic, collaborative)
				.thenApply(ignored -> playlist.withDetails(newName, newDescription, isPublic && !collaborative, collaborative));
		request.whenComplete((result, throwable) -> minecraft.execute(() -> {
			saving = false;
			if (throwable != null) {
				error = SpotifyService.describe(throwable);
				updateSaveButton();
				return;
			}
			minecraft.gui.setScreen(parent);
			onSaved.accept(result);
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;
		graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, 0xFF3E3E3E);
		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF2181818);
		graphics.text(font, title, left + 12, top + 10, Ui.WHITE, false);
		graphics.text(font, Component.translatable("spotify-client.playlist.name"), left + 12, top + 24, Ui.GRAY, false);
		graphics.text(font, Component.translatable("spotify-client.playlist.description"), left + 12, top + 60, Ui.GRAY, false);
		if (error != null) {
			graphics.text(font, Ui.ellipsize(font, error.getString(), PANEL_WIDTH - 24), left + 12, top + 124, 0xFFE9565C, false);
		} else if (saving) {
			graphics.text(font, Component.translatable("spotify-client.loading"), left + 12, top + 124, Ui.GRAY, false);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
