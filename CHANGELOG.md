# Changelog

All notable changes to Nebula are documented here.

## 4.9.0 — 2026-07-12

Follow-up to the 4.8.0 capture review: rare events land, warm scenes glow.
Same performance envelope (cadence and adaptive gas scale unchanged).

### Added
- **Nova** — the rarest event: one star swells to a brilliant HDR point over
  ~2 s, holds, and fades over ~25 s while sliding white → amber, with a tight
  glow and slender diffraction spikes rendered as an unclipped screen-space
  overlay — the brightest star in the sky, not a searchlight. First one
  within ~6–18 min of a session, then 20–60 min apart.
- **Showpiece galaxies** — a second, far rarer galaxy tier on a coarser grid:
  3–4× larger, with inner-disk arm structure, a warm core, and a dark dust
  lane. Roughly one drifts through view every ~10 minutes.

### Changed
- **Warm scenes glow gold instead of brown** — the warm palette stop is now
  amber-gold, emission is boosted toward the warm end, and the ambient term
  follows the region colour there (fixed violet ambient greyed dim orange
  into mud).
- **Small-tier galaxies share the showpiece recipe** — tighter nucleus,
  inter-arm gaps, brighter slower-falloff disk, a mild dust lane, and less
  edge-on bias: the common smudges now resolve into structured disks at 1:1
  instead of featureless ovals. Overall gain raised ~1.5× to compensate for
  the light the arm gaps removed. Arm modulation fades with radius (both
  tiers) — structure lives in the inner disk and the outskirts relax to a
  smooth halo; crisp long S-arms read as corny.
- **Galaxies show near-zero parallax** — both galaxy tiers now ride their
  own zoom phase at a quarter of the star speed: galaxies are effectively at
  infinity, so they hang nearly still while stars stream past, cross-fading
  over ~5-minute cycles instead of ~80 s. (Truly static would freeze the
  composition forever.)
- **Star-forming cores actually appear** — the density threshold and the
  regional gate are looser, and the gate has a 0.12 floor so every dense
  heart carries some inner light while gated regions still blaze at full
  gain. (In 4.8.0 the triple-gated cores effectively never fired.)
- **Milky-way band is visible and structured** — glow gain raised
  (0.12 → 0.30, panel-judged; the band floor sits below the HDR knee, so the
  linear FP16 path renders it far dimmer than SDR grabs suggest), tinted
  near-neutral-warm, and in-band sprinkle stars are both denser (boost
  1.2 → 7.0) and brighter (×1.8 at band core): the band reads as a river of
  resolved faint stars, with the glow as a floor underneath rather than the
  identity.
  Matched against ESO's all-sky panorama, the band also gained a meandering
  dark rift along its centerline and slow amplitude variation along its
  length (star clouds + taper) — computed identically in both passes so the
  star grain dips in the rift and swells in the clouds in register with the
  glow. A smooth constant-width gaussian was the giveaway before.

### Fixed
- **Dark stipple in dense mid-distance gas** — the anti-banding dither now
  fades out with density instead of distance alone, so it no longer
  accumulates into speckle inside dense masses.

## 4.8.0 — 2026-07-04

The "magical sky" release: a visual overhaul of the star field and nebula,
driven by live capture sessions on the Shield. Same performance envelope —
cadence and adaptive gas scale are unchanged.

### Added
- **Star-forming cores** — where the raymarched gas density peaks, emission
  now blooms toward white-pink, gated by a very-low-frequency seed so only
  some cloud masses ignite, with mid-frequency knots for internal structure.
  Cores ride the HDR chain to peak nits: every scene has a luminous heart.
- **Distant galaxy field** — rare cells of a coarse star-grid layer hold a
  tiny inclined two-arm galaxy (hashed rotation, inclination, size,
  brightness). Galaxies drift, grow, and cross-fade with the star-zoom
  layers and are occluded by gas like stars.
- **Milky-way band** — a broad, grainy luminance floor anchored to the view
  direction lifts empty regions from flat black to faint depth; sprinkle-star
  density rises inside it, so the band resolves into star grain where it is
  brightest. Far-field nebula gain also raised (0.22 → 0.30).
- **Palette excursions** — a very slow seed occasionally biases the whole
  scene warm-rose or deep blue for minutes at a time, so long sessions see
  genuinely different moods.

