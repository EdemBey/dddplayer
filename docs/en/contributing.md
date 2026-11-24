# AGENTS.md

Русская версия: [docs/ru/contributing.md](../ru/contributing.md)

A memo for AI agents and new developers. Read it in full before making changes.
Details on the subsystems are in [docs/](../README.md).

## What this project is

DDD Video Player (`top.rootu.dddplayer`) is a video player for Android TV and phones (minSdk 23, targetSdk 34,
compileSdk 36). It plays regular 2D/HDR video and stereo pairs (SBS/HSBS, OU/HOU, Interlaced, 3D(z)Tiled).
Stereo is output as anaglyph (Dubois matrices), Cardboard VR or mono (one eye).
The player also opens playlists from an Intent and M3U/IPTV (groups, live mode, channel switching),
and supports PiP, AFR (display refresh rate matching), a downmix mixer and built-in updates from GitHub Releases.

There is a single module — `:app`. Kotlin, Android Views (no Compose UI), MVVM on LiveData and coroutines.
The player is built on Media3/ExoPlayer 1.9.0, networking goes through OkHttp 5. Video settings are stored in Room,
global ones in SharedPreferences, posters are loaded by Coil 2. The ffmpeg-audio and AV1 decoders and the native Dolby Vision P7 → 8.1 conversion (the `dovi` module, built
only with `-Pdovi=true`) are included as AARs from `app/libs`.

## Build and checks

```bash
./gradlew testDebugUnitTest      # unit tests (JUnit + Robolectric)
./gradlew assembleDebug          # build the APK
./gradlew assembleRelease        # release (minify is off)
```

- JDK 17 and the Android SDK (compileSdk 36) are required. The app version is taken from git:
  `versionCode = git rev-list --count HEAD`, `versionName = git describe --tags --dirty`.
  So the full history is needed (`fetch-depth: 0`), and in a shallow clone the version will be wrong.
- CI: `.github/workflows/android-build.yml` on push/PR to `main` runs the unit tests and builds `assembleDebug`,
  the APK is uploaded as the `dddplayer-debug-apk` artifact (if tests fail — the `unit-test-reports` reports).
- Release: `.github/workflows/release.yml` runs on a `vX.Y.Z` tag (`git tag v1.2.5 && git push origin v1.2.5`).
  It builds `assembleRelease`, signs the APK with a key from a private repository (secrets `KEYSTORE_*`,
  `RELEASE_SIGN_*`) and publishes a GitHub Release with `app-release.apk`. The built-in updater
  (`UpdateManager`) takes the latest non-draft and non-prerelease release and the first `.apk` in it.
  Signing is configured in `app/build.gradle.kts` from environment variables, without them the release is built unsigned.
- **Tests:** `app/src/test` holds JVM unit tests. Pure logic is tested with JUnit, code with Android APIs
  (`Uri`, `SharedPreferences`, `org.json`, `SystemClock`) — under `RobolectricTestRunner`
  (`src/test/resources/robolectric.properties`: SDK 34 and a plain `Application` instead of `App`).
  The `SettingsRepository`/`AppDatabase` singletons are reset in tests via reflection (see `SettingsRepositoryTest`).
  Cover new logic (parsers, calculations, settings and their migrations) with tests. There are no UI tests.
- If the Android SDK is unavailable (a cloud sandbox), tests and compilation are checked only by CI. So:
  - reread your own diff and check imports, property initialization order and resource references;
  - check that every `R.string.*`, `R.id.*`, `R.layout.*` and `R.drawable.*` exists;
  - do not push blindly: one verified change is better than three.

## Code map

