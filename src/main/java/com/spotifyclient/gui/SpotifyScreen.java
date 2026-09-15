package com.spotifyclient.gui;

import com.spotifyclient.SpotifyKeys;
import com.spotifyclient.config.SpotifyConfig;
import com.spotifyclient.gui.ContextMenu.Entry;
import com.spotifyclient.spotify.SavedCache;
import com.spotifyclient.spotify.SpotifyActions;
import com.spotifyclient.spotify.SpotifyAuth;
import com.spotifyclient.spotify.SpotifyLibrary;
import com.spotifyclient.spotify.SpotifyLibrary.Page;
import com.spotifyclient.spotify.SpotifyLibrary.SearchType;
import com.spotifyclient.spotify.SpotifyModels.Collection;
import com.spotifyclient.spotify.SpotifyModels.CollectionKind;
import com.spotifyclient.spotify.SpotifyModels.Device;
import com.spotifyclient.spotify.SpotifyModels.PlayerState;
import com.spotifyclient.spotify.SpotifyModels.Ref;
import com.spotifyclient.spotify.SpotifyModels.Repeat;
import com.spotifyclient.spotify.SpotifyModels.Track;
import com.spotifyclient.spotify.SpotifyService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class SpotifyScreen extends Screen {
	private static final int BACKGROUND = 0xF2121212;
	private static final int SIDEBAR = 0xFF000000;
	private static final int PLAYER = 0xFF181818;
	private static final int DIVIDER = 0xFF2A2A2A;
	private static final int HOVER = 0x22FFFFFF;
	private static final int SELECTED = 0xFF282828;
	private static final int ERROR = 0xFFE9565C;

	private static final int SIDEBAR_WIDTH = 104;
	private static final int PLAYER_HEIGHT = 46;
	private static final int ROW_HEIGHT = 22;
	private static final int SIDEBAR_ROW = 16;
	private static final int QUERY_DEBOUNCE_MS = 350;
	private static final int MAX_QUEUE = 100;

	private enum Section {
		SEARCH("⌕"), QUEUE("⋮"), RECENT("◷"), PLAYLISTS("≡"), LIKED("♥"), ALBUMS("◉"), DEVICES("▣");

		final String icon;

		Section(String icon) {
			this.icon = icon;
		}
	}

	private static Section lastSection = Section.PLAYLISTS;
	private static String lastQuery = "";
	private static SearchType lastSearchType = SearchType.TRACK;

	private record Hit(int x0, int y0, int x1, int y1, @Nullable Runnable action, @Nullable Supplier<List<Entry>> menu) {
	}

	private final class ListView {
		Component title;
		@Nullable Component subtitle;
		final @Nullable String imageUrl;
		final IntFunction<CompletableFuture<Page<Object>>> loader;
		@Nullable Collection context;
		final Section origin;
		final List<Object> rows = new ArrayList<>();
		int nextOffset;
		int generation;
		boolean loading;
		@Nullable Component error;
		double scroll;

		ListView(Component title, @Nullable Component subtitle, @Nullable String imageUrl,
				IntFunction<CompletableFuture<Page<Object>>> loader, @Nullable Collection context, Section origin) {
			this.title = title;
			this.subtitle = subtitle;
			this.imageUrl = imageUrl;
			this.loader = loader;
			this.context = context;
			this.origin = origin;
		}

		void loadMore() {
			if (loading || nextOffset < 0) {
				return;
			}
			loading = true;
			int requestGeneration = generation;
			loader.apply(nextOffset).whenComplete((page, throwable) -> minecraft.execute(() -> {
				if (requestGeneration != generation) {
					return;
				}
				loading = false;
				if (throwable != null) {
					error = SpotifyService.describe(throwable);
					nextOffset = -1;
					return;
				}
				rows.addAll(page.items());
				nextOffset = page.nextOffset();
				if (origin == Section.LIKED && context == null) {
					page.items().forEach(item -> {
						if (item instanceof Track track) {
							SavedCache.markSaved(track.uri(), true);
						}
					});
				}
			}));
		}

		void reload() {
			generation++;
			rows.clear();
			nextOffset = 0;
			loading = false;
			error = null;
			loadMore();
		}

		boolean isLikedSongs() {
			return origin == Section.LIKED && context == null;
		}
	}

	private final @Nullable Screen parent;
	private final Deque<ListView> stack = new ArrayDeque<>();
	private final List<Hit> hits = new ArrayList<>();
	private @Nullable Section section;
	private SearchType searchType = lastSearchType;
	private @Nullable EditBox searchBox;
	private @Nullable String pendingQuery;
	private long queryChangedAt;
	private @Nullable ContextMenu menu;
	private @Nullable List<Collection> editablePlaylists;
	private boolean loadingEditablePlaylists;

	private int left;
	private int top;
	private int right;
	private int bottom;
	private int contentLeft;
	private int playerTop;
	private int listTop;
	private int listBottom;

	private int seekX0, seekX1, seekY0, seekY1;
	private int volumeX0, volumeX1, volumeY0, volumeY1;
	private boolean draggingSeek;
	private boolean draggingVolume;
	private double dragFraction;

	public SpotifyScreen(@Nullable Screen parent) {
		super(Component.translatable("spotify-client.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		SpotifyService.setScreenOpen(true);

		int margin = width < 520 ? 4 : 12;
		left = margin;
		top = margin;
		right = width - margin;
		bottom = height - margin;
		contentLeft = left + SIDEBAR_WIDTH;
		playerTop = bottom - PLAYER_HEIGHT;
		menu = null;

		EditBox previous = searchBox;
		searchBox = new EditBox(font, contentLeft + 8, top + 8, right - contentLeft - 16, 16, previous,
			Component.translatable("spotify-client.search.hint"));
		searchBox.setMaxLength(100);
		if (previous == null) {
			searchBox.setValue(lastQuery);
		}
		searchBox.setResponder(query -> {
			pendingQuery = query;
			queryChangedAt = System.currentTimeMillis();
		});
		addRenderableWidget(searchBox);

		if (section == null) {
			selectSection(lastSection);
		}
	}

	@Override
	public void removed() {
		SpotifyService.setScreenOpen(false);
		if (searchBox != null) {
			lastQuery = searchBox.getValue();
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (pendingQuery != null && System.currentTimeMillis() - queryChangedAt >= QUERY_DEBOUNCE_MS) {
			String query = pendingQuery;
			pendingQuery = null;
			applySearch(query);
		}
	}

	private void selectSection(Section newSection) {
		section = newSection;
		lastSection = newSection;
		stack.clear();
		if (!SpotifyAuth.isLoggedIn()) {
			return;
		}
		switch (newSection) {
			case SEARCH -> {
				if (searchBox != null) {
					setFocused(searchBox);
					applySearch(searchBox.getValue());
				}
			}
			case QUEUE -> push(new ListView(text("section.queue"), text("queue.hint"), null, SpotifyLibrary::queue, null, newSection));
			case RECENT -> push(new ListView(text("section.recent"), null, null, SpotifyLibrary::recentlyPlayed, null, newSection));
			case PLAYLISTS -> push(new ListView(text("section.playlists"), null, null, SpotifyLibrary::playlists, null, newSection));
			case ALBUMS -> push(new ListView(text("section.albums"), null, null, SpotifyLibrary::albums, null, newSection));
			case LIKED -> push(new ListView(text("section.liked"), null, null, SpotifyLibrary::likedTracks, null, newSection));
			case DEVICES -> push(new ListView(text("section.devices"), text("devices.hint"), null,
				offset -> SpotifyLibrary.devices().thenApply(devices -> new Page<>(new ArrayList<Object>(devices), -1)), null, newSection));
		}
	}

	private void push(ListView view) {
		menu = null;
		stack.push(view);
		if (view.error == null) {
			view.loadMore();
		}
	}

	private void applySearch(String rawQuery) {
		String query = rawQuery.trim();
		lastQuery = rawQuery;
		if (section != Section.SEARCH) {
			return;
		}
		stack.clear();
		if (query.isEmpty()) {
			return;
		}
		SearchType type = searchType;
		push(new ListView(Component.translatable("spotify-client.search.results", query), null, null,
			offset -> SpotifyLibrary.search(query, type, offset), null, Section.SEARCH));
	}

	private void openCollection(Collection collection) {
		Component subtitle = Component.translatable("spotify-client.kind." + collection.kind().name().toLowerCase(Locale.ROOT))
			.append(collection.subtitle().isEmpty() ? "" : " • " + collection.subtitle());
		ListView view = new ListView(Component.literal(collection.name()), subtitle, collection.images().large(),
			offset -> SpotifyLibrary.collectionTracks(collection, offset), collection, section != null ? section : Section.PLAYLISTS);
		if (!collection.itemsReadable()) {
			view.error = text("error.playlist_not_readable");
			view.nextOffset = -1;
		}
		push(view);
	}

	private void goToAlbum(Track track) {
		if (track.album() != null) {
			openCollection(Collection.album(track.album(), track.subtitle(), track.images()));
		}
	}

	private void goToArtist(Ref artist) {
		openCollection(Collection.artist(artist));
	}

	private @Nullable ListView findRoot(Section target) {
		for (ListView view : stack) {
			if (view.origin == target && view.context == null) {
				return view;
			}
		}
		return null;
	}

	private void playTrack(ListView view, int index, Track track) {
		Collection context = view.context;
		if (context != null && context.kind() != CollectionKind.ARTIST) {
			SpotifyService.playContext(context.uri(), track.uri());
		} else if (view.isLikedSongs()) {
			playFrom(view, index);
		} else {
			SpotifyService.playTracks(List.of(track.uri()), 0);
		}
	}

	private void playFrom(ListView view, int fromIndex) {
		List<String> uris = new ArrayList<>();
		for (int i = fromIndex; i < view.rows.size() && uris.size() < MAX_QUEUE; i++) {
			if (view.rows.get(i) instanceof Track track) {
				uris.add(track.uri());
			}
		}
		if (!uris.isEmpty()) {
			SpotifyService.playTracks(uris, 0);
		}
	}

	private void createPlaylist(@Nullable Consumer<Collection> afterCreate) {
		minecraft.gui.setScreen(new PlaylistEditScreen(this, null, created -> {
			SpotifyService.info(Component.translatable("spotify-client.info.playlist_created", created.name()));
			editablePlaylists = null;
			ListView playlists = findRoot(Section.PLAYLISTS);
			if (playlists != null) {
				playlists.reload();
			}
			if (afterCreate != null) {
				afterCreate.accept(created);
			}
		}));
	}

	private void editPlaylist(Collection playlist, @Nullable ListView view) {
		minecraft.gui.setScreen(new PlaylistEditScreen(this, playlist, updated -> {
			SpotifyService.info(Component.translatable("spotify-client.info.playlist_updated"));
			editablePlaylists = null;
			if (view != null) {
				view.context = updated;
				view.title = Component.literal(updated.name());
			}
			ListView playlists = findRoot(Section.PLAYLISTS);
			if (playlists != null) {
				playlists.reload();
			}
		}));
	}

	private void deletePlaylist(Collection playlist) {
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			minecraft.gui.setScreen(this);
			if (!confirmed) {
				return;
			}
			SpotifyActions.removeFromLibrary(List.of(playlist.uri())).whenComplete((ignored, error) -> minecraft.execute(() -> {
				if (error != null) {
					SpotifyService.notify(SpotifyService.describe(error));
					return;
				}
				SpotifyService.info(Component.translatable("spotify-client.info.playlist_deleted", playlist.name()));
				editablePlaylists = null;
				while (stack.size() > 1 && stack.peek().context != null && playlist.uri().equals(stack.peek().context.uri())) {
					stack.pop();
				}
				ListView playlists = findRoot(Section.PLAYLISTS);
				if (playlists != null) {
					playlists.reload();
				}
			}));
		}, Component.translatable("spotify-client.playlist.delete_title"),
			Component.translatable("spotify-client.playlist.delete_message", playlist.name())));
	}

	private void addToPlaylist(Collection playlist, Track track) {
		SpotifyActions.addToPlaylist(playlist, List.of(track.uri())).whenComplete((ignored, error) -> minecraft.execute(() -> {
			if (error != null) {
				SpotifyService.notify(SpotifyService.describe(error));
				return;
			}
			SpotifyService.info(Component.translatable("spotify-client.info.added_to_playlist", playlist.name()));
			for (ListView view : stack) {
				if (view.context != null && view.context.uri().equals(playlist.uri())) {
					view.reload();
				}
			}
		}));
	}

	private void removeFromPlaylist(ListView view, Collection playlist, Track track) {
		view.rows.removeIf(row -> row instanceof Track t && t.uri().equals(track.uri()));
		SpotifyActions.removeFromPlaylist(playlist, track.uri()).whenComplete((ignored, error) -> minecraft.execute(() -> {
			if (error != null) {
				SpotifyService.notify(SpotifyService.describe(error));
				view.reload();
				return;
			}
			SpotifyService.info(Component.translatable("spotify-client.info.removed_from_playlist", playlist.name()));
		}));
	}

	private void moveInPlaylist(ListView view, Collection playlist, int from, int to) {
		if (to < 0 || to >= view.rows.size()) {
			return;
		}
		Object moved = view.rows.remove(from);
		view.rows.add(to, moved);
		SpotifyActions.movePlaylistItem(playlist, from, to).whenComplete((ignored, error) -> minecraft.execute(() -> {
			if (error != null) {
				SpotifyService.notify(SpotifyService.describe(error));
				view.reload();
			}
		}));
	}

	private void addToQueue(Track track) {
		SpotifyActions.addToQueue(track.uri()).whenComplete((ignored, error) -> minecraft.execute(() -> {
			if (error != null) {
				SpotifyService.notify(SpotifyService.describe(error));
			} else {
				SpotifyService.info(Component.translatable("spotify-client.info.added_to_queue", track.name()));
			}
		}));
	}

	private List<Collection> editablePlaylists() {
		if (editablePlaylists == null && !loadingEditablePlaylists) {
			loadingEditablePlaylists = true;
			SpotifyLibrary.editablePlaylists().whenComplete((playlists, error) -> minecraft.execute(() -> {
				loadingEditablePlaylists = false;
				if (error != null) {
					SpotifyService.notify(SpotifyService.describe(error));
					editablePlaylists = List.of();
				} else {
					editablePlaylists = playlists;
				}
			}));
		}
		return editablePlaylists != null ? editablePlaylists : List.of();
	}

	private List<Entry> trackMenu(Track track, @Nullable ListView view, int index) {
		List<Entry> entries = new ArrayList<>();
		if (view != null) {
			entries.add(Entry.of(text("menu.play"), () -> playTrack(view, index, track)));
		} else {
			entries.add(Entry.of(text("menu.play"), () -> SpotifyService.playTracks(List.of(track.uri()), 0)));
		}
		entries.add(Entry.of(text("menu.add_to_queue"), () -> addToQueue(track)));

		Boolean saved = SavedCache.get(track.uri());
		entries.add(Entry.of(text(Boolean.TRUE.equals(saved) ? "menu.unlike" : "menu.like"), () -> SavedCache.toggle(track.uri())));

		if (!track.isEpisode()) {
			entries.add(Entry.submenu(text("menu.add_to_playlist"), () -> {
				List<Entry> playlists = new ArrayList<>();
				playlists.add(Entry.of(text("menu.new_playlist"), () -> createPlaylist(created -> addToPlaylist(created, track))));
				List<Collection> editable = editablePlaylists();
				if (editablePlaylists == null) {
					playlists.add(Entry.disabled(text("loading")));
				}
				for (Collection playlist : editable) {
					playlists.add(Entry.of(Component.literal(Ui.ellipsize(font, playlist.name(), 160)), () -> addToPlaylist(playlist, track)));
				}
				return playlists;
			}));
		}

		Collection context = view != null ? view.context : null;
		if (context != null && context.canEditItems()) {
			if (index > 0) {
				entries.add(Entry.of(text("menu.move_up"), () -> moveInPlaylist(view, context, index, index - 1)));
			}
			if (index < view.rows.size() - 1) {
				entries.add(Entry.of(text("menu.move_down"), () -> moveInPlaylist(view, context, index, index + 1)));
			}
			entries.add(Entry.danger(text("menu.remove_from_playlist"), () -> removeFromPlaylist(view, context, track)));
		}

		if (track.album() != null) {
			entries.add(Entry.of(text("menu.go_to_album"), () -> goToAlbum(track)));
		}
		if (track.artists().size() == 1) {
			entries.add(Entry.of(text("menu.go_to_artist"), () -> goToArtist(track.artists().getFirst())));
		} else if (track.artists().size() > 1) {
			entries.add(Entry.submenu(text("menu.go_to_artist"), () -> track.artists().stream()
				.map(artist -> Entry.of(Component.literal(artist.name()), () -> goToArtist(artist))).toList()));
		}
		return entries;
	}

	private List<Entry> collectionMenu(Collection collection, @Nullable ListView view) {
		List<Entry> entries = new ArrayList<>();
		if (view == null || view.context != collection) {
			entries.add(Entry.of(text("menu.open"), () -> openCollection(collection)));
		}
		entries.add(Entry.of(text("menu.play"), () -> SpotifyService.playContext(collection.uri(), null)));
		if (collection.isOwnedByMe()) {
			entries.add(Entry.of(text("menu.edit_playlist"), () -> editPlaylist(collection, view != null && view.context == collection ? view : null)));
			entries.add(Entry.danger(text("menu.delete_playlist"), () -> deletePlaylist(collection)));
		} else {
			Boolean saved = SavedCache.get(collection.uri());
			String key = switch (collection.kind()) {
				case ALBUM -> Boolean.TRUE.equals(saved) ? "menu.remove_album" : "menu.save_album";
				case PLAYLIST, ARTIST -> Boolean.TRUE.equals(saved) ? "menu.unfollow" : "menu.follow";
			};
			entries.add(Entry.of(text(key), () -> SavedCache.toggle(collection.uri())));
		}
		return entries;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		hits.clear();
		boolean loggedIn = SpotifyAuth.isLoggedIn();
		if (loggedIn && stack.isEmpty() && section != null && section != Section.SEARCH) {
			selectSection(section);
		}
		if (searchBox != null) {
			searchBox.visible = loggedIn && section == Section.SEARCH;
		}
		int hoverX = menu != null ? -1 : mouseX;
		int hoverY = menu != null ? -1 : mouseY;

		graphics.fill(left, top, right, bottom, BACKGROUND);
		extractSidebar(graphics, hoverX, hoverY);
		if (loggedIn) {
			extractContent(graphics, hoverX, hoverY);
		} else {
			extractLoggedOut(graphics, hoverX, hoverY);
		}
		extractPlayer(graphics, hoverX, hoverY);
		extractMessage(graphics);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (searchBox != null && searchBox.visible && searchBox.getValue().isEmpty()) {
			graphics.text(font, text("search.hint"), searchBox.getX() + 4, searchBox.getY() + 4, Ui.DARK_GRAY, false);
		}

		if (menu != null) {
			graphics.nextStratum();
			menu.extract(graphics, font, mouseX, mouseY, width, height);
		}
		SavedCache.flush();
	}

	private void extractSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x1 = left + SIDEBAR_WIDTH;
		graphics.fill(left, top, x1, playerTop, SIDEBAR);
		graphics.text(font, "♪ Spotify", left + 8, top + 8, Ui.SPOTIFY_GREEN, false);

		int y = top + 24;
		for (Section entry : Section.values()) {
			boolean selected = entry == section;
			boolean hovered = Ui.inside(mouseX, mouseY, left + 4, y, x1 - 4, y + SIDEBAR_ROW);
			if (selected) {
				graphics.fill(left + 4, y, x1 - 4, y + SIDEBAR_ROW, SELECTED);
			} else if (hovered) {
				graphics.fill(left + 4, y, x1 - 4, y + SIDEBAR_ROW, HOVER);
			}
			graphics.text(font, entry.icon, left + 9, y + 4, selected ? Ui.SPOTIFY_GREEN : Ui.GRAY, false);
			graphics.text(font, Ui.ellipsize(font, text("section." + entry.name().toLowerCase(Locale.ROOT)).getString(), SIDEBAR_WIDTH - 32),
				left + 22, y + 4, selected || hovered ? Ui.WHITE : Ui.GRAY, false);
			hits.add(new Hit(left + 4, y, x1 - 4, y + SIDEBAR_ROW, () -> selectSection(entry), null));
			y += SIDEBAR_ROW + 1;
		}

		int accountY = playerTop - 28;
		if (accountY - 6 <= y) {
			return;
		}
		graphics.fill(left + 8, accountY - 4, x1 - 8, accountY - 3, DIVIDER);
		String name = SpotifyAuth.displayName();
		String account = SpotifyAuth.isLoggedIn() && name != null ? "● " + name : "○ " + text("account.logged_out").getString();
		graphics.text(font, Ui.ellipsize(font, account, SIDEBAR_WIDTH - 16), left + 8, accountY,
			SpotifyAuth.isLoggedIn() ? Ui.SPOTIFY_GREEN : Ui.DARK_GRAY, false);
		boolean settingsHovered = Ui.inside(mouseX, mouseY, left + 4, accountY + 10, x1 - 4, accountY + 22);
		graphics.text(font, "⚙ " + text("settings").getString(), left + 8, accountY + 12, settingsHovered ? Ui.WHITE : Ui.GRAY, false);
		hits.add(new Hit(left + 4, accountY + 10, x1 - 4, accountY + 22, this::openConfig, null));
	}

	private void openConfig() {
		minecraft.gui.setScreen(new SettingsScreen(this));
	}

	private void extractLoggedOut(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int centerX = (contentLeft + right) / 2;
		int y = top + (playerTop - top) / 2 - 24;
		centered(graphics, text("logged_out.title"), centerX, y, Ui.WHITE);
		centered(graphics, text("logged_out.hint"), centerX, y + 14, Ui.GRAY);
		button(graphics, text("logged_out.open_config"), centerX, y + 32, mouseX, mouseY, this::openConfig);
	}

	private void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ListView view = stack.peek();

		if (section == Section.SEARCH && (view == null || view.context == null)) {
			extractSearchHeader(graphics, mouseX, mouseY);
			listTop = top + 48;
		} else if (view != null) {
			extractHeader(graphics, view, mouseX, mouseY);
			listTop = top + 42;
		} else {
			listTop = top + 42;
		}

		graphics.fill(contentLeft, listTop - 1, right, listTop, DIVIDER);
		listBottom = playerTop;

		if (view == null) {
			if (section == Section.SEARCH) {
				centered(graphics, text("search.empty"), (contentLeft + right) / 2, listTop + 20, Ui.GRAY);
			}
			return;
		}
		extractRows(graphics, view, mouseX, mouseY);
	}

	private void extractSearchHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int chipX = contentLeft + 8;
		int chipY = top + 30;
		for (SearchType type : SearchType.values()) {
			Component label = text("search.type." + type.name().toLowerCase(Locale.ROOT));
			int chipWidth = font.width(label) + 12;
			boolean selected = type == searchType;
			boolean hovered = Ui.inside(mouseX, mouseY, chipX, chipY, chipX + chipWidth, chipY + 13);
			graphics.fill(chipX, chipY, chipX + chipWidth, chipY + 13, selected ? Ui.WHITE : hovered ? 0xFF3E3E3E : 0xFF2A2A2A);
			graphics.text(font, label, chipX + 6, chipY + 3, selected ? 0xFF000000 : Ui.WHITE, false);
			hits.add(new Hit(chipX, chipY, chipX + chipWidth, chipY + 13, () -> {
				searchType = type;
				lastSearchType = type;
				if (searchBox != null) {
					applySearch(searchBox.getValue());
				}
			}, null));
			chipX += chipWidth + 4;
		}
	}

	private void extractHeader(GuiGraphicsExtractor graphics, ListView view, int mouseX, int mouseY) {
		int x = contentLeft + 8;
		if (stack.size() > 1) {
			boolean hovered = Ui.inside(mouseX, mouseY, x - 2, top + 12, x + 12, top + 28);
			graphics.text(font, "◀", x + 1, top + 16, hovered ? Ui.WHITE : Ui.GRAY, false);
			hits.add(new Hit(x - 2, top + 12, x + 12, top + 28, () -> {
				stack.pop();
				menu = null;
			}, null));
			x += 16;
		}
		if (view.imageUrl != null) {
			Ui.cover(graphics, font, view.imageUrl, x, top + 6, 30);
			x += 36;
		}

		int actionRight = right - 10;
		Collection context = view.context;
		if (context != null) {
			if (context.isOwnedByMe()) {
				actionRight = headerIcon(graphics, "✕", text("menu.delete_playlist"), actionRight, mouseX, mouseY, ERROR, () -> deletePlaylist(context));
				actionRight = headerIcon(graphics, "✎", text("menu.edit_playlist"), actionRight, mouseX, mouseY, Ui.GRAY, () -> editPlaylist(context, view));
			} else {
				Boolean saved = SavedCache.get(context.uri());
				actionRight = headerIcon(graphics, "♥", null, actionRight, mouseX, mouseY,
					Boolean.TRUE.equals(saved) ? Ui.SPOTIFY_GREEN : Ui.GRAY, () -> SavedCache.toggle(context.uri()));
			}
			actionRight = pill(graphics, "▶ " + text("play").getString(), actionRight, mouseX, mouseY,
				() -> SpotifyService.playContext(context.uri(), null));
		} else if (view.origin == Section.PLAYLISTS) {
			actionRight = pill(graphics, "+ " + text("menu.new_playlist").getString(), actionRight, mouseX, mouseY, () -> createPlaylist(null));
		} else if (view.isLikedSongs()) {
			actionRight = pill(graphics, "▶ " + text("play").getString(), actionRight, mouseX, mouseY, () -> playFrom(view, 0));
		} else if (view.origin == Section.QUEUE || view.origin == Section.RECENT || view.origin == Section.DEVICES) {
			actionRight = headerIcon(graphics, "⟳", text("refresh"), actionRight, mouseX, mouseY, Ui.GRAY, view::reload);
		}

		int titleWidth = actionRight - 6 - x;
		graphics.text(font, Ui.ellipsize(font, view.title.getString(), titleWidth), x, view.subtitle != null ? top + 10 : top + 16, Ui.WHITE, false);
		if (view.subtitle != null) {
			graphics.text(font, Ui.ellipsize(font, view.subtitle.getString(), titleWidth), x, top + 22, Ui.GRAY, false);
		}
	}

	private void extractRows(GuiGraphicsExtractor graphics, ListView view, int mouseX, int mouseY) {
		int viewHeight = listBottom - listTop;
		boolean footer = view.loading || view.nextOffset >= 0;
		int contentHeight = view.rows.size() * ROW_HEIGHT + (footer ? ROW_HEIGHT : 0);
		int maxScroll = Math.max(0, contentHeight - viewHeight);
		view.scroll = Math.clamp(view.scroll, 0, maxScroll);

		PlayerState state = SpotifyService.state();
		String playingUri = state.track() != null ? state.track().uri() : null;

		graphics.enableScissor(contentLeft, listTop, right, listBottom);
		int rowLeft = contentLeft + 6;
		int rowRight = right - 10;
		for (int i = 0; i < view.rows.size(); i++) {
			int y = listTop + i * ROW_HEIGHT - (int) view.scroll;
			if (y + ROW_HEIGHT <= listTop || y >= listBottom) {
				continue;
			}
			int hitTop = Math.max(y, listTop);
			int hitBottom = Math.min(y + ROW_HEIGHT, listBottom);
			boolean hovered = Ui.inside(mouseX, mouseY, rowLeft, hitTop, rowRight, hitBottom);
			if (hovered) {
				graphics.fill(rowLeft, y, rowRight, y + ROW_HEIGHT, HOVER);
			}
			Object row = view.rows.get(i);
			int index = i;
			switch (row) {
				case Track track -> {
					hits.add(new Hit(rowLeft, hitTop, rowRight, hitBottom, () -> playTrack(view, index, track), () -> trackMenu(track, view, index)));
					extractTrackRow(graphics, track, rowLeft, y, rowRight, hitTop, hitBottom, track.uri().equals(playingUri), hovered, mouseX, mouseY);
				}
				case Collection collection -> {
					hits.add(new Hit(rowLeft, hitTop, rowRight, hitBottom, () -> openCollection(collection), () -> collectionMenu(collection, view)));
					extractCollectionRow(graphics, collection, rowLeft, y, rowRight, collection.uri().equals(state.contextUri()));
				}
				case Device device -> {
					hits.add(new Hit(rowLeft, hitTop, rowRight, hitBottom, () -> SpotifyService.transferTo(device), null));
					extractDeviceRow(graphics, device, rowLeft, y, rowRight);
				}
				default -> {
				}
			}
		}

		int footerY = listTop + view.rows.size() * ROW_HEIGHT - (int) view.scroll;
		if (view.loading) {
			centered(graphics, text("loading"), (contentLeft + right) / 2, footerY + 7, Ui.GRAY);
		} else if (view.nextOffset >= 0 && footerY < listBottom) {
			view.loadMore();
		}
		if (view.error != null) {
			graphics.textWithWordWrap(font, view.error, rowLeft + 4, footerY + 8, rowRight - rowLeft - 8, ERROR, false);
		} else if (!view.loading && view.rows.isEmpty()) {
			centered(graphics, text("empty"), (contentLeft + right) / 2, listTop + 20, Ui.GRAY);
		}
		graphics.disableScissor();

		if (maxScroll > 0) {
			int trackX = right - 5;
			int thumbHeight = Math.max(12, viewHeight * viewHeight / contentHeight);
			int thumbY = listTop + (int) ((viewHeight - thumbHeight) * (view.scroll / maxScroll));
			graphics.fill(trackX, listTop, trackX + 2, listBottom, 0xFF202020);
			graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFF7A7A7A);
		}
	}

	private void extractTrackRow(GuiGraphicsExtractor graphics, Track track, int x0, int y, int x1, int hitTop, int hitBottom,
			boolean playing, boolean hovered, int mouseX, int mouseY) {
		Ui.cover(graphics, font, track.images().small(), x0 + 2, y + 2, 18);
		String duration = Ui.time(track.durationMs());
		int durationX = x1 - 4 - font.width(duration);

		int heartX = durationX - 14;
		Boolean saved = SavedCache.get(track.uri());
		boolean liked = Boolean.TRUE.equals(saved);
		if (liked || hovered) {
			boolean heartHovered = Ui.inside(mouseX, mouseY, heartX - 3, hitTop, heartX + 9, hitBottom);
			graphics.text(font, "♥", heartX, y + 7, liked ? Ui.SPOTIFY_GREEN : heartHovered ? Ui.WHITE : Ui.GRAY, false);
			hits.add(new Hit(heartX - 3, hitTop, heartX + 9, hitBottom, () -> SavedCache.toggle(track.uri()), null));
		}
		int moreX = heartX - 14;
		if (hovered) {
			boolean moreHovered = Ui.inside(mouseX, mouseY, moreX - 3, hitTop, moreX + 9, hitBottom);
			graphics.text(font, "...", moreX, y + 7, moreHovered ? Ui.WHITE : Ui.GRAY, false);
			hits.add(new Hit(moreX - 3, hitTop, moreX + 9, hitBottom, null, null));
		}

		int textX = x0 + 26;
		int textWidth = moreX - textX - 6;
		graphics.text(font, Ui.ellipsize(font, (playing ? "♪ " : "") + track.name(), textWidth), textX, y + 3,
			playing ? Ui.SPOTIFY_GREEN : Ui.WHITE, false);
		graphics.text(font, Ui.ellipsize(font, track.subtitle(), textWidth), textX, y + 12, Ui.GRAY, false);
		graphics.text(font, duration, durationX, y + 7, Ui.GRAY, false);
	}

	private void extractCollectionRow(GuiGraphicsExtractor graphics, Collection collection, int x0, int y, int x1, boolean playing) {
		Ui.cover(graphics, font, collection.images().small(), x0 + 2, y + 2, 18);
		int textX = x0 + 26;
		int textWidth = x1 - textX - 14;
		StringBuilder subtitle = new StringBuilder(text("kind." + collection.kind().name().toLowerCase(Locale.ROOT)).getString());
		if (!collection.subtitle().isEmpty()) {
			subtitle.append(" • ").append(collection.subtitle());
		}
		if (collection.total() > 0) {
			subtitle.append(" • ").append(Component.translatable("spotify-client.track_count", collection.total()).getString());
		}
		graphics.text(font, Ui.ellipsize(font, collection.name(), textWidth), textX, y + 3, playing ? Ui.SPOTIFY_GREEN : Ui.WHITE, false);
		graphics.text(font, Ui.ellipsize(font, subtitle.toString(), textWidth), textX, y + 12, Ui.GRAY, false);
		graphics.text(font, "›", x1 - 8, y + 7, Ui.DARK_GRAY, false);
	}

	private void extractDeviceRow(GuiGraphicsExtractor graphics, Device device, int x0, int y, int x1) {
		String icon = switch (device.type().toLowerCase(Locale.ROOT)) {
			case "smartphone" -> "☎";
			case "speaker", "castaudio" -> "♫";
			case "tv", "castvideo" -> "▭";
			default -> "▣";
		};
		graphics.text(font, icon, x0 + 8, y + 7, device.active() ? Ui.SPOTIFY_GREEN : Ui.GRAY, false);
		int textX = x0 + 26;
		graphics.text(font, Ui.ellipsize(font, device.name(), x1 - textX - 60), textX, y + 3, device.active() ? Ui.SPOTIFY_GREEN : Ui.WHITE, false);
		graphics.text(font, device.type(), textX, y + 12, Ui.GRAY, false);
		if (device.active()) {
			Component active = text("devices.active");
			graphics.text(font, active, x1 - 4 - font.width(active), y + 7, Ui.SPOTIFY_GREEN, false);
		}
	}

	private void extractPlayer(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(left, playerTop, right, bottom, PLAYER);
		graphics.fill(left, playerTop, right, playerTop + 1, DIVIDER);

		PlayerState state = SpotifyService.state();
		Track track = state.track();
		int accent = 0xFF000000 | SpotifyConfig.get().accentColor;
		int centerX = (left + right) / 2;
		int progressWidth = Math.min(300, (right - left) * 2 / 5);
		int leftWidth = centerX - 60 - left;

		if (track != null) {
			Ui.cover(graphics, font, track.images().large(), left + 6, playerTop + 6, 34);
			int textX = left + 46;
			int textWidth = leftWidth - 46 - 14;
			String title = Ui.ellipsize(font, track.name(), textWidth);
			graphics.text(font, title, textX, playerTop + 12, Ui.WHITE, false);
			graphics.text(font, Ui.ellipsize(font, track.subtitle(), textWidth), textX, playerTop + 24, Ui.GRAY, false);
			hits.add(new Hit(left, playerTop + 1, textX + textWidth, bottom, track.album() != null ? () -> goToAlbum(track) : null,
				() -> trackMenu(track, null, 0)));

			boolean liked = Boolean.TRUE.equals(SavedCache.get(track.uri()));
			int heartX = textX + font.width(title) + 6;
			boolean heartHovered = Ui.inside(mouseX, mouseY, heartX - 3, playerTop + 9, heartX + 9, playerTop + 22);
			graphics.text(font, "♥", heartX, playerTop + 12, liked ? Ui.SPOTIFY_GREEN : heartHovered ? Ui.WHITE : Ui.GRAY, false);
			hits.add(new Hit(heartX - 3, playerTop + 9, heartX + 9, playerTop + 22, () -> SavedCache.toggle(track.uri()), null));
		} else {
			graphics.text(font, Ui.ellipsize(font, text("player.nothing").getString(), leftWidth - 8), left + 8, playerTop + 12, Ui.GRAY, false);
			graphics.text(font, Ui.ellipsize(font, text("player.nothing_hint").getString(), leftWidth - 8), left + 8, playerTop + 24, Ui.DARK_GRAY, false);
		}

		int controlsY = playerTop + 6;
		iconButton(graphics, "⇄", centerX - 48, controlsY, state.shuffle() ? accent : Ui.GRAY, mouseX, mouseY,
			() -> SpotifyService.setShuffle(!state.shuffle()));
		if (state.shuffle()) {
			graphics.fill(centerX - 49, controlsY + 14, centerX - 47, controlsY + 16, accent);
		}
		iconButton(graphics, "⏮", centerX - 24, controlsY, Ui.WHITE, mouseX, mouseY, SpotifyService::previous);
		boolean playHovered = Ui.inside(mouseX, mouseY, centerX - 8, controlsY - 1, centerX + 8, controlsY + 15);
		graphics.fill(centerX - 8, controlsY - 1, centerX + 8, controlsY + 15, playHovered ? 0xFFFFFFFF : 0xFFE0E0E0);
		String playGlyph = state.playing() ? "⏸" : "▶";
		graphics.text(font, playGlyph, centerX - font.width(playGlyph) / 2, controlsY + 3, 0xFF000000, false);
		hits.add(new Hit(centerX - 8, controlsY - 1, centerX + 8, controlsY + 15, SpotifyService::togglePlayPause, null));
		iconButton(graphics, "⏭", centerX + 24, controlsY, Ui.WHITE, mouseX, mouseY, SpotifyService::next);
		extractRepeatButton(graphics, state.repeat(), centerX + 48, controlsY, accent, mouseX, mouseY);

		seekX0 = centerX - progressWidth / 2;
		seekX1 = centerX + progressWidth / 2;
		seekY0 = playerTop + 30;
		seekY1 = playerTop + 40;
		long duration = track != null ? track.durationMs() : 0;
		double fraction = draggingSeek ? dragFraction : duration > 0 ? (double) state.currentProgressMs() / duration : 0;
		boolean seekHovered = track != null && (draggingSeek || Ui.inside(mouseX, mouseY, seekX0, seekY0, seekX1, seekY1));
		Ui.progressBar(graphics, seekX0, seekY0 + 4, seekX1, seekY0 + 7, fraction, seekHovered ? accent : Ui.WHITE);
		String elapsed = Ui.time((long) (fraction * duration));
		String total = Ui.time(duration);
		graphics.text(font, elapsed, seekX0 - 6 - font.width(elapsed), seekY0 + 2, Ui.GRAY, false);
		graphics.text(font, total, seekX1 + 6, seekY0 + 2, Ui.GRAY, false);

		Device device = state.device();
		String deviceLabel = "▣ " + (device != null ? device.name() : text("player.pick_device").getString());
		int deviceMaxWidth = right - (seekX1 + 40) - 12;
		String shownDevice = Ui.ellipsize(font, deviceLabel, deviceMaxWidth);
		int deviceX = right - 10 - font.width(shownDevice);
		boolean deviceHovered = Ui.inside(mouseX, mouseY, deviceX - 2, playerTop + 8, right - 8, playerTop + 20);
		graphics.text(font, shownDevice, deviceX, playerTop + 10, deviceHovered ? Ui.WHITE : device != null ? accent : Ui.GRAY, false);
		hits.add(new Hit(deviceX - 2, playerTop + 8, right - 8, playerTop + 20, () -> selectSection(Section.DEVICES), null));

		boolean volumeEnabled = device != null && device.supportsVolume();
		volumeX1 = right - 10;
		volumeX0 = volumeX1 - 56;
		volumeY0 = playerTop + 26;
		volumeY1 = playerTop + 36;
		double volume = draggingVolume ? dragFraction : device != null ? device.volume() / 100.0 : 0;
		graphics.text(font, "♪", volumeX0 - 10, volumeY0 + 1, volumeEnabled ? Ui.GRAY : Ui.DARK_GRAY, false);
		boolean volumeHovered = volumeEnabled && (draggingVolume || Ui.inside(mouseX, mouseY, volumeX0, volumeY0, volumeX1, volumeY1));
		Ui.progressBar(graphics, volumeX0, volumeY0 + 4, volumeX1, volumeY0 + 7, volume, volumeHovered ? accent : volumeEnabled ? Ui.WHITE : Ui.DARK_GRAY);
	}

	private void extractMessage(GuiGraphicsExtractor graphics) {
		Component message = SpotifyService.recentMessage();
		if (message == null) {
			return;
		}
		int textWidth = font.width(message);
		int centerX = (contentLeft + right) / 2;
		int y = playerTop - 18;
		graphics.fill(centerX - textWidth / 2 - 6, y - 3, centerX + textWidth / 2 + 6, y + 11, 0xF0282828);
		graphics.text(font, message, centerX - textWidth / 2, y, Ui.WHITE, false);
	}

	private void iconButton(GuiGraphicsExtractor graphics, String glyph, int centerX, int y, int color, int mouseX, int mouseY, Runnable action) {
		boolean hovered = Ui.inside(mouseX, mouseY, centerX - 8, y - 1, centerX + 8, y + 15);
		if (hovered) {
			graphics.fill(centerX - 8, y - 1, centerX + 8, y + 15, HOVER);
		}
		graphics.text(font, glyph, centerX - font.width(glyph) / 2, y + 3, hovered && color == Ui.GRAY ? Ui.WHITE : color, false);
		hits.add(new Hit(centerX - 8, y - 1, centerX + 8, y + 15, action, null));
	}

	private void extractRepeatButton(GuiGraphicsExtractor graphics, Repeat repeat, int centerX, int y, int accent, int mouseX, int mouseY) {
		boolean hovered = Ui.inside(mouseX, mouseY, centerX - 8, y - 1, centerX + 8, y + 15);
		if (hovered) {
			graphics.fill(centerX - 8, y - 1, centerX + 8, y + 15, HOVER);
		}
		int color = repeat != Repeat.OFF ? accent : hovered ? Ui.WHITE : Ui.GRAY;
		int x0 = centerX - 5;
		int y0 = y + 3;
		graphics.fill(x0, y0, x0 + 10, y0 + 1, color);
		graphics.fill(x0, y0 + 7, x0 + 10, y0 + 8, color);
		graphics.fill(x0, y0, x0 + 1, y0 + 8, color);
		graphics.fill(x0 + 9, y0, x0 + 10, y0 + 8, color);
		graphics.fill(x0 + 5, y0 - 1, x0 + 6, y0 + 2, color);
		graphics.fill(x0 + 4, y0 + 6, x0 + 5, y0 + 9, color);
		if (repeat == Repeat.TRACK) {
			graphics.fill(x0 + 3, y0 + 2, x0 + 7, y0 + 6, 0xFF181818);
			graphics.fill(x0 + 5, y0 + 2, x0 + 6, y0 + 6, color);
		}
		if (repeat != Repeat.OFF) {
			graphics.fill(centerX - 1, y + 14, centerX + 1, y + 16, accent);
		}
		hits.add(new Hit(centerX - 8, y - 1, centerX + 8, y + 15, SpotifyService::cycleRepeat, null));
	}

	private int headerIcon(GuiGraphicsExtractor graphics, String glyph, @Nullable Component label, int rightEdge, int mouseX, int mouseY,
			int color, Runnable action) {
		int x0 = rightEdge - 16;
		boolean hovered = Ui.inside(mouseX, mouseY, x0, top + 12, rightEdge, top + 28);
		graphics.fill(x0, top + 12, rightEdge, top + 28, hovered ? 0xFF3E3E3E : 0xFF2A2A2A);
		graphics.text(font, glyph, x0 + (16 - font.width(glyph)) / 2, top + 16, hovered && color == Ui.GRAY ? Ui.WHITE : color, false);
		hits.add(new Hit(x0, top + 12, rightEdge, top + 28, action, null));
		return x0 - 4;
	}

	private int pill(GuiGraphicsExtractor graphics, String label, int rightEdge, int mouseX, int mouseY, Runnable action) {
		int x0 = rightEdge - font.width(label) - 14;
		boolean hovered = Ui.inside(mouseX, mouseY, x0, top + 12, rightEdge, top + 28);
		graphics.fill(x0, top + 12, rightEdge, top + 28, hovered ? 0xFF1ED760 : Ui.SPOTIFY_GREEN);
		graphics.text(font, label, x0 + 7, top + 16, 0xFF000000, false);
		hits.add(new Hit(x0, top + 12, rightEdge, top + 28, action, null));
		return x0 - 4;
	}

	private void button(GuiGraphicsExtractor graphics, Component label, int centerX, int y, int mouseX, int mouseY, Runnable action) {
		int halfWidth = font.width(label) / 2 + 10;
		boolean hovered = Ui.inside(mouseX, mouseY, centerX - halfWidth, y, centerX + halfWidth, y + 18);
		graphics.fill(centerX - halfWidth, y, centerX + halfWidth, y + 18, hovered ? 0xFF1ED760 : Ui.SPOTIFY_GREEN);
		graphics.text(font, label, centerX - font.width(label) / 2, y + 5, 0xFF000000, false);
		hits.add(new Hit(centerX - halfWidth, y, centerX + halfWidth, y + 18, action, null));
	}

	private void centered(GuiGraphicsExtractor graphics, Component text, int centerX, int y, int color) {
		graphics.text(font, text, centerX - font.width(text) / 2, y, color, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		int button = event.input();

		if (menu != null) {
			ContextMenu open = menu;
			if (open.contains(mouseX, mouseY)) {
				if (button == InputConstants.MOUSE_BUTTON_LEFT && open.click(mouseX, mouseY)) {
					clickSound();
					menu = null;
				}
			} else {
				menu = null;
			}
			return true;
		}

		if (searchBox != null && searchBox.visible && searchBox.isMouseOver(mouseX, mouseY)) {
			return super.mouseClicked(event, doubleClick);
		}
		if (searchBox != null) {
			searchBox.setFocused(false);
		}

		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			PlayerState state = SpotifyService.state();
			if (state.track() != null && Ui.inside(mouseX, mouseY, seekX0, seekY0, seekX1, seekY1)) {
				draggingSeek = true;
				dragFraction = fraction(mouseX, seekX0, seekX1);
				return true;
			}
			if (state.device() != null && state.device().supportsVolume() && Ui.inside(mouseX, mouseY, volumeX0, volumeY0, volumeX1, volumeY1)) {
				draggingVolume = true;
				dragFraction = fraction(mouseX, volumeX0, volumeX1);
				return true;
			}
		}

		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (!Ui.inside(mouseX, mouseY, hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
				continue;
			}
			Supplier<List<Entry>> hitMenu = hit.menu() != null ? hit.menu() : menuFor(i);
			boolean openMenu = button == InputConstants.MOUSE_BUTTON_RIGHT || (hit.action() == null && hitMenu != null);
			if (openMenu && hitMenu != null) {
				menu = new ContextMenu(hitMenu, (int) mouseX, (int) mouseY);
				clickSound();
				return true;
			}
			if (button == InputConstants.MOUSE_BUTTON_LEFT && hit.action() != null) {
				clickSound();
				hit.action().run();
				return true;
			}
			if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				continue;
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private @Nullable Supplier<List<Entry>> menuFor(int hitIndex) {
		Hit hit = hits.get(hitIndex);
		for (int i = hitIndex - 1; i >= 0; i--) {
			Hit below = hits.get(i);
			if (below.menu() != null && hit.x0() >= below.x0() && hit.x1() <= below.x1() && hit.y0() >= below.y0() && hit.y1() <= below.y1()) {
				return below.menu();
			}
		}
		return null;
	}

	private void clickSound() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingSeek) {
			dragFraction = fraction(event.x(), seekX0, seekX1);
			return true;
		}
		if (draggingVolume) {
			dragFraction = fraction(event.x(), volumeX0, volumeX1);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingSeek) {
			draggingSeek = false;
			Track track = SpotifyService.state().track();
			if (track != null) {
				SpotifyService.seek((long) (dragFraction * track.durationMs()));
			}
			return true;
		}
		if (draggingVolume) {
			draggingVolume = false;
			SpotifyService.setVolume((int) Math.round(dragFraction * 100));
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (menu != null) {
			menu = null;
		}
		Device device = SpotifyService.state().device();
		if (device != null && device.supportsVolume() && Ui.inside(mouseX, mouseY, volumeX0 - 12, volumeY0, volumeX1, volumeY1)) {
			SpotifyService.setVolume(device.volume() + (int) Math.signum(scrollY) * 5);
			return true;
		}
		ListView view = stack.peek();
		if (view != null && Ui.inside(mouseX, mouseY, contentLeft, listTop, right, listBottom)) {
			view.scroll -= scrollY * ROW_HEIGHT * 1.5;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (menu != null && event.isEscape()) {
			menu = null;
			return true;
		}
		boolean typing = searchBox != null && searchBox.visible && searchBox.isFocused();
		if (!typing) {
			if (event.key() == InputConstants.KEY_SPACE) {
				SpotifyService.togglePlayPause();
				return true;
			}
			if (SpotifyKeys.OPEN.matches(event)) {
				onClose();
				return true;
			}
			if (SpotifyKeys.PLAY_PAUSE.matches(event)) {
				SpotifyService.togglePlayPause();
				return true;
			}
			if (SpotifyKeys.NEXT.matches(event)) {
				SpotifyService.next();
				return true;
			}
			if (SpotifyKeys.PREVIOUS.matches(event)) {
				SpotifyService.previous();
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE && stack.size() > 1) {
				stack.pop();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private static double fraction(double mouseX, int x0, int x1) {
		return Math.clamp((mouseX - x0) / (double) (x1 - x0), 0, 1);
	}

	private static Component text(String key) {
		return Component.translatable("spotify-client." + key);
	}
}