### Changed
- **Star colors follow the blackbody sequence** (M orange → B blue-white);
  the green/pink palette is gone. Brightness biases temperature: the
  brightest stars skew hot, the faint majority skews warm — as in a real sky.
- **Star flares are rare events** — 40–180 s gaps (was 0.1–0.6 s), 4–8 s
  duration, a 0.6 magnitude floor, and a wider bloom, so each flare reads as
  witnessed rather than as texture.
- **Twinkle is chaotic and fast** — a product of incommensurate sines plus a
  slow drift replaces the metronomic pulse; rates raised to 0.6–1.4 Hz.
  Modulates brightness only.
- **Steeper faint-star counts** — magnitude hash to the 4th power and a
  raised sprinkle floor (0.08 → 0.13): endless faint stars under the bright.

### Fixed
- **Star shimmer was grid aliasing** — star cores (~0.34 px sigma) and spike
  cross-sections were far narrower than a screen pixel, so pixel-grid
  alignment modulated their brightness as the zoom drifted them. Stars are
  now convolved with a 0.5 px-sigma pixel gaussian (variances add) with
  near-energy-conserving compensation: resolved stars keep their sharpness,
  sub-pixel stars render as steady points.

## 4.7.1 — 2026-06-26

Phone polish and burn-in fixes on top of 4.7.0. The Shield/TV output is
unchanged — the new star and settings logic resolve to no-ops there.

### Added
- **DPI-aware star sizing** — on high-density panels the stars get a gentle,
  sqrt-damped size boost on top of the width-relative scaling, so they no
  longer look tiny on a phone held close. Anchored to the Shield's 320 dpi, so
  the boost is exactly 1.0 there.

### Changed
- **Sprinkle stars now cross-fade** instead of sitting static — three offset
  fields fade 120° apart, so no sprinkle pixel stays lit in place (burn-in)
  without translating the sub-pixel dots, which would shimmer. Frame cost is
  unchanged.
- **Removed the HDR brightness setting** — the brightest above-knee highlights
  always target the display's full headroom now; the manual bias could only
  undershoot it.

### Fixed
- **Settings layout on phones and TV** — the preference list pads from the real
  window inset, so the first row is no longer hidden under One UI's collapsing
  title, nor gapped below the action bar on Android TV.

## 4.7.0 — 2026-06-24

Nebula becomes a phone app as well as a TV app, with HDR brightness that adapts
per device. The renderer is unchanged on the Shield — the new scaling and HDR
logic are no-ops there.

### Added
- **Runs on phones**, not just Android TV — the `leanback` feature is now
  optional, so Nebula installs and runs on any Android phone with a capable GPU.
- **Surface-relative star scaling** — stars are sized to the surface width, so
  a narrow portrait phone no longer renders them tiny. The scale is exactly
  1.0 on any display ≥1920 wide, leaving Shield/TV output untouched.
- **Live HDR headroom** — on Android 14+ the tonemap reads the compositor's
  actual `getHdrSdrRatio` and re-tunes whenever it changes, instead of relying
  only on the static panel descriptor. Falls back cleanly on older devices.
- **HDR brightness** setting (50–200%) — a manual bias over the auto-detected
  headroom for panels that under- or over-report.

### Changed
- **Max panel brightness** is requested for the dream window, alongside an
  explicit HDR-headroom request, to push the brightest stars and flares toward
  peak luminance where the platform allows it.
- **"Start now"** is manufacturer-aware: it opens the system Screen saver page
  on devices (e.g. Samsung One UI) that block the direct dream-start, and keeps
  the instant launch on Shield/stock Android.

### Fixed
- **Settings layout on phones** — the first preference row is no longer hidden
  behind One UI's oversized collapsing title.

## 4.6 — 2026-06-22

The F-Droid release line — reproducible unsigned builds, a license change, and a
batch of packaging and stability fixes ahead of submission.

### Changed
- **Package renamed** to `com.jordanadema.nebula`.
- **License changed** from MIT to **GPL-3.0-only**.
- **targetSdkVersion raised to 35**.
- **Tuned defaults** to 25 fps and 45% maximum gas resolution, and removed an
  instrumentation-induced stutter.
- **Richer TV banner** with stars and diffraction spikes.

### Fixed
- **HDMI signal loss on exit** — the GL thread now stops when the dream is
  dismissed, so the display no longer drops signal.