```
app/src/main/java/top/rootu/dddplayer/
├── App.kt                      Application: CrashHandler, locale
├── data/                       SettingsRepository (SharedPreferences + Room), VideoSettings, AppDatabase
├── model/                      MediaItem, SubtitleItem, StereoInputType/OutputMode, ResizeMode,
│                               AspectRatioMode, PlaybackSpeed, MenuItem
├── player/                     PlayerManager (ExoPlayer, OkHttp, sources, M3U probe),
│                               NetworkBufferLoadControl, PrefetchingDataSource, ParsingDataSource
├── logic/                      Pure logic: M3uParser, TrackLogic, AnaglyphLogic, AudioMixerLogic,
│                               Matroska/Mp4/UnifiedMetadataReader, SettingsMutator, StereoFormatLogic, DeviceInfoLogic,
│                               UpdateManager
├── renderer/                   StereoGLSurfaceView, StereoRenderer, ShaderSource (GLSL ES 2.0/3.0)
├── viewmodel/                  PlayerViewModel (the center of everything), AnaglyphDelegate, SettingsViewModel,
│                               GlobalSettingsViewModel, UpdateViewModel
├── ui/                         PlayerActivity (Intent, PiP, result), PlayerFragment (player screen),
│                               GlobalSettingsActivity, FileBrowserActivity (files), DeviceInfoActivity (diagnostics),
│                               VrConfigActivity, CrashActivity
│   ├── controller/             PlayerUiController (views), PlayerInputHandler (remote),
│   │                           PlayerTouchHandler (gestures), PlayerTimerController (timers, clock)
│   ├── adapter/                RecyclerView adapters for the menu/playlist
│   └── wiget/                  OutlineTextView (clock with an outline), CenterLayoutManager
└── utils/                      IntentUtils (Intent parsing), MediaFormatHelper, StereoTypeDetector,
                                DuboisMath, CardboardParamsParser, CrashHandler, LocaleUtils, DeviceInfoCollector, afr/*
```

The biggest files: `PlayerViewModel.kt`, `PlayerFragment.kt`, `PlayerUiController.kt`,
`GlobalSettingsActivity.kt` and `PlayerManager.kt`. Read them in parts by grepping for function names.

## Key flows (brief)

1. **Startup:** `PlayerActivity.onCreate` parses the Intent (`IntentUtils.parseIntent`) and calls
   `PlayerViewModel.loadMedia`. A launch from the launcher opens the `FileBrowserActivity` start screen:
   "Continue watching", playlists by link (`forceM3uProbe = true`), storages, SMB and DLNA.
2. **Loading:** `loadMedia` checks a single `.m3u`/`.m3u8` link via `PlayerManager.expandM3u`.
   The link is downloaded once, HLS (`#EXT-X-`) is handed to the player from `PrefetchStore`.
   Then `loadPlaylist` (VM) passes the list to `PlayerManager.loadPlaylist`, which builds the `MediaSource`s.
3. **Output:** 2D goes straight to a `SurfaceView` (needed for HDR). Stereo goes through `StereoGLSurfaceView` → `StereoRenderer`
   (`SurfaceTexture`/`samplerExternalOES`, ES 3.0 shaders with a fallback to ES 2.0).
   Surface switching is done by `PlayerUiController.setSurfaceMode`.
4. **UI state:** `PlayerViewModel` exposes LiveData, `PlayerFragment.observeViewModel` renders it.
   One-shot events are wrapped in `Event<T>` or reset to null.
5. **Settings:** global ones live in `SettingsRepository` (SharedPreferences), and per-video ones —
   in Room `VideoSettings` by URI (stereo mode, depth, position, tracks).
   When "hard" settings change (`getHardSettingsSignature`) the player is recreated, the others are applied on the fly.

In detail: [docs/en/architecture.md](architecture.md), [docs/en/playback.md](playback.md),
[docs/en/playlists.md](playlists.md), [docs/en/rendering.md](rendering.md),
[docs/en/settings.md](settings.md), [docs/en/intent-api.md](intent-api.md).

## Project rules (mandatory)

- **Do not break the current playback behavior.** Add new functionality behind an option,
  and choose defaults so that nothing changes for existing users.
  Examples from the project history: PiP is off by default, live mode is off too,
  an external position is applied immediately by default.
- **TLS trust-all in `PlayerManager` is intentional.** On old Android the root certificates are outdated,
  and a video with an untrusted certificate is better than a video that does not open.
  Do not "fix" it and do not raise it as a review comment.
