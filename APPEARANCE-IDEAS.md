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

---

# v4.11.1 review — 2026-07-26

Eleven raw 4K grabs over ~4 min plus per-frame measurements, freshly
installed 4.11.1 on the Shield, HDR active, gas scale 0.35-0.40, cadence
21-24 fps. Same caveat as always: SDR grabs of HDR output, so highlight
INTENSITY is unreliable — but highlight AREA is not, since a grab cannot
hide a large bright region.

## What the measurements said

1. **The top of the range is empty in every frame.** Pixels above 60%
   grey: 0.004-0.008% of the frame, i.e. ~400 of 8.3M. Above 30% grey:
   under 0.3%. The one exception was the single frame that caught a
   flare (0.020% / 0.76%). The scene's whole luminance hierarchy is
   *star carpet, then nothing, then occasionally one flare* — no frame
   has a bright AREA, only bright POINTS.
2. **Coverage variance, not coverage, is the problem.** Share of the
   frame carrying any gas at all ranged 25% -> 57% -> 96% across the
   set. Widening `COV_LO` in 4.11.0 raised the average, but the sparse
   frames still have no subject whatsoever, and they are common.
3. **The gas has no negative structure.** Every element is additive.
   Erosion carves isotropic Worley holes; nothing is ever *in front of*
   the gas. The only thing that reads as dark is where gas isn't.

