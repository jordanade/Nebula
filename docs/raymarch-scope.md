# v4 — Raymarched Volumetric Clouds (scope)

A fundamentally different renderer from v3.0's 2D emission-gas shader: a ray per
pixel marched through a procedural 3D density field, with self-shadowing and
scattering. The goal is what the 2D shader structurally could not do — **defined
cumulus masses with lit edges and gassy wisps**, as a continuous fly-through.

## Why this gets the look
Form comes from light **attenuating through depth** (Beer–Lambert) and
**self-shadowing** (a short march toward the light at each step), plus a phase
function for forward-scatter "silver lining." That is real volume, not edge
relief on a 2D height field. The fly-through also gets *simpler* than v3.0: the
volume is procedural in world space and the camera just translates forward — no
scale-space crossfade, no reset, inherently burn-in safe.

## Density field (cumulus recipe)
Schneider/"Nubis"-style, adapted for nebula gas (no earth-style ground/sky):
- **Coverage** (low-freq) — where masses are vs. open space → discrete masses + voids.
- **Base shape** (low-freq Perlin–Worley) — rounded billows.
- **Erosion** (high-freq Worley) — eats edges into cauliflower.
- `density = remap(base · coverage − erosion)`.

## Lighting
Per primary step: `inscatter = lightColor · HG_phase · lightMarchShadow · powder · density`,
accumulated against transmittance `T *= exp(-density · dt · sigma)`. Tint the
scatter with the warm 4-stop nebula palette (gas, not white cumulus). Produces
lit crests, shadowed undersides, silver-lined edges.

## Performance — the gating risk (Tegra X1, 2015)
The 2D shader already runs ~15 fps; raymarching is 1–2 orders more work
(dozens of primary samples × several light samples per pixel). Survival levers:
- **Low-res raymarch + upscale** (march at ~¼ area). Fits the existing render-scale path.
- **Adaptive stepping + empty-space skipping** (cheap coverage probe → big steps in voids); early-out at `T < ~0.01`.
- **Tight budgets**: ~48–64 primary steps, ~5 light steps.
- **Temporal reprojection / amortization** — reuse last frame, march a subset per frame. Biggest lever; needs ping-pong **FBOs**.
- **Blue-noise jittered ray starts** to hide low-sample banding.

Realistic optimized outcome: maybe **15–24 fps at reduced res** — must be measured, not assumed. (See Phase 0.)

## Platform: move to GLES 3.0
Current app is GLES **2.0** — no 3D textures (precomputed noise), no float
FBOs/MRT (temporal reprojection), no dynamic loop bounds. The Shield supports ES
**3.1**. Migrate to ES 3.0 (minSDK → API 18+, fine for the Shield). The HDR/EGL
setup mostly carries over.

## Phased plan
- **Phase 0 — Perf spike (FIRST, ½–1 day):** minimal raymarcher, analytic 3D noise, ~64 primary + 5 light steps, empty-space skip, at render scale. Measure fps + GPU work-time + temps on the Shield. **Go/no-go gate.** (Analytic noise is a *conservative* bound; real 3D-texture sampling is cheaper.)
- **Phase 1 — Form:** real density field (coverage/shape/erosion), Beer transmittance, no lighting. Verify cumulus shape.
- **Phase 2 — Light:** light-march shadows, HG phase, powder, ambient → lit edges + bodies.
- **Phase 3 — Perf:** low-res + upscale, temporal reprojection (FBOs), empty-space skip, jitter.
- **Phase 4 — Integrate:** composite stars/galaxy-haze behind via transmittance; HDR; nebula palette; fly-through motion; settings (quality/coverage).
- **Phase 5 — Polish:** cumulus tuning, wisps, lit-edge balance, idle/burn-in checks.

## Carries over from v3.0
Starfield, galaxy haze, HDR scRGB-linear pipeline, settings infrastructure, build
system. Clouds composite in front of the stars using accumulated transmittance.

## Risks
1. **Tegra X1 throughput** — high, gating → Phase 0 de-risks it.
2. ES 2.0 → 3.0 migration — moderate, necessary.
3. Banding at low sample counts — manageable (jitter / blue-noise).
4. Nebula-appropriate art direction (not "earth cloud") — iterative.
5. Fresh renderer → ship as **v4.0** on a branch; v3.0 stays intact.

## Recommendation
Start with the **Phase 0 perf spike** before committing to the full build. One
throwaway raymarch shader on the Shield tells us whether this is a 20 fps reality
or a 6 fps slideshow — and that number shapes every downstream decision
(step budgets, resolution, whether temporal reprojection is mandatory, or whether
to shelve it). Everything else is normal graphics work; the Tegra X1 is the only
true unknown.

---

### Phase 0 results — measured on NVIDIA Shield (Tegra X1), HDR surface

**Spike:** `FRAG_SPIKE` in `NebulaDream.java` (this branch) — analytic 3D value-noise
fbm (3 octaves), 64 primary steps with empty-space skipping + early-out, 5-tap
light march. Rendered at render-scale 0.55 (1056×594).

| Metric | Value |
|---|---|
| Cadence | **~5 fps** |
| GPU work / frame (glFinish) | **~200 ms** |
| Resolution | 1056×594 |

**Look:** the spike produces *real* volumetric cloud form — self-shadowed
billowing masses, depth, fly-through. The thing the 2D shader couldn't do. ✅

**Cost is dominated by the analytic noise:** each density sample is a 3-octave fbm
= 24 hash evals; an in-cloud primary step costs ~6 density samples (1 + 5-tap light
march) ≈ 144 hashes. That's the whole 200 ms.

**Path to viability (all required, not optional):**
- **3D noise *texture* (needs GLES 3.0)** replacing analytic noise — the single
  biggest win, est. **3–6×** (texture fetch vs. 24 hashes).
- **Lower raymarch resolution + upscale** — march at ~0.35–0.4 scale.
- **Temporal reprojection / amortization (FBOs)** — the swing factor, and the most
  complex piece.

**Rough projection with the full stack:** ~20–30 fps at reduced res — *plausible
but not guaranteed*; temporal reprojection decides it.

**Verdict: CONDITIONAL GO.** The look is real and worth wanting. But naive is a
~5 fps slideshow, so the project is only viable if we commit up front to the full
optimization stack (ES 3.0 + 3D-texture noise + low-res march + temporal
reprojection) — a multi-day build with residual perf risk until those land.
Otherwise: shelve, keep v3.0 shipped.

### Phase 1 results — cumulus density field ✅

`dens()` upgraded to the Nubis-style recipe:
- **Coverage** — low-freq fbm gate → discrete masses vs. open space.
- **Billow base** — mid-freq fbm, carved by coverage (`remap`).
- **Worley erosion** — 27-cell 3D Worley eats the edges into cauliflower.

**Form: confirmed.** Stills show genuine cumulus — discrete rounded billowing
masses with cauliflower edges and open voids between them. The thing the 2D
shader structurally couldn't do.

**Cost:** ~1.5 fps / ~680 ms/frame at 1056×594 — the 27-cell analytic Worley
(× ~6 density samples per in-cloud step) dominates. **This is the Phase 3
problem** (3D-texture Worley + low-res march + temporal reprojection); Phase 1 is
about shape, and the shape is right.

**Next (Phase 2):** lighting — light-march shadows, HG phase, powder, ambient,
and the nebula palette → lit edges and shadowed bodies on top of this density.
