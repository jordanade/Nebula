# Nebula Shape Ideas

This note summarizes the remaining shape-direction ideas discussed after comparing the current screensaver output with the reference nebula photo at:

`/Users/jordan/Art/Nebulas/hero-image.fit_lim.size_1600x900.v1727420666.webp`

The current renderer has strong atmosphere, color, stars, and depth, but the cloud shapes still tend to read as procedural volumetric masses rather than a single recognizable nebula structure. Ideas 3 and 5 have already been explored in code: brighter rims/dimmer interiors and fewer/larger density masses. The ideas below are the next likely levers.

## 1. Add A Large Curved Dust-Front Mask

Goal: create one dominant macro silhouette, similar to the dark foreground ridge and glowing boundary in the reference photo.

Implementation direction:

- Add a low-frequency signed mask in raymarch/world space or screen space.
- Shape it as a broad diagonal or horizontal front, then perturb the edge with low-frequency noise.
- Use the mask to suppress emission on one side and boost rim glow close to the boundary.
- Keep the mask slow-moving or tied to existing zoom coordinates so it does not feel like a static overlay.

Conceptual shader sketch:

```glsl
float frontBase = uv.y + 0.18*sin(uv.x*2.0 + uTime*0.03);
float frontNoise = texture(uNoise, vec3(uv*0.45, uTime*0.015)).r - 0.5;
float front = frontBase + frontNoise*0.22;
float dustSide = smoothstep(-0.10, 0.10, front);
float frontRim = exp(-front*front*80.0);
```

Possible use:

- Multiply gas emission by `mix(0.35, 1.0, dustSide)`.
- Add magenta/pink rim emission with `frontRim`, gated by local density.
- Optionally darken stars behind the dust-side if a foreground occlusion pass is added.

Risk:

- If too screen-space, it may look like a 2D matte sliding over the volume.
- If too strong, every frame may look like the same nebula composition.

## 2. Add A Dark Foreground Dust Layer

Goal: make shape visible through occlusion, not just brightness. The reference photo works because a dark dust mass blocks bright gas and stars.

Current issue:

- The renderer intentionally uses very low extinction:

```glsl
T *= exp(-d * dt * 0.08);
```

- That lets stars shine through almost everything, which keeps the scene pretty but weakens silhouettes.

Implementation direction:

- Add a separate foreground dust density, distinct from emissive gas density.
- Dust should have low or no emission.
- Dust should increase extinction much more than the regular gas.
- Gate it to near depth and broad macro shapes so it reads as large foreground structure, not all-over murk.

Conceptual shader sketch:

```glsl
float dust = smoothstep(0.50, 0.78, texture(uNoise, p*0.045 + vec3(8.0, 3.0, 0.0)).r);
dust *= smoothstep(14.0, 4.0, t); // near-only
T *= exp(-dust * dt * 0.55);
```

Possible enhancements:

- Slightly blue/purple tint the extinguished region instead of pure black.
- Use the same macro field as the dust-front mask so the foreground silhouette and rim agree.
- Let only the densest dust occlude stars; avoid dimming the whole field.

Risk:

- Too much extinction can make the screensaver feel like black blobs over stars.
- Needs careful balance with OLED/HDR black levels.

## 4. Add Vertical Plume Structure From The Front

Goal: introduce directional structure like the reference photo's vertical pink plumes rising from the bright edge.

Implementation direction:

- Use anisotropic noise stretched vertically.
- Gate it near the dust-front/rim boundary so plumes originate from a visible structure.
- Use it as a brightness/color modulation, not a new full-density field at first.

Conceptual shader sketch:

```glsl
vec2 plumeUv = vec2(uv.x*1.2, uv.y*4.5);
float plume = texture(uNoise, vec3(plumeUv*0.22, uTime*0.01)).r;
plume = smoothstep(0.48, 0.82, plume);
plume *= frontRimOrNearFront;
```

Possible use:

- Add subtle pink emission above/behind the front.
- Modulate density or rim color vertically.
- Use a soft upward falloff so plumes fade into the background rather than becoming stripes.

Risk:

- Regular vertical noise can become curtain-like or banded.
- Needs enough warp or broken masking to avoid looking like straight procedural columns.

## Suggested Next Step

The highest-impact next experiment is to combine ideas 1 and 2 in a small way:

1. Add a broad curved front mask.
2. Use it to create a dark foreground dust silhouette.
3. Add a subtle magenta rim along the front.

Only after that reads well should plume structure be added. Without a front/rim to anchor them, plumes will likely look like unrelated vertical noise.
