# Changelog

All notable changes to Nebula are documented here.

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
