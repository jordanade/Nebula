package com.jordanadema.nebula;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Consumer;

public class NebulaDream extends DreamService {

    static final String TAG = "NebulaDream";
    private GLSurfaceView glView;
    private NebulaRenderer renderer;
    private DisplayDiagnostics displayDiag;
    private Consumer<Display> hdrRatioListener;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        Prefs prefs = Prefs.from(this);
        DisplayDiagnostics display = DisplayDiagnostics.configure(this);
        displayDiag = display;
        // Perf-ablation harness (dev only). Comma-separated component list read
        // from a shell-settable global setting, e.g.
        //   adb shell settings put global nebula_ablate STARS,BANDGRAIN
        // Each name compiles that component out of the shader; a non-empty value
        // also pins the gas FBO scale so variants are directly comparable.
        String ablate = android.provider.Settings.Global.getString(
            getContentResolver(), "nebula_ablate");
        // Gas-FBO scale to pin the sweep at. The adaptive scaler makes gas scale
        // a dependent variable — drop a component and it just spends the savings
        // on a bigger FBO — so the sweep pins it at the scale the device actually
        // settles to in normal use, and component costs come out in shipping terms.
        float gasPin = 0f;
        try {
            String p = android.provider.Settings.Global.getString(
                getContentResolver(), "nebula_gaspin");
            if (p != null && !p.trim().isEmpty()) gasPin = Float.parseFloat(p.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "bad nebula_gaspin", e);
        }
        Log.i(TAG, "ablate=" + (ablate == null ? "(none)" : ablate) + " gasPin=" + gasPin);
        boolean wantHdr = !Prefs.HDR_OFF.equals(prefs.hdrMode());