- **Reproducible F-Droid builds** — preserve zip alignment when signing, use
  `aapt add` instead of `zip`, compile with `--release 8` for D8 compatibility,
  remove anonymous inner classes that crashed D8, and auto-detect the SDK
  platform and build-tools.

## 4.5 — 2026-06-19

### Added
- **"Start now"** action in the settings menu to launch the screensaver
  directly from the dream's own settings screen.

## 4.4 — 2026-06-17

A star-field quality and performance pass on the v4 volumetric renderer. Star
clusters read more like real galaxy photos, sprinkle stars gain colour variety,
and GPU micro-optimisations free ~4 ms of headroom per frame at 1080p.

### Changed
- **Sharper star cluster/void contrast** — density noise tightened to a narrow
  smoothstep with squared falloff and an early-out hash check before the noise
  lookup, producing clearer sparse voids and dense clusters.
- **Coloured sprinkle stars** — sprinkles now sample `starCol()` with an
  independent hash, giving colour variety instead of monochrome white.
- **Wider sprinkle coverage** — a base density floor ensures sprinkles appear
  everywhere, not only in the densest regions.
- **Brighter galaxy haze** — haze multiplier boosted 6× for a visible diffuse
  background glow behind star clusters.
- **Uniform star brightness** — removed the density-scaled brightness
  attenuation so stars in sparse regions are no longer dimmed.
- **Leaner star shader** — twinkle pulse term removed (saves 1 `sin` + 1 `pow`
  per star), `dot(d,d)` replaces `length()` (saves `sqrt`), cube multiply
  replaces `pow(h,3)`, halo `exp()` merged so the flare-only term fires only
  during flares, and spike threshold raised from 0.50 to 0.65.
- **Steady 25 fps at 1080p** with ~4 ms of per-frame headroom gained from the
  shader simplifications above.

## 4.3 — 2026-06-17

A star-field refinement and uniqueness pass on the v4 volumetric renderer. Each
screensaver session now produces a unique nebula and star field.

### Added
- **Per-session random seed** — each screensaver activation generates a unique
  nebula formation, camera path, and star field so no two sessions look alike.
- **Density-driven sprinkle layer** — a pointillistic sub-pixel star layer that
  clusters in star-dense regions, reinforcing the galaxy-cluster effect.
- **Performance measurement guide** — `docs/measuring-performance.md` documents
  how to measure GPU performance on the Shield, since standard Android
  frame-stats tooling does not work with GLSurfaceView rendering.

### Changed
- **Non-periodic camera motion** — lateral drift now uses dual incommensurate
  sine waves per axis, eliminating the visible periodic loop from previous
  versions.
- **More dramatic star density variation** — coarser density noise with squared
  falloff creates clear sparse voids and dense clusters instead of uniform
  distribution.
- **Density-scaled star brightness** — stars in sparse regions render at 60%
  brightness, reinforcing the cluster/void contrast.
- **Reduced 1080p twinkle intensity** — twinkle amplitude scales with resolution
  so lower-resolution displays don't oversaturate.
- **Slower star twinkle rate** — twinkle frequencies halved for a calmer star
  field.
- **Brighter, properly scheduled flares** — flare magnitude uses a square-root
  distribution for more visible accents; each flare fully fades before the next
  one triggers, eliminating mid-envelope cutoffs.

### Fixed
- Corrected README claim of three-phase star system (has been two-phase since
  v4.0).

## 4.2 — 2026-06-15

A performance and controls release for the v4 volumetric renderer. The main
change is making the default settings cooler and more explicit while leaving
higher-quality options honest for faster Android TV hardware.

### Changed
- **Cooler default performance profile** — render resolution now defaults to
  35% and frame rate defaults to 25 fps. On the NVIDIA Shield test device this
  held ~25 fps with roughly 23–26 ms of sampled GPU work inside a 40 ms frame
  budget.
- **Honest render-resolution control** — the render slider now covers 10–100%
  and the renderer uses the selected value directly. There is no hidden
  low-resolution clamp behind higher user-selected values.
- **Practical frame-rate options** — frame caps are now 10, 15, 20, 25, and
  30 fps. The previous 60 fps and uncapped options were removed because this
  shader cannot use them meaningfully on normal Android TV hardware.
- **Slower special star flares** — the occasional accent flares now use longer
  envelopes with very short handoff gaps, so the special flashes feel less
  twitchy while remaining part of the normal star field.
- **Lighter runtime telemetry** — GPU work logging now samples occasional
  frames instead of forcing `glFinish()` every frame, reducing measurement
  overhead during normal screensaver operation.
