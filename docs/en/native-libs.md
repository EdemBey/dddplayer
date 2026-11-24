# Prebuilt native libraries (AAR)

This folder (`app/libs`) contains prebuilt AARs that `app/build.gradle.kts` picks up automatically (`fileTree("libs") { include("*.aar") }`):

| File | Module | Purpose |
| :--- | :--- | :--- |
| `lib-decoder-ffmpeg-release.aar` | `androidx.media3:lib-decoder-ffmpeg` | Software audio decoders (AC3/E-AC3/DTS/TrueHD and more) |
| `lib-decoder-av1-release.aar` | `androidx.media3:lib-decoder-av1` | Software AV1 video decoder (dav1d) |
| `lib-dovi-release.aar` | `:dovi` (this repository) | Dolby Vision Profile 7 → 8.1 conversion (`libdovi_bridge.so`: JNI bridge + libdovi) |

**The AARs must be built from the same Media3 version as `media3` in `gradle/libs.versions.toml` (currently `1.9.0`).**
Rebuild them when Media3 is updated. Media3 1.9.0 requires NDK `27.0.12077973`, FFmpeg branch `release/6.0`.

## Dolby Vision conversion (`lib-dovi-release.aar`)

The AAR holds `libdovi_bridge.so`: the JNI bridge used by `DoviBridge` and the static
[libdovi](https://github.com/quietvoid/dovi_tool/tree/main/dolby_vision) built from the official
`quietvoid/dovi_tool` repository (tag `libdovi-3.3.2`, set in `dovi/build.gradle.kts`).
The `:dovi` module is not part of the normal build: the app, the tests and CI use the AAR from this folder and need
neither NDK nor Rust. Without the AAR the app still builds; the "P7 → P8.1" option is then hidden.

Requirements: Android SDK with NDK `27.0.12077973` and CMake `3.22.1` (Android Studio → SDK Manager → SDK Tools),
Rust via [rustup](https://rustup.rs), `git`, `bash` (Linux, macOS or WSL).

```bash
./gradlew -Pdovi=true -PdoviAbis=arm64-v8a,armeabi-v7a,x86_64,x86 :dovi:assembleRelease
```

The task clones `dovi_tool` at the tag into `dovi/build/`, builds `libdovi.a` for each ABI with
`cargo rustc --crate-type staticlib --features capi` (`dovi/build_libdovi.sh`, the Rust targets are added with
`rustup target add`), builds the bridge with CMake and copies the result to `app/libs/lib-dovi-release.aar`.
Commit the AAR. ABIs default to `arm64-v8a,armeabi-v7a`; for an emulator add x86:
`-PdoviAbis=arm64-v8a,armeabi-v7a,x86_64,x86`.

When updating libdovi, change the tag in `dovi/build.gradle.kts` and regenerate
`dovi/src/main/cpp/include/libdovi/rpu_parser.h` for that tag (`cargo install cargo-c`, then
`cargo cbuild --release --features capi` in `dovi_tool/dolby_vision`, the header is under `target/`).

Licenses: libdovi is MIT (© quietvoid); the bridge and `DoviExtractorWrapper` / `DoviConvertingTrackOutput` /
`BufferedTransformingTrackOutput` are ported from [edde746/plezy](https://github.com/edde746/plezy) (GPL-3.0).

## Media3 decoders

The binary ffmpeg extension was built with the following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete build instructions:
 - [decoder ffmpeg](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md)
 - [decoder AV1](https://github.com/androidx/media/blob/release/libraries/decoder_av1/README.md)


To assemble ``.aar``:

```
./gradlew :lib-decoder-ffmpeg:assembleRelease
./gradlew :lib-decoder-av1:assembleRelease
```

### Step-by-step guide

If you are on Linux, it is ideal for building the Media3 SW decoders into an AAR.

This is a step-by-step guide (suitable for Debian/Mint/Ubuntu).

#### Step 0: Preparing the environment

You will need:
1.  **Git** (to download the sources).
2.  **Android NDK** (to compile the C++ code).
3.  **CMake** and **Ninja** (build systems).
4.  **Nasm** (to build dav1d/AV1).
5.  **Meson** (to build dav1d).

Open a terminal and install the required packages:

```bash
sudo apt update
sudo apt install git cmake ninja-build nasm python3-pip
pip3 install meson
```
Check:
```bash
meson --version
```

It should print a version (for example, 1.10.1). If it gave an error,
run in the terminal: `export PATH="$HOME/.local/bin:$PATH"` and check again.
If `meson` still cannot be found, try installing it system-wide via apt (but the version there may be old):
`sudo apt install meson`. But it is better to use the pip version with the fixed PATH.

#### Step 1: Downloading the Media3 source code

We need to download the `androidx.media3` sources of version `1.9.0`, which we use in the project.

1.  Create a working folder:
    ```bash
    mkdir media3-build
    cd media3-build
    ```

2.  Clone the Media3 repository:
    ```bash
    git clone https://github.com/androidx/media.git
    cd media
    ```

3.  Switch to the tag of the needed version (for example, `1.9.0`):
    ```bash
    git checkout 1.9.0
    ```

#### Step 2: Setting up the Android NDK

1.  Download the NDK (**Required** `27.0.12077973` - this is set in `android.ndkVersion` inside Media3).
    The best option is to install NDK 27 (it will be located, for example, in `~/Android/Sdk/ndk/27.0.12077973`):
     *  Open Android Studio -> SDK Manager -> SDK Tools.
     *  Check "Show Package Details".
     *  Find NDK (Side by side) and select version **27.0.12077973**.
     *  Click Apply.

2.  Set the `NDK_PATH` environment variable:
    ```bash
    NDK_PATH="$HOME/Android/Sdk/ndk/27.0.12077973"
    # Check the path!
    ```

3.  Set the host platform:
    ```bash
    HOST_PLATFORM="linux-x86_64"
    ```

4.  Set the ABI version for the native code (it equals minSdk and must not exceed it):
    ```bash
    ANDROID_ABI=21
    ```

5.  Set the following shell variable:
    ```bash
    FFMPEG_MODULE_PATH="$(pwd)/libraries/decoder_ffmpeg/src/main"
    ```

#### Step 3: Building FFmpeg (audio decoders)

1.  Go to the FFmpeg module folder:
    ```bash
    cd "${FFMPEG_MODULE_PATH}/jni"
    ```

2.  Download the FFmpeg sources (branch `release/6.0`!!!):
    ```bash
    git clone git://source.ffmpeg.org/ffmpeg
    cd ffmpeg
    git checkout release/6.0
    cd ..
    ```

3.  Set up the list of audio codecs:
    ```bash
    ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
    ```
    *Note: `dca` is DTS.*

4.  Run the build script (this takes time):
    ```bash
    # We are in libraries/decoder_ffmpeg/src/main/jni
    # cd "${FFMPEG_MODULE_PATH}/jni"
    
    ./build_ffmpeg.sh \
      "$(pwd)/.." \
      "${NDK_PATH}" \
      "${HOST_PLATFORM}" \
      ${ANDROID_ABI} \
      "${ENABLED_DECODERS[@]}"
    ```
    *(Here `${ANDROID_ABI}` = `21` is the minimum API level, `$(pwd)/..` is the path to the module).*

    If the script succeeds, an `android-libs` folder will appear in the `ffmpeg` folder.

#### Step 4: Building AV1 (dav1d)

1.  Go back to the root of the `media` repository:
    ```bash
    cd ../../../../../
    ```

2.  Go to the AV1 module folder:
    ```bash
    cd libraries/decoder_av1/src/main/jni
    ```

3.  Download `cpu_features`:
    ```bash
    git clone https://github.com/google/cpu_features
    ```

4.  Download `dav1d`:
    ```bash
    git clone https://code.videolan.org/videolan/dav1d.git
    ```

5.  Run the build script:
    ```bash
    ./build_dav1d.sh \
      "$(pwd)/.." \
      "${NDK_PATH}" \
      "${HOST_PLATFORM}"
    ```

    If the script succeeds, a `nativelib` folder will appear in the same folder.

#### Step 5: Building the AAR files

Now that the native libraries (`.so` / `.a`) are built, they need to be packed into an Android Archive (`.aar`) with Gradle.

1.  Go back to the very root of the `media` project:
    ```bash
    cd ../../../../../
    ```

2.  Create a `local.properties` file and specify the SDK and NDK paths (if they are not in environment variables):
    ```properties
    sdk.dir=/home/your_user/Android/Sdk
    ndk.dir=/home/your_user/Android/Sdk/ndk/27.0.12077973
    ```

3.  Run the AAR build for FFmpeg:
    ```bash
    ./gradlew :lib-decoder-ffmpeg:assembleRelease
    ```

4.  Run the AAR build for AV1:
    ```bash
    ./gradlew :lib-decoder-av1:assembleRelease
    ```

#### Step 6: Copying into the project

After a successful build the `.aar` files will be in:
*   `libraries/decoder_ffmpeg/buildout/outputs/aar/`
*   `libraries/decoder_av1/buildout/outputs/aar/`

1.  Copy them to the `libs` folder of the `DDD Video Player` project.
2.  Rename them for convenience (for example, `lib-decoder-ffmpeg-release.aar`).

#### Step 7: Updating the project

1.  In `libs.versions.toml` set the `media3` version to exactly the one you compiled (for example, `1.9.0`).
2.  In `build.gradle.kts` make sure the AARs are included and conflicting modules are excluded (if there are any).

Remember that the version the AARs were built from and the `media3` modules must match!