- **The buffer in MB** (`NetworkBufferLoadControl`) applies only to network video.
  Local files and the "Auto" mode use the old time-based parameters (15–50 s).
  The size is always limited by the heap: `maxBufferBytesForHeap()`, reserve `clamp(heap/2, 64 MB, 160 MB)`.
- **M3U:** HLS is rejected by any `#EXT-X-` tag. There is no repeated request: the probe response
  is reused once via `PrefetchStore` (TTL 30 s), otherwise one-time tokens are lost.
- **The last channel** is restored only for an M3U playlist and only by exact URI.
- **Strings:** every new string is added to all 5 locales: `values` (en), `values-ru`, `values-uk`,
  `values-be`, `values-zh-rCN`. Hardcoding text in the UI is not allowed.
- **Documentation** exists in English (docs/en) and Russian (docs/ru); update both when you change one.
- **Licenses:** a new library in the APK or code ported from another project is added
  to `OpenSourceLicenses` (the "App → Licenses" screen), and ported code also gets a link to the source in the file header.
- **Code comments are written in Russian**, short and to the point, in the style of the surrounding code.
- **Settings migrations:** if the meaning of a key changes, add a migration to `SettingsRepository`
  (example: `show_clock = false` turns into `clock_transparency = CLOCK_OFF`).
  Do not reuse an old key with a new meaning.
- A new setting must go the whole way: a getter and setter in `SettingsRepository`, `GlobalSettingsViewModel`,
  a view and handler in `GlobalSettingsActivity` and `activity_global_settings.xml`, strings in all locales.
  If it needs a player restart, add it to `getHardSettingsSignature()`. See [docs/en/settings.md](settings.md).

## Common pitfalls

- **Property initialization order in `PlayerUiController`.** Properties are initialized top to bottom.
  If a property touches `controlsView` and other fields declared below, the build fails with
  `Variable '...' must be initialized`. Declare new fields after the ones they depend on.
- **LiveData:** on the main thread use `.value`, from coroutines on IO and from callbacks of other threads — `postValue`.
  Remember that `postValue` is asynchronous, and you cannot read `.value` right after it.
- **The Media3 `MediaItem`'s `mediaId` equals `MediaItem.uuid`** from the project model. It is used to match
  the current item, the playlist, groups and zapping. `absoluteIndex` is the index in the full playlist
  (without the group filter).
- **Dialog callbacks** keep in fields, do not capture them in a lambda once when creating the dialog.
  Otherwise stale references will be called after reopening (such a bug already happened in the playlist).
- **`isRecoveringFromError`:** when reloading after an IO error, `onMediaItemTransition` arrives for the same video.
  At that moment you must not reset the retry counter and the video settings.
- **The GL thread.** `StereoRenderer` methods are called from the UI. Uniforms are applied in `onDrawFrame`,
  and a program switch is done with the `shouldReloadShaders` flag. Do not call GLES outside the GL thread.
- **ES 3.0:** the context is created as ES 3 if the device supports it. If the ES 3.0 shaders failed to build
  or `GL_OES_EGL_image_external_essl3` is missing, the renderer falls back to ES 2.0 and reports
  `onEs3ShadersUnavailable`. Make shader changes **in both versions** in `ShaderSource.kt`.
  The "GLES 2.0" mode creates an ES 2.0 context entirely, this is a workaround for problematic drivers. ES 3.0 → 2.0 shaders
  switch on the fly, and back (from an ES 2.0 context) only after restarting the player screen.
- **Intent keys** are read only by `IntentUtils`. If you change them, update README.md, README_RU.md, docs/en/intent-api.md and docs/ru/intent-api.md.

## Git and PRs

- Work in your own branch, make PRs to `main`. Force-push to shared branches is forbidden: to pull in `main`,
  do a merge.
- Commit messages follow Conventional Commits in English: `type(scope): summary`, where type is
  `feat`/`fix`/`refactor`/`docs`, and scope is `player`/`ui`/`buffer`/`live`/`intent`/`render`/...
  If a commit closes an issue, the number goes at the end: `feat(ui): forced aspect ratio (#17)`.
  One logical change — one commit.
