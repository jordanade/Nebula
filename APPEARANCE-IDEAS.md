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
   photos magical. *(In progress on branch `star-forming-cores`.)*
2. **Shooting stars.** A rare (every 1–3 min) meteor streak in the comp
   pass — moving line segment with fading tail, HDR-boosted head. Scheduled
   from Java like the existing flare scheduler. Highest wow per line of code.
3. **Make flares rarer but bigger.** Minutes-long gaps, but a real
   diffraction burst when one fires (the `fl2*18.0` softening already scales
   this way). Rarity makes an event feel witnessed rather than rendered.
4. **Lift the empty regions.** Raise far-field gain and/or add a very faint
   milky-way band (broad grainy luminance gradient via the existing haze
   machinery) so black regions have depth instead of absence. Keep subtle.
5. **Occasional palette excursions.** Let `temp` wander to the ends of its
   range for a few minutes — a teal-and-gold scene, a rose-magenta scene —
   so long viewing sessions see genuinely different moods.
6. **A distant galaxy.** A tiny, slowly-passing elliptical smudge with a
   brighter core, riding the star-zoom layers; one per several minutes.