- **Simpler motion controls** — removed the separate writhe-speed preference;
  zoom speed remains and now uses a smoother exponential mapping.

### Fixed
- Reduced default thermal load compared with v4.1 while preserving the same
  volumetric look at a lower gas FBO resolution.
- Corrected the documented Shield command for manually starting the screensaver
  by launching Somnambulator with the desk-dock category.

## 4.1 — 2026-06-15

An art-direction and stability pass on the v4 volumetric renderer. The release
keeps the same split-resolution GLES 3.0 pipeline, but makes the nebula forms
read more naturally and tones down star flares so they sit inside the star
field instead of looking like a separate overlay.

### Changed
- **More natural bright star flares** — the occasional accent flares are now
  much smaller, subtler, and limited to four-point vertical/horizontal
  diffraction spikes. Twinkle timing is faster across both ordinary stars and
  accent flares.
- **Macro nebula/dust forms** — foreground gas now has broader light/dark
  shape driven by volumetric noise and shell halos, giving the clouds more
  defined sides without relying on a clean screen-space shadow stencil.
- **Softer nebula boundaries** — near-cloud shell halos preserve the useful
  top/bottom light-dark read while avoiding the obvious curved dark shadow that
  could cut across the screen.
- **Camera-origin fade** — the ray march starts past the camera-origin slab and
  fades in quickly, so flying through a cloud no longer fills the entire frame
  with a flat colour wash.

### Fixed
- Removed the large 8-point accent flare shape from v4 and replaced it with a
  star-sized four-point flare that better matches the surrounding star field.
- Reduced foreground dust quantisation and clumpy near-field breakup by using
  fewer, larger macro forms.
- Eliminated the most visible screen-wide stencil effect from the macro dust
  front.

## 4.0 — 2026-06-13

A ground-up rebuild of the gas rendering: the flat 2D FBM nebula is replaced by
a real-time volumetric raymarch through a 3D noise field, rendered in a
split-resolution pipeline that keeps stars pin-sharp at native panel resolution
while the gas runs at a lower resolution for performance.

### Added
- **Volumetric raymarching** — 46-step ray march through a tiling 3D noise
  texture (coverage × billow base, Worley erosion) replaces the v3 screen-space
  FBM. The gas now has true depth: foreground masses occlude background stars,
  and the camera flies *through* the clouds.
- **Three-stage density LOD** — near gas gets full 4-fetch erosion detail;
  mid-range drops the fine erosion; far-field uses only 2 low-frequency fetches.
  Together they triple the effective march depth while keeping frame time flat.
- **Ionization-front rims** — bright edges fire at every gas boundary where the
  ray crosses from empty space into dense cloud, giving the nebula bright
  structural outlines for free.
- **Gradient relief lighting** — density-gradient normals on near layers give
  the gas sculpted, directional lit/shadow form.
- **Split-resolution pipeline** — the gas is raymarched into a low-res FBO
  (governed by the render-scale setting); the composite pass draws stars,
  diffraction spikes, and galaxy haze at full native resolution and composites
  them behind the gas via its transmittance channel.
- **Tiling 3D noise texture** — a 64³ RGBA texture (R = 3-octave value FBM,
  G = inverted Worley) is generated once on the CPU and sampled in the shader,
  replacing all analytic noise with texture fetches for a ~6× speedup.
- **GLES 3.0** — required for `sampler3D` and `glTexImage3D`; the GLES 2.0
  shader path is removed.

### Changed
- Gas is now **emissive** (emission nebula) rather than lit cloud — no light
  march or phase function; the gas glows, tinted by the v3.1 purple palette.
- **Ultra-transparent extinction** so stars shine through everything and the
  deep march never hits early-out, giving a consistent frame time.
- Far-field banding from 8-bit texture quantisation is broken with a
  **spatial dither** that fades out for near gas (where dense sampling already
  hides it).
- Removed the CPU-scheduled giant-flare overlay (replaced by the per-star
  flares in the composite pass).
- Steady **20 fps** on NVIDIA Shield 2017 at the default render scale.

## 3.1 — 2026-06-08

An art-direction pass: a purpler palette, more dramatic massing, and a fix for
the gas slowly drifting off-screen.

