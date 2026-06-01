# Nebula

A GPU-accelerated nebula fly-through screensaver for Android TV. Vibe coded with Claude Sonnet 4.6 and Opus 4.8.

## What it looks like

A deep-space nebula fly-through rendered entirely in GLSL on the GPU. Wispy violet/indigo filaments drift through a starfield as you continuously zoom inward — new detail perpetually emerging from the center. Runs smoothly on a NVIDIA Shield.

Designed for OLED displays — the majority of pixels remain black at any given moment, with filaments and hot spots occupying a minority of the frame. All elements are in continuous motion.

https://github.com/user-attachments/assets/34a24eed-c80d-4c38-893d-c32da806e17a

## Technical approach

- **Single full-screen quad** — all rendering happens in one fragment shader, zero CPU geometry work per frame
- **Zero-contour filaments** — `exp(-|sfbm(p)| * k)` produces a narrow brightness spike wherever smooth FBM crosses zero; five independent noise fields at different scales and offsets create an overlapping network of thin threads against true black, with per-filament color variation (violet, indigo-blue, magenta-rose) based on which field dominates at each pixel
- **Scale-space fractal zoom** — rotation applied after scaling ensures `pB(t=1) = pA(t=0)` exactly at every octave boundary; new detail continuously elaborates on visible structure with no position jumps, resets, or crossfade artifacts
- **Three-phase star system** — staggered radial zoom layers with symmetric smoothstep fade-in/out; any single layer's reset is covered by the other two
- **GLES 2.0** — compatible with any Android TV device; `highp` precision throughout prevents coordinate overflow artifacts at deep zoom levels
- **Shared noise computation** — all five FBM values computed once per pixel and reused for both filament brightness and color, halving noise evaluation cost vs naïve implementation
- **Burn-in safe** — dual-axis coordinate rotation plus continuous inward zoom guarantees no pixel holds a static value; filaments, hot spots, and stars are all in perpetual motion

## Install

Download `Nebula.apk` from [Releases](../../releases) and sideload via ADB:

```bash
adb install Nebula.apk
```

Set as default screensaver:

```bash
adb shell settings put secure screensaver_components com.nebula/.NebulaDream
adb shell settings put secure screensaver_default_component com.nebula/.NebulaDream
adb shell settings put secure screensaver_enabled 1
```

Set screensaver start time (milliseconds):

```bash
# 5 minutes
adb shell settings put system screen_off_timeout 300000
```

Set screen power off time (milliseconds):

```bash
# 20 minutes
adb shell settings put secure sleep_timeout 1200000
```

Trigger immediately for testing:

```bash
adb shell am start -n com.android.systemui/.Somnambulator
```

## Build from source

No Android Studio required. The checked-in build script compiles the Java source, packages the existing manifest, zipaligns the APK, and writes `Nebula.apk`.

On macOS, the easiest setup is Homebrew plus the Android command-line tools. `sdkmanager` needs JDK 17 or later:

```bash
brew install --cask temurin@17
brew install --cask android-commandlinetools

export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$HOME/Library/Android/sdk"

yes | sdkmanager --sdk_root="$HOME/Library/Android/sdk" \
    "platform-tools" \
    "platforms;android-23" \
    "build-tools;30.0.3"
```

Build tools `30.0.3` are known to work because they still include `dx`.

```bash
ANDROID_HOME="$HOME/Library/Android/sdk"
./build.sh
```

You can also point the script at explicit SDK tools:

```bash
ANDROID_JAR="$HOME/Library/Android/sdk/platforms/android-23/android.jar" \
AAPT="$HOME/Library/Android/sdk/build-tools/30.0.3/aapt" \
DX="$HOME/Library/Android/sdk/build-tools/30.0.3/dx" \
ZIPALIGN="$HOME/Library/Android/sdk/build-tools/30.0.3/zipalign" \
APKSIGNER="$HOME/Library/Android/sdk/build-tools/30.0.3/apksigner" \
./build.sh
```

By default, the script creates `debug.keystore` and debug-signs `Nebula.apk`. To release-sign it, provide a keystore:

```bash
RELEASE_KEYSTORE=/path/to/release.keystore \
RELEASE_KEY_ALIAS=release \
RELEASE_KEYSTORE_PASS=secret \
RELEASE_KEY_PASS=secret \
./build.sh
```

For an unsigned APK:

```bash
SIGNING_MODE=unsigned ./build.sh
```

Install a locally built APK:

```bash
adb install --no-incremental -r Nebula.apk
```

If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed copy was signed with a different key. Uninstall it first, then install the new build:

```bash
adb uninstall com.nebula
adb install --no-incremental Nebula.apk
```

## Compatibility

- Android TV 5.0+ (API 21+), OpenGL ES 2.0
- Tested on NVIDIA Shield 2017 (Tegra X1, Android 11)
- HDR10 displays: colours are tuned for OLED — deep blacks with saturated violet highlights

## License

MIT
