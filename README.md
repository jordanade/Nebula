# Nebula

A GPU-accelerated nebula fly-through screensaver for Android TV. Vibe coded with Claude Sonnet 4.6 and Opus 4.8.

## What it looks like

A deep-space nebula fly-through rendered entirely in GLSL on the GPU. Voluminous, self-shadowed clouds of gas — sculpted with embossed relief and a palette centred on deep violet and indigo, with magenta and cool-blue accents — billow past against a glittering star field as you continuously zoom inward, new detail perpetually emerging from the center. Dramatic bright ridges give way to deep black voids; dense clusters of stars glow as hazy distant galaxies, and the brightest cores throw occasional HDR flares with cross-hatched diffraction spikes. Runs smoothly in HDR on a NVIDIA Shield.

Designed for OLED and HDR displays — deep black voids between dramatic nebula ridges, with the brightest cores driven into real panel headroom. All elements are in continuous motion.

https://github.com/user-attachments/assets/ada655dc-3a19-452c-ad3c-feaa232a154f

## Technical approach

- **Single full-screen quad** — all rendering happens in one fragment shader, zero CPU geometry work per frame
- **Voluminous cloud body** — soft, filled gas masses from smooth FBM (not turbulence, whose `abs()` creases read as hollow bubbles); zero-contour filaments (`exp(-|sfbm(p)| * k)`) sit on top as minor bright accents rather than the main form
- **Volumetric self-shadow** — a short march toward the light through the cloud density accumulates optical depth, so the lit side of each mass stays bright while the far side falls into shadow — a directional gradient across a filled body, the real 3D-cloud cue
- **Embossed relief** — the density gradient drives surface normals for sculpted light/dark relief, read from a band-limited macro density so fine texture never aliases into shimmer
- **Static domain warp** — a position-based (non-animated) noise warp bends straight filaments into curled, billowing lobes: shape without motion
- **Large-scale form** — a low-frequency mask carves bright filament ridges against deep black voids for sculptural composition rather than uniform coverage; density is weighted toward the ridges over the smooth fill
- **Spatial temperature** — a four-stop palette (orange → magenta-pink → violet → blue) centred on violet/indigo, with a gentle drifting "lit front" that illuminates one region like nearby stars while keeping the bright masses in the purple family
- **Scanned framing** — the continuous zoom drifts on a bounded, quasi-periodic orbit centred on a dense, high-contrast region of the noise field, so the composition always holds real structure and never pans off into empty space
- **Galaxy haze + HDR flares** — dense star clusters glow as hazy distant galaxies coincident with the star layer; the brightest cloud cores throw occasional, aperiodic HDR flashes with cross-hatched diffraction spikes
- **Anti-aliased FBM** — mottle octaves fade out as they approach the pixel Nyquist rate (`fwidth`), so rich texture can be stacked without aliasing through the relief gradient
- **Scale-space fractal zoom** — rotation applied after scaling ensures `pB(t=1) = pA(t=0)` exactly at every octave boundary; new detail continuously elaborates on visible structure with no position jumps, resets, or crossfade artifacts
- **Three-phase star system** — staggered radial zoom layers with symmetric smoothstep fade-in/out; any single layer's reset is covered by the other two
- **HDR output** — opt-in FP16 scRGB-linear surface with feature detection and automatic SDR fallback; highlight cores extend into panel headroom while mid-tones keep the tuned SDR look
- **GLES 2.0** — compatible with any Android TV device; `highp` precision throughout prevents coordinate overflow artifacts at deep zoom levels
- **Burn-in safe** — dual-axis coordinate rotation plus continuous inward zoom guarantees no pixel holds a static value; clouds, stars, and flares are all in perpetual motion

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
