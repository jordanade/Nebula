# Nebula

A GPU-accelerated nebula fly-through screensaver for Android TV and NVIDIA Shield.

## What it looks like

A deep-space nebula fly-through rendered entirely in GLSL on the GPU. Wispy violet/indigo filaments drift through a starfield as you continuously zoom inward — new detail perpetually emerging from the center. Runs smoothly on a 2017 NVIDIA Shield (Tegra X1).

## Technical approach

- **Single full-screen quad** — all rendering happens in one fragment shader, zero CPU geometry work per frame
- **Ridged FBM** — `1 - |noise|` per octave produces sharp filament ridges rather than smooth blobs; domain-warped for organic curves
- **Scale-space fractal zoom** — two octave-spaced samples of the same noise field crossfaded as zoom progresses; new detail continuously emerges without position jumps or resets
- **Three-phase star system** — staggered radial zoom layers with symmetric fade-in/out; no visible reset ever
- **GLES 2.0** — compatible with any Android TV device; `highp` precision throughout prevents coordinate overflow artifacts
- **Burn-in safe** — compound sinusoidal drift ensures no pixel is ever static; star layers independently drift

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

Set idle timeout (milliseconds):

```bash
# 10 minutes
adb shell settings put system screen_off_timeout 600000
```

Trigger immediately for testing:

```bash
adb shell settings put system screen_off_timeout 3000
# wait 3 seconds, then restore:
adb shell settings put system screen_off_timeout 600000
```

## Build from source

No Android Studio required. Needs `javac`, `dx`, `aapt`, `zipalign`, `apksigner` from the Android SDK build tools and `android-23` platform jar.

```bash
ANDROID_JAR=/path/to/android-23/android.jar
DX=/path/to/build-tools/dx
AAPT=/path/to/build-tools/aapt
ZIPALIGN=/path/to/build-tools/zipalign
APKSIGNER=/path/to/build-tools/apksigner

mkdir -p obj bin

javac -source 8 -target 8 -bootclasspath $ANDROID_JAR \
    -d obj src/com/nebula/NebulaDream.java

$DX --dex --output=bin/classes.dex obj/

$AAPT package -f -F bin/nebula.unaligned.apk \
    -M AndroidManifest.xml -I $ANDROID_JAR
cd bin && zip -j nebula.unaligned.apk classes.dex && cd ..

$ZIPALIGN -f 4 bin/nebula.unaligned.apk bin/nebula.aligned.apk

$APKSIGNER sign --ks your.keystore \
    --out bin/Nebula.apk bin/nebula.aligned.apk
```

## Compatibility

- Android TV 5.0+ (API 21+), OpenGL ES 2.0
- Tested on NVIDIA Shield 2017 (Tegra X1, Android 9)
- HDR10 displays: colours are tuned for OLED — deep blacks with saturated violet highlights

## License

MIT
