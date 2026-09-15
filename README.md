# Spotify Client

A client-side Fabric mod that brings Spotify into Minecraft: an always-on "now playing" widget, playback keys, and an in-game client to search, browse your library, manage playlists and control playback.

> **Not affiliated with Spotify or Mojang.** This mod is a remote control for your own Spotify account through the official Spotify Web API. Audio plays from your Spotify app (computer, phone, speaker…), not from Minecraft.

## Features

**Now playing widget**
- Cover art, title, artist, progress bar and playing device
- Position (any screen corner + offsets), size, width, background opacity and accent color
- Optional: hide while paused, hide with the F3 debug screen (always hidden with F1)
- Live preview while adjusting it in the settings

**Playback keys**
- Play / pause, next, previous, open the client, show / hide the widget
- Work in game and inside the client; keyboard media keys can be bound

**In-game client**
- Search tracks, albums, playlists and artists
- Your playlists, Liked Songs, saved albums, queue and recently played tracks
- Album and artist pages
- Like / unlike tracks, save albums, follow playlists and artists
- Create, edit and delete playlists; add, remove and reorder tracks
- Add to queue, go to album, go to artist (right-click any row)
- Transport controls, shuffle, repeat, seek bar, volume and device switching

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Mod loader | Fabric Loader 0.19.5 or newer |
| Java | 25 |
| Required mod | [Fabric API](https://modrinth.com/mod/fabric-api) |
| Optional mod | [Mod Menu](https://modrinth.com/mod/modmenu) (adds a settings button) |
| Spotify | **Spotify Premium**, and a free Spotify Developer app (see below) |

## Installation

1. Install Fabric Loader for Minecraft 26.2.
2. Download `spotify-client-<version>.jar` from the [latest release](https://github.com/Ronflement/spotify-client/releases/latest) and put it, with Fabric API, in your `mods` folder.
3. Launch the game.

## Spotify setup

Spotify requires every third-party app to have its own Client ID. You create it once, for free, in the Spotify Developer dashboard.

1. Open the [Spotify Developer dashboard](https://developer.spotify.com/dashboard) and log in.
2. Click **Create app**:
   - give it any name and description;
   - under **Redirect URIs**, add exactly `http://127.0.0.1:43127/callback`;
   - under **Which API/SDKs are you planning to use?**, check **Web API**;
   - accept the terms and save.
3. In the app's **User Management** tab, add the Spotify account email of everyone who will use this app, including yourself.
4. Copy the app's **Client ID** (you don't need the Client Secret).
5. In Minecraft, open the settings (from the client with **⚙ Settings**, or from Mod Menu), go to **Account**, paste the Client ID and click **Log in**.
6. Approve the access in your browser. Once the page says the login succeeded, you can close it and go back to the game.

The settings screen shows the redirect URI with a **Copy** button. If you change the redirect port, update the redirect URI in your Spotify app to match.

## Usage

### Default keys

| Action | Default key |
|---|---|
| Open the client | `Y` |
| Play / pause | `Numpad 5` |
| Next track | `Numpad 6` |
| Previous track | `Numpad 4` |
| Show / hide widget | Unbound |

Change them in **Settings → Keys** (click a key, then press the new one; Escape cancels, Backspace unbinds) or in **Options → Controls → Spotify Client**.

### In the client

- **Left click** a track to play it, a playlist or album to open it.
- **Right click** a track, playlist, album or artist (or click `...` on a track) for more actions.
- **Space** toggles play / pause; **Backspace** goes back to the previous page.
- Click or drag the seek bar and the volume bar; scroll over the volume bar to adjust it.
- Click the device name in the bottom right corner to switch devices.
- Nothing plays? Open Spotify on a device first, then pick it in **Devices**.

## Settings

| Tab | Options |
|---|---|
| Account | Log in / out, Client ID, redirect port, redirect URI, setup steps |
| Widget | Visibility, screen corner, offsets, size, width, opacity, content, accent color |
| Playback | Announce track changes above the hotbar, widget update frequency |
| Keys | All key bindings |

Settings are saved immediately to `config/spotify-client.json`.

## Limitations

These come from Spotify's rules for apps in development mode:

- **Spotify Premium** is required for the app owner and to control playback.
- A development app can have **up to 5 users**, added manually in User Management. Beyond that, each person should create their own app and use their own Client ID.
- Search returns **10 results per page**; more results load automatically as you scroll.
- Tracks are only listed for **playlists you own or collaborate on**. Other playlists can still be played with **Play**.
- Artist "top tracks" are not available; artist pages show albums and singles.
- **Smart shuffle** is not available through the Spotify Web API; only regular shuffle is supported.
- API usage counts against a shared quota. If Spotify rate-limits requests, the mod waits and retries.

## Privacy and security

- The mod talks only to Spotify (`accounts.spotify.com`, `api.spotify.com`) and to Spotify's image servers for cover art.
- Login uses the OAuth Authorization Code flow with PKCE: no Client Secret is stored or needed.
- During login, a temporary local server listens on `127.0.0.1` (default port `43127`) to receive the authorization, then shuts down.
- Login tokens are stored locally in `config/spotify-client-spotify-tokens.json`. **Do not share this file**: it gives access to your Spotify account. Log out from the settings to delete it.
- No data is sent anywhere else.

## Troubleshooting

| Message | Solution |
|---|---|
| Enter the Client ID first | Paste your app's Client ID in **Settings → Account**. |
| Redirect port already in use | Choose another port, and update the redirect URI in your Spotify app. |
| Failed: check the Client ID and Redirect URI | The redirect URI in your Spotify app must match the one shown in the settings exactly. |
| Login refused in the browser | Log in again and approve the access. Make sure your account is listed in the app's User Management. |
| No Spotify device available | Open Spotify on your computer or phone, then try again or pick it in **Devices**. |
| Spotify Premium is required | Playback control requires a Premium account. |
| Missing Spotify permissions | Log out and log in again from the settings. |
| Too many Spotify requests | Wait a few seconds. A longer update interval in **Settings → Playback** reduces requests. |

## Building from source

Requires JDK 25.

```bash
./gradlew build
```

The mod jar is created in `build/libs/`. To start a development client:

```bash
./gradlew runClient
```

## License

Spotify Client is **source available** under the [PolyForm Strict License 1.0.0](LICENSE), with an additional permission for modpacks.

In short (the [LICENSE](LICENSE) file is what applies):
- ✅ You can read the code and use the mod for noncommercial purposes.
- ✅ You can include the **unmodified** mod jar in **free, noncommercial modpacks**, with credit and a link to the project page.
- ❌ You cannot modify the mod, distribute modified versions, redistribute the source code, or reupload the jar on its own.
- ❌ No commercial use.

## Contributing

This project does **not accept contributions**. Pull requests will be closed without review.

Spotify is a trademark of Spotify AB. Minecraft is a trademark of Mojang AB. This project is not affiliated with, endorsed by, or sponsored by either company.
