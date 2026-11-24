# Video output (`renderer/`)

## Two surfaces

`player_fragment.xml` contains both surfaces. The visible one is chosen by `PlayerUiController.setSurfaceMode(isStereo)`.

| Mode | Surface | Why |
|:---|:---|:---|
| 2D (`StereoInputType.NONE`) | `SurfaceView` inside `AspectRatioFrameLayout` | Direct output: HDR/Dolby Vision, tunneling, minimal load |
| Stereo | `StereoGLSurfaceView` → `StereoRenderer` | The shader splits the stereo pair and outputs anaglyph, VR or a single eye |

When the mode changes, `PlayerFragment` attaches the needed surface to the player again
(`attachSurfaceToPlayer`, `onSurfaceReady`). In 2D the scale (`ResizeMode`, zoom) and the forced
aspect ratio (`AspectRatioMode`: AUTO, 4:3, 16:9, 1.85, 2.35, 2.39) are applied
to `AspectRatioFrameLayout`.

## StereoRenderer

- Runs on the GL thread, `RENDERMODE_WHEN_DIRTY` mode. Frames arrive through `SurfaceTexture.onFrameAvailable`
  and are updated in `onDrawFrame` (`updateTexImage` and the texture matrix).
- The setters (`setInputType`, `setOutputMode`, `setAnaglyphMatrices`, `setDepth`, `setDistortion`, ...)
  are called from the UI. They only change fields and call `requestRender()`, and the uniforms are applied
  in `updateShaderUniforms()` inside `onDrawFrame`.
- `u_inputType` and `u_outputMode` are passed as the `ordinal` of the `StereoInputType` and `StereoOutputMode` enums.
  **The order of the enum constants must match the `INPUT_*` and `OUTPUT_*` constants in the shaders.**
  If you add a value, add it to the end of the enum and to both shaders.
- Depth (`u_depth`) is a shift in pixels normalized to the width of a single frame (`setSingleFrameDimensions`).
  The frame size is computed by `PlayerViewModel.calculateFrameSize` from the stereo pair type.

### ES 3.0 and ES 2.0

- `PlayerFragment` creates an ES 3 context if the device supports ES 3.0 (`reqGlEsVersion >= 0x30000`)
  and the "GLES 2.0" mode (`render_api_mode`) is not selected in settings. Otherwise an ES 2 context is created.
- `ShaderSource` contains two versions of the shaders: `VERTEX_SHADER`/`FRAGMENT_SHADER` (ES 2.0) and
  `VERTEX_SHADER_300`/`FRAGMENT_SHADER_300` (ES 3.0). The geometry is the same. ES 3.0 uses `highp`
  and, for matrices computed in linear RGB, blending in linear space (`u_linearBlend`, exact
  sRGB formulas via `pow`: approximations gave an error of up to 10 levels of 8 bit).
- Fallback: if `GL_OES_EGL_image_external_essl3` is missing or the ES 3.0 shader failed to build, the renderer switches to
  ES 2.0 and calls `onEs3ShadersUnavailable`. After that the render API choice item in the OSD is hidden.
- `setRenderApi(forceLegacy)` switches the shaders on the fly (`shouldReloadShaders`). From an ES 2 context
  ES 3.0 cannot be enabled on the fly: the method returns false, and the user sees a restart toast.
- **Make every change to shader logic in both versions.**
- HDR in stereo mode is not supported: GL output is SDR. This is a deliberate decision,
  because the gain does not pay for the complexity.

## Anaglyph

- The types (`StereoRenderer.AnaglyphType`) are split into glasses groups: Red-Cyan, Yellow-Blue, Green-Magenta and Red-Blue
  (Mono only). Each group has the variants Dubois, Half Color, Color, Mono, Custom (RC also has Optimized).
- The 3×3 matrices for the left and right eye come from `AnaglyphLogic.calculateMatrix`: these are presets or
  `DuboisMath.calculate` for Custom (hue and color leakage of the glasses, calculated in linear RGB).
- Linear blending (`usesLinearMatrix`) is applied to Dubois, Optimized and Custom. Mono and Half Color
  are built on Rec.601 luma weights for gamma values, and Color copies channels, so their blending
  stays in gamma.
- The last selected filter in each glasses group is stored in `pref_filter_<GROUP>`.

## Cardboard VR

- `OUTPUT_CARDBOARD` outputs side-by-side with barrel distortion correction: `u_k1`, `u_k2` and the scale
  `u_distortScale`. The inter-lens distance is set through `u_screenSeparation`.
- The VR parameters are stored in `vr_k1`, `vr_k2`, `vr_scale` and `global_screen_separation_pct` (`AnaglyphDelegate`).
  Importing a profile from a Cardboard QR code (`google.com/cardboard/cfg?...`) is done by `VrConfigActivity`
  with `CardboardParamsParser`.

## Stereo format names

Everywhere (menu, info panel badge, documentation) the same abbreviations are used: SBS/HSBS — horizontal
stereo pair full/anamorphic (Half), OU/HOU — vertical (Over-Under) full/anamorphic, Interlaced,
3D(z)Tiled. In code the vertical stereo pair is still `StereoInputType.TOP_BOTTOM`: the enum name is stored
in Room (`VideoSettings.inputType`), and renaming it would break saved settings.
Half or Full is determined by `StereoFormatLogic.isHalfFrame` from the proportions of a half frame, and the
size of one eye depends on it too (`PlayerViewModel.calculateFrameSize`).

