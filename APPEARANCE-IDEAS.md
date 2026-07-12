# Appearance ideas — making the nebula more magical

Observations from a live-capture session on the Shield (2026-07-03, five raw
4K screencaps ~20s apart, v4.7.x build). Captures are SDR grabs of HDR
output, so star highlights pop harder on the panel than in the files.

## What holds the current look back

1. **The gas glows dim, not luminous.** Interiors are deliberately dimmed
   (`emit=tcol*d*0.34` in `FRAG_GAS`) with rims carrying the shape. Clouds
   read as *smoke lit by moonlight* rather than *emission nebulae*. No frame
   had a bright heart anywhere.
2. **60–70% of most frames is near-black.** The far-field layers render at
   only `0.22` gain, so empty regions are flat black rather than faintly deep.
3. **The palette lands consistently on the same muted violet.** The 4-stop
   palette (warm/pink/violet/blue) is centered at `temp=0.60`; pink and blue
   stops rarely dominate, and the warm stop reads as brown at low emission.
4. **Nothing rare ever happens.** Star flares fire every ~0.1–0.6s, so they
   become texture, not event. There is no "did you see that?" moment.

The core issue: the scene has no luminance hierarchy — nothing the eye is
drawn to.

## Ideas, ranked by payoff-per-effort

1. **Star-forming cores.** Where gas density peaks, add a bright emission
   term that blooms toward white-pink and rides the HDR boost so cores hit
   peak nits. Dark dust framing brilliant hearts is what makes real nebula
   photos magical. *(Shipped in 4.8.0.)*
2. **Shooting stars.** A rare (every 1–3 min) meteor streak in the comp
   pass — moving line segment with fading tail, HDR-boosted head. Scheduled
   from Java like the existing flare scheduler. Highest wow per line of code.
   *(Deferred: meteors are an atmospheric phenomenon, so they'd break the
   in-nebula fiction — though twinkle and diffraction spikes already borrow
   from the same ground-sky vocabulary, so this stays on the table.)*
3. **Make flares rarer but bigger.** Minutes-long gaps, but a real
   diffraction burst when one fires (the `fl2*18.0` softening already scales
   this way). Rarity makes an event feel witnessed rather than rendered.
   *(Shipped in 4.8.0: 40–180s gaps, 4–8s duration, 0.6 magnitude floor.)*
4. **Lift the empty regions.** Raise far-field gain and/or add a very faint
   milky-way band (broad grainy luminance gradient via the existing haze
   machinery) so black regions have depth instead of absence. Keep subtle.
   *(Shipped in 4.8.0: far-field 0.22→0.30 plus a drifting grainy band.)*
5. **Occasional palette excursions.** Let `temp` wander to the ends of its
   range for a few minutes — a teal-and-gold scene, a rose-magenta scene —
   so long viewing sessions see genuinely different moods.
   *(Shipped in 4.8.0: slow noise seed biases temp ±0.35 for minutes at a time.)*
6. **Distant galaxies.** Tiny elliptical smudges with brighter cores,
   riding the star-zoom layers. *(Shipped in 4.8.0: a sparse procedural
   field — rare cells of a coarse grid hold a small inclined galaxy with
   hashed rotation/inclination/size/brightness, zooming and cross-fading
   with the star layers as a permanent population. A single scheduled
   galaxy was tried first and felt like it appeared out of nowhere.)*

---

# v4.8.0 review — 2026-07-06

Nine raw 4K screencaps over ~4.5 min (6 @ 15s apart, 3 @ 30s apart),
freshly installed 4.8.0. Same caveat as above: SDR grabs of HDR output.

## What landed

- **Galaxies read beautifully.** Several inclined smudges visible in every
  frame; they feel like part of the sky, not decals. Edge-on ones read as
  thin bright slivers.
- **Star rendering holds up at 1:1.** Blackbody variety is visible (warm
  orange dots next to blue-white ones), spikes are crisp, and the grain /
  dither keeps the dark gradients essentially banding-free.
- **Palette excursions produce real variety at the accent level** — one
  frame had teal patches, two had warm gold/amber regions. No frame felt
  identical to another.
- **Best frames now have real depth**: near rims + mid glow + far-field
  + galaxies stack into a credible deep sky.

## What still holds the look back

1. **Star-forming cores never appeared.** Zero core blooms in ~4.5 min of
   frames. The core term is a product of three sparse gates: `coreAmt³`
   (needs d→0.72), `coreGate` (very-low-freq noise in 0.58–0.80 — the
   p*0.016 field means core-enabled regions are *minutes* apart at cruise
   speed), and `knot`. Frequency problem, not amplitude: the flagship
   4.8.0 feature is effectively absent for long stretches while muddy
   dense hearts (below) are always present.
2. **Warm regions still go brown-mud, not gold.** (Item 3 from the v4.7
   list, still true.) The warm stop `(1.00,0.44,0.16)` at interior gain
   0.34 lands at ~(0.17,0.07,0.03) pre-tonemap — dark brown. Worse, the
   ambient term is 60% fixed *violet* — violet + dim orange = grey mud.
   One capture's dense mass had exactly this dead brown heart; the one
   gold patch that worked was simply brighter.
3. **The milky-way band is imperceptible.** Max contribution ≈0.023
   pre-tonemap. The in-band sprinkle boost does read (faint dot fields in
   the empty frames), but the glow floor itself doesn't. ~half of all
   frames are still near-black voids. (Verify on-panel first — SDR grabs
   may undersell it.)
4. **Dense warm masses show a soft dark stipple** (visible at 1:1) —
   likely the far-field per-sample dither (0.010) accumulating across
   many steps in dense gas, or fine-erosion holes at gas-FBO resolution.
   Reads as "dirty" rather than structured.

