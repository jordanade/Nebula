# Changelog

All notable changes to Nebula are documented here.

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
