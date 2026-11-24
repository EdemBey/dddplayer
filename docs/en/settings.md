# Settings

## Where things are stored

| Storage | What | Code |
|:---|:---|:---|
| SharedPreferences `global_prefs` | Global app settings | `data/SettingsRepository` |
| Room `ddd_player_db`, table `video_settings` | Settings of a specific video by URI: stereo mode, output, anaglyph, swap, depth, position, duration, IDs of the audio and subtitle tracks | `data/VideoSettings`, `data/AppDatabase` |

Room records older than 30 days are deleted when a playlist is loaded (`cleanupOldSettings`).

`SettingsRepository` is a singleton (`getInstance(context)`). Each setting has a
`get/is...` + `set...` pair in it, and the keys are declared as strings right inside these methods.

## Global settings (main ones)

| Key | Default | Values / meaning |
|:---|:---|:---|
| `resume_mode` | `RESUME_ASK` (0) | 0 ask, 1 always, 2 never |
| `external_position_mode` | `EXTERNAL_POSITION_APPLY` (0) | Position from an Intent: 0 apply immediately, 1 ask |
| `up_button_action` | `UP_ACTION_INFO` (3) | 0 nothing, 1 OSD settings, 2 side menu, 3 info panel, pressing again — side menu (before 1.6 the default was 2) |
| `ok_button_action` | `OK_ACTION_PAUSE_PANEL` (1) | 0 pause, 1 pause + panel, 2 panel |
| `horizontal_swipe_action` | 2 | Horizontal swipe (phone): 0 — none, 1 — seek, 2 — exit or Prev/Next, 3 — right exit, left Next. A swipe does not start from the screen edge (32dp): the system Back is there |
| `show_back_button` | false | Exit arrow in the info panel (touch screens, not focusable from a remote) |
| `hidden_control_buttons` | empty | Hidden buttons of the bottom panel (`speed`, `resize`, `pip`, `quality`, `audio`, `subtitles`). The hidden ones are stored so that new buttons are visible |
| `sub_font`, `sub_size`, `sub_text_color`, `sub_bg_color`, `sub_edge_type`, `sub_edge_color`, `sub_bottom`, `sub_embedded` | as in the standard `SubtitleView` | Subtitle style (`SubtitleStyle`, screen `SubtitleStyleActivity`, applied by `SubtitleStyleOptions.apply`) |
| `gesture_volume_brightness` | true | Vertical swipes: volume on the right, brightness on the left |
| `confirm_exit` | true | Exit on a second Back press within 2 s |
| `auto_pip` | false | Auto-PiP when leaving the screen |
| `live_mode_control` | false | Live mode (see [playlists.md](playlists.md)) |
| `show_info_on_change` | false | Info panel on playlist item change |
| `show_playlist_index` | true | Numbers in the playlist |
| `saved_playlists` | — | Playlists by link on the start screen (JSON: id, name, url). The former `startup_playlist_uri` is migrated here once and deleted |
| `smb_servers` | — | SMB network folders of the start screen (JSON: id, name, address, user, password, domain). Stored in a separate `smb_prefs` file that is excluded from backups (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`); resetting settings leaves it alone |
| `watch_history` | — | The "Continue watching" history (JSON, up to 30 entries, one per source: a folder or a playlist) |
| `last_channel_uri` | null | Last M3U channel (internal) |
| `clock_transparency` | `CLOCK_OFF` (-1) | -1 off, 0..80 % transparency. Migrates from the old `show_clock` |
| `buffer_display_mode` | `BUFFER_DISPLAY_OFF` (0) | 0 off, 1 in the panel, 2 next to the clock (and in the info panel) |
| `target_buffer_mb` | `BUFFER_SIZE_AUTO` (-1) | Network video buffer in MB, the options are limited by the heap |
| `render_api_mode` | `RENDER_API_AUTO` (0) | 0 auto (ES 3.0 if available), 1 force GLES 2.0 |
| `pause_dim_level` | 60 | Dimming on pause, % |
| `seek_step_ms` | 5000 | Seek step for one ←/→ press, ms: 500 (0.5 s), then 1000…60000 |
| `global_resize_mode`, `video_zoom_scale`, `remember_zoom` | 0 (FIT), 115, false | Scale |
| `video_decoder_priority` | same as `decoder_priority` if it was changed, otherwise `EXTENSION_RENDERER_MODE_ON` (HW+) | Priority of the built-in AV1 (dav1d) over the device's decoders, "Video" section |
| `audio_decoder_priority` | same as `decoder_priority` if it was changed, otherwise `EXTENSION_RENDERER_MODE_PREFER` (SW) | Priority of the built-in FFmpeg over the device's decoders, "Audio" section |
| `decoder_priority` | — | The former shared setting; only the default value for the two new ones |
| `tunneling_enabled` | false | Tunneling |
| `default_playback_speed` | 1.0 | Speed at the start of playback; live stream — always 1.00x. Also set by a long press on the speed menu item |
| `custom_playback_speed` | 1.10 | "Custom speed" in the speed menu: a slider 0.25–4.00x with a 0.05 step |
| `hdr10plus_enabled` | true | HDR10+ dynamic metadata; off — it is stripped, the video plays as HDR10 (player restart) |
| `dv_mode` | `DV_MODE_AUTO` (1) | "Dolby Vision mode": 0 "Dolby Vision" (DV decoder), 1 "Auto" (HEVC if the display or decoder does not support DV), 2 "HDR10/HLG" (always HEVC), 3 "Dolby Vision (P7 → P8.1)" — Profile 7 → 8.1 conversion (libdovi; without a DV display or decoder it behaves like "auto"). Migrates from the old `map_dv_to_hevc` (off → 0); the former default 2 is converted to 1 once (flag `dv_mode_auto_default`) |
| `stereo_downmix`, `mix_*` | true, preset 0 | Downmix of multichannel audio to stereo and the mixer coefficients |
| `hd_audio_iec` | false | DTS-HD MA and TrueHD/Atmos as a bitstream through the IEC 61937 192 kHz/7.1 carrier, like Kodi. Restarts the player (`getHardSettingsSignature`) |
| `skip_silence`, `loudness_boost` | false, 0 | Skip silence, volume boost |
| `auto_skip_op`, `auto_skip_ed`, `auto_skip_recap` | false | Auto-skip of the opening, ending, recap (X-DDD-Segments), otherwise only the button. The "Segments" category (`section_segments`). The old shared `auto_skip_segments = true` is carried over to all three and deleted |
| `skip_segments_marks` | true | Segment bands on the seek bar; off — an empty list is passed to `ChapterSeekBar` |
| `skip_color_op`, `skip_color_ed`, `skip_color_recap` | 0, 2, 4 | Band color by type: index in `SkipSegmentsLogic.BAND_COLORS` (0 yellow, 1 white, 2 red, 3 green, 4 blue, 5 purple). Chosen from a list of names, applied on return from settings. The old shared `skip_segments_color` (if not 0) is carried over to the opening and ending and deleted |
| `skip_segments_button_seconds` | 0 | For how many seconds after a segment starts the "Skip" button is visible: chosen from a list: 1, 2, 3, 4, 5, 10, 15, 20, 30 s or "Whole segment" (0, the last item); a value outside the list is read as 0 (`SkipSegmentsLogic.buttonSegment`). OK and auto-skip take the whole segment (`active`) |
| `audio_track_memory`, `audio_track_stats` | `AUDIO_MEMORY_OFF` (0), — | Audio track memory: 0 off, 1 within a playlist (the track chosen in this playlist), 2 + between videos (experimental): up to 20 most used tracks (label: name, language, codec, channels), the user changes the order in settings |
| `pref_audio_lang`, `pref_sub_lang` | `""` | `""` — as in the file, `device` — the system language, otherwise a language code |
| `subtitle_auto_mode` | `SUB_AUTO_DEFAULT` (0) | Auto-enabling subtitles when a video loads: 0 as the player decides, 1 off, 2 forced only (first in the audio language), 3 full (in `pref_sub_lang`, otherwise with the default flag, otherwise the first ones). Subtitles saved for the video and a manual choice take priority (`TrackLogic.autoSubtitleIndex`) |
| `frame_rate_matching`, `afr_*` | false, ... | AFR |
| `app_language` | `system` | Interface language |
| `def_output_mode`, `def_anaglyph_type` | ANAGLYPH, RC_DUBOIS | Default output for a new stereo video (the "Default 3D mode" setting; also updated when video settings are saved in the OSD) |
| `stereo_auto_detect` | true | Stereo pair auto-detection. false — the "Do not process" mode: the frame is output as is, stereo is turned on manually in the OSD |
| `pref_filter_<GROUP>` | — | Last filter in a glasses group |
| `calib_<group>_set/leak_a/leak_b`, `anaglyph_lens_swap`, `calibration_version` | — | Anaglyph glasses calibration from the wizard (the group's "Calibration" filter), lens order, a version for re-reading in the player |
| `vr_k1`, `vr_k2`, `vr_scale`, `global_screen_separation_pct` | 0.34, 0.10, 1.2, 0 | VR parameters |

See `SettingsRepository.kt` for the full list.

"App → Reset all settings" (`resetAllSettings`) clears `global_prefs` except user data
(`USER_DATA_KEYS`: history, playlists, last channel, the update check cache; SMB servers live in the separate `smb_prefs`). Settings of individual
videos in Room are not touched, the calibration counter is incremented. The confirmation is a `ChoiceDialog` with focus on "Cancel";
after the reset the settings screen is opened again (and the language is reset if it was chosen manually).
Add a new key holding user data (not a setting) to `USER_DATA_KEYS`.

## Global settings screen

`GlobalSettingsActivity`: on the left a list of categories (`categories`, items `item_settings_category`), on the right
the scrollable settings of the selected category (containers `section_*`, only one is visible).
Categories in order: Playback (`section_playback`), Interface (`section_ui`), Video, Audio, Subtitles, Segments, 3D,
Control (`section_control`), App (`section_app`). Each has its own `ic_settings_*` icon.

Remote control: focus on a category shows its settings immediately; Right — into the settings (to the last
item selected in the category); Left or Back — back to the categories; CH+/CH- and PgUp/PgDn — the neighboring category
from any column; Back in the category list closes the screen. Choosing from a list of values is `ChoiceDialog.show`,
several checkboxes (for example, the control panel buttons) — `ChoiceDialog.showMulti`.

## Player restart

`getHardSettingsSignature()` includes the decoder priority, tunneling, DV→HEVC, downmix with the mixer
and the buffer. When the user returns from settings, `PlayerViewModel.checkSettingsAndRestart()` compares the
signature with the previous one. If it changed, the player is recreated keeping the position, otherwise
`applyLiveSettings()` is called (track languages, skip silence, volume).

## Player OSD settings

The panel at the top (the Up button in OSD mode or a menu item) shows the settings of the current video
(`SettingType`): stereo pair type, output, glasses, render API, filter, Custom parameters, swap, depth,
screen separation, VR distortion and zoom, tracks. The list of available items depends on the mode
(`updateAvailableSettings`). Left/Right change the value (`changeSettingValue`), OK saves
to Room (`saveCurrentSettings`), Back rolls the changes back (`restoreSettings`).

## How to add a global setting

1. `SettingsRepository`: add a getter and a setter (and value constants if needed). The default value
   **must preserve the current behavior.**
2. If an old key changed meaning, write a migration (example — `getClockTransparency`).
3. `GlobalSettingsViewModel`: add a `MutableLiveData` with the initial value from the repository and a change method.
4. `activity_global_settings.xml`: add an item to the container of the right category (`section_playback`, `section_ui`,
   `section_video`, `section_audio`, `section_subtitles`, `section_segments`, `section_3d`, `section_control`, `section_app`) following the
   neighboring ones. Items are focusable: navigation is from a remote. A new category is a new `section_*` container
   plus an entry in `GlobalSettingsActivity.categories`.
5. `GlobalSettingsActivity`: `lateinit` fields, `bindViews`, a handler in `setupListeners` and a subscription
   in `observeViewModel`. For choosing from a list use `showSingleChoiceDialog`.
6. Add the strings (`title` and `summary`/values) to all 5 locales.
7. If the setting requires recreating ExoPlayer, add it to `getHardSettingsSignature()`.
8. Use the setting where it is needed (usually `PlayerViewModel`, `PlayerManager` or `PlayerInputHandler`).
