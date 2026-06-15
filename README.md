# Nebula

A GPU-accelerated nebula fly-through screensaver for Android TV. Vibe coded with Claude Sonnet 4.6, Opus 4.6/4.8, Fable 5, and GPT 5.5.

## What it looks like

A deep-space nebula rendered in real-time volumetric raymarching on the GPU. The camera flies through translucent clouds of emissive gas — sculpted by a tiling 3D noise field with Worley erosion and lit by gradient relief, shell halos, and ionization-front rims — in a palette centred on deep violet and indigo, with magenta and cool-blue accents. Foreground masses occlude background stars with true depth; macro dust forms add broad light/dark structure without screen-space stencil shadows; dense star clusters glow as pointillistic distant galaxies, and the brightest stars throw subtle aperiodic HDR flares with four-point diffraction spikes. A split-resolution pipeline keeps stars pin-sharp at native panel resolution while the gas runs at a tunable lower resolution. Runs at a steady 20 fps in HDR on a NVIDIA Shield.

Designed for OLED and HDR displays — deep black voids between dramatic nebula ridges, with the brightest cores driven into real panel headroom. All elements are in continuous motion.

https://github.com/user-attachments/assets/57fb08e1-4646-4891-99c6-fbb6daee7a99

## Technical approach

- **Volumetric raymarching** — a 46-step ray march through a tiling 3D noise field renders true-depth gas clouds; the camera flies through the nebula, with foreground masses occluding background stars via a transmittance channel
- **Tiling 3D noise texture** — a 64³ RGBA texture (R = 3-octave value FBM, G = inverted Worley) generated once on the CPU; replaces all analytic noise with texture fetches for a ~6× speedup
- **Coverage × billow density** — a low-frequency coverage gate carves large cloud masses, eroded by two scales of Worley detail for organic cauliflower edges
- **Three-stage density LOD** — near gas: full 4-fetch erosion; mid-range: coarse erosion only; far-field: 2 low-frequency fetches. Triples effective march depth while keeping frame time flat
- **Emissive gas** — the nebula glows (emission nebula, not sunlit cloud), tinted by a four-stop palette (orange → magenta → violet → blue) driven by a drifting large-scale region field
- **Gradient relief lighting** — density-gradient normals give near layers directional lit/shadow sculpting; mid and rear layers stay as pure soft glow
- **Ionization-front rims + shell halos** — bright edges fire at gas boundaries while a softer shell halo gives near clouds readable light/dark sides without drawing a visible screen-space stencil
- **Macro dust fronts** — large foreground dust forms are driven by volumetric noise rather than a clean front mask, so they add broad shape and star occlusion without obvious curved shadow artifacts
- **Camera-origin fade** — the ray march starts past the camera-origin slab and fades in quickly, preventing a cloud exactly on the camera from filling the whole frame with flat colour
- **Ultra-transparent extinction** — very low opacity so stars shine through all masses; the deep march rarely hits early-out, giving a consistent frame time
- **Split-resolution pipeline** — gas is raymarched into a low-res FBO (render-scale setting); stars, diffraction spikes, and galaxy haze are drawn at native panel resolution in a full-res composite pass
- **Spatial dither** — breaks far-field 8-bit texture banding; fades out for near gas where dense sampling already hides quantisation
- **Galaxy haze + HDR flares** — dense star clusters glow as hazy distant galaxies coincident with the star layer; the brightest stars throw small, aperiodic HDR flashes with four-point diffraction spikes
- **Scale-space fractal zoom** — rotation applied after scaling ensures `pB(t=1) = pA(t=0)` exactly at every octave boundary; new detail continuously elaborates on visible structure with no position jumps, resets, or crossfade artifacts
- **Three-phase star system** — staggered radial zoom layers with symmetric smoothstep fade-in/out; any single layer's reset is covered by the other two
- **HDR output** — opt-in FP16 scRGB-linear surface with feature detection and automatic SDR fallback; highlight cores extend into panel headroom while mid-tones keep the tuned SDR look
- **GLES 3.0** — required for `sampler3D` / `glTexImage3D`; `highp` precision throughout prevents coordinate overflow artifacts at deep zoom levels
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

- Android TV 5.0+ (API 21+), OpenGL ES 3.0
- Tested on NVIDIA Shield 2017 (Tegra X1, Android 11)
- HDR10 displays: colours are tuned for OLED — deep blacks with saturated violet highlights

## License

MIT
