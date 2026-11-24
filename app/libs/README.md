# Prebuilt native libraries (AAR)

This folder contains prebuilt AARs that `app/build.gradle.kts` picks up automatically (`fileTree("libs") { include("*.aar") }`):

| File | Module | Purpose |
| :--- | :--- | :--- |
| `lib-decoder-ffmpeg-release.aar` | `androidx.media3:lib-decoder-ffmpeg` | Software audio decoders (AC3/E-AC3/DTS/TrueHD and more) |
| `lib-decoder-av1-release.aar` | `androidx.media3:lib-decoder-av1` | Software AV1 video decoder (dav1d) |
| `lib-dovi-release.aar` | `:dovi` (this repository) | Dolby Vision Profile 7 → 8.1 conversion (`libdovi_bridge.so`: JNI bridge + libdovi) |

How to rebuild them: [docs/en/native-libs.md](../../docs/en/native-libs.md).

Как пересобрать: [docs/ru/native-libs.md](../../docs/ru/native-libs.md).
