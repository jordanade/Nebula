# Nebula HDR screensaver

A GPU-accelerated HDR nebula fly-through screensaver for Android phones and TV, optimized for the NVIDIA Shield. Vibe coded with Claude Sonnet 4.6, Opus 4.6/4.8/5, Fable 5, and GPT-5.5.

## What it looks like

A deep-space nebula rendered in real-time volumetric raymarching on the GPU. The camera flies through translucent clouds of emissive gas, sculpted by a tiling 3D noise field with Worley erosion and lit by gradient relief, shell halos, and ionization-front rims, in a palette centred on deep violet and indigo with warm accents — and the densest cloud hearts ignite into brilliant white-pink star-forming cores driven into HDR headroom. The masses are filamentary rather than billowing: the density lookup is compressed along a slowly-turning axis, so gas gathers into streamers and sheets instead of cauliflower. Dark structure does as much work as light — a connected web of dust lanes carves channels through the masses, and dense foreground dust reaches real opacity, cutting silhouettes out of the star field behind it. The brightest stars light the gas they sit in, throwing a blue-white reflection halo that exists only where there is cloud to scatter it. The palette itself has moods: a very slow seed occasionally pushes whole scenes warm-rose or deep blue for minutes at a time. A faint, grainy milky-way band gives empty sky depth and resolves into star grain where it brightens. Star colors follow the real blackbody sequence — a faint warm sea under rare blue-white brights — with chaotic scintillation-style twinkle; a single star flares into a diffraction burst every half minute or so, and every 20–60 minutes one swells into a nova that fades over half a minute. Tiny inclined galaxies drift among the star layers, with a far rarer showpiece tier several times larger. The flight is not on rails: the CPU evaluates the same noise field the GPU marches, scores the sky ahead, and biases the camera toward richer regions when it is heading somewhere empty. And a session *arrives* — the star field comes up first, then the gas blooms in behind it while the camera closes distance, so the clouds grow rather than merely brighten. A split-resolution pipeline keeps stars pin-sharp at native app-surface resolution while the gas runs at an adaptive lower resolution. Defaults to 40% maximum gas resolution and 25 fps for steady HDR playback, and the star field scales to the surface so it looks right on both wide TV and tall phone displays.

Designed for OLED and HDR displays — deep black voids between dramatic nebula ridges, with the brightest cores driven into real panel headroom. No pixel holds a static value: every element drifts, zooms, or cross-fades continuously.

https://github.com/user-attachments/assets/42083e8e-e0de-4afc-b9fc-fa41bab04d36

## Technical approach

