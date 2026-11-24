# Playback (`player/`)

## PlayerManager

`PlayerManager` owns the ExoPlayer instance. Only `PlayerViewModel` talks to it.

- `initializePlayer()` builds the player from the current settings:
  - `MediaCodecSelector`: a request for a Dolby Vision decoder is replaced with HEVC according to `dv_mode` — always, never or
    "auto" (if there is no DV decoder or the display does not report DV support, `isDolbyVisionSupported`);
    the selector sees only the MIME type, so when substituting, the video renderer is replaced with `DolbyVisionVideoRenderer`:
    for profiles without a compatible HEVC layer (5, 9, 10, see `DolbyVisionLogic`) it uses the normal decoder selection;
  - `dv_mode` = 3: `ExtractorsFactory` wraps MKV/MP4 in `DoviExtractorWrapper`. For `dvhe.07` the RPU
    is converted to 8.1 (libdovi, mode 2, like Kodi's `convertdovi`), the EL layer is dropped, and the format
    becomes `dvhe.08`. Profiles 5/8 pass through unchanged (in the log `DoviConvertTrack: DV ...: native`).
    The native part (`libdovi_bridge.so`: the `DoviBridge` JNI bridge and the static libdovi from quietvoid/dovi_tool)
    is built by the separate `dovi` module into `app/libs/lib-dovi-release.aar` (`./gradlew -Pdovi=true
    :dovi:assembleRelease`, see `app/libs/README.md`). Without the AAR `DoviBridge.isAvailable()` = false: the item
    is hidden in settings, and a saved `dv_mode` = 3 works as "auto";
  - `hdr10plus_enabled` = false (#10, like Kodi): MKV/MP4/fMP4/TS are wrapped in `Hdr10PlusExtractorWrapper`,
    video tracks go through `Hdr10PlusStrippingTrackOutput`. `Hdr10PlusStripper` removes ITU-T T.35 HDR10+
    (country 0xB5, provider 0x003C, application 4): in HEVC — prefix SEI messages (a mixed SEI is rebuilt),
    in AV1 — metadata OBUs; for VP9 from MKV the additional sample data is dropped. Only the
    start of a frame up to the first slice is scanned. T.35 Dolby Vision is left alone. HLS and DASH are not processed;
  - `DefaultTrackSelector`: tunneling, preferred audio and subtitle languages (`""` — default,
    `device` — system languages), `exceedRendererCapabilitiesIfNecessary`. Whether tunneling really turned on
    is visible in `AudioTrackConfig.tunneling` (`onAudioTrackInitialized`): then the video badge in the info panel has `↳`.
    The audio badge shows the track and the output format (`AC3 5.1 > AC3 5.1` — passthrough, `> PCM` — the player decodes);
  - `DefaultRenderersFactory`: priority of extension decoders separately for video (`video_decoder_priority`, AV1) and audio
    (`audio_decoder_priority`, ffmpeg) — passed to `buildVideoRenderers`/`buildAudioRenderers`,
    `enableDecoderFallback`. With `stereo_downmix` a custom `DefaultAudioSink` is attached with
    `ChannelMixingAudioProcessor` (matrices from `AudioMixerLogic`) and an audio buffer of at most 256 KB
    so as not to run out of memory. Without downmix the standard sink is used, and passthrough works;
  - `hd_audio_iec`: the selected sink is wrapped in `IecCarrierSink` (`IecCarrierOutput.wrap`). DTS-HD MA and
    TrueHD are packed into IEC 61937 (`DtsHdIecPacker`, `TrueHdMatPacker`, a port from plezy, byte-for-byte identical to FFmpeg)
    and go through a separate `DefaultAudioSink` without processors with an `AudioTrack` in `ENCODING_IEC61937`
    192 kHz/7.1. Everything else goes as before. Why: Fire OS accepts raw `ENCODING_DTS_HD`, but passes
    only the DTS core (and silence on some receivers), and raw TrueHD is not passed everywhere. Downmix does not
    affect the bitstream, as with AC3/DTS passthrough. The carrier is not used with tunneling, with a speed other than 1.0x,
    with 44.1 kHz family rates, and if the HDMI route does not accept IEC 61937 192 kHz/7.1. If the route accepts the carrier
    but it is unavailable right now, TrueHD and DTS-HD are decoded (the raw stream is silent there); with no carrier on the
    route (Android < 10, no IEC 61937) — the old path, as with the option off. A track labeled A_DTS
    (MKV) is checked by its first block: without an HD part it goes to the regular sink (we offer it the carrier only
    if the regular sink accepts DTS directly; the error of such a switch arrives as `InitializationException`).
    With the carrier the audio badge shows `TrueHD (IEC 61937)` / `DTS-HD (IEC 61937)` (`carrierCodecLabel`).
    The stream mismatch latch is reset in `loadPlaylist` and on `onMediaItemTransition`;
  - `NetworkBufferLoadControl` (see below);
  - `LoudnessEnhancer` by `audioSessionId` (`loudness_boost`), `skipSilence`, seek step 15 s.
- `loadPlaylist(items, startIndex, startPosMs)` turns `MediaItem` into `Media3MediaItem`
  (`mediaId = uuid`, metadata, external subtitles, `LiveConfiguration`: target offset 15 s,
  max 30 s, speed-up to 1.05x) and calls `setMediaSources`.
- `buildMediaSources` picks the HLS factory if the type is `application/x-mpegURL`. The type is determined
  by the OkHttp interceptor from Content-Type or extension after redirects, by the M3U probe, or by extension.
  For everything else `DefaultMediaSourceFactory` is used. Both factories work through `ParsingDataSource`.
- `reloadMediaItems` reloads the sources from a position when recovering from an error.
- `releasePlayer(isFinalRelease, saveState)`: with `saveState` the index and position
  needed for a hot restart are remembered.
- `applyLiveSettings()` applies settings that do not need a restart: tracks, skip silence, volume.

### Network

- One `OkHttpClient`: redirects, connection retry, 60 s timeouts.
- **TLS trust-all and `hostnameVerifier { true }` are intentional.** On old Android the root
  certificates are outdated, and opening the video matters more than verifying the certificate. Do not change this.
- An application interceptor records the HLS, DASH or SmoothStreaming MIME type by Content-Type and the final URL
  in `resolvedMediaTypes`. This way the right factory is chosen after a redirect on reload.
- The HTTP headers of an item (`MediaItem.headers` from the Intent `headers` or from M3U `#EXTVLCOPT`/`#EXTHTTP`)
  are bound to the item itself: `buildMediaSources` takes them by `mediaId` (`headersByMediaId`), and the source
  factory of that item (`createParsingDataSourceFactory(headers)`, `ResolvingDataSource`) adds them
  to every request: the HLS playlist, segments, subtitles, metadata reading. We do not switch them globally on `onMediaItemTransition`:
  the player starts loading the next item earlier. The default User-Agent is
  `App.USER_AGENT`, only if the source did not set its own (`requestHeaders`). We do not call `setUserAgent` on
  `OkHttpDataSource.Factory`: it adds User-Agent as a second header on top of the given one, and the server answers 403.

### DataSource chain

```
ParsingDataSource → PrefetchingDataSource → DefaultDataSource (file/content/asset) → OkHttpDataSource
```

- **`PrefetchingDataSource` / `PrefetchStore`.** If the response was already downloaded by the M3U probe and turned out not to be M3U
  (for example, HLS), it is handed to the player without a repeated request. The response is single-use and lives 30 s. `getUri()`
  returns the URI after redirects so that relative paths in HLS resolve correctly.
  This matters for links with one-time tokens.
- **`ParsingDataSource`** copies the first bytes through `TeeDataSource` into a pipe (64 KB, at most 50 MB)
  and parses MKV/MP4 metadata in a separate coroutine (`UnifiedMetadataReader`: track names and languages
  that ExoPlayer does not provide). Each session has its own pipes, so a repeated `open` does not break the previous one.

### Extractors

- TS: HDMV DTS, non-IDR keyframes, detect access units, ignore splice info, extended timestamp
  search. This is needed for broken IPTV streams. HLS uses the same flags (`DefaultHlsExtractorFactory`).
- MP4: `FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES(_H265)`. Seeking is enabled for CBR.
- `DefaultLoadErrorHandlingPolicy`: at least 5 load retries.

## Buffer: NetworkBufferLoadControl

A subclass of `DefaultLoadControl` that calls its protected constructor.

| Mode | Network | Local files |
|:---|:---|:---|
| "Auto" (`target_buffer_mb = -1`) | 15–50 s by time (the old parameters) | 15–50 s |
| N MB | target size N MB, the time limit is removed | 15–50 s (default) |

- Playback starts after 0.5 s of buffer, after a rebuffer — after 5 s.
- In any mode the size is limited by `maxBufferBytesForHeap()`: reserve = `clamp(heap / 2, 64 MB, 160 MB)`,
  buffer = `heap − reserve`, but at least 16 MB. Examples: heap 100 MB → 36 MB, 192 → 96, 512 → 352, 1024 → 864.
  Without this, 4K remuxes crashed with `OutOfMemoryError` on TVs with a small heap.
- `prioritizeTimeOverSizeThresholds = false` in all modes: time must not inflate the buffer beyond the size.
- The options in settings (`getTargetBufferOptions`) are filtered by the same limit. If the saved value
  is no longer available, `getTargetBufferCorrectMB()` returns "Auto".
- The audio buffer with downmix is limited to 256 KB, and this limit stays.
- The fill percentage for the UI is returned by `getNetworkBufferFillPercent()`. Where it is shown is set by
  `buffer_display_mode`: off, in the panel or next to the clock (single clock and info panel).

## Error recovery (`PlayerViewModel.tryRecoverFromError`)

- `HlsPlaylistTracker.PlaylistStuckException`: for live the player jumps to the live edge, for VOD — to the current position,
  then `prepare()` again.
- IO errors (`IO_UNSPECIFIED`, `NETWORK_CONNECTION_FAILED/TIMEOUT`) are retried up to 3 times from the current position
  via `reloadMediaItems`. While the reload runs, `isRecoveringFromError = true`, and a transition to the same
  item does not reset the counter and settings.
- `PARSING_CONTAINER_UNSUPPORTED` (HLS or DASH is hidden behind the link): if the interceptor learned the real type,
  the problem item gets the MIME type, and the playlist is reloaded.
- Video renderer error: the video track is disabled, the sound keeps playing (`videoDisabledError`
  and a toast). On a switch to another item the video is enabled back.
- Audio renderer error: audio is turned off, a toast suggests choosing another track.
- The error screen shows the code, message and cause chain (`PlaybackErrorLogic.causeLines`), a long press
  copies the details. For an IO error when reading via another app's provider (`content://`) a
  hint is added: such apps often serve the file without seeking.

## AFR and FPS

- `utils/afr/*` switches the display mode to the video's frame rate (optionally the resolution too).
  There is 23.976/29.97 correction, rate doubling, skipping 24 Hz, a pause on switching and skipping short videos.
- `RuntimeFpsDetector` measures FPS from rendering if the container has none.

## Updates

`UpdateManager` reads `https://api.github.com/repos/usmanec/dddplayer/releases`, compares versions,
downloads the APK and starts the installation (`REQUEST_INSTALL_PACKAGES`, `FileProvider`).


## Chapters

Media3 does not expose container chapters, so the player reads them itself:

- `logic/ChapterParser` — parsing Matroska (`Chapters`/`ChapterAtom`) and MP4 (`chpl`), navigating via `SeekHead` and restoring double-encoded titles;
- `player/ChapterLoader` — a range request for the first 6 MB (or reading a local file), then `SeekHead`, and if it is absent — walking the top-level elements skipping the payload by size; cached by URL;
- `PlayerViewModel.chapters` — the list is loaded asynchronously on start and on playlist item change;
- a "Chapters" item appears in the main menu, navigation is done via `seekTo`.

`Chapters` is not always in the first megabytes: in some releases it sits after the clusters (for example, at the 50th MB). `SeekHead` gives the exact offset, and the fallback walk repeats the behavior of a full EBML parser.

The parser is covered by unit tests (`ChapterParserTest`): nested `ChapterAtom`, `SeekHead` beyond the window, a secondary `SeekHead`, a walk without `SeekHead`, mojibake and MP4 `chpl`.