        // Drive the panel for maximum highlight luminance: full screen
        // brightness for any surface, plus an explicit request for the
        // display's full HDR headroom so the brightest stars/flares can reach
        // peak nits rather than the compositor's conservative default.
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            // Max panel brightness for any surface. On devices that grant app
            // HDR headroom this still leaves room above SDR white for the
            // brightest stars; on those that don't (measured: One UI pins
            // getHdrSdrRatio at 1.0 for an scRGB surface at every brightness),
            // max SDR white is the only lever, so 1.0 is the right call there.
            lp.screenBrightness = 1.0f; // BRIGHTNESS_OVERRIDE_FULL
            window.setAttributes(lp);
            if (wantHdr && Build.VERSION.SDK_INT >= 34) {
                // Opportunistic: harmless where headroom isn't granted, and the
                // ratio listener re-tunes live on devices that do grant it.
                try {
                    window.setDesiredHdrHeadroom(HdrTuning.MAX_HEADROOM);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "setDesiredHdrHeadroom rejected", e);
                }
            }
        }

        HdrTuning hdrTuning = HdrTuning.from(display.display);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3); // v4: GLES 3.0 for sampler3D + glTexImage3D
        // HDR is opt-in and feature-detected; falls back to an 8-bit SDR
        // config when unsupported or disabled. The same object chooses the
        // config and creates the (optionally HDR-colorspace) window surface.
        HdrSurface hdr = new HdrSurface(prefs.hdrMode());
        glView.setEGLConfigChooser(hdr);
        glView.setEGLWindowSurfaceFactory(hdr);
        // Split-resolution: the window uses the best app surface Android grants,
        // while adaptive render scale governs only the low-res gas FBO.
        renderer = new NebulaRenderer(
            prefs.zoomMul(), prefs.frameCapFps(), hdr,
            prefs.renderScale(), hdrTuning, display, ablate, gasPin);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(glView);

        registerHdrRatioListener(display.display);
    }

    // The granted HDR/SDR ratio is dynamic — it shifts with panel brightness,
    // adaptive brightness and thermal headroom. Re-derive the tonemap tuning
    // whenever it changes and hot-swap it into the renderer (read every frame).
    private void registerHdrRatioListener(Display d) {
        boolean available = d != null && Build.VERSION.SDK_INT >= 34 && d.isHdrSdrRatioAvailable();
        Log.i(TAG, "HDR ratio listener: sdk=" + Build.VERSION.SDK_INT
            + " available=" + available
            + " initial=" + (available ? String.format("%.2f", d.getHdrSdrRatio()) : "n/a"));
        if (!available) return;
        hdrRatioListener = disp -> {
            Log.i(TAG, "HDR ratio changed live=" + String.format("%.2f", disp.getHdrSdrRatio()));
            HdrTuning t = HdrTuning.from(disp);
            if (renderer != null) renderer.setHdrTuning(t);
        };
        d.registerHdrSdrRatioChangedListener(getMainExecutor(), hdrRatioListener);
    }

    @Override
    public void onDetachedFromWindow() {
        if (hdrRatioListener != null && displayDiag != null && displayDiag.display != null
                && Build.VERSION.SDK_INT >= 34) {
            displayDiag.display.unregisterHdrSdrRatioChangedListener(hdrRatioListener);
            hdrRatioListener = null;
        }
        if (glView != null) {
            glView.onPause();
            glView = null;
        }
        renderer = null;
        super.onDetachedFromWindow();
    }

    static class NebulaRenderer implements GLSurfaceView.Renderer {

        private static final float[] QUAD = {
            -1f,-1f,  1f,-1f,  -1f,1f,
             1f,-1f,  1f, 1f,  -1f,1f
        };

        // ── Tweakable constants ─────────────────────────────────────────
        // Changing a value here updates the GPU shader AND CPU flare
        // scheduler in lock-step — no manual sync needed.

        // Star density distribution
        private static final float SD_FREQ_LO  = 0.028f;
        private static final float SD_FREQ_HI  = 0.05f;
        private static final float SD_W_LO     = 0.55f;
        private static final float SD_W_HI     = 0.45f;
        private static final float SD_SS_LO    = 0.38f;
        private static final float SD_SS_HI    = 0.58f;
        // ~1.9x the historical population (0.38/0.92). Bumped +20% over the
        // prior 0.24/0.78/0.54: each threshold is set so (1 - thresh) scales by
        // 1.2 across the whole density ramp, i.e. 20% more lit cells at every
        // density. 2x was once ~1ms too much, but the sky layer since freed
        // comp-pass headroom, so the extra lit cells fit under budget.
        private static final float STAR_FLOOR  = 0.088f;
        private static final float STAR_CEIL   = 0.736f;
        private static final float STAR_RANGE  = 0.648f;

        // Star grid and zoom
        private static final float STAR_DEN    = 80.0f;
        // Stars are sized in cell space, so their pixel size tracks surface
        // WIDTH (width/STAR_DEN per cell). A narrow portrait phone surface
        // therefore renders much smaller stars than a wide TV. uStarScale
        // multiplies the grid density to hold star pixel size constant relative
        // to STAR_REF_WIDTH: 1.0 for any surface >= the reference (every
        // Shield/TV config, so they are untouched), <1 on narrower surfaces so
        // stars grow (fewer, larger) to match the reference look.
        private static final float STAR_REF_WIDTH = 1920.0f;
        private static final float STAR_REF_DPI = 320.0f; // Shield's density: dpiBoost = 1 there
        private static final float STAR_SCALE_MIN = 0.30f;
        private static final float SZ_SPEED    = 0.0120f;
        private static final float SZ_MAX      = 0.75f;
        private static final float L0_OX = 0.0f,  L0_OY = 0.0f;
        private static final float L1_OX = 0.37f, L1_OY = 0.21f;
        private static final float L0_PHASE = 0.0f, L1_PHASE = 0.5f;

        // Sprinkle stars
        private static final float SP_DEN      = 800.0f;
        private static final float SP_CORE     = 1200.0f;
        private static final float SP_BRI      = 0.30f;
        private static final float SP_BASE     = 0.18f; // raised floor: endless faint stars beneath the bright ones
        private static final float SP_SS_LO    = 0.25f;
        private static final float SP_SS_HI    = 0.65f;
        // Thins the sprinkle field OUTSIDE the milky-way band. The mask
        // saturates (smoothstep on band) so the band proper — not just its
        // centreline — keeps its full count, and only the genuinely off-band sky
        // is thinned; the smoothstep edge keeps the transition seamless.
        // sprinkleLayer's dens only sets which cells light up
        // (thresh=0.97-dens*0.97), so this removes dots rather than dimming
        // them. Lit fraction is 0.03+0.97*dens, so the 0.03 floor means the
        // multiplier is NOT the count ratio: 0.78 yields ~20% fewer dots.
        private static final float SP_NONBAND_MUL = 0.78f;
        // Extra band-only sprinkle field on a FINER grid than SP_DEN. The main
        // SP_DEN grid saturates in the band core (every cell already lit), so
        // raising its density there adds nothing — a second, finer field is the
        // only way to raise the in-band count further. Baked into the sky layer,
        // so it costs nothing per frame. SP_BAND_BRI dims it so more dots don't
        // read as a brighter band. Tune SP_BAND_DEN for count, SP_BAND_BRI for
        // brightness. (When the sky layer is off it is computed live instead.)
        private static final float SP_BAND_DEN = 1450.0f;
        private static final float SP_BAND_BRI = 0.75f;
        // Faint-star carpet (band only): coarser grid than the sprinkles but
        // RESOLVED dots — ~0.6px cores at 1080p (CP_K sets radius ~0.15 cell).
        // v4.9.0 measurement: boosting sub-pixel sprinkle counts 7x moved the
        // visible dot ratio only to ~1.4x; footprint, not count, buys stars.
        private static final float CP_DEN      = 720.0f;
        private static final float CP_K        = 34.0f;
        private static final float CP_BRI      = 0.12f;
        // Braided-rift detail (second dust lane + fine mottle); was disabled
        // during the 2026-07-14 brightness triage, re-enabled once the grain
        // range compression fixed the all-or-nothing behaviour.
        private static final boolean BAND_BRAID = true;
        // Master milky-way-band brightness. The band's light comes from TWO
        // additive gains in two passes — the gas-pass broad halo (0.058) and the
        // comp-pass diffuse grain (0.084) — and their ratio sets the band's
        // character (soft glow vs native-res texture). Both ride this scale, so
        // tuning it changes brightness WITHOUT disturbing that balance. Does not
        // touch the band's resolved dots (band-only sprinkle field + faint-star
        // carpet), which keep their count regardless.
        private static final float BAND_BRI = 0.90f;

        // Band LUT: texel count and the bAlong half-range it covers around
        // the seed centre (on-screen range is ~±1.2; fetches clamp at edges).
        private static final int   BAND_LUT_N    = 256;
        private static final float BAND_LUT_HALF = 1.5f;

        // Erosion bite. The erosion USED to be applied as rm(d, ero*k, 1.0) — a
        // floor-lift-and-renormalise — whose top always maps back to 1.0, so the
        // erosion noise had mathematically ZERO effect as d->1:
        //   d=0.3 -> 58% texture range, d=0.7 -> 11%, d=0.85 -> 4%, d=1.0 -> 0%.
        // The densest gas was therefore smooth BY CONSTRUCTION, and dense =
        // bright, which is why the brightest patches always blobbed out. No
        // tonemap/HDR tuning could fix it: the texture was already gone inside
        // the raymarch. Applied multiplicatively instead, the erosion bites at
        // every density — at d=1 it still carves the full 1..(1-BITE) range.
        // Calibrated to match the old bite at mid density (d=0.5: old 0.375 =
        // x0.75 -> BITE 0.25; ero2 old 0.359 = x0.718 -> BITE2 0.28), so the
        // mid-range look is preserved while dense interiors gain their texture.
        // Dense masses do get thinner (they are now actually eroded) — that is
        // the point, but it is why these are tunable.
        private static final float ERO_BITE  = 0.28f;
        private static final float ERO2_BITE = 0.30f;
        private static final float ERO_HI    = 0.60f;

        // Galaxy haze
        private static final float HAZE_MUL    = 0.22f;

        // Star-forming cores: the densest gas hearts bloom toward white-pink
        // and ride the HDR chain, giving frames a luminous focal point.
        private static final float CORE_LO   = 0.40f;
        private static final float CORE_HI   = 0.72f;
        private static final float CORE_GAIN = 4.0f;

        // Nebula gas HDR: the gas rides a LOWER ceiling and a gentler ramp than
        // the stars. The star curve saturates by design — a point highlight
        // should slam to peak the moment it clears the knee — but the gas was
        // sharing it, so any mass past the knee jumped to near-peak at once.
        // Density variation inside a bright mass then mapped to almost the same
        // output (texture gone) at full palette saturation, across whatever area
        // the mass covered. Gas now climbs progressively and tops out partway up
        // the panel's range, leaving the top to stars, novas and the cores: a few
        // highlights blaze, large areas do not.
        private static final float GAS_HDR_CEIL = 0.3600f;  // 0.37 ->0.3145 ->0.2673 ->0.2272 (each -15%), +25% ->0.2840, now +27% ->0.36.  // ERO_HI carves interior form OUT of density, so cranking it thins the gas  // (mean x0.70 at 0.60). Brightness pays that back so form and presence rise together.
        private static final float GAS_HDR_KNEE = 1.25f; // gas starts boosting later than stars
        private static final float GAS_HDR_GAIN = 0.27f; // and climbs far more slowly once it does
        // Gas emission contrast (the lever the ceiling cuts above could not be):
        // the bulk emission is LINEAR in density, so a broad moderate-density
        // region accumulates into a large, uniform, fully saturated wash — the
        // "undifferentiated technicolor field." Dropping the HDR ceiling only
        // dims that wash uniformly; it stays a wash (the 0.37->0.2272 cuts above
        // are exactly that, and it still dominates the frame). This curve instead
        // suppresses the low-mid body while leaving the dense hearts (which reach
        // GAS_CONTRAST_KNEE) untouched, so the field reads as a few bright masses
        // over dark space, not one glow. FLOOR = how far the dimmest gas is pushed
        // down; KNEE = accumulated luminance at which gas returns to full
        // strength. Peaks are never boosted — average field brightness drops too.
        private static final float GAS_CONTRAST_FLOOR = 0.42f;
        private static final float GAS_CONTRAST_KNEE  = 0.85f;
        // Star-forming core carve-out (light approximation): cores are the
        // brightest gas, so a mask keyed on the PURE gas peak (gas.rgb, before
        // stars blend in) isolates them without a separate render target. Given
        // the wash is suppressed and warm gas reduced, gas peak > ~1.0 is
        // essentially only cores. Masked pixels (a) keep their pink instead of
        // desaturating to white with the rest of the bright gas, and (b) ride a
        // higher HDR ceiling than the wash, so a firing core blazes as a luminous
        // pink heart. Gating (rarity) is unchanged — this only changes how a core
        // that does fire is tonemapped. LO/HI map gas peak to mask 0..1.
        private static final float CORE_HDR_LO    = 1.30f; // gas peak where core carve-out begins (measured: bright wash tops out ~1.24, so this sits just above it)
        private static final float CORE_HDR_HI    = 2.50f; // gas peak for full carve-out (a firing, source-boosted core)
        // NEUTRALISED (2026-07-16). The carve-out keyed on gas brightness, but
        // measured gas peaks show the bright WASH reaches 1.24-1.5 — overlapping
        // CORE_HDR_LO — so it fired on ordinary bright gas and gave it back its
        // saturation and a raised ceiling: undifferentiated technicolor blobs,
        // the one thing the nebula must never do. Brightness cannot separate
        // cores from bright wash; only a true core signal (a second render
        // target from the gas pass) can. Until then cores stay folded into the
        // gas. Set CEIL>GAS_HDR_CEIL and DESAT_KEEP>0 to re-enable.
        private static final float CORE_HDR_CEIL  = GAS_HDR_CEIL;
        private static final float CORE_DESAT_KEEP = 0.0f;
        // Star HDR ramp scale. The star boost curve saturates almost as soon as a
        // star clears the knee: measured on the Shield (starGain 13.67, starMax
        // 8.44), stars at linear 3/4/6/8 all landed at boost 7.20/7.40/7.44/7.44
        // — a 2.7x spread of real brightness pinned into a 3% spread of output,
        // so most stars read as near-full. The intrinsic magnitudes are already
        // faint-skewed (mag=0.16+0.84*hm^3); it is this ramp that flattens them.
        // Scaling the gain down stretches the curve so magnitude differences
        // survive to the panel. The very brightest still reach peak (the curve
        // still saturates up there) — only the mid range spreads back out.
        // Tuned by curve arithmetic against the device's logged HDR tuning
        // (starGain 13.67, starMax 8.44, knee 2.03) — screencap histograms could
        // not adjudicate this (each capture run lands on a different camera
        // position, and the dot detector is dominated by sprinkles, not stars).
        // Boost at star linear 2/3/4/8: gain 1.0 -> 5.96/7.20/7.40/7.44 (the mid
        // range is pinned at the ceiling); 0.70 -> 5.04/6.78/7.25/7.44 (barely a
        // change); 0.45 -> 3.85/5.87/6.75/7.41; 0.35 -> 3.21/5.22/6.27/7.35.
        //
        // Set HIGH enough that the brightest stars hit the panel's max. Measured
        // on-device (debug build rendering starLum, which is pre-tonemap and so
        // invisible to a normal screencap): the brightest ordinary star peaks at
        // starLum ~4.2. Its output vs the 8.44 cap: 0.90 -> 100%, 0.80 -> 99.7%,
        // 0.60 -> 98.0%, 0.35 -> 89.9%. 0.90 puts it AT the cap.
        // This knob is NOT the way to get brightness variety — lowering it only
        // buys spread by dragging the top down. The population shape does that
        // job instead: see STAR_MAG_POW (fewer bright) and STAR_MAG_FLOOR
        // (deeper faint end).
        private static final float STAR_HDR_GAIN = 0.90f;

        // Star rendering
        private static final float SPIKE_THRESH = 0.65f;
        // Star magnitude: mag = FLOOR + (1-FLOOR)*hm^POW, hm uniform in [0,1].
        // FLOOR sets the faint end (and so the intrinsic RANGE, floor -> 1.0):
        // 0.16 gave only 6.25x, 0.08 doubles it to 12.5x.
        // POW sets the SHAPE — how many stars are bright vs dim. hm=1 always maps
        // to mag 1.0, so raising POW takes brightness AWAY from the population
        // without touching the brightest star: it pushes ever more of the field
        // toward the faint end at every level. E[hm^POW]=1/(POW+1), so the mean
        // magnitude falls as POW rises. Fraction of stars above SPIKE_THRESH
        // (0.65): POW 3 -> ~15%, POW 5 -> ~9%. This — not STAR_HDR_GAIN — is the
        // lever for "fewer bright stars, more dim ones".
        // The formula lives in three places (starLayer, the flare overlay, and
        // cpuStarMag for the CPU picker); they MUST agree, hence shared constants.
        private static final float STAR_MAG_FLOOR = 0.08f;
        private static final float STAR_MAG_POW   = 5.0f;

        // Distant star stratum: fills the empty gap between the near stars (1.0x
        // zoom speed) and the galaxies (0.25x). A slower, dimmer, denser star
        // field reads as genuinely more distant — real parallax, not the
        // phase-staggered crossfade of one speed that the two near layers are.
        // Runs on its OWN independent 2-phase cycle (like the galaxies), so it
        // never touches L0/L1_PHASE and the flare/nova scheduler stays untouched.
        // Its two calls are each gated behind their fade weight (frame-coherent,
        // no warp divergence) so each is free during its ~20% off-window.
        private static final float FAR_STAR_SPEED = 0.45f; // fraction of near-star zoom speed
        private static final float FAR_STAR_DEN   = 1.6f;  // grid-density multiple (denser -> smaller, more numerous)
        private static final float FAR_STAR_BRI   = 0.55f; // brightness multiple (distance -> fainter)
        // Flat star-density for the distant stratum, replacing the two vn()
        // clustering-field lookups. Those run BEFORE the sparse-star early-out,
        // so ~76% of pixels pay for them whether or not a cell holds a star —
        // the bulk of starLayer's per-pixel cost, doubled by this layer. The
        // distant field does not need its own clustering: it already gathers
        // toward the band via sBias, and finer clumping is invisible on a faint,
        // dense, far layer. 0.28 is the field's approximate mean, so the star
        // COUNT is preserved — only the clustering goes. Measured: spikes cost
        // 0.10ms and twinkle 1.14ms, so the vn() pair is what is worth cutting.
        private static final float FAR_STAR_DENS  = 0.28f;

        // Galaxy population: the fraction of grid cells holding a galaxy is
        // (1 - threshold). Small galaxies are the texture tier; lowering the
        // threshold populates more cells. 0.982 -> 0.970 takes the small
        // population from ~1.8% to ~3.0% of cells (~1.7x). The big/showpiece
        // tier stays rare (see GAL_BIG_THRESH). Cost is near-free: the
        // per-pixel cell lookup runs regardless of the threshold.
        private static final float GAL_SMALL_THRESH = 0.970f;
        // Showpiece-galaxy rarity: ~0.35% of cells, about one in view every
        // ~10 minutes. Shared with the CPU visibility gate (bigGalaxyVisible),
        // which must test the exact same threshold the shader does — if the two
        // disagree the gate can hide a galaxy the shader would have drawn.
        private static final float GAL_BIG_THRESH = 0.9965f;

        // Flare scheduling. Gaps walked 40-180s -> 24-104s -> 10-40s: placement
        // is verified on-screen (the rotation-aware picker), so the low visible
        // rate was purely the schedule. 10-40s gap + 4-8s duration ~= a flare
        // every ~15-50s (avg ~30s), frequent enough to notice without becoming
        // texture.
        private static final float FLARE_DUR_MIN = 4.0f;
        private static final float FLARE_DUR_RNG = 4.0f;
        private static final float FLARE_GAP_MIN = 10.0f;
        private static final float FLARE_GAP_RNG = 30.0f;
        // Flares only fire on brighter-than-average stars. Star magnitude is
        // mag=0.16+0.84*hm^3 with hm uniform, so E[hm^3]=1/4 and the MEAN mag is
        // 0.37. 0.45 sits comfortably above it, selecting the top ~30% (hm>0.70)
        // — a flare lands on a star that was already prominent, instead of a
        // faint one suddenly out-blazing its neighbours. The picker checks this
        // CPU-side against the same hash the shader uses.
        private static final float FLARE_MIN_MAG = 0.45f;
        // Novas are the rarest event, so they ride the genuinely brightest stars
        // — a stricter bar than the flares'. 0.70 selects the top ~14% (hm>0.86)
        // against the 0.37 mean. Kept below the point where the picker would
        // struggle to find a qualifying on-screen star within its 200 attempts.
        private static final float NOVA_MIN_MAG = 0.70f;
        // Fraction of the flare's duration spent rising. The old envelope was
        // sin^2 — symmetric, so it read as a soft swell rather than an event.
        // A real flare brightens abruptly and decays slowly.
        private static final float FLARE_RISE = 0.12f;
        // Decay exponent for the flare tail (lower = lingers longer). The shader
        // AMPLIFIES this: core/glow scale as fv^2 and spikes as fv^4, so the
        // envelope's exponent is effectively doubled/quadrupled on screen. The
        // old quadratic tail meant the core fell as k^4 and the spikes as k^8 —
        // down to 6% by the halfway point, which snapped out far too fast. 1.0
        // (linear) put the core on k^2 and the spikes on k^4; 0.75 slows it
        // further still (core k^1.5, spikes k^3).
        private static final float FLARE_DECAY_POW = 0.75f;
        // How fast a flaring star stops twinkling. The base star keeps rendering
        // under the flare overlay, and its twinkle drives the core, the halo, the
        // spike BRIGHTNESS and the spike LENGTH (spTight) — so an igniting star's
        // spikes visibly writhe. Damping tw->1.0 as the flare rises stills all of
        // them at once. Raised 2.5 -> 8.0: at 2.5 the damping tracked the
        // envelope, so twinkle crept back in DURING the fade (steady only while
        // env*mag > ~0.4). At 8.0 the star stays fully steady until env*mag falls
        // below ~0.125 — i.e. past ~80% of the event, by which point the flare
        // core is under 2% of peak and effectively gone.
        private static final float FLARE_STEADY = 8.0f;

        // Nova scheduling: the rarest event. One star swells to a brilliant HDR
        // point (~2s rise), holds, and fades over ~25s with a white->amber
        // shift. First one arrives within ~6-18 min so a normal session sees
        // one; after that, 20-60 min apart.
        private static final float NOVA_DUR_MIN   = 24.0f;
        private static final float NOVA_DUR_RNG   = 10.0f;
        private static final float NOVA_GAP_MIN   = 1200.0f;
        private static final float NOVA_GAP_RNG   = 2400.0f;
        private static final float NOVA_FIRST_MIN = 360.0f;
        private static final float NOVA_FIRST_RNG = 720.0f;

        // Layer offsets for Java (matches shader L0/L1 constants)
        private static final float[][] LAYER_OFF = {
            {L0_OX, L0_OY}, {L1_OX, L1_OY}
        };
        private static final float[] LAYER_PHASE = {L0_PHASE, L1_PHASE};

        // GLSL #define blocks — injected after #version so shader code uses
        // named constants instead of magic numbers.
        private static final String GAS_DEFS =
            "#define SZ_SPEED "  + SZ_SPEED  + "\n" +
            "#define SZ_MAX "    + SZ_MAX    + "\n" +
            "#define STAR_DEN "  + STAR_DEN  + "\n" +
            "#define L0_OX "     + L0_OX     + "\n" +
            "#define L0_OY "     + L0_OY     + "\n" +
            "#define L1_OX "     + L1_OX     + "\n" +
            "#define L1_OY "     + L1_OY     + "\n" +
            "#define HAZE_MUL "  + HAZE_MUL  + "\n" +
            "#define BAND_BRI "  + BAND_BRI  + "\n" +
            "#define SD_FREQ_LO " + SD_FREQ_LO + "\n" +
            "#define SD_FREQ_HI " + SD_FREQ_HI + "\n" +
            "#define SD_W_LO "    + SD_W_LO    + "\n" +
            "#define SD_W_HI "    + SD_W_HI    + "\n" +
            "#define SD_SS_LO "   + SD_SS_LO   + "\n" +
            "#define SD_SS_HI "   + SD_SS_HI   + "\n" +
            "#define CORE_LO "    + CORE_LO    + "\n" +
            "#define CORE_HI "    + CORE_HI    + "\n" +
            "#define ERO_BITE "  + ERO_BITE  + "\n" +
            "#define ERO2_BITE " + ERO2_BITE + "\n" +
            "#define ERO_HI "    + ERO_HI    + "\n" +
            "#define CORE_GAIN "  + CORE_GAIN  + "\n" +
            "#define GAS_CONTRAST_FLOOR " + GAS_CONTRAST_FLOOR + "\n" +
            "#define GAS_CONTRAST_KNEE "  + GAS_CONTRAST_KNEE  + "\n";

        private static final String COMP_DEFS =
            "#define SD_FREQ_LO "  + SD_FREQ_LO  + "\n" +
            "#define SD_FREQ_HI "  + SD_FREQ_HI  + "\n" +
            "#define SD_W_LO "     + SD_W_LO     + "\n" +
            "#define SD_W_HI "     + SD_W_HI     + "\n" +
            "#define SD_SS_LO "    + SD_SS_LO    + "\n" +
            "#define SD_SS_HI "    + SD_SS_HI    + "\n" +
            "#define STAR_FLOOR "  + STAR_FLOOR  + "\n" +
            "#define STAR_CEIL "   + STAR_CEIL   + "\n" +
            "#define STAR_RANGE "  + STAR_RANGE  + "\n" +
            "#define STAR_DEN "    + STAR_DEN    + "\n" +
            "#define SZ_SPEED "    + SZ_SPEED    + "\n" +
            "#define SZ_MAX "      + SZ_MAX      + "\n" +
            "#define L0_OX "       + L0_OX       + "\n" +
            "#define L0_OY "       + L0_OY       + "\n" +
            "#define L1_OX "       + L1_OX       + "\n" +
            "#define L1_OY "       + L1_OY       + "\n" +
            "#define L0_PHASE "    + L0_PHASE    + "\n" +
            "#define L1_PHASE "    + L1_PHASE    + "\n" +
            "#define SP_DEN "      + SP_DEN      + "\n" +
            "#define SP_CORE "     + SP_CORE     + "\n" +
            "#define SP_BRI "      + SP_BRI      + "\n" +
            "#define SP_BASE "     + SP_BASE     + "\n" +
            "#define SP_SS_LO "    + SP_SS_LO    + "\n" +
            "#define SP_SS_HI "    + SP_SS_HI    + "\n" +
            "#define SP_NONBAND_MUL " + SP_NONBAND_MUL + "\n" +
            "#define BAND_BRI " + BAND_BRI + "\n" +
            "#define SP_BAND_DEN " + SP_BAND_DEN + "\n" +
            "#define SP_BAND_BRI " + SP_BAND_BRI + "\n" +
            "#define CP_DEN "      + CP_DEN      + "\n" +
            "#define CP_K "        + CP_K        + "\n" +
            "#define CP_BRI "      + CP_BRI      + "\n" +
            "#define GAS_HDR_CEIL " + GAS_HDR_CEIL + "\n" +
            "#define GAS_HDR_KNEE " + GAS_HDR_KNEE + "\n" +
            "#define GAS_HDR_GAIN " + GAS_HDR_GAIN + "\n" +
            "#define CORE_HDR_LO "    + CORE_HDR_LO    + "\n" +
            "#define CORE_HDR_HI "    + CORE_HDR_HI    + "\n" +
            "#define CORE_HDR_CEIL "  + CORE_HDR_CEIL  + "\n" +
            "#define CORE_DESAT_KEEP " + CORE_DESAT_KEEP + "\n" +
            "#define STAR_HDR_GAIN " + STAR_HDR_GAIN + "\n" +
            "#define FLARE_STEADY " + FLARE_STEADY + "\n" +
            "#define SPIKE_THRESH " + SPIKE_THRESH + "\n" +
            "#define STAR_MAG_FLOOR " + STAR_MAG_FLOOR + "\n" +
            "#define STAR_MAG_POW " + STAR_MAG_POW + "\n" +
            "#define FAR_STAR_SPEED " + FAR_STAR_SPEED + "\n" +
            "#define FAR_STAR_DEN "   + FAR_STAR_DEN   + "\n" +
            "#define FAR_STAR_BRI "   + FAR_STAR_BRI   + "\n" +
            "#define FAR_STAR_DENS "  + FAR_STAR_DENS  + "\n" +
            "#define GAL_SMALL_THRESH " + GAL_SMALL_THRESH + "\n" +
            "#define GAL_BIG_THRESH " + GAL_BIG_THRESH + "\n";

        private static final String VERT_ES3 =
            "#version 300 es\n" +
            "in vec2 aPos;\n" +
            "out vec2 vUv;\n" +
            "void main(){\n" +
            "  vUv=aPos*0.5+0.5;\n" +
            "  gl_Position=vec4(aPos,0.0,1.0);\n" +
            "}\n";

        // ── PHASE 0 PERF SPIKE ────────────────────────────────────────────────
        // Minimal volumetric cloud raymarcher with analytic 3D noise, empty-space
        // skipping, early-out, and a 5-tap light march. Throwaway — its only job is
        // to measure Tegra X1 raymarch throughput (go/no-go for the v4 build).
        // Analytic noise is a CONSERVATIVE bound; 3D-texture sampling would be cheaper.
        // ── v4 PASS 1: the raymarched gas, rendered into a low-res FBO.
        // Outputs rgb = gas emission (pre-tonemap linear), a = transmittance T,
        // so the full-res composite pass can place stars BEHIND the gas. ────────
        private static final String FRAG_GAS =
            "#version 300 es\n" +
            GAS_DEFS +
            "precision highp float;\n" +
            "precision highp sampler3D;\n" +
            "uniform float uTime;\n" +
            "uniform vec2  uRes;\n" +       // gas FBO resolution (same aspect as the panel)
            "uniform float uZoom;\n" +      // star zoom speed (haze rides the star grid)
            "uniform float uStarScale;\n" + // grid-density scale (1.0 = reference width)
            "uniform vec2 uSeed;\n" +
            "uniform sampler3D uNoise;\n" + // R = value fbm, G = inverted Worley (billow)
            // 256x1 RGBA: per-frame CPU bake of the 1D along-band terms
            // (R=rift meander, G=rift depth noise, B=star-cloud amp noise,
            // A=bulge). One fetch replaces four vn() evaluations per pixel.
            "uniform sampler2D uBandLut;\n" +
            "in vec2 vUv;\n" +
            "out vec4 fragColor;\n" +
            // ── Galaxy haze (v3.1 pointillistic): SOFT, so it lives in this low-res
            // pass; it rides the same zooming grid as the comp pass's stars so the
            // two read as one entity. ─────────────────────────────────────────────
            "float h1(vec2 i){ vec2 p=fract(i*vec2(0.1031,0.1030)); p+=dot(p,p+19.19); return fract(p.x*p.y); }\n" +
            "float vn(vec2 p){\n" +
            "  vec2 i=floor(p),f=fract(p);\n" +
            "  vec2 u=f*f*f*(f*(f*6.0-15.0)+10.0);\n" +
            "  return mix(mix(h1(i),h1(i+vec2(1,0)),u.x),\n" +
            "             mix(h1(i+vec2(0,1)),h1(i+vec2(1,1)),u.x),u.y);\n" +
            "}\n" +
            "vec3 hazeLayer(vec2 uv,float den,float ox,float oy){\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  float gdn=vn(gp*SD_FREQ_LO+vec2(ox*0.1,oy*0.1))*SD_W_LO+vn(gp*SD_FREQ_HI+11.0)*SD_W_HI;\n" +
            "  float r=smoothstep(SD_SS_LO,SD_SS_HI,gdn); r*=r;\n" +
            "  float glow=exp(-2.5/(r+0.001))*step(0.001,r);\n" +
            "  vec3 gcol=mix(vec3(0.40,0.42,0.62),vec3(1.0,0.92,0.80),glow);\n" +
            "  return gcol*glow*glow*HAZE_MUL;\n" +
            "}\n" +
            "float rm(float v,float l,float h){ return clamp((v-l)/(h-l),0.0,1.0); }\n" +
                        // ── Phase 3: density from the precomputed 3D noise TEXTURE (uniform, no
            // analytic noise / branching) — R=value fbm, G=inverted Worley billow. ──
            "float dens(vec3 p){\n" +
            "  float base=texture(uNoise,p*0.062).r;\n" +             // larger billow base
            // Coverage: low-frequency gate makes fewer, larger cloud masses while
            // still rejecting the weakest saddles between them.
            "  float cov=smoothstep(0.26,0.68,texture(uNoise,p*0.022+0.31).r);\n" +
            "  float d=rm(base,1.0-cov,1.0)*cov;\n" +
            "  float ero=texture(uNoise,p*0.22).g;\n" +              // broad Worley erosion
            "  d=rm(d,ero*ERO_BITE,1.0);\n" +
            "  float ero2=texture(uNoise,p*0.58).g;\n" +             // fine erosion for detailed edges
            "  d=rm(d,ero2*ERO2_BITE,1.0);\n" +
            // rm() renormalises, so its bite -> 0 as d -> 1 (dense gas smooth by
            // construction). This multiplicative term does NOT renormalise, so it
            // still carves at d=1 — it only ADDS to the rm contrast above, rather
            // than replacing it (replacing it was weaker everywhere and made the
            // whole nebula smoother).
            "  d*=(1.0-ERO_HI*(0.68*ero+0.32*ero2));\n" +
            "  return pow(d,1.8);\n" +                              // fuller interiors for larger readable masses
            "}\n" +
            // Mid-distance density (3 fetches): keeps the coarse Worley erosion
            // for readable cloud edges but drops the fine ero2 term (p*0.58) whose
            // sub-pixel detail aliases into shimmer at this range.
            "float densMid(vec3 p){\n" +
            "  float base=texture(uNoise,p*0.062).r;\n" +
            "  float cov=smoothstep(0.26,0.68,texture(uNoise,p*0.022+0.31).r);\n" +
            "  float d=rm(base,1.0-cov,1.0)*cov;\n" +
            "  float ero=texture(uNoise,p*0.22).g;\n" +
            "  d=rm(d,ero*ERO_BITE,1.0);\n" +
            "  d=rm(d,0.20,1.0);\n" +
            "  d*=(1.0-ERO_HI*ero);\n" +
                                // approximate the fine erosion's average
            "  return pow(d,1.8)*0.95;\n" +
            "}\n" +
            // Cheap far-field density (2 low-freq fetches, no erosion detail): used
            // beyond the detail horizon where cauliflower edges are sub-pixel anyway.
            // Low-freq coords are also cache-friendly — big strides were thrashing
            // the texture cache with the full 4-fetch dens().
            "float densFar(vec3 p){\n" +
            "  float base=texture(uNoise,p*0.062).r;\n" +
            "  float cov=smoothstep(0.26,0.68,texture(uNoise,p*0.022+0.31).r);\n" +
            "  float d=rm(base,1.0-cov,1.0)*cov;\n" +
            "  d=rm(d,0.18,1.0);\n" +                                // approximate the erosion's average bite
            "  return pow(d,1.8)*0.9;\n" +
            "}\n" +
            // ── Phase 2: HG phase (silver lining) + powder + violet ambient + palette ─
            "float hg(float c,float g){ float g2=g*g; return (1.0-g2)/pow(max(1.0+g2-2.0*g*c,1e-3),1.5); }\n" +
            "void main(){\n" +
            "  vec2 uv=vUv*2.0-1.0; uv.x*=uRes.x/uRes.y;\n" +
            "  vec3 ro=vec3(sin(uTime*0.05*uZoom)*0.7+sin(uTime*0.0171*uZoom)*0.45+uSeed.x*50.0,cos(uTime*0.037*uZoom)*0.5+cos(uTime*0.0123*uZoom)*0.35+uSeed.y*50.0,uTime*0.40*uZoom+uSeed.x*37.0);\n" + // fly forward + gentle drift (off-axis)
            "  vec3 rd=normalize(vec3(uv,1.5));\n" +
            "  vec3 ldir=normalize(vec3(0.55,0.5,-0.35));\n" +
            // nebula colour: v3.1's purple-centred 4-stop palette, driven by a
            // drifting large-scale region field (warm accents stay rare).
            "  float reg=texture(uNoise,vec3(uv*0.35,uTime*0.02)).r;\n" +
            // Palette excursion: a very slow seed occasionally pushes the whole
            // scene warm-rose or deep blue for a few minutes, so a long session
            // sees genuinely different moods, not one constant violet.
            "  float excN=texture(uNoise,vec3(uTime*0.0011+uSeed.x,0.73,0.29)).r;\n" +
            "  float tempBias=0.35*(1.0-smoothstep(0.20,0.38,excN))-0.24*smoothstep(0.62,0.80,excN);\n" +
            "  float temp=clamp((reg-0.5)*1.5+0.60+tempBias,0.0,1.0);\n" +
            "  vec3 warm=vec3(0.88,0.55,0.26);\n" +  // muted amber (was 1.00,0.60,0.24 — too bright/yellow; redder stops went brown at low emission)
            "  vec3 pink=vec3(0.96,0.28,0.60);\n" +  // magenta-pink
            "  vec3 midc=vec3(0.49,0.14,0.94);\n" +  // deep violet
            "  vec3 cool=vec3(0.31,0.50,1.00);\n" +  // blue
            "  vec3 tcol = (temp<0.33) ? mix(warm,pink,temp/0.33)\n" +
            "             : (temp<0.66) ? mix(pink,midc,(temp-0.33)/0.33)\n" +
            "                           : mix(midc,cool,(temp-0.66)/0.34);\n" +
            "  vec3 sunCol=tcol;\n" +
            // Warm regions only read as luminous gold, never as dim brown: boost
            // emission toward the warm end, and let the ambient follow the region
            // colour there (fixed violet ambient greyed dim orange into mud).
            "  float warmSide=smoothstep(0.10,0.45,temp);\n" +
            "  float warmB=1.0+0.15*(1.0-warmSide);\n" + // was 0.45: warm gas read too bright/blobby. Keeps a small lift off dim-brown mud without pumping.
            "  vec3 ambCol=mix(vec3(0.09,0.07,0.24),tcol*0.30,mix(0.75,0.40,warmSide));\n" +
            "  float t=1.35+fract(sin(dot(gl_FragCoord.xy,vec2(41.3,289.1))+uTime)*43758.5)*0.08;\n" + // start beyond camera-origin gas; prevents full-frame color wash when flying through a cloud
            "  float T=1.0; vec3 col=vec3(0.0);\n" +
            "#ifndef ABL_NO_MARCH\n" +
            // A single macro front per pixel, not a per-sample volume contour:
            // this gives the dust layer one readable silhouette and one matching rim.
            "  vec2 frontDrift=vec2(sin(uTime*0.031),cos(uTime*0.027))*0.55;\n" +
            "  vec3 fp=ro+rd*7.5+vec3(frontDrift,0.0);\n" +
            "  float frontNoise=texture(uNoise,fp*0.050+vec3(2.7,5.1,uTime*0.004)).r-0.5;\n" +
            "  float frontDetailNoise=texture(uNoise,fp*0.115+vec3(8.4,2.2,uTime*0.006)).r-0.5;\n" +
            "  float frontBreak=smoothstep(0.30,0.76,texture(uNoise,fp*0.085+vec3(4.8,8.2,uTime*0.005)).r);\n" +
            "  float front=uv.y+0.20*uv.x+0.18*sin((uv.x+frontDrift.x*0.12)*2.25+0.7+frontDrift.y*0.18)+frontNoise*0.32+frontDetailNoise*0.18+0.02;\n" +
            "  float frontHalo=exp(-front*front*18.0)*smoothstep(-0.16,0.20,front);\n" +
            // ── NEBULA shading: highly-transparent EMISSIVE gas. No light march,
            // no phase — the gas GLOWS (emission nebula), it is not sunlit cloud.
            // Very low extinction: rays cross whole masses; stars shine through.
            // 42 steps + faster growth + bigger empty strides: keeps the deep
            // volumetric reach while leaving thermal headroom at 20fps.
            "  float dPrev=0.0;\n" +                                 // previous sample's density (for boundary rims)
            "  for(int i=0;i<42;i++){\n" +
            "    if(T<0.07) break;\n" +                              // raised early-out: imperceptible, restores termination on translucent gas
            // Distance-adaptive stepping: near gas finely sampled, far gas coarser
            // (it is smaller on screen) — the step budget reaches far deeper.
            "    float g=1.0+t*0.10;\n" +
            "    vec3 p=ro+rd*t;\n" +
            "    float nearAmt;\n" +
            "    float d;\n" +
            "    if(t<6.0){ nearAmt=1.0; d=dens(p); }\n" +
            "    else if(t<11.0){ nearAmt=1.0-smoothstep(6.0,11.0,t); d=mix(densMid(p),dens(p),nearAmt); }\n" +
            "    else if(t<20.0){ nearAmt=0.0; d=mix(densFar(p),densMid(p),1.0-smoothstep(11.0,20.0,t)); }\n" +
            "    else { nearAmt=0.0; d=densFar(p); }\n" +           // three-stage LOD: fine erosion fades first, then coarse
            "    float cameraFade=smoothstep(1.45,2.9,t);\n" +
            // Dither breaks far-field 8-bit texture banding; near gas is sampled
            // densely enough that banding is invisible, so kill the dither there
            // (it accumulated into visible static when the nebula filled the screen).
            // Also fade it out with density: banding only shows in thin gas, and
            // in dense mid/far masses the accumulated dither read as dark stipple.
            "    float dith=mix(0.010,0.0,nearAmt)*(1.0-smoothstep(0.12,0.35,d));\n" +
            "    d=max(d+(fract(sin(dot(p.xy+vec2(p.z),vec2(12.9898,78.233)))*43758.55)-0.5)*dith,0.0);\n" +
            "    d*=cameraFade;\n" +
            "#ifdef ABL_NO_DUST\n" +
            "    float dustD=0.0;\n" +
            "    float dustOcc=0.0;\n" +
            "#else\n" +
            "    float dustMacro=smoothstep(0.53,0.80,texture(uNoise,p*0.038+vec3(6.3,1.7,0.0)).r);\n" +
            "    float nearDust=(1.0-smoothstep(4.0,12.0,t))*cameraFade;\n" +
            "    float dustD=dustMacro*nearDust*(0.38+0.50*frontBreak);\n" +
            "    float dustOcc=dustD*dustD;\n" +
            "#endif\n" +
            "    if(d>0.01){\n" +
            "      float dt=0.11*g;\n" +
            "      vec3 emit=(tcol*d*0.34+ambCol*d*0.18)*warmB;\n" +  // dimmer interiors; rims carry more of the shape
            "      emit*=1.0-0.50*dustOcc;\n" +
            // Relief + rims fade into the NEAR layers; mid/rear stays pure soft
            // glow, avoiding a hard distance where dark blobs become bright rims.
            "#ifndef ABL_NO_RELIEF\n" +
            "      if(nearAmt>0.001){\n" +
            "        float frontDetail=smoothstep(0.34,0.82,texture(uNoise,p*0.085+vec3(9.1,3.8,uTime*0.003)).r);\n" +
            "        float ds=densFar(p);\n" +
            "        float dlit=densFar(p+ldir*0.34);\n" +
            "        float lit=clamp((ds-dlit)*9.0,0.0,1.0);\n" +
            // Softened directional relief: an emission nebula glows from within
            // and has no true sunlit/shadowed side, so this is only a faint depth
            // cue now — near side 0.70..1.60x (was a harsh 0.38..2.53x that read
            // as an unrealistic cloud shadow when a mass loomed close).
            "        emit*=mix(1.0,0.70+0.90*lit,nearAmt);\n" +
            // EDGE: ionization-front rim — fires when the ray crosses a boundary
            // (density jumping from ~nothing to substantial between samples).
            "        float rim=smoothstep(0.015,0.16,d-dPrev)*clamp(1.0-dPrev*6.0,0.0,1.0);\n" +
            "        emit+=mix(tcol,vec3(1.0,0.62,0.92),0.35)*rim*1.15*nearAmt;\n" +
            "        float frontGrain=0.42+0.82*frontDetail;\n" +
            "        float shell=smoothstep(0.018,0.10,d)*(1.0-smoothstep(0.22,0.48,d));\n" +
            "        emit+=mix(tcol,vec3(1.0,0.48,0.90),0.30)*frontHalo*(d*0.20+shell*0.68)*frontGrain*0.35*nearAmt;\n" +
            "      }\n" +
            "#endif\n" +
            // STAR-FORMING CORE: the densest hearts glow white-pink. Gated by a
            // very-low-frequency seed so only some masses ignite — a localized
            // brilliance the eye is drawn to, not a global interior brightening.
            // Added after relief (cores are self-luminous, not sunlit) but before
            // distance falloff (deep cores still recede); dust still occludes.
            "#ifndef ABL_NO_CORE\n" +
            "      float coreAmt=smoothstep(CORE_LO,CORE_HI,d);\n" +
            "      if(coreAmt>0.001){\n" +
            "        float coreGate=smoothstep(0.50,0.76,texture(uNoise,p*0.016+vec3(3.7,7.3,1.9)).r);\n" +
            // Mid-frequency knots give the heart internal structure — a few
            // brilliant clumps inside the dense mass, not an even wash.
            "        float knot=smoothstep(0.52,0.82,texture(uNoise,p*0.30+vec3(1.3,4.1,6.2)).r);\n" +
            // Gate floor 0.12: every dense heart carries some inner light; the
            // gated regions blaze at full CORE_GAIN. Cores were a triple product
            // of sparse gates and effectively never appeared in normal viewing.
            "        emit+=mix(tcol,vec3(1.0,0.86,0.96),0.65)*coreAmt*coreAmt*coreAmt*mix(0.12,1.0,coreGate)*mix(0.25,1.0,knot)*CORE_GAIN*(1.0-0.50*dustOcc);\n" +
            "      }\n" +
            "#endif\n" +
            // Distance falloff: deep gas contributes progressively less, so the
            // mid/rear stack reads as faint depth, not an accumulated bright wall.
            "      emit*=1.0/(1.0+t*0.055);\n" +
            "      col+=T*vec3(0.004,0.003,0.012)*dustD*dt;\n" +
            "      col+=T*emit*dt;\n" +
            "      T*=exp(-(d*0.08+dustOcc*0.50)*dt);\n" +         // gas + visible foreground dust extinction
            "      t+=dt;\n" +
            "    } else if(dustD>0.015){\n" +
            "      float dt=0.11*g;\n" +
            "      col+=T*vec3(0.003,0.003,0.010)*dustD*dt;\n" +
            "      T*=exp(-dustOcc*0.50*dt);\n" +
            "      t+=dt;\n" +
            "    } else { t+=0.28*g; }\n" +
            "    dPrev=d;\n" +
            "    if(t>48.0) break;\n" +                              // deep march range
            "  }\n" +
            "#endif\n" +
            "  col*=0.95;\n" + // gain for the tonemap (slightly dimmer overall)
            // Emission contrast: suppress the broad low-mid wash, keep the dense
            // hearts. Drives the gas from a uniform saturated field toward a few
            // bright masses over dark space — the differentiation the linear
            // emission never had, and which ceiling cuts could not create. Keyed
            // on peak channel (hue-preserving) and never exceeds 1.0, so no
            // highlight is boosted; the mid body just falls away.
            "  float gcl=max(max(col.r,col.g),col.b);\n" +
            "  col*=mix(GAS_CONTRAST_FLOOR,1.0,smoothstep(0.0,GAS_CONTRAST_KNEE,gcl));\n" +

            // Galaxy haze + deep-space floor BEHIND the gas (same three-phase star
            // zoom as the comp pass, so haze and stars move as one entity).
            "  vec3 hz=vec3(0.0);\n" +
            "#ifndef ABL_NO_HAZE\n" +
            "  float hzSP=SZ_SPEED*uZoom;\n" +
            "  float hzPh=uTime*hzSP;\n" +
            "  float t1=fract(hzPh+0.000);\n" +
            "  float t2=fract(hzPh+0.333);\n" +
            "  float t3=fract(hzPh+0.667);\n" +
            "  float f1=smoothstep(0.10,0.30,t1)*(1.0-smoothstep(0.70,0.90,t1));\n" +
            "  float f2=smoothstep(0.10,0.30,t2)*(1.0-smoothstep(0.70,0.90,t2));\n" +
            "  float f3=smoothstep(0.10,0.30,t3)*(1.0-smoothstep(0.70,0.90,t3));\n" +
            "  vec2 sc=vUv-0.5; sc.y*=uRes.y/uRes.x;\n" +
            "  vec2 sr1=vec2(sc.x*0.951-sc.y*0.309, sc.x*0.309+sc.y*0.951);\n" +
            "  vec2 sr2=vec2(sc.x*0.423-sc.y*0.906, sc.x*0.906+sc.y*0.423);\n" +
            "  vec2 sr3=vec2(-sc.x*0.602-sc.y*0.799, sc.x*0.799-sc.y*0.602);\n" +
            "  hz+=hazeLayer(sr1/exp(t1*SZ_MAX)+0.5+uSeed,STAR_DEN*uStarScale,L0_OX,L0_OY)*f1;\n" +
            "  hz+=hazeLayer(sr2/exp(t2*SZ_MAX)+0.5+uSeed,STAR_DEN*uStarScale,L1_OX,L1_OY)*f2*0.85;\n" +
            "  hz+=hazeLayer(sr3/exp(t3*SZ_MAX)+0.5+uSeed,STAR_DEN*uStarScale,0.71,0.53)*f3*0.70;\n" +
            "  hz+=vec3(0.0);\n" +
            "#endif\n" +
            "  float farShape=0.0;\n" +
            "#ifndef ABL_NO_FAR\n" +
            // Single mid-depth slab (was three at 55/72/95): the three stacked
            // layers cost ~4ms and read as one faint haze anyway. One sample at
            // ~65 with full weight matches the old weighted-sum brightness.
            "  vec3 pFar=ro+rd*65.0;\n" +
            "  float farBase=texture(uNoise,pFar*0.062).r;\n" +
            "  float farCov=smoothstep(0.26,0.68,texture(uNoise,pFar*0.022+0.31).r);\n" +
            "  float farD=rm(farBase,1.0-farCov,1.0)*farCov;\n" +
            "  float farEro=texture(uNoise,pFar*0.22).g;\n" +
            "  farD=rm(farD,farEro*0.24,1.0);\n" +
            "  farShape=pow(farD,2.0);\n" +
            "#endif\n" +
            // Band gaussian computed before far-gas: the far blobs are dimmed
            // inside the band core so they stop competing with it — measured
            // v4.9.0, they sat in the same luminance/scale class and the band
            // read as one more blotch.
            "  float bandPos=rd.y*1.9+0.30*rd.x+0.22*sin(uTime*0.0093);\n" +
            "  float band=exp(-bandPos*bandPos*3.0);\n" +
            "#ifndef ABL_NO_FAR\n" +
            "  float farReg=texture(uNoise,vec3(pFar.xy*0.03,uTime*0.01)).r;\n" +
            "  float farTemp=clamp((farReg-0.5)*1.4+0.55+tempBias*0.8,0.0,1.0);\n" +
            "  vec3 farCol=(farTemp<0.5)?mix(pink,midc,farTemp/0.5):mix(midc,cool,(farTemp-0.5)/0.5);\n" +
            // Cool/violet shift: keeps far gas off the band's neutral-warm axis.
            "  farCol*=vec3(0.88,0.92,1.10);\n" +
            "  col+=T*farCol*farShape*0.30*(1.0-0.35*band);\n" +
            "#endif\n" +
            // Milky-way band, gas-pass share: only the broad SOFT halo lives
            // here — this FBO is 0.10-0.5x resolution and bilinear-upsampled,
            // so any detail finer than ~3 screen px cannot survive it. The
            // textured majority of the band's light (grain, dust braiding,
            // faint-star carpet) is added at native res in the comp pass; the
            // two stay in register because both recompute the same terms from
            // the shared vn(). Gain split: 0.12 halo here + 0.135*avg-grain
            // in the comp pass. Panel-walked (2026-07-14): halo at 0.12 reads
            // right, but the comp grain glow was identified as the too-bright
            // component and halved from its capture-derived 0.27.
            "#ifndef ABL_NO_BANDGAS\n" +
            "  float bAlong=rd.x*1.66-rd.y*0.26+uSeed.x*5.0;\n" +
            // LUT covers bAlong in [center-1.5, center+1.5]; on-screen range
            // is ~±1.2, and the fetch clamps at the edges.
            "  vec4 bl=texture(uBandLut,vec2((bAlong-uSeed.x*5.0)*0.33333+0.5,0.5));\n" +
            "  float riftPos=bandPos*3.2+(bl.r-0.5)*1.6;\n" +
            "  float rift=1.0-0.60*exp(-riftPos*riftPos*5.0)*(0.35+0.65*bl.g);\n" +
            // Per-seed bulge hotspot (baked in LUT alpha): the real band
            // brightens ~5x toward the galactic centre; centre position spans
            // +-4 along-band units so most seeds keep it off-screen and some
            // sessions get the core.
            "  float bulge=bl.a;\n" +
            "  float bandAmp=0.60+0.80*bl.b*bl.b+1.0*bulge;\n" +
            "  float bandGrain=texture(uNoise,vec3(rd.xy*1.2+uSeed,0.37)+vec3(uTime*0.0021)).r;\n" +
            // Soft knee at 0.8: where bulge, star-cloud amp and grain peaks
            // coincide the product spikes to ~3x typical — compress only that
            // top end, the body of the band passes through untouched.
            "  float bx=band*(0.35+0.65*bandGrain)*bandAmp*rift;\n" +
            "  bx/=1.0+max(bx-0.8,0.0)*0.5;\n" +
            // Broad band halo lowered 0.12 -> 0.0725 to match the comp grain drop
            // (the resolved sprinkle field now carries the band's brightness).
            // 0.0725 -> 0.058: -20% band brightness, applied to BOTH this halo and
            // the comp-pass grain gain so their balance is preserved. Further
            // trims ride BAND_BRI (the master scale) rather than this number.
            "  col+=T*mix(vec3(0.20,0.185,0.205),vec3(0.245,0.205,0.165),bulge)*bx*0.058*BAND_BRI;\n" +
            "#endif\n" +
            "  col+=T*hz;\n" +
            "  fragColor=vec4(col,T);\n" +
            "}\n";

        // ── v4 PASS 2: full-resolution composite. Samples the low-res gas
        // FBO (bilinear — soft, as gas should be), draws the starfield, galaxy
        // haze and accent flares at NATIVE resolution (pin-sharp points/spikes),
        // composites them behind the gas via its transmittance, then applies
        // the v3.1 output chain (hue drift, fade, desat, tonemap, HDR). ──────
        private static final String FRAG_COMP =
            "#version 300 es\n" +
            COMP_DEFS +
            "precision highp float;\n" +
            "uniform float uTime;\n" +
            "uniform vec2  uRes;\n" +
            "uniform float uZoom;\n" +
            "uniform float uStarScale;\n" + // grid-density scale (1.0 = reference width)
            "uniform float uHdr;\n" +
            "uniform float uHdrKnee;\n" +
            "uniform float uHdrGain;\n" +
            "uniform float uHdrMax;\n" +
            "uniform float uHdrStarGain;\n" +
            "uniform float uHdrStarMax;\n" +
            "uniform vec4 uStarFlare;\n" +
            "uniform vec4 uNova;\n" +   // xy = cell, z = envelope*magnitude, w = layer id
            "uniform float uNovaP;\n" + // nova progress 0..1 (drives white -> amber shift)
            "uniform sampler2D uBandLut;\n" + // shared with the gas pass; see there
            "uniform vec2 uSeed;\n" +
            "uniform sampler2D uGas;\n" +
            // Per-layer showpiece-galaxy presence: x for layer 0, y for layer
            // 1; 1.0 when one is in frame. See galaxyBigLayer.
            "uniform vec2 uGalBigOn;\n" +
            // Baked sky layer: sprinkles+carpet in rgb, band-grain noise in a.
            // uSky is the current epoch's bake, uSkyPrev the previous one; the
            // sprinkle dots cross-dissolve between them (uSprBlend 0->1) at each
            // epoch boundary so the burn-in reposition never blinks.
            "uniform sampler2D uSky;\n" +
            "uniform sampler2D uSkyPrev;\n" +
            "uniform float uSprBlend;\n" +
            "in vec2 vUv;\n" +
            "out vec4 fragColor;\n" +
            // ── Stars + pointillistic galaxy haze, ported faithfully from v3.1 ──
            "float h1(vec2 i){ vec2 p=fract(i*vec2(0.1031,0.1030)); p+=dot(p,p+19.19); return fract(p.x*p.y); }\n" +
            "vec2 h2(vec2 i){ vec2 p=fract(i*vec2(0.1031,0.1030)); p+=dot(p,p.yx+19.19); return fract((p.xx+p.yx)*p.xy); }\n" +
            "float vn(vec2 p){\n" +
            "  vec2 i=floor(p),f=fract(p);\n" +
            "  vec2 u=f*f*f*(f*(f*6.0-15.0)+10.0);\n" +
            "  return mix(mix(h1(i),h1(i+vec2(1,0)),u.x),\n" +
            "             mix(h1(i+vec2(0,1)),h1(i+vec2(1,1)),u.x),u.y);\n" +
            "}\n" +
            // Blackbody sequence only — real stars are never green or pink.
            // Runs M orange -> K -> G/F warm white -> A white -> B blue-white.
            // Brightness biases temperature: the brightest stars skew hot/blue
            // (giants), the faint majority skews warm — as in a real sky.
            "vec3 starCol(float h,float mag){\n" +
            "  float t=clamp(h*0.75+mag*0.55-0.15,0.0,1.0);\n" +
            "  if(t<0.25) return mix(vec3(1.00,0.62,0.36),vec3(1.00,0.78,0.58),t*4.0);\n" +
            "  if(t<0.50) return mix(vec3(1.00,0.78,0.58),vec3(1.00,0.94,0.86),(t-0.25)*4.0);\n" +
            "  if(t<0.75) return mix(vec3(1.00,0.94,0.86),vec3(0.92,0.95,1.00),(t-0.50)*4.0);\n" +
            "  return mix(vec3(0.92,0.95,1.00),vec3(0.72,0.82,1.00),(t-0.75)*4.0);\n" +
            "}\n" +
            "float twinkleComp(){ return 1.0; }\n" +
            "float starTwinkle(vec2 cell,float lid,float mag){\n" +
            "#ifdef ABL_NO_TWINKLE\n" +
            "  return 1.0;\n" +
            "#else\n" +
            // The distant stratum does not twinkle. Its stars are packed 1.6x
            // denser (so sub-pixel) and, being far, are the ones scintillation
            // would be least readable on — meanwhile amt=mix(0.85,0.40,mag)
            // gives the FAINTEST stars the largest amplitude, so this layer was
            // paying the most for the least. lid is a literal at every call
            // site, so this folds away at compile time rather than branching:
            // the near layers keep the full twinkle, the far pair drops it.
            "  if(lid>2.5) return 1.0;\n" + // far stratum is lid 3/4; near layers are 0/1
            "  float seed=h1(cell+vec2(17.0+lid*13.0,31.0-lid*7.0));\n" +
            "  float rate=mix(0.60,1.40,h1(cell+5.3+lid*1.7));\n" + // lively sparkle: 0.7-1.7s per cycle
            "  float ph=6.2831853*(seed+uTime*rate);\n" +
            // Real scintillation is chaotic, not sinusoidal: a product of
            // incommensurate sines gives irregular flashes and deep dips,
            // plus a slow drift component underneath.
            "  float w=sin(ph)*sin(ph*1.618+seed*7.0)*0.8+sin(ph*0.313+seed*3.0)*0.2;\n" +
            "  float wave=w*0.5+0.5;\n" +
            "  float comp=twinkleComp();\n" +
            "  float amt=mix(0.85,0.40,mag)*comp;\n" +
            "  return 1.0+amt*(wave-0.5);\n" +
            "#endif\n" +
            "}\n" +
            "vec3 starLayer(vec2 uv,float den,float ox,float oy,float ca,float sa,float lid,float densField){\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            // Anti-alias by pixel convolution: star cores are far narrower than
            // a screen pixel (sigma ~0.34px at 1080p), so grid alignment used to
            // modulate their sampled brightness — a shimmer that dwarfed the
            // intentional twinkle. Model the pixel as a ~0.5px-sigma gaussian
            // and convolve (variances add): resolved stars keep nearly their
            // intrinsic sharpness, sub-pixel stars get a steady floor. The
            // energy term below keeps widened stars from brightening.
            "  float pxc=max(fwidth(gp.x),fwidth(gp.y));\n" +
            "  float kPix=1.0/(2.0*(0.50*pxc)*(0.50*pxc));\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell);\n" +
            "  if(h<STAR_FLOOR) return vec3(0.0);\n" +
            "  float dens;\n" +
            "  if(densField>0.5){\n" +
            "    float dn=vn(cell*SD_FREQ_LO+vec2(ox*0.1,oy*0.1))*SD_W_LO+vn(cell*SD_FREQ_HI+11.0)*SD_W_HI;\n" +
            "    dens=smoothstep(SD_SS_LO,SD_SS_HI,dn);\n" +
            "    dens*=dens;\n" +
            "  } else { dens=FAR_STAR_DENS; }\n" +
            "  float thresh=STAR_CEIL-dens*STAR_RANGE;\n" +
            "  if(h<thresh) return vec3(0.0);\n" +
            "  vec2 df=f-h2(cell+3.7);\n" +
            "  float d2=dot(df,df);\n" +
            "  float hm=h1(cell+7.7); float mag=STAR_MAG_FLOOR+(1.0-STAR_MAG_FLOOR)*pow(hm,STAR_MAG_POW);\n" + // faint-skewed power law; see STAR_MAG_FLOOR / STAR_MAG_POW.
            "  float bri=mag;\n" +
            "  float tw=starTwinkle(cell,lid,mag);\n" +
            // A flaring star steadies as it ignites. The base star keeps drawing
            // under the flare overlay, and tw drives the core, the halo, the
            // spike brightness AND the spike length (spTight below) — so without
            // this its spikes writhe at 0.6-1.4Hz through the whole event.
            // Damping tw->1.0 (twDelta->0) stills all of them together. Matches
            // only the flaring cell on the flaring layer; far-star layers (lid
            // 3/4) never match since uStarFlare.w is 0 or 1.
            "  if(uStarFlare.z>0.0001&&abs(lid-uStarFlare.w)<0.5\n" +
            "     &&all(lessThan(abs(cell-uStarFlare.xy),vec2(0.5)))){\n" +
            "    tw=mix(tw,1.0,clamp(uStarFlare.z*FLARE_STEADY,0.0,1.0));\n" +
            "  }\n" +
            "  float twDelta=tw-1.0;\n" +
            // Twinkle modulates brightness only. Width twinkle is meaningless
            // for a clamped sub-pixel star, and worse: it routed through the
            // energy compensation, which moves opposite the brightness wave
            // and muted the twinkle. (Flares are no longer drawn here — they are
            // an unclipped grid-space overlay in main(), like the nova, so their
            // spikes can cross cell boundaries instead of being clipped.)
            "  float kCore=2500.0;\n" +
            "  float kEff=(kCore*kPix)/(kCore+kPix);\n" +
            // ^0.75: mostly energy-conserving (kills alias shimmer) but lets a
            // resolved near star sit a touch brighter than its far clamped self.
            "  float core=exp(-d2*kEff)*bri*(1.0+twDelta*1.60)*pow(kEff/kCore,0.75);\n" +
            "  float eh=exp(-d2*100.0);\n" +
            "  float halo=eh*mag*0.15*(0.74+0.26*tw);\n" +
            "  float spike=0.0;\n" +
            "#ifndef ABL_NO_SPIKE\n" +
            "  if(mag>SPIKE_THRESH){\n" +
            "    vec2 sdf=vec2(ca*df.x+sa*df.y,-sa*df.x+ca*df.y);\n" +
            "    float spTight=32.0/max(0.45,1.0+twDelta*1.10);\n" +
            "    float kSp=(5000.0*kPix)/(5000.0+kPix);\n" +
            "    float spWn=sqrt(kSp/5000.0);\n" + // 1D energy term for the widened cross-section
            "    float spH=exp(-sdf.y*sdf.y*kSp)*exp(-sdf.x*sdf.x*spTight)*spWn;\n" +
            "    float spV=exp(-sdf.x*sdf.x*kSp)*exp(-sdf.y*sdf.y*spTight)*spWn;\n" +
            "    spike=(spH+spV)*bri*bri*(0.14+0.32*tw);\n" +
            "  }\n" +
            "#endif\n" +
            "  return starCol(h1(cell+9.1),mag)*(core+halo+spike);\n" +
            "}\n" +
            // Sparse galaxy field: a coarse grid (1/4 star density, so smudges
            // have room) where rare cells hold a small inclined two-arm galaxy —
            // warm core, cool disk, hashed rotation/inclination/size/brightness.
            // Riding the same zoom layers as the stars, galaxies drift, grow and
            // cross-fade as part of the sky rather than appearing from nowhere.
            "vec3 galaxyLayer(vec2 uv,float den,float ox,float oy){\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell+vec2(7.3,29.1));\n" +
            "  if(h<GAL_SMALL_THRESH) return vec3(0.0);\n" +
            "  vec2 df=f-(vec2(0.35)+0.30*h2(cell+11.3));\n" + // keep the smudge clear of cell edges
            "  float gang=6.2831*h1(cell+3.1);\n" +
            "  vec2 grot=vec2(cos(gang)*df.x+sin(gang)*df.y,-sin(gang)*df.x+cos(gang)*df.y);\n" +
            // Same structural recipe as the showpiece tier, scaled down: less
            // edge-on bias, tight nucleus, deep inter-arm gaps, brighter
            // slower-falloff disk, and a mild dust lane on inclined ones.
            "  grot.y*=mix(1.3,2.8,h1(cell+5.9));\n" +          // varied inclination
            "  grot*=mix(9.0,16.0,h1(cell+13.7));\n" +          // varied size
            "  float gr=length(grot);\n" +
            "  float gcore=exp(-gr*gr*4.5);\n" +
            // Smooth inclined disk (no spiral-arm term): the atan+cos arm
            // modulation cost more than it read at this scale — a squashed,
            // dust-laned oval is what the eye actually resolves.
            "  float gdisk=exp(-gr*1.45)*0.55;\n" +
            "  float lane=1.0-0.55*exp(-grot.y*grot.y*16.0)*smoothstep(0.35,0.80,gr)*(1.0-smoothstep(1.8,2.6,gr));\n" +
            "  vec3 gcol=mix(vec3(0.72,0.80,1.00),vec3(1.00,0.90,0.78),gcore);\n" +
            // 1.5x overall vs the oval era: the deep inter-arm gaps and tight
            // nucleus halved the integrated light and sank them below visibility.
            "  return gcol*(gcore+gdisk*lane)*mix(0.55,1.05,h1(cell+17.9));\n" +
            "}\n" +
            // Showpiece tier: a far coarser grid (cells ~3x the galaxy grid's, so
            // a big smudge fits without clipping) where a very rare cell holds a
            // 3-4x galaxy with readable two-arm structure and a dust lane along
            // the midplane. Roughly one drifts through view every ~10 minutes —
            // the small population is texture, this is an event.
            // `on` is 1.0 only while a showpiece galaxy is actually within
            // frame on this layer, which the CPU decides once per frame (see
            // bigGalaxyVisible). These are rare enough that most of the time no
            // layer has one in view, and this returns before touching a pixel.
            //
            // Reaching the h1 rejection below is not free: gp/floor/fract plus
            // the hash ran for all ~2M pixels on both layers to discard 99.65%
            // of them, measured at 1.80ms. (The same probe showed only 0.27ms
            // of GALBIG's 2.07ms ablation delta is register pressure that no
            // runtime gate could recover — so this gate collects nearly all of
            // it.) The h1 test stays: when a galaxy IS in view it still picks
            // out which cell, so the drawn result is bit-identical to before.
            "vec3 galaxyBigLayer(vec2 uv,float den,float ox,float oy,float on){\n" +
            "  if(on<0.5) return vec3(0.0);\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell+vec2(19.7,3.9));\n" +
            "  if(h<GAL_BIG_THRESH) return vec3(0.0);\n" +
            "  vec2 df=f-(vec2(0.40)+0.20*h2(cell+11.3));\n" + // keep the big smudge well clear of cell edges
            "  float gang=6.2831*h1(cell+3.1);\n" +
            "  vec2 grot=vec2(cos(gang)*df.x+sin(gang)*df.y,-sin(gang)*df.x+cos(gang)*df.y);\n" +
            // Bias toward face-on (high inclination squashed the arms into a
            // featureless oval); small nucleus, deep inter-arm gaps, dark lane.
            "  grot.y*=mix(1.2,2.2,h1(cell+5.9));\n" +
            "  grot*=mix(10.0,13.0,h1(cell+13.7));\n" +
            "  float gr=length(grot);\n" +
            "  float gcore=exp(-gr*gr*5.0);\n" +
            // Smooth inclined disk (arm term dropped, see galaxyLayer).
            "  float gdisk=exp(-gr*1.35)*0.85;\n" +
            "  float lane=1.0-0.72*exp(-grot.y*grot.y*14.0)*smoothstep(0.35,0.80,gr)*(1.0-smoothstep(1.8,2.6,gr));\n" +
            "  vec3 gcol=mix(vec3(0.70,0.78,1.00),vec3(1.00,0.88,0.74),gcore);\n" +
            "  return gcol*(gcore*0.95+gdisk*lane)*mix(0.50,0.85,h1(cell+17.9));\n" +
            "}\n" +
            "vec3 sprinkleLayer(vec2 uv,float den,float ox,float oy,float dens){\n" +
            "  float thresh=0.97-dens*0.97;\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell+vec2(41.0,17.0));\n" +
            "  if(h<thresh) return vec3(0.0);\n" +
            "  vec2 df=f-h2(cell+23.7);\n" +
            "  float d2=dot(df,df);\n" +
            "  float core=max(0.0,1.0-d2*SP_CORE);\n" +
            "  core*=core;\n" +
            "  float b=core*SP_BRI;\n" +
            "  vec3 sc=starCol(h1(cell+9.3),0.15);\n" +
            "  return sc*b;\n" +
            "}\n" +
            // Faint-star carpet: the band's near-saturated population of dim
            // but RESOLVED dots (footprint ~0.6px; sub-pixel dots lose most of
            // their light to the pixel-placement lottery). Only drawn in-band.
            "vec3 carpetLayer(vec2 uv,float ox,float oy,float dens){\n" +
            "  float thresh=0.97-dens*0.97;\n" +
            "  vec2 gp=uv*CP_DEN+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell+vec2(53.0,11.0));\n" +
            "  if(h<thresh) return vec3(0.0);\n" +
            "  vec2 df=f-h2(cell+31.7);\n" +
            "  float d2=dot(df,df);\n" +
            "  float core=max(0.0,1.0-d2*CP_K);\n" +
            "  core*=core;\n" +
            "  return starCol(h1(cell+4.9),0.05)*core*CP_BRI*(0.4+0.6*h1(cell+2.3));\n" +
            "}\n" +
            "void main(){\n" +
            "  vec4 gas=texture(uGas,vUv);\n" +
            "  vec3 col=gas.rgb;\n" +
            "  float T=gas.a;\n" +
            // ── Stars + galaxy haze BEHIND the clouds (v3.1 three-phase zooming
            // star system), weighted by the ray's remaining transmittance T so
            // dense masses occlude them and they shine through voids/thin gas. ──
            "  vec3 bg=vec3(0.0);\n" +
            "  float SZSP=SZ_SPEED*uZoom;\n" +
            "  float ph=uTime*SZSP;\n" +
            "  float t1=fract(ph+L0_PHASE);\n" +
            "  float t2=fract(ph+L1_PHASE);\n" +
            "  float f1=smoothstep(0.10,0.30,t1)*(1.0-smoothstep(0.70,0.90,t1));\n" +
            "  float f2=smoothstep(0.10,0.30,t2)*(1.0-smoothstep(0.70,0.90,t2));\n" +
            "  vec2 sc=vUv-0.5; sc.y*=uRes.y/uRes.x;\n" +
            "  vec2 sr1=vec2(sc.x*0.951-sc.y*0.309, sc.x*0.309+sc.y*0.951);\n" +
            "  vec2 sr2=vec2(sc.x*0.423-sc.y*0.906, sc.x*0.906+sc.y*0.423);\n" +
            "  vec2 s1=sr1/exp(t1*SZ_MAX)+0.5+uSeed;\n" +
            "  vec2 s2=sr2/exp(t2*SZ_MAX)+0.5+uSeed;\n" +
            // Milky-way band structure, recomputed IDENTICALLY to the gas pass
            // (same vn(), same coords) so native-res detail here lands in
            // register with the low-res halo there. Computed before the star
            // layers because the bright stars are brightness-biased into the
            // band (sBias) — in the real sky every stellar population rises
            // toward the plane, and stars that ignore the band contradict it.
            "  vec2 buv=vUv*2.0-1.0; buv.x*=uRes.x/uRes.y;\n" +
            "  vec3 brd=normalize(vec3(buv,1.5));\n" +
            "  float bandPos=brd.y*1.9+0.30*brd.x+0.22*sin(uTime*0.0093);\n" +
            "  float band=exp(-bandPos*bandPos*3.0);\n" +
            "  float bAlong=brd.x*1.66-brd.y*0.26+uSeed.x*5.0;\n" +
            // All vn-based band terms sit behind a band+transmittance gate:
            // every downstream use multiplies by band and rides T, so pixels
            // that are off-band OR behind dense gas skip the noise calls
            // entirely, and the defaults are visually continuous at the gate
            // edge. Without gating the native-res band work cost ~4.5ms/frame
            // on the Shield and forced the adaptive gas scale down.
            "  float brift=1.0; float bbandAmp=1.0; float bbulge=0.0;\n" +
            "#ifdef ABL_NO_BANDSTRUCT\n" +
            "  if(false){\n" +
            "#else\n" +
            "  if(band>0.02&&T>0.03){\n" +
            "#endif\n" +
            // One LUT fetch replaces the four along-band vn() calls (same
            // bake the gas pass reads, so the passes stay in register).
            "    vec4 bl=texture(uBandLut,vec2((bAlong-uSeed.x*5.0)*0.33333+0.5,0.5));\n" +
            "    float briftPos=bandPos*3.2+(bl.r-0.5)*1.6;\n" +
            // Comp-only rift detail on top of the gas pass's primary lane
            // (kept in register via the shared meander term): a second offset
            // meander braids with the first (reusing bl.g as its meander),
            // and a fine dust mottle breaks both into patches. Sharper
            // profile (k=7) and deeper floor than v4.9.0: dust should read
            // as near-black structure. The dust term is the one survivor
            // that genuinely needs 2D per-pixel noise.
            (BAND_BRAID
            ? "    float brift2Pos=bandPos*3.2+(bl.g-0.5)*2.2+0.60;\n" +
              "    float bdust=vn(vec2(bAlong*7.0,bandPos*9.0)+7.7);\n" +
              "    brift=1.0-0.68*exp(-briftPos*briftPos*7.0)*(0.35+0.65*bl.g)\n" +
              "         -0.40*exp(-brift2Pos*brift2Pos*6.0)*smoothstep(0.35,0.75,bdust);\n" +
              "    brift=max(brift,0.10);\n"
            : "    brift=1.0-0.60*exp(-briftPos*briftPos*5.0)*(0.35+0.65*bl.g);\n") +
            "    bbulge=bl.a;\n" +
            "    bbandAmp=0.60+0.80*bl.b*bl.b+1.0*bbulge;\n" +
            "  }\n" +
            "  float sBias=1.0+0.5*band*brift;\n" +
            "#ifndef ABL_NO_STARS\n" +
            "  bg+=starLayer(s1,STAR_DEN*uStarScale,L0_OX,L0_OY,0.951,0.309,0.0,1.0)*f1*sBias;\n" +
            "  bg+=starLayer(s2,STAR_DEN*uStarScale,L1_OX,L1_OY,0.423,0.906,1.0,1.0)*f2*sBias;\n" +
            "#endif\n" +
            // Distant star stratum: own slow 2-phase crossfade cycle (0.45x), so
            // it parallaxes as a more-distant layer without touching the near
            // layers' phases or the scheduler. Denser + dimmer sells the
            // distance. Each add is gated behind its fade weight — the weights
            // depend only on uTime, so the branch is coherent across the frame
            // and the layer costs nothing during its ~20% faded-out window.
            "#ifndef ABL_NO_FARSTARS\n" +
            "  float fph=uTime*SZSP*FAR_STAR_SPEED;\n" +
            "  float ft1=fract(fph+0.0);\n" +
            "  float ft2=fract(fph+0.5);\n" +
            "  float ff1=smoothstep(0.10,0.30,ft1)*(1.0-smoothstep(0.70,0.90,ft1));\n" +
            "  float ff2=smoothstep(0.10,0.30,ft2)*(1.0-smoothstep(0.70,0.90,ft2));\n" +
            "  if(ff1>0.001){\n" +
            "    vec2 fs1=sr1/exp(ft1*SZ_MAX)+0.5+uSeed;\n" +
            "    bg+=starLayer(fs1,STAR_DEN*uStarScale*FAR_STAR_DEN,0.71,0.53,0.951,0.309,3.0,0.0)*ff1*sBias*FAR_STAR_BRI;\n" +
            "  }\n" +
            "  if(ff2>0.001){\n" +
            "    vec2 fs2=sr2/exp(ft2*SZ_MAX)+0.5+uSeed;\n" +
            "    bg+=starLayer(fs2,STAR_DEN*uStarScale*FAR_STAR_DEN,0.19,0.83,0.423,0.906,4.0,0.0)*ff2*sBias*FAR_STAR_BRI;\n" +
            "  }\n" +
            "#endif\n" +
            // Galaxies get their own zoom phase at quarter speed: they are
            // effectively at infinity, so they should show almost no parallax
            // while stars stream past. Truly static would freeze the
            // composition forever; 0.25x reads as "vastly farther" while the
            // population still evolves over ~5-minute layer cycles.
            "#ifndef ABL_NO_GALAXY\n" +
            "  float gph=uTime*SZSP*0.25;\n" +
            "  float g1=fract(gph+L0_PHASE);\n" +
            "  float g2=fract(gph+L1_PHASE);\n" +
            "  float gf1=smoothstep(0.10,0.30,g1)*(1.0-smoothstep(0.70,0.90,g1));\n" +
            "  float gf2=smoothstep(0.10,0.30,g2)*(1.0-smoothstep(0.70,0.90,g2));\n" +
            "  vec2 gs1=sr1/exp(g1*SZ_MAX)+0.5+uSeed;\n" +
            "  vec2 gs2=sr2/exp(g2*SZ_MAX)+0.5+uSeed;\n" +
            "#ifndef ABL_NO_GALSMALL\n" +
            "  bg+=galaxyLayer(gs1,STAR_DEN*uStarScale*0.25,L0_OX,L0_OY)*gf1;\n" +
            "  bg+=galaxyLayer(gs2,STAR_DEN*uStarScale*0.25,L1_OX,L1_OY)*gf2;\n" +
            "#endif\n" +
            "#ifndef ABL_NO_GALBIG\n" +
            "  bg+=galaxyBigLayer(gs1,STAR_DEN*uStarScale*0.075,L0_OX,L0_OY,uGalBigOn.x)*gf1;\n" +
            "  bg+=galaxyBigLayer(gs2,STAR_DEN*uStarScale*0.075,L1_OX,L1_OY,uGalBigOn.y)*gf2;\n" +
            "#endif\n" +
            "#endif\n" +
            // Milky-Way band grain: the two expensive noise octaves are baked
            // into uSky.a; the band structure (tint/rift/soft-knee) is re-applied
            // live here so it stays exact. Kept on the gas output path (added to
            // col), as it was before the sky layer.
            "  if(band>0.02&&T>0.03){\n" +
            "    float bgrain=texture(uSky,vUv).a;\n" +
            "    vec3 bandCol=mix(vec3(0.20,0.185,0.205),vec3(0.245,0.205,0.165),bbulge);\n" +
            "    float cx=band*bbandAmp*brift*bgrain;\n" +
            "    cx/=1.0+max(cx-0.8,0.0)*0.5;\n" +
            // Diffuse band grain lowered 0.20 -> 0.105: the denser resolved
            // sprinkle field now carries the band's presence, so the smooth glow
            // no longer needs to. (Walked down to 0.08, then back up halfway.)
            // 0.105 -> 0.084: -20% band brightness, matching the gas-pass halo cut.
            // Further trims ride BAND_BRI (the master scale), not this number.
            "    col+=T*bandCol*cx*0.084*BAND_BRI;\n" +
            "  }\n" +
            // Sprinkles + carpet: baked (frozen per 40s epoch, incl. the finer
            // band-only field) into uSky.rgb. At each epoch the pattern jumps to
            // a new position for OLED burn-in protection; rather than blink the
            // whole field to black to hide the jump (very visible once the band
            // is dense), cross-dissolve from the previous bake to the current
            // one. mix (not add) keeps total density constant through the blend.
            "  bg+=mix(texture(uSkyPrev,vUv).rgb,texture(uSky,vUv).rgb,uSprBlend);\n" +
            // NOVA: a once-per-session-scale event — one star swells to a
            // brilliant point over ~2s, holds, and fades over ~25s sliding
            // white -> amber. Rendered here as a screen-space overlay (NOT in
            // starLayer, whose per-cell evaluation clips anything larger than
            // one grid cell): reconstruct the star's grid position from its
            // cell + intra-cell hash, and lay unclipped kernels in grid units
            // so the blaze zooms with its layer. Riding bg keeps it behind gas
            // and on the HDR star-boost chain.
            "#ifdef ABL_NO_NOVA\n" +
            "  if(false){\n" +
            "#else\n" +
            "  if(uNova.z>0.0001){\n" +
            "#endif\n" +
            "    float fn=(uNova.w<0.5)?f1:f2;\n" +
            "    vec2 sN=(uNova.w<0.5)?s1:s2;\n" +
            "    float caN=(uNova.w<0.5)?0.951:0.423; float saN=(uNova.w<0.5)?0.309:0.906;\n" +
            "    vec2 offN=(uNova.w<0.5)?vec2(L0_OX,L0_OY):vec2(L1_OX,L1_OY);\n" +
            "    vec2 cellN=vec2(uNova.x,uNova.y);\n" +
            "    vec2 dgp=sN*(STAR_DEN*uStarScale)+offN-(cellN+h2(cellN+3.7));\n" +
            "    float r2=dot(dgp,dgp);\n" +
            "    float nv=uNova.z*fn;\n" +
            "    float nv2=nv*nv;\n" +
            // Restrained: the brightest star in the sky, not a searchlight —
            // tight glow, short thin spikes, gains low enough that only the
            // core saturates.
            "    float nCore=exp(-r2*22.0)*nv2*8.0;\n" +
            "    float nGlow=exp(-r2*2.6)*nv2*0.7+exp(-r2*0.55)*nv2*0.10;\n" +
            "    vec2 nd=vec2(caN*dgp.x+saN*dgp.y,-saN*dgp.x+caN*dgp.y);\n" +
            "    float nSp=(exp(-nd.y*nd.y*250.0)*exp(-nd.x*nd.x*0.16)\n" +
            "              +exp(-nd.x*nd.x*250.0)*exp(-nd.y*nd.y*0.16))*nv2*0.45;\n" +
            "    vec3 nCol=mix(vec3(1.00,0.97,0.92),vec3(1.00,0.70,0.42),uNovaP*uNovaP);\n" +
            "    bg+=nCol*(nCore+nGlow+nSp);\n" +
            "  }\n" +
            // FLARE: same unclipped grid-space overlay as the nova (was drawn
            // inside starLayer, whose per-cell evaluation clipped spikes that
            // crossed a cell boundary — often hiding 1-2 of the four spikes).
            // Reconstruct the flaring star's grid position and lay the widened
            // core, halo and short cross-spikes in grid units so they extend
            // past the cell. The base star still renders steadily in starLayer;
            // this adds the blaze on top. fv^2/fv^4 reproduce the old core/spike
            // falloff (old bri = mag + 8*env^2, spike ~ bri^2).
            "  if(uStarFlare.z>0.0001){\n" +
            "    float vis=(uStarFlare.w<0.5)?f1:f2;\n" +
            "    vec2 sF=(uStarFlare.w<0.5)?s1:s2;\n" +
            "    float caF=(uStarFlare.w<0.5)?0.951:0.423; float saF=(uStarFlare.w<0.5)?0.309:0.906;\n" +
            "    vec2 offF=(uStarFlare.w<0.5)?vec2(L0_OX,L0_OY):vec2(L1_OX,L1_OY);\n" +
            "    vec2 cellF=vec2(uStarFlare.x,uStarFlare.y);\n" +
            "    vec2 dgp=sF*(STAR_DEN*uStarScale)+offF-(cellF+h2(cellF+3.7));\n" +
            "    float r2=dot(dgp,dgp);\n" +
            "    float fv=uStarFlare.z; float fv2=fv*fv;\n" +
            "    float fCore=exp(-r2*130.0)*fv2*8.0;\n" +
            "    float fGlow=exp(-r2*40.0)*fv2*0.9+exp(-r2*12.0)*fv2*0.30;\n" +
            "    vec2 fd=vec2(caF*dgp.x+saF*dgp.y,-saF*dgp.x+caF*dgp.y);\n" +
            // Spikes EXTEND as the flare peaks (smaller coeff = longer reach), so
            // the burst visibly throws its arms out and draws them back in rather
            // than just brightening in place.
            "    float spK=mix(26.0,11.0,fv);\n" +
            // Anti-alias the spikes by pixel convolution, exactly as starLayer
            // does for its own. Without this the thin gaussians (k=5000/9000 ->
            // far narrower than a pixel) are point-sampled, so they shimmer as
            // the star drifts across the pixel grid — which reads as the flare
            // "twinkling" even though nothing here touches starTwinkle. spWn
            // conserves energy so the widened spike does not brighten.
            "    float pxcF=max(fwidth(dgp.x),fwidth(dgp.y));\n" +
            "    float kPixF=1.0/(2.0*(0.50*pxcF)*(0.50*pxcF));\n" +
            "    float kSpF=(5000.0*kPixF)/(5000.0+kPixF);\n" +
            "    float spWnF=sqrt(kSpF/5000.0);\n" +
            "    float fSp=(exp(-fd.y*fd.y*kSpF)*exp(-fd.x*fd.x*spK)\n" +
            "              +exp(-fd.x*fd.x*kSpF)*exp(-fd.y*fd.y*spK))*fv2*fv2*30.0*spWnF;\n" +
            // Secondary spike pair at 45 deg — shorter and much fainter, turning
            // the plain 4-point cross into an 8-point burst.
            "    vec2 fe=vec2((fd.x+fd.y)*0.70711,(fd.y-fd.x)*0.70711);\n" +
            "    float kSp2F=(9000.0*kPixF)/(9000.0+kPixF);\n" +
            "    float spWn2F=sqrt(kSp2F/9000.0);\n" +
            "    float fSp2=(exp(-fe.y*fe.y*kSp2F)*exp(-fe.x*fe.x*spK*2.6)\n" +
            "               +exp(-fe.x*fe.x*kSp2F)*exp(-fe.y*fe.y*spK*2.6))*fv2*fv2*8.0*spWn2F;\n" +
            "    float hmF=h1(cellF+7.7); float fmag=STAR_MAG_FLOOR+(1.0-STAR_MAG_FLOOR)*pow(hmF,STAR_MAG_POW);\n" +
            "    vec3 fc0=starCol(h1(cellF+9.1),fmag);\n" +
            // Chromatic layering instead of one flat tint: the core saturates to
            // white-hot, the spikes carry the star's own colour, and the diffuse
            // halo scatters cooler/bluer — the way a real bright point blooms.
            "    vec3 fHot=mix(fc0,vec3(1.0),0.80);\n" +
            "    vec3 fCool=mix(fc0,vec3(0.55,0.70,1.00),0.50);\n" +
            "    bg+=(fHot*fCore + fc0*(fSp+fSp2) + fCool*fGlow)*vis;\n" +
            "  }\n" +
            "  vec3 starSig=T*bg;\n" +
            // (deep-space floor moved to the gas pass with the haze)
            "  col+=starSig;\n" +

            // ── v3.1 output chain: hue drift, fade-in, desat rolloff, tonemap, HDR ─
            "  float drift=uTime*0.0035;\n" +
            "  vec3 driftRgb=vec3(1.0)+0.10*vec3(sin(drift),sin(drift+2.0944),sin(drift+4.1888));\n" +
            "  float fadeIn=smoothstep(0.0,10.0,uTime);\n" +
            "  col*=driftRgb;\n" +
            "  starSig*=driftRgb;\n" +
            "  col*=fadeIn;\n" +
            "  starSig*=fadeIn;\n" +
            "  float pk=max(max(col.r,col.g),col.b);\n" +
            "  float luma=dot(col,vec3(0.30,0.40,0.30));\n" +
            // Bright gas desaturates toward its own luma: a hot emitter reads as
            // white-hot, not as the same violet at ten times the intensity. Starts
            // earlier and goes further than the old 2.4/5.0/0.40 — that left the
            // top end fully saturated, which is what made blown masses scream.
            // Core mask from the PURE gas peak (gas.rgb, no stars): isolates the
            // brightest gas = star-forming cores. Cores keep CORE_DESAT_KEEP of
            // their pink while the rest of the bright gas still washes to white.
            "  float gasPk=max(max(gas.r,gas.g),gas.b);\n" +
            "  float coreMask=smoothstep(CORE_HDR_LO,CORE_HDR_HI,gasPk);\n" +
            "  col=mix(col,vec3(luma),smoothstep(1.6,4.4,pk)*0.72*(1.0-coreMask*CORE_DESAT_KEEP));\n" +
            "  vec3 base=col/(col+vec3(0.85));\n" +
            "  base=pow(max(base,vec3(0.0)),vec3(0.92))*1.12;\n" +
            "  vec3 starBase=starSig/(starSig+vec3(0.85));\n" +
            "  starBase=pow(max(starBase,vec3(0.0)),vec3(0.92))*1.12;\n" +
            "  float bk=step(1.0/255.0,max(max(base.r,base.g),base.b));\n" +
            "  base*=bk;\n" +
            "#ifdef ABL_NO_HDR\n" +
            "  if(false){\n" +
            "#else\n" +
            "  if(uHdr>0.5){\n" +
            "#endif\n" +
            "    vec3 lin=pow(base,vec3(2.2));\n" +
            "    vec3 starLin=pow(max(starBase,vec3(0.0)),vec3(2.2));\n" +
            "    float lum=max(max(col.r,col.g),col.b);\n" +
            "    float starLum=max(max(starSig.r,starSig.g),starSig.b);\n" +
            // Gas path: later knee, slower ramp, lower ceiling. The exp curve is
            // kept (it still rolls off cleanly) but GAS_HDR_GAIN stretches it so
            // the gas separates across its whole range instead of saturating just
            // past the knee — that separation IS the texture inside a bright mass.
            // Wash ceiling, lifted toward the core ceiling for masked core pixels
            // (raises both the boost ceiling and the rolloff point, so a core
            // actually climbs above the restrained wash).
            "    float gasMax=1.0+(uHdrMax-1.0)*mix(GAS_HDR_CEIL,CORE_HDR_CEIL,coreMask);\n" +
            "    float hi=max(lum-uHdrKnee*GAS_HDR_KNEE,0.0);\n" +
            "    float boostMax=max(gasMax-1.0,1.0);\n" +
            "    float boost=boostMax*(1.0-exp(-(uHdrGain*GAS_HDR_GAIN*hi)/boostMax));\n" +
            "    vec3 hdr=lin*(1.0+boost);\n" +
            "    float starHi=max(starLum-uHdrKnee*0.55,0.0);\n" +
            "    float starBoostMax=max(uHdrStarMax-1.0,1.0);\n" +
            "    float starBoost=starBoostMax*(1.0-exp(-(uHdrStarGain*STAR_HDR_GAIN*starHi)/starBoostMax));\n" +
            "    float starMask=smoothstep(0.10,0.90,starLum);\n" +
            "    hdr+=starLin*starMask*starBoost;\n" +
            "    float hpk=max(max(hdr.r,hdr.g),hdr.b);\n" +
            // Gas rolls off at gasMax, not the panel peak; stars still reach theirs.
            "    float peakMax=mix(gasMax,uHdrStarMax,starMask);\n" +
            "    if(hpk>peakMax){\n" +
            "      float rolled=peakMax-(peakMax-1.0)*exp(-(hpk-1.0)/(peakMax-1.0));\n" +
            "      hdr*=rolled/max(hpk,1e-4);\n" +
            "    }\n" +
            "    fragColor=vec4(max(hdr,vec3(0.0)),1.0);\n" +
            "  } else {\n" +
            "    base+=(h1(gl_FragCoord.xy)-0.5)/255.0*bk;\n" +
            "    fragColor=vec4(clamp(base,0.0,1.0),1.0);\n" +
            "  }\n" +
            "}\n";

        // ── SKY BAKE PASS: renders the screen-space, slowly-varying subset of
        // the composite — sprinkles + carpet + the
        // Milky-Way grain noise — into a full-res FBO. Re-baked only when the
        // sprinkle epoch changes (~40s), so ~6ms/frame of procedural evaluation
        // becomes one texture fetch in the comp pass. Output: rgb = combined
        // sprinkle+carpet emission (star path, epoch-faded live in comp),
        // a = raw band-grain noise (gas path; band structure re-applied live in
        // comp so tint/rift/knee stay exact). Helpers duplicated from FRAG_COMP.
        private static final String FRAG_SKY =
            "#version 300 es\n" +
            COMP_DEFS +
            "precision highp float;\n" +
            "uniform float uTime;\n" +
            "uniform vec2  uRes;\n" +
            "uniform float uZoom;\n" +
            "uniform float uStarScale;\n" +
            "uniform vec2 uSeed;\n" +
            "uniform sampler2D uBandLut;\n" +
            "in vec2 vUv;\n" +
            "out vec4 fragColor;\n" +
            "float h1(vec2 i){ vec2 p=fract(i*vec2(0.1031,0.1030)); p+=dot(p,p+19.19); return fract(p.x*p.y); }\n" +
            "vec2 h2(vec2 i){ vec2 p=fract(i*vec2(0.1031,0.1030)); p+=dot(p,p.yx+19.19); return fract((p.xx+p.yx)*p.xy); }\n" +
            "float vn(vec2 p){\n" +
            "  vec2 i=floor(p),f=fract(p);\n" +
            "  vec2 u=f*f*f*(f*(f*6.0-15.0)+10.0);\n" +
            "  return mix(mix(h1(i),h1(i+vec2(1,0)),u.x),\n" +
            "             mix(h1(i+vec2(0,1)),h1(i+vec2(1,1)),u.x),u.y);\n" +
            "}\n" +
            "vec3 starCol(float h,float mag){\n" +
            "  float t=clamp(h*0.75+mag*0.55-0.15,0.0,1.0);\n" +
            "  if(t<0.25) return mix(vec3(1.00,0.62,0.36),vec3(1.00,0.78,0.58),t*4.0);\n" +
            "  if(t<0.50) return mix(vec3(1.00,0.78,0.58),vec3(1.00,0.94,0.86),(t-0.25)*4.0);\n" +
            "  if(t<0.75) return mix(vec3(1.00,0.94,0.86),vec3(0.92,0.95,1.00),(t-0.50)*4.0);\n" +
            "  return mix(vec3(0.92,0.95,1.00),vec3(0.72,0.82,1.00),(t-0.75)*4.0);\n" +
            "}\n" +
            "vec3 sprinkleLayer(vec2 uv,float den,float ox,float oy,float dens){\n" +
            "  float thresh=0.97-dens*0.97;\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell+vec2(41.0,17.0));\n" +
            "  if(h<thresh) return vec3(0.0);\n" +
            "  vec2 df=f-h2(cell+23.7);\n" +
            "  float d2=dot(df,df);\n" +
            "  float core=max(0.0,1.0-d2*SP_CORE);\n" +
            "  core*=core;\n" +
            "  float b=core*SP_BRI;\n" +
            "  vec3 sc=starCol(h1(cell+9.3),0.15);\n" +
            "  return sc*b;\n" +
            "}\n" +
            "vec3 carpetLayer(vec2 uv,float ox,float oy,float dens){\n" +
            "  float thresh=0.97-dens*0.97;\n" +
            "  vec2 gp=uv*CP_DEN+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell+vec2(53.0,11.0));\n" +
            "  if(h<thresh) return vec3(0.0);\n" +
            "  vec2 df=f-h2(cell+31.7);\n" +
            "  float d2=dot(df,df);\n" +
            "  float core=max(0.0,1.0-d2*CP_K);\n" +
            "  core*=core;\n" +
            "  return starCol(h1(cell+4.9),0.05)*core*CP_BRI*(0.4+0.6*h1(cell+2.3));\n" +
            "}\n" +
            "void main(){\n" +
            "  vec2 sc=vUv-0.5; sc.y*=uRes.y/uRes.x;\n" +
            "  float SZSP=SZ_SPEED*uZoom;\n" +
            "  float ph=uTime*SZSP;\n" +
            "  float t1=fract(ph+L0_PHASE);\n" +
            "  float t2=fract(ph+L1_PHASE);\n" +
            "  float f1=smoothstep(0.10,0.30,t1)*(1.0-smoothstep(0.70,0.90,t1));\n" +
            "  float f2=smoothstep(0.10,0.30,t2)*(1.0-smoothstep(0.70,0.90,t2));\n" +
            "  vec2 sr1=vec2(sc.x*0.951-sc.y*0.309, sc.x*0.309+sc.y*0.951);\n" +
            "  vec2 sr2=vec2(sc.x*0.423-sc.y*0.906, sc.x*0.906+sc.y*0.423);\n" +
            "  vec2 s1=sr1/exp(t1*SZ_MAX)+0.5+uSeed;\n" +
            "  vec2 s2=sr2/exp(t2*SZ_MAX)+0.5+uSeed;\n" +
            "  vec2 buv=vUv*2.0-1.0; buv.x*=uRes.x/uRes.y;\n" +
            "  vec3 brd=normalize(vec3(buv,1.5));\n" +
            "  float bandPos=brd.y*1.9+0.30*brd.x+0.22*sin(uTime*0.0093);\n" +
            "  float band=exp(-bandPos*bandPos*3.0);\n" +
            "  float bAlong=brd.x*1.66-brd.y*0.26+uSeed.x*5.0;\n" +
            "  float brift=1.0; float bbandAmp=1.0; float bbulge=0.0;\n" +
            "  if(band>0.02){\n" +
            "    vec4 bl=texture(uBandLut,vec2((bAlong-uSeed.x*5.0)*0.33333+0.5,0.5));\n" +
            "    float briftPos=bandPos*3.2+(bl.r-0.5)*1.6;\n" +
            (BAND_BRAID
            ? "    float brift2Pos=bandPos*3.2+(bl.g-0.5)*2.2+0.60;\n" +
              "    float bdust=vn(vec2(bAlong*7.0,bandPos*9.0)+7.7);\n" +
              "    brift=1.0-0.68*exp(-briftPos*briftPos*7.0)*(0.35+0.65*bl.g)\n" +
              "         -0.40*exp(-brift2Pos*brift2Pos*6.0)*smoothstep(0.35,0.75,bdust);\n" +
              "    brift=max(brift,0.10);\n"
            : "    brift=1.0-0.60*exp(-briftPos*briftPos*5.0)*(0.35+0.65*bl.g);\n") +
            "    bbulge=bl.a;\n" +
            "    bbandAmp=0.60+0.80*bl.b*bl.b+1.0*bbulge;\n" +
            "  }\n" +
            "  vec2 scUv=sc+0.5;\n" +
            "  vec2 sdUv=(f1>=f2)?s1:s2;\n" +
            "  float sdOx=(f1>=f2)?L0_OX:L1_OX; float sdOy=(f1>=f2)?L0_OY:L1_OY;\n" +
            "  vec2 sdGp=sdUv*STAR_DEN*uStarScale+vec2(sdOx,sdOy);\n" +
            "  float sdField=vn(sdGp*SD_FREQ_LO+vec2(sdOx*0.1,sdOy*0.1))*SD_W_LO+vn(sdGp*SD_FREQ_HI+11.0)*SD_W_HI;\n" +
            "  float sdD=smoothstep(SD_SS_LO,SD_SS_HI,sdField); sdD*=sdD;\n" +
            "  float spDn=vn(scUv*3.2+uSeed*2.0+vec2(5.7,3.1))*0.55+vn(scUv*8.5+uSeed*4.0+17.3)*0.45;\n" +
            "  float spDens=(SP_BASE+(1.0-SP_BASE)*smoothstep(SP_SS_LO,SP_SS_HI,spDn))*(1.0+sdD)*(1.0+band*3.0*bbandAmp*brift)*mix(SP_NONBAND_MUL,1.0,smoothstep(0.04,0.35,band));\n" +
            "  float spEpoch=floor(uTime/40.0);\n" +
            "  vec2 spJit=vec2(h1(vec2(spEpoch,7.3)),h1(vec2(spEpoch,19.1)))*13.0;\n" +
            "  vec3 spr=sprinkleLayer(scUv,SP_DEN,0.19+spJit.x,0.43+spJit.y,spDens);\n" +
            // Extra finer band-only field: raises the in-band dot count past the
            // SP_DEN grid's saturation. Dimmed by SP_BAND_BRI.
            "  float bandSpr=band*bbandAmp*brift;\n" +
            "  if(bandSpr>0.02){ spr+=sprinkleLayer(scUv,SP_BAND_DEN,0.53+spJit.x,0.11+spJit.y,bandSpr*2.5)*SP_BAND_BRI; }\n" +
            "  float cpD=band*bbandAmp*brift*0.9;\n" +
            "  if(cpD>0.02){ spr+=carpetLayer(scUv,0.31+spJit.x,0.57+spJit.y,cpD); }\n" +
            "  vec3 skyRgb=spr*(1.0+band*0.8);\n" +
            "  float bgrain=0.0;\n" +
            "  if(band>0.02){\n" +
            "    float gsum=vn(scUv*90.0+uSeed*7.0)*0.55+vn(scUv*260.0+uSeed*13.0+3.7)*0.45;\n" +
            "    bgrain=0.45+0.9*gsum;\n" +
            "  }\n" +
            "  fragColor=vec4(skyRgb,bgrain);\n" +
            "}\n";

        // Pass 1 (gas, low-res FBO): program + locations
        private int progGas, gAPos, gUTime, gURes, gUNoise, gUZoom, gUSeed, gUStarScale, gUBandLut;
        // Pass 2 (composite, native res): program + locations
        private int progComp, cAPos, cUTime, cURes, cUZoom, cUHdr, cUHdrKnee, cUHdrGain, cUHdrMax,
            cUHdrStarGain, cUHdrStarMax, cUStarFlare, cUSeed, cUGas, cUStarScale, cUBandLut, cUGalBigOn;
        private int noiseTex;            // v4: 3D noise texture
        // 256x1 RGBA8 bake of the 1D along-band terms (see uBandLut in the
        // shaders); refreshed each frame on the CPU — 256 cpuVn samples.
        private int bandLutTex;
        private ByteBuffer bandLutBuf;
        private int gasFbo, gasTex;      // v4: low-res gas render target
        private int gasW, gasH;
        private final java.util.Random sfRng = new java.util.Random();
        // Random per launch in normal use. The harness pins them: the seed sets
        // cloud coverage and band placement, so a fresh scene per run changes
        // how far rays march before the early-out — that scene-to-scene spread
        // is several ms and would otherwise drown the component deltas.
        private final float seedX;
        private final float seedY;
        private int sfSlot;
        private float sfStart = -1f, sfDur, sfMag, sfEnv, sfNext = 3f;
        private int sfLayer;
        private float sfCellX, sfCellY;
        private int cUNova, cUNovaP;
        private float nvStart = -1f, nvDur, nvMag, nvEnv, nvP, nvNext = -1f;
        private int nvLayer;
        private float nvCellX, nvCellY;

        private static float cpuFract(float x) { return x - (float)Math.floor(x); }
        private static float cpuH1(float ix, float iy) {
            float px = cpuFract(ix * 0.1031f);
            float py = cpuFract(iy * 0.1030f);
            float d = px*(px+19.19f) + py*(py+19.19f);
            px += d;
            py += d;
            return cpuFract(px * py);
        }
        private static float cpuVn(float px, float py) {
            float ix = (float)Math.floor(px), iy = (float)Math.floor(py);
            float fx = px - ix, fy = py - iy;
            float ux = fx*fx*fx*(fx*(fx*6f-15f)+10f);
            float uy = fy*fy*fy*(fy*(fy*6f-15f)+10f);
            float a = cpuH1(ix, iy), b = cpuH1(ix+1, iy);
            float c = cpuH1(ix, iy+1), d = cpuH1(ix+1, iy+1);
            return (a*(1-ux)+b*ux)*(1-uy) + (c*(1-ux)+d*ux)*uy;
        }
        // Bake the along-band structure terms into the 256x1 LUT. Must stay
        // formula-identical to what the shaders computed inline pre-LUT:
        // R = rift meander vn, G = rift depth vn, B = star-cloud amp vn,
        // A = bulge gaussian. The GL_TEXTURE_2D bind on unit 1 is left in
        // place for both passes to sample.
        private void updateBandLut(float t) {
            float center = seedX * 5f;
            float hsOff = (cpuFract(seedX * 61.7f + 0.137f) - 0.5f) * 8f;
            bandLutBuf.position(0);
            for (int i = 0; i < BAND_LUT_N; i++) {
                float bAlong = center + (i / (float)(BAND_LUT_N - 1) - 0.5f) * (2f * BAND_LUT_HALF);
                float rn   = cpuVn(bAlong * 2.2f, 3.7f + t * 0.0035f);
                float rn2  = cpuVn(bAlong * 5.0f, 8.1f);
                float lenN = cpuVn(bAlong * 1.1f, 1.3f);
                float d = bAlong - center - hsOff;
                float bulge = (float)Math.exp(-d * d * 1.4f);
                bandLutBuf.put((byte)(Math.min(1f, Math.max(0f, rn))   * 255f + 0.5f));
                bandLutBuf.put((byte)(Math.min(1f, Math.max(0f, rn2))  * 255f + 0.5f));
                bandLutBuf.put((byte)(Math.min(1f, Math.max(0f, lenN)) * 255f + 0.5f));
                bandLutBuf.put((byte)(Math.min(1f, Math.max(0f, bulge))* 255f + 0.5f));
            }
            bandLutBuf.position(0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bandLutTex);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, BAND_LUT_N, 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bandLutBuf);
        }

        // Star magnitude for a cell — mirrors starLayer's
        // hm=h1(cell+7.7); mag=STAR_MAG_FLOOR+(1-STAR_MAG_FLOOR)*hm^3, so the CPU
        // picker and the shader agree on which stars are bright. Must stay in
        // lock-step with the two shader copies of this formula.
        private static float cpuStarMag(float cellX, float cellY) {
            float hm = cpuH1(cellX + 7.7f, cellY + 7.7f);
            return STAR_MAG_FLOOR + (1f - STAR_MAG_FLOOR) * (float)Math.pow(hm, STAR_MAG_POW);
        }
        private boolean cpuHasStar(float cellX, float cellY, float ox, float oy) {
            float h = cpuH1(cellX, cellY);
            if (h < STAR_FLOOR) return false;
            float dn = cpuVn(cellX*SD_FREQ_LO+ox*0.1f, cellY*SD_FREQ_LO+oy*0.1f)*SD_W_LO
                      + cpuVn(cellX*SD_FREQ_HI+11f, cellY*SD_FREQ_HI+11f)*SD_W_HI;
            float dens = Math.max(0f, Math.min(1f, (dn-SD_SS_LO)/(SD_SS_HI-SD_SS_LO)));
            dens = dens*dens*(3f-2f*dens);
            dens = dens*dens;
            float thresh = STAR_CEIL - dens*STAR_RANGE;
            return h > thresh;
        }
        // Does grid cell (cellX,cellY) of a layer project onto the visible frame?
        // Inverts the shader's star transform: cell -> grid uv s = sr/zoom+0.5+seed,
        // where sr = R(sc) is the screen coord rotated by the layer's (ca,sa). We
        // undo the rotation and check |sc| against the frame bounds (sc.x in
        // ±0.5, sc.y in ±0.5*H/W). The flare/nova picker sampled an axis-aligned
        // box that ignores this rotation, so without the check many events (esp.
        // the ~65°-rotated layer 1) landed off-screen and were never seen.
        private boolean cpuOnScreen(float cellX, float cellY, float ox, float oy,
                                    float ca, float sa, float zoom) {
            if (screenW <= 0 || screenH <= 0) return true;
            float den = STAR_DEN * starScale;
            float s1x = (cellX + 0.5f - ox) / den;
            float s1y = (cellY + 0.5f - oy) / den;
            float srx = (s1x - 0.5f - seedX) * zoom;
            float sry = (s1y - 0.5f - seedY) * zoom;
            float scx =  ca * srx + sa * sry;   // R^T(sr): undo the layer rotation
            float scy = -sa * srx + ca * sry;
            float aspect = (float) screenH / screenW;
            float m = 0.94f; // keep the event a touch inside the frame edge
            return Math.abs(scx) < 0.5f * m && Math.abs(scy) < 0.5f * aspect * m;
        }
        private FloatBuffer quadBuf;
        private long startMs;
        private long lastDrawMs;
        private long fpsT0; private int fpsN; private long workAccNs;
        private int workSamples;
        private static final int GL_TIME_ELAPSED_EXT = 0x88BF;
        // [0] = gas pass (low-res FBO), [1] = composite pass (native res).
        private int[] timerQuery;
        private long gasAccNs, compAccNs;
        private boolean timerQueryPending;
        private boolean hasTimerQuery;
        private int screenW, screenH;
        private float starScale = 1f;    // grid-density scale; 1.0 = reference width

        private final float zoomMul;     // zoom-speed multiplier (1.0 = default)
        private final float gasScaleMax; // user setting; adaptive scaling never exceeds it
        private float gasScaleActive;    // actual gas FBO scale relative to the app surface
        // Minimum ms between frames. A cap only helps thermals when frame work
        // is below the budget; at high render scales this shader is GPU-bound.
        private final long frameMs;
        private final HdrSurface hdr;
        // Hot-swappable: replaced from the HDR ratio listener on the main
        // thread, read on the GL thread each frame. volatile makes the swap
        // visible without locking.
        private volatile HdrTuning hdrTuning;
        private final DisplayDiagnostics display;
        // Perf-ablation harness (dev only): see NebulaDream.onAttachedToWindow.
        private final String ablate;
        private final String ablDefs;
        private final boolean ablating;
        private final float gasPin;
        // Baked sky layer (always on): sprinkles + carpet + band grain, cached
        // in a full-res FBO and re-baked once per ~40s epoch.
        private int progSky, skAPos, skUTime, skURes, skUZoom, skUStarScale, skUSeed, skUBandLut;
        private int cUSky, cUSkyPrev, cUSprBlend;
        // Double-buffered so the epoch reposition can cross-dissolve (current +
        // previous bake) instead of blinking to black.
        private final int[] skyFbo = new int[2];
        private final int[] skyTex = new int[2];
        private int skyCur;            // index of the current (latest) bake
        private float skyBakeTime;     // t of the last bake, for the dissolve ramp
        private static final float SKY_DISSOLVE = 4.0f; // seconds to cross-dissolve
        private int lastBakedEpoch = -999999;

        NebulaRenderer(float zoomMul, int frameCapFps, HdrSurface hdr, float gasScale,
                       HdrTuning hdrTuning, DisplayDiagnostics display, String ablate,
                       float gasPin) {
            this.gasPin = gasPin;
            this.zoomMul = zoomMul;
            this.frameMs = (frameCapFps > 0) ? Math.round(1000.0 / frameCapFps) : 0L;
            this.hdr = hdr;
            this.gasScaleMax = gasScale;
            this.gasScaleActive = gasScale;
            this.hdrTuning = hdrTuning;
            this.display = display;
            this.ablate = (ablate == null) ? "" : ablate.trim();
            this.ablating = !this.ablate.isEmpty();
            StringBuilder sb = new StringBuilder();
            for (String name : this.ablate.split(",")) {
                name = name.trim().toUpperCase(java.util.Locale.US);
                // NONE is the harness baseline: pins the gas scale, ablates nothing.
                if (name.isEmpty() || name.equals("NONE")) continue;
                sb.append("#define ABL_NO_").append(name).append(" 1\n");
            }
            this.ablDefs = sb.toString();
            // Fixed scene for every variant in a sweep; random otherwise.
            this.seedX = ablating ? 0.3137f : (float) Math.random();
            this.seedY = ablating ? 0.6842f : (float) Math.random();
            // Same for event scheduling: a flare igniting inside one run's
            // measurement window and not another's is worth ~1ms of comp pass.
            if (ablating) sfRng.setSeed(20260714L);
        }

        // Splice #define blocks in after the #version line (which must stay
        // first) so the guards in the shader bodies see them.
        private String ablate(String src) { return spliceDefs(src, ""); }
        private String spliceDefs(String src, String extra) {
            String defs = ablDefs + extra;
            if (defs.isEmpty()) return src;
            int nl = src.indexOf('\n');
            return src.substring(0, nl + 1) + defs + src.substring(nl + 1);
        }

        void setHdrTuning(HdrTuning t) { if (t != null) this.hdrTuning = t; }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
            GLES20.glClearColor(0f,0f,0f,1f);
            // On context recreation (e.g. resume), drop stale GL objects first.
            if (progGas!=0)  { GLES20.glDeleteProgram(progGas);  progGas=0; }
            if (progComp!=0) { GLES20.glDeleteProgram(progComp); progComp=0; }
            if (progSky!=0)  { GLES20.glDeleteProgram(progSky);  progSky=0; }
            gasFbo=0; gasTex=0; // ids are stale on a new context; recreated in onSurfaceChanged
            skyFbo[0]=0; skyFbo[1]=0; skyTex[0]=0; skyTex[1]=0; skyCur=0; lastBakedEpoch=-999999;
            lastDrawMs=0;

            progGas = buildProg(VERT_ES3, ablate(FRAG_GAS));
            gAPos   = GLES20.glGetAttribLocation(progGas,"aPos");
            gUTime  = GLES20.glGetUniformLocation(progGas,"uTime");
            gURes   = GLES20.glGetUniformLocation(progGas,"uRes");
            gUNoise = GLES20.glGetUniformLocation(progGas,"uNoise");
            gUZoom  = GLES20.glGetUniformLocation(progGas,"uZoom");
            gUSeed  = GLES20.glGetUniformLocation(progGas,"uSeed");
            gUStarScale = GLES20.glGetUniformLocation(progGas,"uStarScale");
            gUBandLut = GLES20.glGetUniformLocation(progGas,"uBandLut");

            progComp = buildProg(VERT_ES3, ablate(FRAG_COMP));
            cAPos    = GLES20.glGetAttribLocation(progComp,"aPos");
            cUTime   = GLES20.glGetUniformLocation(progComp,"uTime");
            cURes    = GLES20.glGetUniformLocation(progComp,"uRes");
            cUZoom   = GLES20.glGetUniformLocation(progComp,"uZoom");
            cUHdr    = GLES20.glGetUniformLocation(progComp,"uHdr");
            cUHdrKnee= GLES20.glGetUniformLocation(progComp,"uHdrKnee");
            cUHdrGain= GLES20.glGetUniformLocation(progComp,"uHdrGain");
            cUHdrMax = GLES20.glGetUniformLocation(progComp,"uHdrMax");
            cUHdrStarGain = GLES20.glGetUniformLocation(progComp,"uHdrStarGain");
            cUHdrStarMax = GLES20.glGetUniformLocation(progComp,"uHdrStarMax");
            cUSeed   = GLES20.glGetUniformLocation(progComp,"uSeed");
            cUStarFlare = GLES20.glGetUniformLocation(progComp,"uStarFlare");
            cUNova   = GLES20.glGetUniformLocation(progComp,"uNova");
            cUNovaP  = GLES20.glGetUniformLocation(progComp,"uNovaP");
            cUGas    = GLES20.glGetUniformLocation(progComp,"uGas");
            cUGalBigOn = GLES20.glGetUniformLocation(progComp,"uGalBigOn");
            cUStarScale = GLES20.glGetUniformLocation(progComp,"uStarScale");
            cUBandLut = GLES20.glGetUniformLocation(progComp,"uBandLut");
            cUSky    = GLES20.glGetUniformLocation(progComp,"uSky");
            cUSkyPrev= GLES20.glGetUniformLocation(progComp,"uSkyPrev");
            cUSprBlend=GLES20.glGetUniformLocation(progComp,"uSprBlend");

            progSky = buildProg(VERT_ES3, ablate(FRAG_SKY));
            skAPos      = GLES20.glGetAttribLocation(progSky,"aPos");
            skUTime     = GLES20.glGetUniformLocation(progSky,"uTime");
            skURes      = GLES20.glGetUniformLocation(progSky,"uRes");
            skUZoom     = GLES20.glGetUniformLocation(progSky,"uZoom");
            skUStarScale= GLES20.glGetUniformLocation(progSky,"uStarScale");
            skUSeed     = GLES20.glGetUniformLocation(progSky,"uSeed");
            skUBandLut  = GLES20.glGetUniformLocation(progSky,"uBandLut");

            noiseTex = buildNoiseTexture(64); // re-upload each context (ids go stale); CPU gen is cached
            int[] lutId = new int[1];
            GLES20.glGenTextures(1, lutId, 0);
            bandLutTex = lutId[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bandLutTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            if (bandLutBuf == null) {
                bandLutBuf = ByteBuffer.allocateDirect(BAND_LUT_N * 4).order(ByteOrder.nativeOrder());
            }
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BAND_LUT_N, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            String exts = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            hasTimerQuery = exts != null && exts.contains("GL_EXT_disjoint_timer_query");
            if (hasTimerQuery) {
                timerQuery = new int[2];
                GLES30.glGenQueries(2, timerQuery, 0);
            } else {
                Log.w(TAG, "GL_EXT_disjoint_timer_query unavailable; GPU work timing disabled");
            }
            timerQueryPending = false;
            ByteBuffer bb=ByteBuffer.allocateDirect(QUAD.length*4);
            bb.order(ByteOrder.nativeOrder());
            quadBuf=bb.asFloatBuffer();
            quadBuf.put(QUAD).position(0);
            startMs=SystemClock.elapsedRealtime();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            screenW=w; screenH=h;
            GLES20.glViewport(0,0,w,h);

            // Hold star pixel size constant relative to the reference width (so a
            // narrow phone surface doesn't render tiny stars), then enlarge a bit
            // more on high-density panels — pixel parity alone leaves stars
            // physically small on a dense phone held close. The DPI term is
            // sqrt-damped and clamped to >=1, so it only ever enlarges, and is
            // exactly 1.0 at the Shield's 320 dpi reference (Shield unchanged).
            int dpi = (display != null && display.densityDpi > 0) ? display.densityDpi : (int) STAR_REF_DPI;
            float dpiBoost = (float) Math.max(1.0, Math.sqrt(dpi / STAR_REF_DPI));
            float widthScale = Math.min(1f, w / STAR_REF_WIDTH);
            starScale = Math.max(STAR_SCALE_MIN, widthScale / dpiBoost);
            Log.i(TAG, "star scale=" + String.format("%.3f", starScale) + " surfaceW=" + w
                + " dpi=" + dpi + " dpiBoost=" + String.format("%.2f", dpiBoost));

            gasScaleActive = initialGasScale(w, h);
            recreateGasTarget();
            recreateSkyTarget();
            String limit = (display == null) ? null : display.surfaceLimitMessage(w, h);
            if (limit != null) Log.w(TAG, limit);
        }

        // Full-resolution RGBA16F targets (double-buffered) for the baked sky
        // layer. Sampled 1:1 in the comp pass (NEAREST, so the resolved sprinkle
        // dots stay sharp). Two buffers so an epoch reposition cross-dissolves.
        private void recreateSkyTarget() {
            for (int b = 0; b < 2; b++) {
                if (skyFbo[b]!=0) { GLES20.glDeleteFramebuffers(1,new int[]{skyFbo[b]},0); skyFbo[b]=0; }
                if (skyTex[b]!=0) { GLES20.glDeleteTextures(1,new int[]{skyTex[b]},0); skyTex[b]=0; }
                int[] id=new int[1];
                GLES20.glGenTextures(1,id,0); skyTex[b]=id[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,skyTex[b]);
                GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_RGBA16F,screenW,screenH,0,
                    GLES20.GL_RGBA,GLES30.GL_HALF_FLOAT,null);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glGenFramebuffers(1,id,0); skyFbo[b]=id[0];
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,skyFbo[b]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,skyTex[b],0);
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
                    Log.w(TAG,"Sky FBO "+b+" incomplete.");
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            skyCur=0;
            lastBakedEpoch=-999999; // force a re-bake into the new target
            Log.i(TAG,"Sky FBO "+screenW+"x"+screenH+" (x2)");
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // Frame pacing: throttle the GL thread to the configured cap.
            if (frameMs > 0 && lastDrawMs != 0) {
                long sleep = frameMs - (SystemClock.elapsedRealtime() - lastDrawMs);
                if (sleep > 0) { try { Thread.sleep(sleep); } catch (InterruptedException ignored) {} }
            }
            lastDrawMs=SystemClock.elapsedRealtime();

            // Collect pending GPU timer query results (non-blocking). The comp
            // query is issued last, so its availability implies the gas one too.
            if (hasTimerQuery && timerQueryPending) {
                int[] avail = new int[1];
                GLES30.glGetQueryObjectuiv(timerQuery[1], GLES30.GL_QUERY_RESULT_AVAILABLE, avail, 0);
                if (avail[0] != 0) {
                    int[] gpuNs = new int[1];
                    GLES30.glGetQueryObjectuiv(timerQuery[0], GLES30.GL_QUERY_RESULT, gpuNs, 0);
                    gasAccNs += gpuNs[0];
                    GLES30.glGetQueryObjectuiv(timerQuery[1], GLES30.GL_QUERY_RESULT, gpuNs, 0);
                    compAccNs += gpuNs[0];
                    workAccNs = gasAccNs + compAccNs;
                    workSamples++;
                    timerQueryPending = false;
                }
            }

            // Log cadence plus sampled GPU work-time.
            fpsN++;
            if (fpsT0==0) fpsT0=lastDrawMs;
            else if (lastDrawMs-fpsT0>=2000) {
                float workMs = (workSamples > 0) ? workAccNs/(float)workSamples/1e6f : 0f;
                float gasMs  = (workSamples > 0) ? gasAccNs/(float)workSamples/1e6f : 0f;
                float compMs = (workSamples > 0) ? compAccNs/(float)workSamples/1e6f : 0f;
                Log.i(TAG,"SPIKE cadence="+String.format("%.1f",fpsN*1000f/(lastDrawMs-fpsT0))
                    +"fps gpuWork="+String.format("%.1f",workMs)
                    +"ms gasMs="+String.format("%.2f",gasMs)
                    +" compMs="+String.format("%.2f",compMs)
                    +" n="+workSamples
                    +" ablate="+(ablating?ablate:"none")
                    +" surface="+screenW+"x"+screenH
                    +" gas="+gasW+"x"+gasH
                    +" gasScale="+String.format("%.2f",gasScaleActive)
                    +" hdr="+(hdr!=null&&hdr.hdrActive)
                    +" activeMode="+((display==null)?"unknown":display.activeMode())
                    +" requestedMode="+((display==null)?"none":display.requestedMode)
                    +" hdrCaps="+((display==null)?"unknown":display.hdrCaps)
                    +" hdrRatio="+((display==null)?"n/a":display.hdrRatioLabel()));
                // The harness pins the gas scale: adapting it mid-sweep would
                // resize the FBO between variants and corrupt the comparison.
                if (!ablating && SystemClock.elapsedRealtime() - startMs > 6000) adaptGasScale(workMs);
                fpsN=0; fpsT0=lastDrawMs; workAccNs=0; gasAccNs=0; compAccNs=0; workSamples=0;
            }
            // Sample every frame while ablating (tight error bars on the deltas);
            // 1-in-20 otherwise, which is all the adaptive scaler needs.
            boolean sampleWork = hasTimerQuery && !timerQueryPending
                && (ablating || (fpsN % 20) == 0);

            float t=(SystemClock.elapsedRealtime()-startMs)/1000f;
            updateBandLut(t); // binds the LUT on unit 1 for both passes

            // ── SKY BAKE (prototype): re-render the cached sprinkle/carpet/grain
            // layer only when the ~40s sprinkle epoch rolls over. Everything it
            // bakes is screen-space and static within an epoch, so this runs a
            // handful of times per session; the comp pass just samples it. ─────
            if (progSky != 0 && skyFbo[0] != 0) {
                int epoch = (int)Math.floor(t / 40.0);
                if (epoch != lastBakedEpoch) {
                    boolean first = (lastBakedEpoch == -999999);
                    int dst = first ? skyCur : (1 - skyCur); // bake into the OTHER buffer
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, skyFbo[dst]);
                    GLES20.glViewport(0, 0, screenW, screenH);
                    GLES20.glUseProgram(progSky);
                    GLES20.glUniform1f(skUTime, t);
                    GLES20.glUniform2f(skURes, (float)screenW, (float)screenH);
                    GLES20.glUniform1f(skUZoom, zoomMul);
                    GLES20.glUniform1f(skUStarScale, starScale);
                    GLES20.glUniform2f(skUSeed, seedX, seedY);
                    GLES20.glUniform1i(skUBandLut, 1);
                    quadBuf.position(0);
                    GLES20.glVertexAttribPointer(skAPos,2,GLES20.GL_FLOAT,false,8,quadBuf);
                    GLES20.glEnableVertexAttribArray(skAPos);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);
                    skyCur = dst;
                    // First bake: no valid previous buffer, so start past the
                    // dissolve (blend=1) to show the fresh bake immediately.
                    skyBakeTime = first ? (t - SKY_DISSOLVE) : t;
                    lastBakedEpoch = epoch;
                    Log.i(TAG, "Sky layer baked (epoch " + epoch + ", buf " + dst + ")");
                }
            }

            // ── PASS 1: raymarch the gas into the low-res FBO ────────────────
            if (sampleWork) GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, timerQuery[0]);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,gasFbo);
            GLES20.glViewport(0,0,gasW,gasH);
            GLES20.glUseProgram(progGas);
            GLES20.glUniform1f(gUTime,t);
            GLES20.glUniform2f(gURes,(float)gasW,(float)gasH);
            GLES20.glUniform1f(gUZoom,zoomMul);
            GLES20.glUniform1f(gUStarScale,starScale);
            GLES20.glUniform2f(gUSeed,seedX,seedY);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D,noiseTex);
            GLES20.glUniform1i(gUNoise,0);
            GLES20.glUniform1i(gUBandLut,1);
            quadBuf.position(0);
            GLES20.glVertexAttribPointer(gAPos,2,GLES20.GL_FLOAT,false,8,quadBuf);
            GLES20.glEnableVertexAttribArray(gAPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);

            if (sampleWork) GLES30.glEndQuery(GL_TIME_ELAPSED_EXT);

            // ── PASS 2: composite at native res (sharp stars/haze/flare) ─────
            if (sampleWork) GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, timerQuery[1]);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            GLES20.glViewport(0,0,screenW,screenH);
            GLES20.glUseProgram(progComp);
            GLES20.glUniform1f(cUTime,t);
            GLES20.glUniform2f(cURes,(float)screenW,(float)screenH);
            GLES20.glUniform1f(cUZoom,zoomMul);
            GLES20.glUniform1f(cUStarScale,starScale);
            GLES20.glUniform2f(cUSeed,seedX,seedY);
            HdrTuning tuning = hdrTuning; // snapshot the volatile for a tear-free frame
            GLES20.glUniform1f(cUHdr,(hdr!=null && hdr.hdrActive)?1f:0f);
            GLES20.glUniform1f(cUHdrKnee,tuning.knee);
            GLES20.glUniform1f(cUHdrGain,tuning.gain);
            GLES20.glUniform1f(cUHdrMax,tuning.max);
            GLES20.glUniform1f(cUHdrStarGain,tuning.starGain);
            GLES20.glUniform1f(cUHdrStarMax,tuning.starMax);
            updateFlare(t);
            GLES20.glUniform4f(cUStarFlare, sfCellX, sfCellY, sfEnv * sfMag, (float)sfLayer);
            updateNova(t);
            GLES20.glUniform4f(cUNova, nvCellX, nvCellY, nvEnv * nvMag, (float)nvLayer);
            GLES20.glUniform1f(cUNovaP, nvP);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,gasTex);
            GLES20.glUniform1i(cUGas,0);
            // Showpiece-galaxy gate, recomputed per frame. The galaxy layers
            // ride their own quarter-speed zoom phase (gph below mirrors the
            // shader), so the visible cell box moves and must be re-tested.
            float gph = t * SZ_SPEED * zoomMul * 0.25f;
            float gp1 = cpuFract(gph + L0_PHASE);
            float gp2 = cpuFract(gph + L1_PHASE);
            GLES20.glUniform2f(cUGalBigOn,
                bigGalaxyVisible(gp1, L0_OX, L0_OY, 0.951f, 0.309f) ? 1f : 0f,
                bigGalaxyVisible(gp2, L1_OX, L1_OY, 0.423f, 0.906f) ? 1f : 0f);
            GLES20.glUniform1i(cUBandLut,1);
            if (cUSky >= 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,skyTex[skyCur]);
                GLES20.glUniform1i(cUSky,2);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,skyTex[1-skyCur]);
                GLES20.glUniform1i(cUSkyPrev,3);
                float blend = clamp((t - skyBakeTime) / SKY_DISSOLVE, 0f, 1f);
                GLES20.glUniform1f(cUSprBlend, blend);
            }
            quadBuf.position(0);
            GLES20.glVertexAttribPointer(cAPos,2,GLES20.GL_FLOAT,false,8,quadBuf);
            GLES20.glEnableVertexAttribArray(cAPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);

            if (sampleWork) {
                GLES30.glEndQuery(GL_TIME_ELAPSED_EXT);
                timerQueryPending = true;
            }
        }

        private void updateFlare(float t) {
            if (t >= sfNext) {
                // Rare flares must all be significant; floor the magnitude.
                sfMag = 0.6f + 0.4f*(float)Math.sqrt(sfRng.nextFloat());
                sfDur = FLARE_DUR_MIN + sfRng.nextFloat() * FLARE_DUR_RNG;
                sfStart = t;
                float sph = t * SZ_SPEED * zoomMul;
                float bestF = -1f;
                for (int i = 0; i < LAYER_OFF.length; i++) {
                    float ti = sph + LAYER_PHASE[i];
                    ti -= (float)Math.floor(ti);
                    float fi = Math.max(0f, Math.min(1f, (ti - 0.10f) / 0.20f))
                             * Math.max(0f, Math.min(1f, (0.90f - ti) / 0.20f));
                    if (fi > bestF) { bestF = fi; sfLayer = i; }
                }
                float lox = LAYER_OFF[sfLayer][0], loy = LAYER_OFF[sfLayer][1];
                float lti = sph + LAYER_PHASE[sfLayer];
                lti -= (float)Math.floor(lti);
                float zoom = (float)Math.exp(lti * SZ_MAX);
                float den = STAR_DEN * starScale; // match the shader's scaled grid
                float cxCenter = (0.5f + seedX) * den + lox;
                float cyCenter = (0.5f + seedY) * den + loy;
                // Generous square candidate box (1.3x) so the layer's rotated
                // on-screen rect is fully covered; cpuOnScreen then rejects the
                // corners that fall outside the frame. Prefer an on-screen star,
                // but always leave sfCell on a real star so the flare isn't blank.
                float span = 1.3f * (den / 2f) / zoom;
                float ca = (sfLayer == 0) ? 0.951f : 0.423f;
                float sa = (sfLayer == 0) ? 0.309f : 0.906f;
                for (int attempt = 0; attempt < 200; attempt++) {
                    float cx = (float)Math.floor(cxCenter - span + sfRng.nextFloat() * 2f * span);
                    float cy = (float)Math.floor(cyCenter - span + sfRng.nextFloat() * 2f * span);
                    if (!cpuHasStar(cx, cy, lox, loy)) continue;
                    if (cpuStarMag(cx, cy) < FLARE_MIN_MAG) continue; // brighter-than-average only
                    sfCellX = cx; sfCellY = cy; // fallback: last bright star found
                    if (cpuOnScreen(cx, cy, lox, loy, ca, sa, zoom)) break;
                }
                sfNext = t + sfDur + FLARE_GAP_MIN + sfRng.nextFloat() * FLARE_GAP_RNG;
                Log.i(TAG,"FLARE cell="+sfCellX+","+sfCellY+" layer="+sfLayer+" mag="+String.format("%.3f",sfMag));
            }
            sfEnv = 0f;
            if (sfStart >= 0f && t < sfStart + sfDur) {
                float p = (t - sfStart) / sfDur;
                // Asymmetric: abrupt rise, long decay, so the flare ignites and
                // then fades rather than swelling in and out evenly. (Note the
                // event's "quivering spike" character does NOT come from this
                // envelope: the base star keeps twinkling at 0.6-1.4Hz under the
                // overlay, and the spike term is fv^4, so spikes only bloom at
                // the very tip of the envelope.)
                if (p < FLARE_RISE) {
                    float r = p / FLARE_RISE;
                    sfEnv = r * r * (3f - 2f * r);        // smoothstep in
                } else {
                    float d = (p - FLARE_RISE) / (1f - FLARE_RISE);
                    float k = 1f - d;
                    sfEnv = (float)Math.pow(k, FLARE_DECAY_POW);
                }
            }
        }

        private void updateNova(float t) {
            if (nvNext < 0f) nvNext = NOVA_FIRST_MIN + sfRng.nextFloat() * NOVA_FIRST_RNG;
            if (t >= nvNext) {
                nvMag = 1.25f + 0.35f * sfRng.nextFloat();
                nvDur = NOVA_DUR_MIN + sfRng.nextFloat() * NOVA_DUR_RNG;
                nvStart = t;
                // Unlike flares, a nova outlives a large part of a star-layer
                // cycle: pick the layer with the most remaining visibility, not
                // the currently most-visible one, so it doesn't cross-fade away
                // mid-event.
                float sph = t * SZ_SPEED * zoomMul;
                float bestRem = -1f;
                for (int i = 0; i < LAYER_OFF.length; i++) {
                    float ti = cpuFract(sph + LAYER_PHASE[i]);
                    float rem = 0.90f - ti;
                    if (ti < 0.62f && rem > bestRem) { bestRem = rem; nvLayer = i; }
                }
                float lox = LAYER_OFF[nvLayer][0], loy = LAYER_OFF[nvLayer][1];
                float lti = cpuFract(sph + LAYER_PHASE[nvLayer]);
                float zoom = (float)Math.exp(lti * SZ_MAX);
                float den = STAR_DEN * starScale;
                float cxCenter = (0.5f + seedX) * den + lox;
                float cyCenter = (0.5f + seedY) * den + loy;
                // On-screen placement (same rotation-aware fix as the flare).
                float span = 1.3f * (den / 2f) / zoom;
                float ca = (nvLayer == 0) ? 0.951f : 0.423f;
                float sa = (nvLayer == 0) ? 0.309f : 0.906f;
                for (int attempt = 0; attempt < 200; attempt++) {
                    float cx = (float)Math.floor(cxCenter - span + sfRng.nextFloat() * 2f * span);
                    float cy = (float)Math.floor(cyCenter - span + sfRng.nextFloat() * 2f * span);
                    if (!cpuHasStar(cx, cy, lox, loy)) continue;
                    if (cpuStarMag(cx, cy) < NOVA_MIN_MAG) continue; // brightest stars only
                    nvCellX = cx; nvCellY = cy;
                    if (cpuOnScreen(cx, cy, lox, loy, ca, sa, zoom)) break;
                }
                nvNext = t + nvDur + NOVA_GAP_MIN + sfRng.nextFloat() * NOVA_GAP_RNG;
                Log.i(TAG,"NOVA cell="+nvCellX+","+nvCellY+" layer="+nvLayer+" mag="+String.format("%.3f",nvMag)+" dur="+String.format("%.1f",nvDur));
            }
            nvEnv = 0f; nvP = 0f;
            if (nvStart >= 0f && t < nvStart + nvDur) {
                float p = (t - nvStart) / nvDur;
                nvP = p;
                // ~2s rise to full, short hold, long exponential fade; a final
                // window ramp lands the tail at exactly zero.
                float rise = Math.min(1f, p / 0.08f);
                rise = rise * rise * (3f - 2f * rise);
                float fall = (p > 0.18f) ? (float)Math.exp(-(p - 0.18f) * 4.2f) : 1f;
                float end = Math.max(0f, Math.min(1f, (p - 0.85f) / 0.15f));
                end = 1f - end * end * (3f - 2f * end);
                nvEnv = rise * fall * end;
            }
        }

        // Is a showpiece galaxy in frame on this star layer? Maps the four
        // screen corners through the shader's own transform to get the visible
        // cell box, then tests the same hash the shader does.
        //
        // Answering per-layer (not per-cell) is all the shader needs: once the
        // gate opens it runs its normal per-pixel hash, so it still finds every
        // visible galaxy itself and draws an identical result. The gate only
        // decides whether the layer is worth looking at.
        //
        // The grid is deliberately coarse (den ~6 across the frame), so this
        // scans a few dozen cells per layer per frame — nothing next to the
        // 1.80ms/frame it saves the GPU. MUST NOT under-report: a false
        // negative pops a galaxy out of the sky, so the box is padded by a
        // cell, and the mapping is affine (rotate + uniform scale + translate),
        // so the corners' bounding box provably contains every cell any pixel
        // can land in.
        private boolean bigGalaxyVisible(float zoomPhase, float ox, float oy,
                                         float ca, float sa) {
            if (screenW <= 0 || screenH <= 0) return true; // unknown frame: never hide

            float den  = STAR_DEN * starScale * 0.075f;
            float zoom = (float) Math.exp(zoomPhase * SZ_MAX);
            float aspect = (float) screenH / screenW;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float scx = ((i & 1) == 0 ? -0.5f : 0.5f);
                float scy = ((i & 2) == 0 ? -0.5f : 0.5f) * aspect;
                // sr = R(sc), matching the shader's sr1/sr2 construction.
                float srx = ca * scx - sa * scy;
                float sry = sa * scx + ca * scy;
                // gs = sr/zoom + 0.5 + seed;  gp = gs*den + offset
                float gpx = (srx / zoom + 0.5f + seedX) * den + ox;
                float gpy = (sry / zoom + 0.5f + seedY) * den + oy;
                minX = Math.min(minX, gpx); maxX = Math.max(maxX, gpx);
                minY = Math.min(minY, gpy); maxY = Math.max(maxY, gpy);
            }
            int c0x = (int) Math.floor(minX) - 1, c1x = (int) Math.floor(maxX) + 1;
            int c0y = (int) Math.floor(minY) - 1, c1y = (int) Math.floor(maxY) + 1;
            for (int cy = c0y; cy <= c1y; cy++) {
                for (int cx = c0x; cx <= c1x; cx++) {
                    if (cpuH1(cx + 19.7f, cy + 3.9f) >= GAL_BIG_THRESH) return true;
                }
            }
            return false;
        }

        private float initialGasScale(int w, int h) {
            if (gasPin > 0f) return gasPin; // harness: fixed FBO across all variants
            float basePixels = 1920f * 1080f;
            float windowPixels = Math.max(1f, (float) w * (float) h);
            float thermalScale = (float) Math.sqrt(basePixels / windowPixels);
            return clamp(gasScaleMax * Math.min(1f, thermalScale), 0.10f, gasScaleMax);
        }

        private void adaptGasScale(float workMs) {
            if (frameMs <= 0 || workMs <= 0f || screenW <= 0 || screenH <= 0) return;
            float budget = frameMs * 0.88f;
            float next = gasScaleActive;
            if (workMs > budget && gasScaleActive > 0.101f) {
                float factor = (float) Math.sqrt(Math.max(0.25f, budget / workMs)) * 0.96f;
                next = gasScaleActive * factor;
            } else if (workMs < budget - 4.5f && gasScaleActive < gasScaleMax - 0.005f) {
                // Grow whenever there is >=3ms of headroom under the shrink
                // budget (the old frameMs*0.58 threshold was unreachable at
                // typical ~31ms work, so a thermally-lowered startup scale
                // stuck for the whole session — dither artifacts worsen as
                // gas resolution drops, so recovering matters). The 4.5ms
                // deadband keeps it from flapping against the shrink branch
                // (3ms was measured to oscillate 0.28<->0.30 on a hot
                // Shield, reallocating the FBO every few seconds). Step must
                // be 1.06+: below that, at scales <=0.25 the move is under
                // the 0.01 delta gate below and the grow silently never
                // happens.
                next = gasScaleActive * 1.06f;
            }
            next = clamp(next, 0.10f, gasScaleMax);
            if (Math.abs(next - gasScaleActive) >= 0.01f) {
                gasScaleActive = next;
                recreateGasTarget();
                Log.i(TAG,"Adaptive gas scale now "+String.format("%.2f",gasScaleActive)
                    +" for "+gasW+"x"+gasH+" targetFps="+String.format("%.1f",1000f/frameMs));
            }
        }

        private void recreateGasTarget() {
            // RGBA16F preserves HDR gas highlights; RGBA8 fallback is a safe
            // degradation for drivers without renderable half-float textures.
            if (gasFbo!=0) { GLES20.glDeleteFramebuffers(1,new int[]{gasFbo},0); gasFbo=0; }
            if (gasTex!=0) { GLES20.glDeleteTextures(1,new int[]{gasTex},0); gasTex=0; }
            gasW=Math.max(1,Math.round(screenW*gasScaleActive));
            gasH=Math.max(1,Math.round(screenH*gasScaleActive));
            int[] id=new int[1];
            GLES20.glGenTextures(1,id,0); gasTex=id[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,gasTex);
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_RGBA16F,gasW,gasH,0,
                GLES20.GL_RGBA,GLES30.GL_HALF_FLOAT,null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glGenFramebuffers(1,id,0); gasFbo=id[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,gasFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,gasTex,0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG,"RGBA16F gas FBO incomplete; falling back to RGBA8.");
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,gasTex);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,gasW,gasH,0,
                    GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
                    throw new RuntimeException("Gas FBO incomplete even with RGBA8.");
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            Log.i(TAG,"Gas FBO "+gasW+"x"+gasH+" (surface "+screenW+"x"+screenH
                +", scale "+String.format("%.2f",gasScaleActive)
                +", userMax "+String.format("%.2f",gasScaleMax)+")");
        }

        private static float clamp(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        private int buildProg(String vs,String fs){
            int v=shader(GLES20.GL_VERTEX_SHADER,vs);
            int f=shader(GLES20.GL_FRAGMENT_SHADER,fs);
            int p=GLES20.glCreateProgram();
            GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);
            GLES20.glLinkProgram(p);
            int[] ok=new int[1];
            GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
            if (ok[0]==0) {
                String log=GLES20.glGetProgramInfoLog(p);
                GLES20.glDeleteProgram(p);
                GLES20.glDeleteShader(v);
                GLES20.glDeleteShader(f);
                Log.e(TAG,"Program link failed: "+log);
                throw new RuntimeException("Program link failed: "+log);
            }
            // Shaders are now linked into the program; flag them for deletion.
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }

        // ── v4: tiling 3D noise texture (R = 3-octave value fbm, G = inverted
        // Worley billow). Generated once on the CPU (cached buffer), uploaded with
        // glTexImage3D + REPEAT so the volume tiles seamlessly. Replacing analytic
        // noise with fetches removes the marcher's ALU cost uniformly. ─────────────
        private static ByteBuffer noiseBuf; // cached across context recreations

        private static float hashN(int x,int y,int z){
            int h=x*374761393 + y*668265263 + z*2147483647;
            h=(h^(h>>13))*1274126177;
            return ((h^(h>>16)) & 0x7fffffff)/(float)0x7fffffff;
        }
        // Tiling value noise on a 'per'-periodic lattice with quintic interpolation.
        private static float vnoise(float px,float py,float pz,int per){
            int x0=(int)Math.floor(px), y0=(int)Math.floor(py), z0=(int)Math.floor(pz);
            float fx=px-x0, fy=py-y0, fz=pz-z0;
            float ux=fx*fx*fx*(fx*(fx*6-15)+10), uy=fy*fy*fy*(fy*(fy*6-15)+10), uz=fz*fz*fz*(fz*(fz*6-15)+10);
            float v=0f;
            for(int dz=0;dz<=1;dz++) for(int dy=0;dy<=1;dy++) for(int dx=0;dx<=1;dx++){
                float w=(dx==0?1-ux:ux)*(dy==0?1-uy:uy)*(dz==0?1-uz:uz);
                v+=w*hashN(((x0+dx)%per+per)%per, ((y0+dy)%per+per)%per, ((z0+dz)%per+per)%per);
            }
            return v;
        }
        // Tiling Worley: feature point per cell, wrap the cell lookup by 'per'.
        private static float worleyN(float px,float py,float pz,int per){
            int x0=(int)Math.floor(px), y0=(int)Math.floor(py), z0=(int)Math.floor(pz);
            float best=8f;
            for(int dz=-1;dz<=1;dz++) for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++){
                int cx=x0+dx, cy=y0+dy, cz=z0+dz;
                int wx=((cx%per)+per)%per, wy=((cy%per)+per)%per, wz=((cz%per)+per)%per;
                float ox=hashN(wx,wy,wz), oy=hashN(wx+57,wy+113,wz+271), oz=hashN(wx+733,wy+929,wz+101);
                float rx=cx+ox-px, ry=cy+oy-py, rz=cz+oz-pz;
                float d=rx*rx+ry*ry+rz*rz;
                if(d<best) best=d;
            }
            return (float)Math.sqrt(best);
        }

        private int buildNoiseTexture(int N){
            if (noiseBuf==null) {
                long t0=SystemClock.elapsedRealtime();
                // RG8 deliberately: RG16F was tried for the quantization banding and
                // DOUBLED frame cost (2x texel bytes halves the effective texture
                // cache — this marcher is cache-bound). Banding is instead broken up
                // by a per-sample density dither in the march loop.
                noiseBuf=ByteBuffer.allocateDirect(N*N*N*2).order(ByteOrder.nativeOrder());
                for(int z=0;z<N;z++){
                    float pz=z/(float)N;
                    for(int y=0;y<N;y++){
                        float py=y/(float)N;
                        for(int x=0;x<N;x++){
                            float px=x/(float)N;
                            // R: 3-octave tiling value fbm (periods 4,8,16 inside the volume)
                            float f=0.5333f*vnoise(px*4,py*4,pz*4,4)
                                   +0.2667f*vnoise(px*8,py*8,pz*8,8)
                                   +0.1333f*vnoise(px*16,py*16,pz*16,16);
                            // G: inverted Worley billow (period 6), 2 octaves
                            float w=1f-worleyN(px*6,py*6,pz*6,6);
                            float w2=1f-worleyN(px*12,py*12,pz*12,12);
                            float g=Math.max(0f,Math.min(1f,w*0.65f+w2*0.35f));
                            noiseBuf.put((byte)(Math.max(0f,Math.min(1f,f))*255));
                            noiseBuf.put((byte)(g*255));
                        }
                    }
                }
                Log.i(TAG,"3D noise "+N+"^3 generated in "+(SystemClock.elapsedRealtime()-t0)+"ms");
            }
            noiseBuf.position(0);
            int[] tex=new int[1];
            GLES20.glGenTextures(1,tex,0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D,tex[0]);
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RG8,N,N,N,0,
                GLES30.GL_RG,GLES30.GL_UNSIGNED_BYTE,noiseBuf);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_REPEAT);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_REPEAT);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_WRAP_R,GLES20.GL_REPEAT);
            return tex[0];
        }
        private int shader(int type,String src){
            int s=GLES20.glCreateShader(type);
            GLES20.glShaderSource(s,src);
            GLES20.glCompileShader(s);
            int[] ok=new int[1];
            GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
            if (ok[0]==0) {
                String log=GLES20.glGetShaderInfoLog(s);
                GLES20.glDeleteShader(s);
                Log.e(TAG,"Shader compile failed: "+log);
                throw new RuntimeException("Shader compile failed: "+log);
            }
            return s;
        }
    }
}