## Ideas, ranked by payoff-per-effort

1. **De-mud the warm palette.** Boost emission ~1.3–1.5× as temp→warm
   (the warm stop only works luminous), shift the warm stop toward
   amber-gold `(1.0,0.60,0.24)`, and let `ambCol` follow `tcol` more when
   warm so violet ambient stops greying it. Cheap; pays out on every warm
   excursion. *(Shipped 2026-07-06: all three; warm regions verified
   gold-rose in captures.)*
2. **Make cores witnessable.** Widen `CORE_LO` 0.46→~0.40, soften the
   coreGate lower edge 0.58→~0.50, and/or add a small *ungated* core term
   (~0.5 gain) so every dense heart has inner light and gated ones blaze.
   A core roughly every few minutes is the target cadence.
   *(Shipped 2026-07-06: CORE_LO 0.40, gate 0.50–0.76, and a 0.12 gate
   floor so ungated hearts glow at ~0.5 effective gain.)*
3. **A rare nova.** Once per ~20–60 min, one star swells to a brilliant
   HDR point over ~2 s, holds, fades over ~20–30 s with a slight
   white→amber shift. Pure deep-space fiction (no atmosphere problem),
   reuses the uStarFlare scheduling machinery with a different envelope.
   Fills the still-open "did you see that?" slot the deferred meteors
   were meant for. *(Shipped 2026-07-06. Gotcha: anything bigger than
   one star-grid cell CANNOT be drawn inside starLayer — per-cell
   evaluation clips it. The nova is a screen-space overlay in the comp
   main(): reconstruct the star's grid position from cell + intra-cell
   hash, invert the layer transform, lay kernels in grid units so the
   blaze zooms with its layer. First nova 6–18 min in, then 20–60 min.
   Retuned same day per Jordan: first cut was "too big and loud" — now
   mag 1.25–1.6 with a tight glow and short slender spikes; the target
   is "brightest star in the sky", not a searchlight.)*
4. **Lift the band until it registers.** 2–3× the band gain (0.12→~0.30
   is still subtle), tint slightly warm so it reads as unresolved
   starlight rather than blue haze. Judge on-panel, not in grabs.
   *(Shipped 2026-07-06 at 0.30, near-neutral-warm tint; Jordan judged
   0.30 a touch strong as a featureless wash, but 0.20 proved
   near-invisible on the HDR panel — the band floor sits far below the
   HDR knee, so the linear FP16 path crushes it relative to SDR grabs.
   Settled at glow 0.30 WITH structure (below) — 0.38 was a touch
   strong on-panel — plus in-band sprinkle boost 7.0 (panel-judged
   upward twice from 2.5) and in-band dot brightness ×1.8: grain, not
   wash, is the band's identity. Lesson:
   judge shadow-level features on the panel; captures oversell them.
   Same day, after comparing against ESO's all-sky panorama (eso0932a):
   added a meandering dark rift along the centerline and star-cloud
   amplitude variation along the band's length — the real band is
   defined as much by dust as by light, and a smooth constant-width
   gaussian was the mathematical tell. The rift/cloud terms are
   computed IDENTICALLY in the gas and comp passes (same vn(), same
   coords) so grain density and glow structure stay in register.)*
5. **Galaxy hierarchy.** Keep the field; add a much rarer second tier
   (~once per 10 min in view): 3–4× larger, visible two-arm structure,
   maybe a dust lane. The current population is texture; one showpiece
   makes it an event. *(Shipped 2026-07-06: separate coarser grid —
   den×0.075, gate 0.9965 — so the big smudge doesn't clip at cell
   edges. First cut read as a featureless oval: high inclination
   squashed the arms and the disk was ~10× dimmer than the nucleus, so
   arm modulation died in the tonemap. Fixed with a face-on bias
   (incl 1.2–2.2), a small nucleus, deep inter-arm gaps
   (garm=0.15+0.85·ga²), and a brighter slower-falloff disk — clear
   two-arm spirals verified at test density. The same recipe was then
   back-ported to the small-tier galaxyLayer at Jordan's request, scaled
   for ~30–60px smudges: incl 1.3–2.8, gcore 4.5, gaps 0.20+0.80·ga²,
   disk exp(-1.45)·0.55, lane 0.55 — the common smudges read as tiny
   spirals at 1:1 now. Two follow-ups from Jordan: the arm gaps halved
   integrated light and sank them below visibility, so overall gain went
   up 1.5×; and both tiers moved to their own zoom phase at 0.25× star
   speed — galaxies are at infinity and should show ~no parallax, but
   truly static would freeze the composition, so quarter speed reads as
   "vastly farther" while the population still evolves over ~5-min
   cycles. Final arm pass: Jordan called the crisp long S-arms "corny" —
   arm modulation now fades with radius (armVis=1−smoothstep(0.9,2.0,gr),
   winding down to 3.0/3.2, floors up to 0.30/0.25), so structure lives
   in the inner disk and the outskirts relax to a smooth halo. The 1:1
   result reads like a photographed galaxy: bright core, soft envelope,
   lane pinch, hint of arm asymmetry.)*
6. **Chase the dense-mass stipple.** A/B `dith=0` on-device; if it's the
   dither, gate it by accumulated density rather than distance alone. If
   it's erosion holes, clamp `ero2` bite in high-d regions.
   *(Shipped 2026-07-06: it was the dither — now fades with pre-dither
   density `1-smoothstep(0.12,0.35,d)`; dense masses verified clean.)*

Stretch: steer the camera drift toward coverage (Java generated the
noise, so Java can evaluate it and bias `ro` laterally toward denser
sky) — would raise the rich-frame ratio without touching gains. Likely
overkill if 4 lands. *(Still open.)*