### Changed
- **Bounded, relocated zoom orbit** — the per-cycle zoom-seam offset was an
  unbounded straight translation, so over ~10 min the gas panned off-frame into
  the voids and the screen emptied to near-black. It now follows a bounded,
  quasi-periodic orbit centred on a genuinely dense, high-contrast region of the
  noise field (found by scanning the macro-density), so the framing always holds
  real structure. Seam continuity is preserved (`offB(cyc) == offA(cyc+1)`).
- **Purple/indigo palette** — re-centred the spatial-temperature ramp on
  purple/violet instead of warm pink, deepened the purple anchor toward indigo,
  swung the dense-gas tint from warm to violet, and eased the lit-front warm
  pull so bright masses stay purple rather than going red/brown.
- **More dramatic massing, ~25% less coverage** — density is now weighted toward
  filament ridges over smooth fill, with a lower big-shape floor and higher cloud
  threshold, so the frame reads as bright ridges over deep black voids instead of
  wall-to-wall gas.
- **Brighter faint gas** — the emission curve lifts faint/mid densities toward
  linear (`mix(d*d, d, 0.2)`) so subtle gas reads more clearly while cores and
  voids are unchanged.

## 3.0 — 2026-06-07

Refinements to the v3.0 nebula:

### Changed
- **Softer relief** (×165 → ×120) — the strong emboss was amplifying the noise
  grain into straight, parallel "strata"; softening it makes the gas read as
  organic flowing wisps.
- **Quintic value noise** — replaces the cubic interpolation, removing the
  square-grid artifacts that showed as axis-aligned ridges under the relief.
- **Finer domain-warp octave** — gently curls locally-straight ridges without
  over-shearing the dense gas into sheets.
- **Disabled the parallax background haze layer** — its soft style clashed with
  the main relief nebula (kept in code, one line to re-enable).

### Performance
- Render-scale default **55%** and a **15 fps** frame-cap option, giving a steady
  15 fps on the Shield. Temps stay ~55–59 °C (far below throttling); the
  render-scale slider (50–100%) trades frame rate for detail.

## 3.0 — 2026-06-04

A ground-up rework of the look: from thin violet filaments against black to
voluminous, self-shadowed nebula clouds drifting against a glittering star
field, in HDR.

### Added
- **Voluminous cloud body** built from smooth FBM — soft, filled gas masses
  instead of thin filaments (filaments remain as minor bright accents).
- **Volumetric self-shadowing** — a short march toward the light through the
  cloud density gives lit crests and shadowed undersides, reading as real 3D
  cloud volume rather than a lit sheet.
- **Embossed relief** lighting from the macro-density gradient for sculpted
  surface form, read from a band-limited density so it never aliases.
- **Static domain warp** that bends straight filaments into curled, billowing
  lobes — adds shape without adding motion.
- **Large-scale form field** carving big bright cloud masses against deep dark
  voids for sculptural composition.
- **Warm four-stop palette** (orange → magenta-pink → violet → blue), biased
  warm and range-widened, with a drifting "lit front" that illuminates one
  region like nearby stars.
- **Galaxy haze** — dense star clusters glow as hazy distant galaxies,
  coincident with and moving at the same speed as the star layer.
- **Occasional HDR flares** at the brightest cloud cores, with cross-hatched
  diffraction spikes; stronger diffraction spikes on the brightest star
  twinkles.
- **HDR output** — opt-in FP16 scRGB-linear surface with feature detection and
  automatic SDR fallback; highlight cores extend into panel headroom.
- **Settings page** — HDR mode, render scale, frame-rate cap, zoom speed, and
  writhe speed.
- **Anti-aliased FBM** helper whose octaves fade out near the pixel Nyquist
  rate, so rich mottle/texture can be stacked without shimmer.

### Changed
- Reduced gas "writhing" by half and softened it to a faint structural warp —
  a real nebula fly-through is parallax and zoom, not churning.
- Rebalanced density so clouds are the dominant body and filaments are accents.
- Tuned highlight roll-off so bright cores keep their hue instead of blowing
  out into harsh white shapes.
- Extra faint stars distributed through the dark voids between cloud masses.

### Fixed
- Edge aliasing/shimmer from feeding high-frequency grain through the relief
  gradient — relief now reads a smooth macro density.
- Hollow "bubble"/cell-wall artifacts from using turbulence for the cloud body.

### Notes
- Tested in HDR on an NVIDIA Shield 2017 (Tegra X1, Android 11).
- Single full-screen quad; all rendering in one GLES 2.0 fragment shader.