The info panel shows the stereo format as a text badge (`StereoFormatLogic.badgeLabel`), for stereo only:
`3D (HSBS)`, `3D (OU) RL` when the eyes are swapped, `2D (HOU) L` when a single eye is output (the letter is the source eye
on screen, taking the swap into account).

## Stereo pair auto-detection

`StereoTypeDetector.detect(format, uri)` first looks at `Format.stereoMode` from the container, then
searches the file name for markers (`sbs`, `half-sbs`, `lr`, `side-by-side`, `ou`, `tb`, `over-under`, `interlace`,
`3dz-tiled`, ...). Auto-detection fires only if the stereo mode is not set yet
(`inputType == NONE` after loading the saved settings), and shows a toast.

## Calibration wizards (`ui/calibration`)

`CalibrationWizardActivity` (modes `vr` and `anaglyph`) uses the same `StereoRenderer` as the player.
The test pictures (`CalibrationPatterns`) are drawn programmatically, with no files and no licenses, via `Canvas` straight
onto the renderer's input surface (`setInputBufferSize`), into an HSBS 1920×1080 frame (an anamorphic horizontal stereo pair). So the anaglyph and distortion
in the wizard are exactly the same as when watching.

Each step starts with an instruction card with the buttons Back / Adjust (on a step without parameters —
View) / Next. In the adjust or view mode: ◀ ▶ change the value, ▲ ▼ select the parameter, OK or Back
return to the instruction. On a touch screen: the panel edges change the value, the center opens the instruction, a swipe up or
down selects another parameter.

In VR there are two panels, one per eye. The buttons of the second panel repeat the highlight of the first. The panels are shifted by
∓(separation × screen width), like the lens centers in the shader (`0.25 − separation` and `0.75 + separation`), and clipped to their own
half of the screen. VR subtitles work the same way: each `SubtitleView` sits in its own half container.

- **VR:** intro → screen separation (crosshair) → K1/K2 (grid) → scale (frame) → depth check.
  The result is saved to `global_screen_separation_pct`, `vr_k1`, `vr_k2`, `vr_scale`, that is, into the defaults.
- **Anaglyph:** glasses type and the side of the first color's filter (for example, Red - Cyan, red on the left) →
  left eye → right eye → depth check.
  - A filter passes the light of its own color. The eye behind filter A gets the image encoded in color A,
    so with "red on the right" the eye matrices are swapped.
  - Filters are not ideal: part of the other eye's image leaks through the filter (ghosting). The eye steps (`GHOST`)
    show a gray background through the anaglyph, the letter L to the left eye only and R to the right eye only. The user closes
    one eye and changes "ghosting suppression" until the other letter is least noticeable, ideally
    not visible at all (a shadow darker than the background — too little, lighter — too much). The value is the filter leakage (level 0..70 on the sRGB scale, leakage = linear luminance).
    Measuring the leakage by color matching (scales, rectangles) was tried: through a red filter cyan
    looks almost white, so there is nothing to compare. A disappearing letter is clear at once.
  - The model (`DuboisMath.calculateWithCrosstalk`): classic Dubois for ideal filters, then from the channels of
    its own color the other eye's image that passed through the filter with a leakage share is subtracted ("anti-shadow"),
    and the white balance is restored (several iterations). Suppression works while the channels do not go to 0:
    on mid and light tones the shadow disappears, on dark ones it remains. Without leakage the result is classic Dubois.
    Initial values for Red - Cyan: red 35, cyan 15 (`AnaglyphLogic.defaultCalibration`).
  - The check scene is a stereo pair with a small parallax (up to ~1 % of the width): a grid behind the screen, a square at screen
    level, a row of circles from "behind the screen" to "in front of the screen". In VR the parallax is 1.5 times larger.
  - The result is a separate filter **"Calibration"** (`RC/YB/GM_CALIBRATED`, added to the end of the enum so as not to shift
    the saved ordinals), and the calibration is stored in `SettingsRepository.get/setAnaglyphCalibration(group)`.
    The filter appears first in the group's filter list only if a calibration exists, and is selected by default
    (`def_anaglyph_type`, `pref_filter_<GROUP>`). The matrices are computed by `AnaglyphLogic.calculateCalibrated`.
    The built-in filters (Dubois and others) and Custom do not change.
  - The filter side (`anaglyph_lens_swap` = the first color's filter on the right) is global: in this case `PlayerFragment`
    swaps the eye matrices. Red - Blue has only black-and-white Mono, so the eye steps are skipped.
- **Reset** (Settings → 3D and VR → Reset calibration) deletes the saved values and the VR parameters. The "Calibration" filter
  selected by default is replaced with the group's built-in filter. If a video's settings still hold the
  "Calibration" filter, the built-in filter's matrices are used for it.
- After saving or resetting, `calibration_version` is incremented. The player re-reads the parameters in `onResume`
  (`PlayerViewModel.reloadCalibrationIfChanged`).