- **Volumetric raymarching** — a 42-step ray march through a tiling 3D noise field renders true-depth gas clouds; the camera flies through the nebula, with foreground masses occluding background stars via a transmittance channel
- **Tiling 3D noise texture** — a 64³ RG8 texture (R = 3-octave value FBM, G = inverted Worley) generated once on the CPU; replaces all analytic noise with texture fetches for a ~6× speedup. RG16F was tried and *doubled* frame cost — twice the texel bytes halves the effective texture cache, and this marcher is cache-bound
- **Coverage × billow density** — a low-frequency coverage gate carves large cloud masses, eroded by two scales of Worley detail for organic cauliflower edges
- **Filamentary anisotropy** — the density lookup is compressed along a single axis, which elongates the sampled features along it: masses become streamers and sheets rather than isotropic lumps, for one dot product and no extra fetch. The axis is frame-uniform and derived on the CPU from a very-low-frequency read of the noise at the camera, so filament direction changes as the camera crosses field domains instead of reading as a fixed comb. Deliberately excludes the coverage gate (whose ~45-unit period against a 48-unit march would put the entire frame inside one blob or outside all of them) and the Worley erosion (detail breaking *across* the filaments is what stops them looking extruded)
- **Dust lanes** — a gaussian trough on the low end of the inverted-Worley channel, i.e. the web of cell boundaries: a connected network of thin sheets, which is the topology real dust lanes have. Free, since it reads the G channel of a fetch the march already makes. Emission is suppressed at every depth so mid and far masses gain dark veining, while extinction applies only in the near field — integrated over the full march an ungated lane term becomes global fog rather than dust
- **Silhouette dust** — large foreground dust forms are driven by volumetric noise rather than a clean front mask, so they add broad shape without curved shadow artifacts, and dense ones reach genuine opacity with a Worley-bitten edge (again from the same fetch), cutting silhouettes out of the star field behind them. Occlusion is cubed rather than squared, so the extra extinction lands on the cores instead of fogging the whole form
- **Reflection nebulosity** — the top ~1% of stars by magnitude throw a blue-white halo, gated on local column opacity so it appears only where there is gas to scatter it (blue because scattering is wavelength-dependent — a colour the four-stop emission palette cannot reach). Sources are picked on the CPU and passed as uniforms, once per star-layer cycle while that layer's fade is still zero, so halos fade in with the layer and are never clipped by a cell boundary. Flares and novas scatter into their surrounding cloud on the same term
- **Three-stage density LOD** — near gas: full 4-fetch erosion; mid-range: coarse erosion only; far-field: 2 low-frequency fetches. Triples effective march depth while keeping frame time flat
- **Emissive gas** — the nebula glows (emission nebula, not sunlit cloud), tinted by a four-stop palette (orange → magenta → violet → blue) driven by a drifting large-scale region field, with slow palette excursions that occasionally push whole scenes warm-rose or deep blue
- **Star-forming cores** — the densest gas hearts bloom toward white-pink through a low-frequency ignition gate and mid-frequency knot structure, riding the HDR chain to peak nits
- **Gradient relief lighting** — density-gradient normals give near layers directional lit/shadow sculpting; mid and rear layers stay as pure soft glow
- **Ionization-front rims + shell halos** — bright edges fire at gas boundaries while a softer shell halo gives near clouds readable light/dark sides without drawing a visible screen-space stencil
- **Camera-origin fade** — the ray march starts past the camera-origin slab and fades in quickly, preventing a cloud exactly on the camera from filling the whole frame with flat colour
- **Near-transparent extinction** — the gas itself is barely opaque, so stars shine through whole masses; only dust cores and lane crossings reach real occlusion, and where they do the ray hits the early-out sooner, which partly funds them
- **Steered camera** — the fly-through was open-loop, so what it flew past was luck, and measured coverage swung 25%–96% frame to frame. The CPU trilinear-samples the same 64³ buffer the GPU marches (matching GL's texel-centre convention exactly), scores a look-ahead fan of candidate lateral offsets weighted by the marcher's own distance falloff, and biases the camera toward the best. It steers only when the current path scores poorly, so it acts as a *floor* on scene richness rather than a target — always seeking maximum density would trade bare starfield for the flat full-frame wash the emission-contrast curve exists to suppress. Two cascaded lags (a proportional controller feeding a velocity low-pass) keep the correction smooth in velocity as well as position, so a new heading is leaned into over ~25 s
- **Staggered arrival** — one fade over the whole frame dissolved up the finished image; stars now come up over ~3 s and the gas blooms in behind them over ~12 s, so the sky arrives in the order a sky should. The gas start is *delayed* rather than merely slowed, which is what makes the stagger legible — and which yields a few seconds of nothing but the pin-sharp star field
- **Arrival flight** — the camera starts 8 units behind its nominal path and closes the gap over 20 s, so masses ahead grow instead of brightening in place. Note that scaling the sample distance cannot achieve this: the transverse ray spread scales with the same `t`, so the cone sampled at parameter `t` is exactly the normal march's cone at `k·t` — perspective is preserved and the march simply goes deeper. Expressed as a decaying position offset rather than a speed multiplier so it converges to exactly zero and the later path is unchanged
- **Split-resolution pipeline** — gas is raymarched into a low-res FBO (nebula resolution cap setting, 10–100%, default 40%, used as the adaptive upper bound); stars, diffraction spikes, and galaxy haze are drawn at native app-surface resolution in a full-res composite pass
- **Spatial dither** — breaks far-field 8-bit texture banding; fades out for near gas where dense sampling already hides quantisation
- **Galaxy haze + rare HDR flares** — dense star clusters glow as hazy distant galaxies coincident with the star layer; every ~15–50 s one of the brighter stars flares into a 4–8 s HDR diffraction burst with an eight-point spike pattern, an asymmetric fast-rise/slow-decay envelope, and spikes that visibly extend as it peaks
- **Nova** — the rarest event: one of the genuinely brightest stars swells to a brilliant HDR point over ~2 s, holds, then fades over ~25 s sliding white → amber. Drawn as an unclipped screen-space overlay in the composite pass, because anything wider than one star-grid cell cannot be drawn inside `starLayer` — its per-cell evaluation clips it. First one within ~6–18 min of a session, then 20–60 min apart
- **Distant galaxy field** — rare cells of a coarse grid riding the star-zoom layers each hold a tiny inclined galaxy (warm nucleus, cool dust-laned disk) with hashed rotation, inclination, size, and brightness, plus a far rarer showpiece tier on its own coarser grid at 3–4× the size. Both ride a quarter-speed zoom phase: galaxies are effectively at infinity and should show almost no parallax while stars stream past. A per-frame CPU visibility test skips the showpiece layer entirely unless one is actually in frame — *reaching* its 99.65% rejection cost 1.80 ms/frame of coordinate math and hashing on every pixel
- **Distant star stratum** — a third star field on its own slow (0.45×) crossfade cycle, denser and dimmer, filling the gap between the near stars and the galaxies; farther things moving slower is what actually reads as parallax
- **Blackbody star colors** — the star palette runs the real stellar sequence (M orange → B blue-white), with brightness biasing temperature so bright stars skew hot and the faint majority skews warm
- **Anti-aliased sub-pixel stars** — star gaussians are convolved with a ~0.5 px pixel gaussian (variances add, near-energy-conserving), so sub-pixel stars render as steady points instead of shimmering with grid alignment; twinkle is a chaotic product of incommensurate sines, like real scintillation
- **Milky-way band** — a broad grainy luminance floor anchored to the view direction; sprinkle-star density rises inside it so the band resolves into stars where brightest. A meandering dark rift runs along the centreline and star-cloud amplitude varies along its length — a smooth constant-width gaussian was the mathematical tell that it was procedural. The rift and cloud terms are computed identically in the gas and composite passes so grain density and glow stay in register, and a per-seed bulge hotspot means some sessions get the galactic core in view
- **Scale-space fractal zoom** — rotation applied after scaling ensures `pB(t=1) = pA(t=0)` exactly at every octave boundary; new detail continuously elaborates on visible structure with no position jumps, resets, or crossfade artifacts
- **Two-phase star system** — staggered radial zoom layers with symmetric smoothstep fade-in/out; each layer's reset is covered by the other
- **Baked sky layer** — the sprinkle, carpet and band-grain fields are screen-space and static within a ~40 s epoch, so they are rendered once into a full-res FP16 target and sampled thereafter. Double-buffered, so the epoch's burn-in reposition cross-dissolves instead of blinking
- **Per-session random seed** — each screensaver activation randomises the camera path, nebula formation, and star field so no two sessions look alike
- **HDR output** — opt-in FP16 scRGB-linear surface with feature detection and automatic SDR fallback; highlight cores use display-reported headroom while mid-tones keep the tuned SDR look
- **GLES 3.0** — required for `sampler3D` / `glTexImage3D`; `highp` precision throughout prevents coordinate overflow artifacts at deep zoom levels
- **Burn-in safe** — dual-axis coordinate rotation plus continuous inward zoom guarantees no pixel holds a static value; clouds, stars, and flares are all in perpetual motion, and the faint background sprinkles cross-fade between offset fields so they never hold a fixed pixel either

## Install

Download `Nebula.apk` from [Releases](../../releases) and sideload via ADB:

```bash
adb install Nebula.apk
```

Set as default screensaver:

```bash
adb shell settings put secure screensaver_components com.jordanadema.nebula/.NebulaDream
adb shell settings put secure screensaver_default_component com.jordanadema.nebula/.NebulaDream
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
adb shell am start -a android.intent.action.MAIN \
  -c android.intent.category.DESK_DOCK \
  -n com.android.systemui/.Somnambulator
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
    "platforms;android-35" \
    "build-tools;35.0.0"
```

Build tools `35.0.0` are the default and use `d8`.

```bash
ANDROID_HOME="$HOME/Library/Android/sdk"
./build.sh
```

You can also point the script at explicit SDK tools:

```bash
ANDROID_JAR="$HOME/Library/Android/sdk/platforms/android-35/android.jar" \
AAPT="$HOME/Library/Android/sdk/build-tools/35.0.0/aapt" \
D8="$HOME/Library/Android/sdk/build-tools/35.0.0/d8" \
ZIPALIGN="$HOME/Library/Android/sdk/build-tools/35.0.0/zipalign" \
APKSIGNER="$HOME/Library/Android/sdk/build-tools/35.0.0/apksigner" \
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
adb uninstall com.jordanadema.nebula
adb install --no-incremental Nebula.apk
```

## Compatibility

- Android 5.0+ (API 21+), phone or TV, OpenGL ES 3.0
- **Requires a powerful GPU** — the volumetric raymarcher is tuned for NVIDIA Shield-class (Tegra X1, ~512 GFLOPS) and modern flagship-phone hardware. Most other Android TV devices (Google TV Streamer, Chromecast, Walmart Onn, etc.) use Mali-G31 MP2 GPUs that are 30–80× weaker and cannot run the gas pass at any usable frame rate; among mainstream Android TV boxes the Shield has by far the most GPU headroom. Recent flagship phones (Snapdragon 8 Gen 3 / Adreno 750 and similar) run it comfortably.
- Built against Android API 35 by default; compile SDK overrides must be API 29+ because the source uses modern HDR capability constants
- Tested on NVIDIA Shield 2017 (Tegra X1, Android 11) and Galaxy S24 (Adreno 750, Android 16)
- HDR10 displays: colours are tuned for OLED — deep blacks with saturated violet highlights

## Diagnostics

Nebula requests the highest-resolution display mode at 60 Hz or lower, but Android may still give the app a smaller surface. Logcat lines tagged `NebulaDream` report the requested mode, active mode, app surface size, gas FBO size, HDR capabilities, and adaptive gas scale. If Android has a display-size override such as `wm size 1920x1080`, the app will log that 4K is being limited instead of claiming native 4K rendering.

On some Android TV devices, an exact `wm size 3840x2160` override may be ignored while a near-4K override is accepted. Treat this as a test-only mode: third-party launchers may not scale their icon grids correctly under the global override.

```bash
adb shell wm size 3839x2160
adb shell wm density 640
```

Return to the common 1080p UI override with:

```bash
adb shell wm size 1920x1080
adb shell wm density 320
```

## License

GPL-3.0-only
