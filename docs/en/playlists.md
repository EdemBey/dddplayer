# Playlists, M3U/IPTV and live mode

## Where a playlist comes from

1. **Intent** (`IntentUtils.parseIntent`) — a single file or `video_list`, see [intent-api.md](intent-api.md).
2. **A single `.m3u`/`.m3u8` link** from an Intent — the player checks whether it is a playlist.
3. **Playlists by link on the start screen** (`saved_playlists`: name and link; the former startup playlist
   `startup_playlist_uri` is migrated here once). The M3U check is forced (`forceM3uProbe = true`,
   key `EXTRA_FORCE_M3U`), because such links often have no extension (`get.php?...`).

## M3U probe (`PlayerManager.expandM3u`)

- Only a single link whose path ends with `.m3u`/`.m3u8` is checked (or any link with `force`).
- http(s) is downloaded **once** (`fetchForProbe`), everything else is read via `ContentResolver`.
  The limit is `MAX_PROBE_BYTES` (32 MB). Content-Type `video/*` and `audio/*` immediately means "not a playlist",
  except the `*mpegurl` types (`M3uParser.isMediaContentType`): TorrServer serves the "Play all" playlist as `audio/x-mpegurl`.
- `M3uParser.isPlaylist`: there is `#EXTINF` and **not a single** `#EXT-X-`. Any HLS tag means HLS.
- If it is not M3U, the response is put into `PrefetchStore` and the player gets it without a second request.
  If the text contains `#EXT-X-`, the type is immediately marked as HLS (`probedMediaTypes`).

## M3uParser

- `#EXTINF:<attrs>,<title>`: the title is everything after the first comma **outside quotes**.
  Attributes: `tvg-logo`/`logo` (poster), `group-title` (group).
- `#EXTGRP:` sets the default group for the following items if they have no `group-title`.
- `#EXTVLCOPT:http-user-agent|http-referrer|http-referer|http-cookie` and `#EXTHTTP:{json}` set the
  headers of the next item. They are merged with the headers of the parent link.
- Relative paths are resolved against the playlist URI.

## Playlist state in PlayerViewModel

- `currentPlaylist` is the full list. Every item has an `absoluteIndex`, its number in the full list.
- `playlistUiState` (`PlaylistUiState`) holds the displayed items filtered by group, the current group
  and the list of groups. In the playlist dialog groups are switched with the Left/Right buttons (`cycleGroup`, there is an "All" entry)
  and chosen in the groups dialog (`selectGroup`). If the group was changed but nothing was selected, the group of the current item
  is restored after closing (`revertToPlayingGroup`).
- `isM3uPlaylist` — the playlist came from M3U.

## Last channel

Works only for an M3U playlist: on every switch the URI is saved to `last_channel_uri`.
On the next M3U load the start item is looked up **by exact URI**. If it is not found, the player starts from the first one.
Playlists from an Intent are not affected by this logic.

## Live mode

`isOverallLiveMode()` returns true only if all three conditions hold:
1. `live_mode_control` is enabled in settings (**off by default**);
2. the playlist has more than one item;
3. a live stream has already played in the current playlist (`isSessionLive`). Both M3U and any other playlist qualify.

In live mode, when the control panel is hidden, the buttons work as follows (`PlayerInputHandler`):
- **OK** opens the playlist;
- **Left/Right** switch the channel (`zapChannel`): the channel name is shown immediately, and the channel itself
  starts 1.5 s after the last press. This way you can flip through several channels in a row.
  OK starts the selected channel immediately (`confirmZap`), any other button cancels the selection (`stopZap`).
- **Up** shows the info panel, and pressing it again opens the side menu.
- The info panel is shown on every channel change. Outside live mode this is controlled by the
  `show_info_on_change` setting.

On a phone in live mode a horizontal swipe cycles through the channels of the current group
(`PlayerFragment.handleHorizontalSwipe`), and a tap on the top zone opens the playlist.

## Resuming playback (`handleResumeLogic`)

Works for the first item of a new playlist and on every switch to another item (`resumeCheckedItemId`:
reloading the same item — a quality or CDN change — does not trigger the dialog). Does not work for live.
The position of the current item is saved before switching with the Prev/Next buttons and from the list; on
auto-advance the finished item is marked as watched (position 0).

1. **A position from an external app** (`position` in the Intent) takes priority over the one saved in the player:
   - `external_position_mode = APPLY` (default): the player starts from this position immediately;
   - `= ASK`: the player starts from 0, and the position is kept in `pendingExternalPosition` (together with the item uuid)
     and offered in the resume dialog.
2. Otherwise the **saved position** from Room is used (`VideoSettings.lastPosition`, key — `key:<resumeKey>`
   or the URL) according to `resume_mode`: ask (default), always or never. If there is no record, the position
   from the app's `X-DDD-Positions` is used.
3. A position is meaningful if it is at least 5 s and at most 95 % of the duration (`isResumePositionMeaningful`).
