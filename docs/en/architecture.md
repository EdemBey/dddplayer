# Architecture

## Layers

```
PlayerActivity ──(Intent, PiP, result)─────┐
                                            ▼
PlayerFragment ◄──LiveData── PlayerViewModel ──► PlayerManager ──► ExoPlayer
 │  PlayerUiController (views)    │  AnaglyphDelegate     │  OkHttp, DataSource chain,
 │  PlayerInputHandler (remote)   │  SettingsRepository   │  NetworkBufferLoadControl, M3U probe
 │  PlayerTouchHandler (gestures) │  (prefs + Room)       │
 │  PlayerTimerController         │                       │
 ▼                                ▼                       ▼
SurfaceView (2D/HDR)  or  StereoGLSurfaceView ─► StereoRenderer (GLSL)
```

- **`PlayerActivity`** is the only entry point for video. It checks file permissions,
  parses the Intent (`IntentUtils`) and handles PiP (the play/pause action via `RemoteAction` and a
  `BroadcastReceiver`, auto-PiP by setting). The launcher entry point is
  `FileBrowserActivity`; `PlayerActivity` has its own `taskAffinity`: otherwise Android TV treats the PiP window and the app
  icon as one task and opens the start screen inside the player's task. Old shortcuts to `PlayerActivity`
  (MAIN/LAUNCHER) are still redirected to the start screen. In PiP the system moves the player
  to a separate task, so it is remembered in `PlayerActivity.pipActivity`: a new video closes the old PiP window
  (`closeFromPictureInPicture`), and opening the app from outside (launcher, Recents, `PipReturnCallbacks`)
  expands it by launching the same activity again (a start screen opened by the icon is closed immediately
  and never shown) (`restorePictureInPicture`, SystemUI treats this as a
  return from PiP). `finish()` (`prepareFinish`) builds the result for the calling app (`return_result`). Key presses are passed to
  `PlayerFragment.handleKeyEvent` via `dispatchKeyEvent`.
- **`PlayerFragment`** is the player screen. It creates the GL surface (ES 2/3 choice), subscribes to LiveData
  (`observeViewModel`), shows menus, dialogs (resume, playlist, groups,
  quality, zoom) and AFR. It also handles Back with an exit confirmation.
- **`PlayerUiController`** holds `findViewById` and the operations on views: panels, clock, badges,
  surface switching, stereo/VR layout.
- **`PlayerInputHandler`** is the remote control logic: OSD settings, OK/Up on settings, seeking
  with the seek bar, live mode (OK — playlist, Left/Right — channels, Up — info panel).
- **`PlayerViewModel`** holds the player and playlist state. It is responsible for loading media, groups, zapping,
  resume, error recovery, settings of the current video (stereo, tracks, scale, aspect
  ratio), menus and FPS/info badges. 3D/VR parameters are moved to `AnaglyphDelegate`.
- **`SettingsViewModel`** is the state of the OSD settings panel (visibility, current item).
- **`PlayerManager`** owns ExoPlayer. It creates and recreates the player, builds sources,
  applies headers, runs the M3U probe and provides track metadata.
- **`GlobalSettingsActivity` / `GlobalSettingsViewModel`** are the global settings screen, plus checking
  and installing updates (`UpdateManager`, GitHub Releases).
- **`FileBrowserActivity`** is the start screen (opened on launch from the launcher) and the built-in file browser.
  Main screen: "Continue watching" (history by source: a folder or a playlist, `HistoryEntry`;
  an episode in the same folder replaces the entry instead of adding a new one; the first 3 are shown, the rest are under "All history"),
  playlists by link (`SavedPlaylist`, add/edit/delete, the former `startup_playlist_uri` is migrated here),
  storages, SMB network folders and DLNA media servers. The player receives the source via the internal `IntentUtils.EXTRA_HISTORY_*` keys and updates the history
  on video change and on save (`PlayerViewModel.updateWatchHistory`); videos opened by other apps do not get into the
  history. File browser: internal storage, SD, USB (roots — `StorageRoots`,
  filter and natural sort — `FileBrowserLogic`). The videos of a folder are passed to the player as a playlist via
  `video_list` (Uri in extras: `file://` in data is forbidden by the system) with `FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP`
  so that a second player is not opened. The `file://` path gives the player seeking (unlike the `content://` of
  some file managers) and subtitle search next to the video. On Android 11+ subtitles (not media files) are visible
  only with "all files access" (`MANAGE_EXTERNAL_STORAGE`); the browser offers it as a separate item.