Conclusion: what was missing was not gain or coverage. It was dust.
The band already learned this in 4.9.0 ("the real band is defined as
much by dust as by light") and the nebula never got the lesson.

## Shipped 2026-07-26

1. **Dust lanes.** A gaussian trough on the LOW end of the inverted-Worley
   channel — the web of cell boundaries, a connected network of thin
   sheets, which is the topology real dust lanes have. Free: it is the G
   channel of the dust fetch the march already makes. Emission
   suppression at every depth; extinction near-field only (see below).
2. **Silhouette dust.** The macro forms never reached opacity and never
   cut an edge. `DUST_BITE` takes the Worley from the same fetch to give
   the form a cauliflower edge, the ramp is tighter (0.53..0.80 ->
   0.53..0.72), occlusion is cubed instead of squared and extinction
   goes 0.50 -> 1.10 — so the gain lands on the cores (which now black
   out stars) while edges stay sheer. Cubing offsets the extinction rise
   at mid densities almost exactly, so this is contrast, not fog.
3. **Reflection nebulosity.** `starSig=T*bg` meant a star was only ever
   OCCLUDED by gas, never lit it — two independent worlds stacked. The
   top ~1% of stars by magnitude now throw a blue-white halo gated on
   local column opacity. Blue because scattering is wavelength-dependent,
   which is also why it puts a colour on screen the four-stop emission
   palette cannot reach. Flares and novas scatter into their surrounding
   cloud on the same term.
4. **Camera steering** (the open stretch item from the 4.8.0 list). The
   CPU trilinear-samples the same 64^3 buffer the GPU marches, scores a
   look-ahead fan of candidate lateral offsets weighted by the marcher's
   own distance falloff, and low-passes a drift toward the best. It
   steers only when the current path scores below `DRIFT_ENOUGH`, so it
   behaves as a FLOOR on scene richness rather than a target — steering
   toward maximum density always would trade bare starfield for the flat
   full-frame wash the contrast curve exists to suppress.

   Two cascaded first-order lags do the smoothing: a proportional
   controller sets the wanted lateral velocity (capped at `DRIFT_SPEED`)
   and the actual velocity lags that over `DRIFT_TAU`. Moving the
   position toward the target at a fixed rate — the first version — is
   smooth in position but DISCONTINUOUS in velocity, so every time the
   ring probe picked a new heading the lateral motion reversed instantly.
   Verified on device: increments decelerate 0.09 -> 0.07 -> 0.04 -> 0.02
   -> 0.01 into the target and reverse just as gently.

   `DRIFT_ENOUGH`, not `DRIFT_MAX`, turned out to be the knob for how
   much the camera roams: at 0.13 it cleared the bar and froze after ~2.3
   units, far short of its 9-unit authority. At 0.17 a poor stretch
   (score 0.007) had it travel out past 4.4 units and climb back.

## Cost

+0.52 ms total against 4.11.1 on the Shield, measured with the ablation
harness pinning seed, event RNG and gas scale, over 20 paired 2s windows
(gas +0.18, comp +0.34 — the comp figure is not resolvable at this noise
floor, where scene variation swings +-2.5 ms window to window; the gas
figure is consistent and mechanistic). Free-running it holds the 25 fps
cap at ~28 ms with the adaptive gas scale sitting at the 0.40 user
maximum rather than flapping below it.

Two comp-pass reductions went in alongside: the baked sky layer was
fetched twice per pixel (once inside a branch, which also made it an
implicit-LOD fetch in non-uniform control flow) and is now fetched once,
and the previous epoch's bake is only fetched during the ~4s dissolve
instead of every frame. Both are strict reductions by construction even
though they sit under the noise floor.

## Four things that were wrong first, and why

- **An iso-surface trough on the value-fbm channel is not a lane.** Both
  noise channels sit at p05=0.28 / p50=0.47 / p95=0.66 (sd ~0.12) — a
  3-octave value fbm never approaches 0 or 1. A trough of half-width
  0.10 therefore covered ~p15..p50 of the ENTIRE VOLUME and read as the
  whole nebula dimming by half. The fix was not a tighter trough (that
  becomes thinner than the step size and aliases) but a different
  channel: both have the same distribution, so what makes the Worley
  right is its TOPOLOGY — its low values are connected sheets, the fbm's
  are disconnected blobs. `logNoisePercentiles` now logs both channels
  every run; any threshold tuned against these fields without that in
  hand is a guess.
- **Ungated lane extinction is fog, not dust.** Integrated over the whole
  40-unit march at mean laneAmt ~0.12 and d ~0.1 it reached an optical
  depth of ~0.77 — about 2.4x the gas's own extinction — and collapsed
  the mid-tone band ~3x. Now scaled by `nearAmt`. The split matters:
  emission suppression everywhere gives structure, occlusion only up
  close gives silhouettes, and beyond the near LOD there is nothing left
  to silhouette against anyway.
- **A per-pixel neighbourhood sum draws rectangles.** Summing an NxN cell
  neighbourhood per pixel means the set of contributing stars changes
  DISCONTINUOUSLY as a pixel crosses a cell boundary, so any kernel with
  amplitude left at the neighbourhood edge tiles the sky with hard
  rectangles (3x3 at K=1.2 did; 5x5 at K=1.6 was clean). Sources are now
  picked on the CPU and passed as uniforms — the bigGalaxyVisible /
  nova-overlay pattern — which removes the width constraint entirely.
- **Moving the halo work to uniforms is only a win if the HASH moves
  too.** The first CPU-gated cut passed the grid CELL and let the shader
  resolve `h2(cell+3.7)`, so it still ran 16 hashes per pixel and
  measured +0.30 ms *worse* than the per-pixel version it replaced. The
  intra-cell offset is constant for a whole cycle; passing the resolved
  grid POSITION instead is what makes the loop cheap.

Also, on the first cut the halos were ~140 per frame at gas-level
brightness (REFL_MAG 0.86, gain 0.55) and Jordan's verdict on the panel
was "terrible" — twice. Rarity (top 1%), a veil-level gain (0.12), and a
gate on VISIBLE rather than merely present gas are what made it read.
Halos over sky that looks empty float with nothing to scatter off.

## Still open, ranked by payoff-per-effort

1. **Filamentary anisotropy.** Every density fetch is isotropic
   (`p*0.062`, `p*0.22`, `p*0.58`), so every mass is the same cauliflower
   at every scale — the same mathematical tell the constant-width band
   gaussian was. Stretching the sample coordinate along a slowly-rotating
   world axis and compressing it along `ldir` turns masses into streamers
   and pillars with the bright front on the ionized side. One matrix
   multiply on the coord, no new fetches. Biggest identity change
   available for the least code.
2. **One resolved landmark.** Frames without a flare still have nowhere
   for the eye to land. A very rare globular cluster (radial hash-dot
   swarm, unresolved bright core, resolving to grain at the rim) or open
   cluster (a few hot blue stars with reflection nebulosity, which now
   exists) on the galaxyBig CPU-gate pattern, so it costs nothing when
   absent.
3. **Stagger the arrival.** One 10s `fadeIn` currently ramps everything
   together. Stars up over ~3s and gas blooming in behind them over ~12s
   would make the sky *develop* rather than dissolve in.
4. **Let it know what time it is.** Nothing reads the wall clock. Biasing
   `tempBias` by hour — deep blue-violet after midnight, warmer in the
   evening — is invisible in any one session and quietly uncanny across
   many.
5. **Binaries.** 1-2% of bright stars get a close companion with a
   contrasting blackbody colour. Albireo is the best thing in a small
   telescope, and it rewards anyone who walks up to the panel.
6. **Live nebula behind the settings screen.** `SettingsActivity` is a
   bare `PreferenceFragment`; the renderer behind a translucent list
   would make the zoom and resolution sliders preview themselves.

Open items from this pass: the flare/nova gas scatter (`EVENT_REFL`) is
implemented but never verified on a flare firing INSIDE a mass — the one
event the A/B caught was in thin gas and looked identical to baseline.
And the adaptive gas scaler still flaps (0.38 -> 0.40 -> 0.37 -> 0.35 ->
0.37 -> 0.36 -> 0.38 -> 0.40 in two minutes, seven FBO reallocations), so
the 4.5 ms deadband is not holding at 25 fps.
