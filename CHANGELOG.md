# Changelog

All notable changes to Nebula are documented here.

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