- **DLNA** (`logic/DlnaLogic`, `player/DlnaClient`) uses no third-party libraries: SSDP media server discovery (M-SEARCH
  to 239.255.255.250:1900 with `MulticastLock`, through every IPv4 interface, for `MediaServer:1`, `ContentDirectory:1`
  and `upnp:rootdevice`; the device description is read with a 4 s timeout), the device description (ContentDirectory service), the `Browse` request
  page by page and DIDL-Lite parsing (folders, videos by `protocolInfo` video/*, subtitles text/srt and so on). The videos of a server
  folder are opened as a playlist of HTTP links (seeking via Range), the server's subtitles are passed in
  `video_list.subtitles`. In the history the source is the server description address plus the folder id (`HomeLogic.dlnaSource`): on
  resume the folder is read again, and the video is looked up by link, then by title (the server address may have changed).
- **SMB** (`logic/SmbLogic`, `player/SmbConnections`, `player/SmbDataSource`, the smbj library) — network folders
  of the start screen (`smb_servers`: address `smb://host/share[/folder]`, user, password, domain; empty — guest).
  smbj cannot list the server's shares, so the share is given in the address. The player receives `smb://...`
  links: `SmbRoutingDataSource` under `PrefetchingDataSource` passes them to `SmbDataSource` (reading from any position
  of the file, seeking), everything else goes to `DefaultDataSource`. `SmbConnections` finds the credentials by server and share
  among the saved folders; sessions and resources are reused. Subtitles next to the video (`SmbLogic.subtitlesFor`)
  are passed in `video_list.subtitles`. In the history the source is the folder link without credentials.
- **`VrConfigActivity`** accepts Cardboard links (QR codes of lens profiles) and saves the distortion parameters.
- **`CrashHandler` / `CrashActivity`** catch unhandled exceptions and show the log. A device report
  (`DeviceInfoCollector`) is appended to the log; it is collected in the `:crash` process, not in the crashed one.
- **`DeviceInfoActivity`** (Settings → App → Device diagnostics) is a report for analyzing complaints:
  model and Android, display and HDR modes (`Display`), audio outputs with EDID formats (`AudioDeviceInfo`) and
  passthrough according to Media3 (`AudioCapabilities`), video and audio decoders (`MediaCodecList`: HW/SW, size,
  10-bit/HDR10/HDR10+, Dolby Vision profiles) plus the built-in dav1d and FFmpeg. The data is collected by
  `DeviceInfoCollector` (each section separately, an error in one does not break the others), profile labels come from
  `DeviceInfoLogic`. The screen is the same as for the error log: `LogReportView` (layout `dialog_error_log`) — upload
  to termbin.com with a link and a QR code, copying the whole text by a long press on the log. The last upload
  is cached (`LogUploadCache`, the `log_upload_cache` preferences file): with the same SHA-256 of the text the link and QR
  are shown immediately. termbin keeps pastes for a month, the cache trusts a link for 15 days.

## Data model

- `model/MediaItem` is a playlist item. Fields: `uri`, `title`, `filename`, `posterUri`, `headers`, `subtitles`,
  `startPositionMs`, `group`, `uuid` (also the Media3 `mediaId`) and `absoluteIndex` (index in the full playlist).
- `data/VideoSettings` (Room, table `video_settings`, key — URI) stores the stereo mode, anaglyph,
  swap, depth, the last position and duration, and the IDs of the audio and subtitle tracks.
- Global settings live in `SettingsRepository` (SharedPreferences), see [settings.md](settings.md).

## Player lifecycle

1. `PlayerFragment.onViewCreated` creates `PlayerUiController` and the GL surface, and then, when the player
   is created, attaches the surface (`attachSurfaceToPlayer`).
2. `PlayerViewModel.loadMedia` → `PlayerManager.loadPlaylist`. If there is no ExoPlayer yet, `initializePlayer()`
   is called, otherwise the sources are replaced.
3. `onMediaItemTransition` → `handleMediaItemTransition`: the stereo mode is reset, `VideoSettings` are loaded
   by URI (or the global defaults), resume fires for the first item of a new playlist,
   and for M3U the last channel is remembered.
4. If "hard" settings changed on return from settings (decoders, tunneling, DV→HEVC,
   downmix, buffer), `checkSettingsAndRestart()` recreates the player keeping the position.
   Other settings are applied via `applyLiveSettings()`.
5. While running, the player saves the current video settings (`saveCurrentSettings`) when leaving the screen and in `finish()`.

## Threads and coroutines

- ExoPlayer and LiveData are updated on the main thread. The network and file reading of the M3U probe run on
  `Dispatchers.IO` (`expandM3u`), after which the result returns to `viewModelScope`.
- `ParsingDataSource` parses MKV/MP4 metadata in its own coroutine, the data arrives through a pipe.
- `StereoRenderer` runs on the `GLSurfaceView` GL thread (`RENDERMODE_WHEN_DIRTY`, frames by
  `onFrameAvailable`).

## Resources

- Layouts are in `res/layout`: `player_fragment.xml`, `playback_controls.xml`, `layout_top_info_panel.xml`,
  `layout_top_settings_panel.xml`, `activity_global_settings.xml` and `dialog_*.xml`.
- Strings exist in 5 locales: `values` (en), `values-ru`, `values-uk`, `values-be`, `values-zh-rCN`.
  The `MissingTranslation` lint is off, so keep track of translation completeness yourself.
- `xml/network_security_config.xml` allows cleartext and user certificates.
