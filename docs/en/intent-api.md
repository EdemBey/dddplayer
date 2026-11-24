# Intent API: implementation

A user-facing description with examples is in [README.md](../../README.md#intent-api-integration)
and [README_RU.md](../../README_RU.md#intent-api-интеграция). This document describes how the keys are parsed in code.
When you change the keys, update all three documents.

## Entry point

`PlayerActivity` (`exported`, a repeated Intent arrives in `onNewIntent`) accepts `ACTION_VIEW`
for `rtsp`, `http`, `https`, `ftp`, `content`, `file`. The MIME types `video/*`, `application/sdp`,
HLS (`application/vnd.apple.mpegurl`, `application/x-mpegURL`), `application/dash+xml` fit, as well as
video extensions in the path without a MIME type (see `AndroidManifest.xml`). For non-network URIs the permission
`READ_MEDIA_VIDEO` (API 33+) or `READ_EXTERNAL_STORAGE` is requested first.

`handleIntent` → `IntentUtils.parseIntent(context, intent)` → `Pair<List<MediaItem>, startIndex>`
→ `PlayerViewModel.loadMedia`. The `return_result` flag is read separately.

## Parsing (`utils/IntentUtils`)

If there is `video_list`, **playlist mode** runs, otherwise if `intent.data` is present, a **single file**
is parsed. If there is neither, nothing is opened (in a debug build — a test video).
A launch from the launcher (`ACTION_MAIN`) redirects to the start screen (`FileBrowserActivity`).

The internal start screen keys (`IntentUtils.EXTRA_HISTORY_KIND/SOURCE/NAME`, `EXTRA_FORCE_M3U`) with the prefix
`top.rootu.dddplayer.` are not needed by external apps: the player uses them to keep the "Continue watching" history
and to force-check a playlist link for M3U.

### Single file

| Key | Type | `MediaItem` field |
|:---|:---|:---|
| `title` / `android.intent.extra.TITLE` | String | `title` (otherwise the file name from `OpenableColumns.DISPLAY_NAME` or the last path segment) |
| `position` | Int / Long / String | `startPositionMs` (`getLongExtraCompat`) |
| `thumbnail` | String | `posterUri` |
| `headers` | String[] / ArrayList<String> | `headers` (name-value pairs) |
| `subs`, `subs.name`, `subs.filename` | Uri[] (or ArrayList), String[] | `subtitles`. If not passed, for `file://` files next to the video are searched (`SubtitleFinder`) |
| `ddd_quality` / `quality` | String (JSON) or Bundle | Alternative URLs of one video: `[{"name":"1080p","url":"..."}]` or `{"1080p":"..."}`. The quality button is shown if there is more than one option |

### Playlist

| Key | Type | Note |
|:---|:---|:---|
| `video_list` | Parcelable[] Uri / ArrayList<Uri> / ArrayList<String> | Required |
| `video_list.name` | String[] | Titles (otherwise `filename`, otherwise the path segment) |
| `video_list.filename` | String[] | File names |
| `video_list.thumbnail` | String[] | Posters, an empty string — no poster |
| `video_list.subtitles` | ArrayList<Bundle> | In each Bundle: `uris` (Uri[]) and `names` (String[]) |
| `headers` | String[] | Common to all items |
| `start_index` | Int | Fallback start index (if `intent.data` matched no item), clamped to the valid range |
| `position` | Int / Long / String | Applied only to the item whose URI matches `intent.data` |

The start item is the one whose URI matches `intent.data` (the main way). `start_index` is used only if
`intent.data` is not set or not found in the list; with neither, the first item starts.

In `video_list` mode `ddd_quality` / `quality` is applied only to the start item: the qualities belong to the current
episode, not to the whole playlist.

A fallback transport for apps that pass only HTTP headers (for example, Lampa Android):
`X-DDD-Quality: [{"name":"1080p","url":"..."}]`. The header is parsed as qualities and removed from headers so that it does not
go to the video server.

### Qualities for the other episodes of a playlist

`X-DDD-Quality-Sources` is a JSON object "index of the item in `video_list`" → "URL that returns a list of sources":

```json
{"0":"https://site/api/player/based?source=44%3Atoken","1":"https://site/api/player/based?source=44%3Atoken2"}
```

The header is also removed from headers. When the user moves to an episode that has no `ddd_quality`, the player
requests the descriptor (once per episode, cached), builds the quality list by the same rule as the plugin
(provider priority, one entry per real file, `Auto` = the original link), and shows the quality menu.

The value can also be a ready quality list instead of an address — this is how already resolved playlists
(for example, HDrezka) are passed, where there is nothing to request:

```json
{"1":[{"name":"720p","url":"https://.../720.mp4"},{"name":"1080p","url":"https://.../1080.mp4"}]}
```

Such a list immediately becomes the episode's `urlQualities`, with no network request.

### Voiceovers as separate streams

`X-DDD-Voiceover-Path: /voiceovers` — the voiceovers of an episode are returned by the episode's own URL with this path, the query stays the same:
for `https://site/play.m3u8?q=1080&url=...` that is `https://site/voiceovers?q=1080&url=...`. It is needed when a voiceover
is muxed into the video and each voiceover has its own stream: it cannot be switched as a track. The header is one for the whole playlist and does not
depend on indexes, so it also works when Lampa Android passes a single episode instead of a list. The path must
start with `/`, the address is built only for http/https.

The header is removed from headers. When an episode starts playing, the player requests the address once
(`MediaItem.voiceoverSourceUrl`, `UrlQualityLoader.loadVoiceovers`) and stores the response in the item (`MediaItem.voiceovers`):

```json
{"voiceovers":[
  {"id":"lostfilm","name":"LostFilm","selected":true,"url":"https://.../stream.m3u8",
   "qualities":[{"name":"1080p","url":"https://.../1080.m3u8"},{"name":"720p","url":"https://.../720.m3u8"}]}
]}
```

- `name` and `url` (http/https) are required, `url` is the voiceover stream at the default quality;
- `selected` is the voiceover that the episode link from `video_list` plays;
- `qualities` are the qualities of this voiceover, they replace the episode's `urlQualities` on switching;
- `id` is the voiceover name that does not change from episode to episode (equal to `name` by default).

If there is more than one voiceover, the audio button opens their list instead of the track list
(`PlayerFragment.showAudioTrackMenu`). `PlayerViewModel.setVoiceover` replaces the episode link keeping the position and
keeps the previous quality if the new voiceover has it (compared by `name`), otherwise it plays the voiceover's `url` and an `Auto` item
appears in the quality menu. The selected `id` is remembered until a new playlist is loaded: in the next episodes this
voiceover turns on by itself as soon as their list arrives. Until then the episode plays for a second or two with its default
voiceover and then reloads from the same position.

### Resume for playlist episodes

- `X-DDD-Item-Keys` — `{"0":"rezka:10877:56:1:1", ...}`: a stable episode key (`MediaItem.resumeKey`). Settings and
  position are stored by `key:<key>`, not by URL — needed when links are signed and change on every request.
- `X-DDD-Positions` — `{"1":734000}`: a position in ms known to the app (`MediaItem.knownPositionMs`). Used
  if the player has no record of its own for the episode.

Both headers are removed from headers.

### Audio replacement over a part of the video

- `X-DDD-Audio-Overlay` — `{"0":{"music":"https://.../ending.m4a","segments":"https://.../skip-times?malId=1&episode=2"}}`:
  "index of the item in `video_list`" → the music and the segments address (`MediaItem.endingOverlay`). The player itself appends
  `duration=<seconds>` to the address, parses the response `{"data":[{"type":"ed","startTime":..,"endTime":..}]}` and on the ending mutes
  the video, playing the music in sync with the position, pause and seeking. Segments shorter than 87 seconds and segments other than `ed`
  are ignored, the replacement is no longer than 90 seconds. The header is removed from headers.

### Skipping the opening, ending and recap

- `X-DDD-Segments` — `{"0":[{"type":"op","startTime":0,"endTime":89},{"type":"ed","startTime":1273,"endTime":1372}],"1":"https://..."}`:
  "index of the item in `video_list`" → ready segments (seconds) or an address (`MediaItem.skipSegments` / `skipSegmentsUrls`).
  Both can be passed as an object `{"segments":[...],"url":"https://..."}` (both keys are optional).
  `url` can also be an array of addresses (http/https only): the player tries them in order and stops
  at the first response with an opening (`op`); if there is no opening anywhere, the first non-empty response is taken (for example, TheIntroDB,
  then IntroDB); a string still works.
  Then the ready ones (for example, YummyAnime) are shown immediately, and the ones downloaded from the address (AniSkip/kopenkin) are added
  to them (`SkipSegmentsLogic.combine`): all ready ones stay, a downloaded segment is discarded only if it
  overlaps a ready one by more than 5 s; touching and overlap up to 5 s keep both (for example, the ending song
  and the credits right after it, both `ed`).
  The player downloads segments from the address when the episode duration is known; `episodeLength=0` in the address is replaced
  with the duration in whole seconds (this is how AniSkip picks timings), and `duration=0` — with the duration in seconds
  with fractions up to 3 digits, for example `1512.085` (kopenkin `/api/player/skip-times` requires the duration with ±2 s accuracy,
  without it it answers 400). Response: `{"data":[...]}` in the format above, an AniSkip v2 response
  `{"results":[{"interval":{"startTime":..,"endTime":..},"skipType":"op"}]}` or an array directly. For series:
  TheIntroDB v4 `{"intro":[{"start_ms":..,"end_ms":..}],"recap":[..],"credits":[..],"preview":[..]}` (the arrays may
  be absent; `start_ms: null` — from the start, `end_ms: null` — to the end of the video, the duration is needed) and IntroDB
  `{"intro":{..}|null,"recap":..,"outro":..,"post_credits":..}`. Type mapping: `intro` → `op`,
  `credits`/`outro` → `ed`, `recap` → `recap`; `preview` and `post_credits` are ignored. Junk from the series databases
  is discarded: segments shorter than 5 s and credits starting at 0.
  `op`/`ed`/`recap` (and `mixed-op`/`mixed-ed`) are taken into account, the rest is ignored (`SkipSegmentsLogic`).
- `aniskip` in the object form — `{"0":{"aniskip":["https://api.aniskip.com/v2/skip-times/<mal>/<n>?...&episodeLength=0", ...]}}`:
  AniSkip addresses for the episode and its neighbors (AniSkip numbers are sometimes shifted). The player downloads all of them and keeps only
  the results whose `episodeLength` matches the duration of the playing video (±1.5 s)
  (`SkipSegmentsLogic.parseAniSkipMatching`); the first address takes precedence. What is found is added to `segments` by the same
  `combine` rule.
- While the position is inside a segment (except the last second), the "Skip opening/ending/recap" button is visible; with the panel hidden
  OK on the remote presses it. Segments are marked on the seek bar. Auto-skip is set separately for the opening,
  ending and recap (`auto_skip_op`/`auto_skip_ed`/`auto_skip_recap`, off by default): each segment
  is skipped immediately, once per episode. The rest is configured in the "Segments" category: showing the bands
  (`skip_segments_marks`), the band color for each type (`skip_color_op`/`skip_color_ed`/`skip_color_recap`) and the button display time (`skip_segments_button_seconds`;
  OK skips the whole segment even after the button has hidden). Segments also become chapters (`SkipSegmentsLogic.mergeChapters`):
  "Opening/Ending/Recap" at the start and "Episode" at the end, so the next/previous chapter buttons, the chapter menu and the marks
  on the bar work with them. The header is removed from headers.

String arrays are read by `getSmartStringArray`: `String[]`, `ArrayList<String>`, `CharSequence[]`
and `ArrayList<CharSequence>` fit.

## Position from an external app

`startPositionMs > 0` on the start item:
- with `external_position_mode = APPLY` (default) the player starts from this position immediately;
- with `= ASK` the player starts from 0 and shows the resume dialog with this position.

A position from an Intent takes priority over the one saved in the player. See [playlists.md](playlists.md#resuming-playback-handleresumelogic).

## Result (`return_result = true`)

`PlayerActivity.finish()` calls `setResult(RESULT_OK, Intent("top.rootu.dddplayer.intent.result.VIEW"))`:

| Key | Type | Value |
|:---|:---|:---|
| `data` | Uri | URI of the current (last) item |
| `position` | Long | Position in ms. When the video finishes it equals the duration |
| `duration` | Long | Duration in ms |
| `end_by` | String | `completion` (the video ended by itself; with `return_result` the activity closes) or `user` |
