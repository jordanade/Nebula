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
    private float hdrBias = 1.0f;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        Prefs prefs = Prefs.from(this);
        DisplayDiagnostics display = DisplayDiagnostics.configure(this);
        displayDiag = display;
        hdrBias = prefs.hdrBias();
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

        HdrTuning hdrTuning = HdrTuning.from(display.display, hdrBias);

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
            prefs.renderScale(), hdrTuning, display);
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
            HdrTuning t = HdrTuning.from(disp, hdrBias);
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
        private static final float STAR_FLOOR  = 0.38f;
        private static final float STAR_CEIL   = 0.92f;
        private static final float STAR_RANGE  = 0.54f;

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
        private static final float STAR_SCALE_MIN = 0.45f;
        private static final float SZ_SPEED    = 0.0120f;
        private static final float SZ_MAX      = 0.75f;
        private static final float L0_OX = 0.0f,  L0_OY = 0.0f;
        private static final float L1_OX = 0.37f, L1_OY = 0.21f;
        private static final float L0_PHASE = 0.0f, L1_PHASE = 0.5f;

        // Sprinkle stars
        private static final float SP_DEN      = 800.0f;
        private static final float SP_CORE     = 1200.0f;
        private static final float SP_BRI      = 0.30f;
        private static final float SP_BASE     = 0.08f;
        private static final float SP_SS_LO    = 0.25f;
        private static final float SP_SS_HI    = 0.65f;

        // Galaxy haze
        private static final float HAZE_MUL    = 0.22f;

        // Star rendering
        private static final float SPIKE_THRESH = 0.65f;

        // Flare scheduling
        private static final float FLARE_DUR_MIN = 1.5f;
        private static final float FLARE_DUR_RNG = 2.0f;
        private static final float FLARE_GAP_MIN = 0.1f;
        private static final float FLARE_GAP_RNG = 0.5f;

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
            "#define SD_FREQ_LO " + SD_FREQ_LO + "\n" +
            "#define SD_FREQ_HI " + SD_FREQ_HI + "\n" +
            "#define SD_W_LO "    + SD_W_LO    + "\n" +
            "#define SD_W_HI "    + SD_W_HI    + "\n" +
            "#define SD_SS_LO "   + SD_SS_LO   + "\n" +
            "#define SD_SS_HI "   + SD_SS_HI   + "\n";

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
            "#define SPIKE_THRESH " + SPIKE_THRESH + "\n";

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
            "  d=rm(d,ero*0.20,1.0);\n" +
            "  float ero2=texture(uNoise,p*0.58).g;\n" +             // fine erosion for detailed edges
            "  d=rm(d,ero2*0.22,1.0);\n" +
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
            "  d=rm(d,ero*0.20,1.0);\n" +
            "  d=rm(d,0.20,1.0);\n" +                                // approximate the fine erosion's average
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
            "  float temp=clamp((reg-0.5)*1.5+0.60,0.0,1.0);\n" +
            "  vec3 warm=vec3(1.00,0.44,0.16);\n" +  // orange/red (rare warm accent)
            "  vec3 pink=vec3(0.96,0.28,0.60);\n" +  // magenta-pink
            "  vec3 midc=vec3(0.49,0.14,0.94);\n" +  // deep violet
            "  vec3 cool=vec3(0.31,0.50,1.00);\n" +  // blue
            "  vec3 tcol = (temp<0.33) ? mix(warm,pink,temp/0.33)\n" +
            "             : (temp<0.66) ? mix(pink,midc,(temp-0.33)/0.33)\n" +
            "                           : mix(midc,cool,(temp-0.66)/0.34);\n" +
            "  vec3 sunCol=tcol;\n" +
            "  vec3 ambCol=mix(vec3(0.09,0.07,0.24),tcol*0.30,0.40);\n" + // violet ambient tinted toward the region colour
            "  float t=1.35+fract(sin(dot(gl_FragCoord.xy,vec2(41.3,289.1))+uTime)*43758.5)*0.08;\n" + // start beyond camera-origin gas; prevents full-frame color wash when flying through a cloud
            "  float T=1.0; vec3 col=vec3(0.0);\n" +
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
            "    float dith=mix(0.010,0.0,nearAmt);\n" +
            "    d=max(d+(fract(sin(dot(p.xy+vec2(p.z),vec2(12.9898,78.233)))*43758.55)-0.5)*dith,0.0);\n" +
            "    d*=cameraFade;\n" +
            "    float dustMacro=smoothstep(0.53,0.80,texture(uNoise,p*0.038+vec3(6.3,1.7,0.0)).r);\n" +
            "    float frontDetail=smoothstep(0.34,0.82,texture(uNoise,p*0.085+vec3(9.1,3.8,uTime*0.003)).r);\n" +
            "    float nearDust=(1.0-smoothstep(4.0,12.0,t))*cameraFade;\n" +
            "    float dustD=dustMacro*nearDust*(0.38+0.50*frontBreak);\n" +
            "    float dustOcc=dustD*dustD;\n" +
            "    if(d>0.01){\n" +
            "      float dt=0.11*g;\n" +
            "      vec3 emit=tcol*d*0.34+ambCol*d*0.18;\n" +        // dimmer interiors; rims carry more of the shape
            "      emit*=1.0-0.50*dustOcc;\n" +
            // Relief + rims fade into the NEAR layers; mid/rear stays pure soft
            // glow, avoiding a hard distance where dark blobs become bright rims.
            "      if(nearAmt>0.001){\n" +
            "        float ds=densFar(p);\n" +
            "        float dlit=densFar(p+ldir*0.34);\n" +
            "        float lit=clamp((ds-dlit)*9.0,0.0,1.0);\n" +
            "        emit*=mix(1.0,0.38+2.15*lit,nearAmt);\n" +
            // EDGE: ionization-front rim — fires when the ray crosses a boundary
            // (density jumping from ~nothing to substantial between samples).
            "        float rim=smoothstep(0.015,0.16,d-dPrev)*clamp(1.0-dPrev*6.0,0.0,1.0);\n" +
            "        emit+=mix(tcol,vec3(1.0,0.62,0.92),0.35)*rim*1.15*nearAmt;\n" +
            "        float frontGrain=0.42+0.82*frontDetail;\n" +
            "        float shell=smoothstep(0.018,0.10,d)*(1.0-smoothstep(0.22,0.48,d));\n" +
            "        emit+=mix(tcol,vec3(1.0,0.48,0.90),0.30)*frontHalo*(d*0.20+shell*0.68)*frontGrain*0.35*nearAmt;\n" +
            "      }\n" +
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
            "  col*=0.95;\n" + // gain for the tonemap (slightly dimmer overall)

            // Galaxy haze + deep-space floor BEHIND the gas (same three-phase star
            // zoom as the comp pass, so haze and stars move as one entity).
            "  vec3 hz=vec3(0.0);\n" +
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
            "  vec3 pFar=ro+rd*55.0;\n" +
            "  float farBase=texture(uNoise,pFar*0.062).r;\n" +
            "  float farCov=smoothstep(0.26,0.68,texture(uNoise,pFar*0.022+0.31).r);\n" +
            "  float farD=rm(farBase,1.0-farCov,1.0)*farCov;\n" +
            "  float farEro=texture(uNoise,pFar*0.22).g;\n" +
            "  farD=rm(farD,farEro*0.24,1.0);\n" +
            "  farD=pow(farD,2.0);\n" +
            "  vec3 pFar2=ro+rd*72.0;\n" +
            "  float farBase2=texture(uNoise,pFar2*0.062).r;\n" +
            "  float farCov2=smoothstep(0.26,0.68,texture(uNoise,pFar2*0.022+0.31).r);\n" +
            "  float farD2=rm(farBase2,1.0-farCov2,1.0)*farCov2;\n" +
            "  float farEro2=texture(uNoise,pFar2*0.22).g;\n" +
            "  farD2=rm(farD2,farEro2*0.24,1.0);\n" +
            "  farD2=pow(farD2,2.0);\n" +
            "  vec3 pFar3=ro+rd*95.0;\n" +
            "  float farBase3=texture(uNoise,pFar3*0.062).r;\n" +
            "  float farCov3=smoothstep(0.26,0.68,texture(uNoise,pFar3*0.022+0.31).r);\n" +
            "  float farD3=rm(farBase3,1.0-farCov3,1.0)*farCov3;\n" +
            "  float farEro3=texture(uNoise,pFar3*0.22).g;\n" +
            "  farD3=rm(farD3,farEro3*0.24,1.0);\n" +
            "  farD3=pow(farD3,2.0);\n" +
            "  float farShape=farD*0.45+farD2*0.35+farD3*0.20;\n" +
            "  float farReg=texture(uNoise,vec3(pFar.xy*0.03,uTime*0.01)).r;\n" +
            "  float farTemp=clamp((farReg-0.5)*1.4+0.55,0.0,1.0);\n" +
            "  vec3 farCol=(farTemp<0.5)?mix(pink,midc,farTemp/0.5):mix(midc,cool,(farTemp-0.5)/0.5);\n" +
            "  col+=T*farCol*farShape*0.22;\n" +
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
            "uniform vec2 uSeed;\n" +
            "uniform sampler2D uGas;\n" +
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
            "vec3 starCol(float h){\n" +
            "  if(h<0.2) return vec3(0.55,0.78,1.00);\n" +
            "  if(h<0.4) return vec3(0.45,1.00,1.00);\n" +
            "  if(h<0.6) return vec3(1.00,1.00,1.00);\n" +
            "  if(h<0.8) return vec3(1.00,0.62,0.88);\n" +
            "  return      vec3(0.55,1.00,0.82);\n" +
            "}\n" +
            "float twinkleComp(){ return 1.0; }\n" +
            "float starTwinkle(vec2 cell,float lid,float mag){\n" +
            "  float seed=h1(cell+vec2(17.0+lid*13.0,31.0-lid*7.0));\n" +
            "  float rate=mix(0.20,0.50,h1(cell+5.3+lid*1.7));\n" +
            "  float ph=6.2831853*(seed+uTime*rate);\n" +
            "  float wave=sin(ph)*0.5+0.5;\n" +
            "  float comp=twinkleComp();\n" +
            "  float amt=mix(0.65,0.30,mag)*comp;\n" +
            "  return 1.0+amt*(wave-0.5);\n" +
            "}\n" +
            "vec3 starLayer(vec2 uv,float den,float ox,float oy,float ca,float sa,float lid){\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell);\n" +
            "  if(h<STAR_FLOOR) return vec3(0.0);\n" +
            "  float dn=vn(cell*SD_FREQ_LO+vec2(ox*0.1,oy*0.1))*SD_W_LO+vn(cell*SD_FREQ_HI+11.0)*SD_W_HI;\n" +
            "  float dens=smoothstep(SD_SS_LO,SD_SS_HI,dn);\n" +
            "  dens*=dens;\n" +
            "  float thresh=STAR_CEIL-dens*STAR_RANGE;\n" +
            "  if(h<thresh) return vec3(0.0);\n" +
            "  vec2 df=f-h2(cell+3.7);\n" +
            "  float d2=dot(df,df);\n" +
            "  float hm=h1(cell+7.7); float mag=0.18+0.82*hm*hm*hm;\n" +
            "  vec2 fc=cell-vec2(uStarFlare.x,uStarFlare.y); float isFlare=step(abs(lid-uStarFlare.w),0.5)*(1.0-step(0.25,dot(fc,fc)));\n" +
            "  float fl=isFlare*uStarFlare.z;\n" +
            "  float fl2=fl*fl;\n" +
            "  float bri=mag+fl2*8.0;\n" +
            "  float tw=starTwinkle(cell,lid,mag);\n" +
            "  float twDelta=tw-1.0;\n" +
            "  float soft=1.0+fl2*18.0;\n" +
            "  float coreSoft=soft/max(0.52,1.0+twDelta*0.85);\n" +
            "  float core=exp(-d2*2500.0/coreSoft)*bri*(1.0+twDelta*1.60);\n" +
            "  float eh=exp(-d2*100.0);\n" +
            "  float halo=eh*mag*0.15*(0.74+0.26*tw);\n" +
            "  if(fl2>0.0001) halo+=exp(-d2*40.0)*fl2*0.6;\n" +
            "  float spike=0.0;\n" +
            "  if(mag>SPIKE_THRESH||fl2>0.0001){\n" +
            "    vec2 sdf=vec2(ca*df.x+sa*df.y,-sa*df.x+ca*df.y);\n" +
            "    float spTight=32.0/((1.0+fl2*1.0)*max(0.45,1.0+twDelta*1.10));\n" +
            "    float spH=exp(-sdf.y*sdf.y*5000.0)*exp(-sdf.x*sdf.x*spTight);\n" +
            "    float spV=exp(-sdf.x*sdf.x*5000.0)*exp(-sdf.y*sdf.y*spTight);\n" +
            "    spike=(spH+spV)*bri*bri*(0.14+0.32*tw);\n" +
            "  }\n" +
            "  return starCol(h1(cell+9.1))*(core+halo+spike);\n" +
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
            "  vec3 sc=starCol(h1(cell+9.3));\n" +
            "  return sc*b;\n" +
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
            "  bg+=starLayer(s1,STAR_DEN*uStarScale,L0_OX,L0_OY,0.951,0.309,0.0)*f1;\n" +
            "  bg+=starLayer(s2,STAR_DEN*uStarScale,L1_OX,L1_OY,0.423,0.906,1.0)*f2;\n" +
            "  vec2 sdUv=(f1>=f2)?s1:s2;\n" +
            "  float sdOx=(f1>=f2)?L0_OX:L1_OX; float sdOy=(f1>=f2)?L0_OY:L1_OY;\n" +
            "  vec2 sdGp=sdUv*STAR_DEN*uStarScale+vec2(sdOx,sdOy);\n" +
            "  float sdField=vn(sdGp*SD_FREQ_LO+vec2(sdOx*0.1,sdOy*0.1))*SD_W_LO+vn(sdGp*SD_FREQ_HI+11.0)*SD_W_HI;\n" +
            "  float sdD=smoothstep(SD_SS_LO,SD_SS_HI,sdField); sdD*=sdD;\n" +
            "  vec2 scUv=sc+0.5;\n" +
            "  float spDn=vn(scUv*3.2+uSeed*2.0+vec2(5.7,3.1))*0.55+vn(scUv*8.5+uSeed*4.0+17.3)*0.45;\n" +
            "  float spDens=(SP_BASE+(1.0-SP_BASE)*smoothstep(SP_SS_LO,SP_SS_HI,spDn))*(1.0+sdD);\n" +
            "  bg+=sprinkleLayer(scUv,SP_DEN,0.19,0.43,spDens);\n" +
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
            "  col=mix(col,vec3(luma),smoothstep(2.4,5.0,pk)*0.40);\n" +
            "  vec3 base=col/(col+vec3(0.85));\n" +
            "  base=pow(max(base,vec3(0.0)),vec3(0.92))*1.12;\n" +
            "  vec3 starBase=starSig/(starSig+vec3(0.85));\n" +
            "  starBase=pow(max(starBase,vec3(0.0)),vec3(0.92))*1.12;\n" +
            "  float bk=step(1.0/255.0,max(max(base.r,base.g),base.b));\n" +
            "  base*=bk;\n" +
            "  if(uHdr>0.5){\n" +
            "    vec3 lin=pow(base,vec3(2.2));\n" +
            "    vec3 starLin=pow(max(starBase,vec3(0.0)),vec3(2.2));\n" +
            "    float lum=max(max(col.r,col.g),col.b);\n" +
            "    float starLum=max(max(starSig.r,starSig.g),starSig.b);\n" +
            "    float hi=max(lum-uHdrKnee,0.0);\n" +
            "    float boostMax=max(uHdrMax-1.0,1.0);\n" +
            "    float boost=boostMax*(1.0-exp(-(uHdrGain*hi)/boostMax));\n" +
            "    vec3 hdr=lin*(1.0+boost);\n" +
            "    float starHi=max(starLum-uHdrKnee*0.55,0.0);\n" +
            "    float starBoostMax=max(uHdrStarMax-1.0,1.0);\n" +
            "    float starBoost=starBoostMax*(1.0-exp(-(uHdrStarGain*starHi)/starBoostMax));\n" +
            "    float starMask=smoothstep(0.10,0.90,starLum);\n" +
            "    hdr+=starLin*starMask*starBoost;\n" +
            "    float hpk=max(max(hdr.r,hdr.g),hdr.b);\n" +
            "    float peakMax=mix(uHdrMax,uHdrStarMax,starMask);\n" +
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

        // Pass 1 (gas, low-res FBO): program + locations
        private int progGas, gAPos, gUTime, gURes, gUNoise, gUZoom, gUSeed, gUStarScale;
        // Pass 2 (composite, native res): program + locations
        private int progComp, cAPos, cUTime, cURes, cUZoom, cUHdr, cUHdrKnee, cUHdrGain, cUHdrMax,
            cUHdrStarGain, cUHdrStarMax, cUStarFlare, cUSeed, cUGas, cUStarScale;
        private int noiseTex;            // v4: 3D noise texture
        private int gasFbo, gasTex;      // v4: low-res gas render target
        private int gasW, gasH;
        private final java.util.Random sfRng = new java.util.Random();
        private final float seedX = (float)Math.random();
        private final float seedY = (float)Math.random();
        private int sfSlot;
        private float sfStart = -1f, sfDur, sfMag, sfEnv, sfNext = 3f;
        private int sfLayer;
        private float sfCellX, sfCellY;

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
        private FloatBuffer quadBuf;
        private long startMs;
        private long lastDrawMs;
        private long fpsT0; private int fpsN; private long workAccNs;
        private int workSamples;
        private static final int GL_TIME_ELAPSED_EXT = 0x88BF;
        private int[] timerQuery;
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

        NebulaRenderer(float zoomMul, int frameCapFps, HdrSurface hdr, float gasScale,
                       HdrTuning hdrTuning, DisplayDiagnostics display) {
            this.zoomMul = zoomMul;
            this.frameMs = (frameCapFps > 0) ? Math.round(1000.0 / frameCapFps) : 0L;
            this.hdr = hdr;
            this.gasScaleMax = gasScale;
            this.gasScaleActive = gasScale;
            this.hdrTuning = hdrTuning;
            this.display = display;
        }

        void setHdrTuning(HdrTuning t) { if (t != null) this.hdrTuning = t; }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
            GLES20.glClearColor(0f,0f,0f,1f);
            // On context recreation (e.g. resume), drop stale GL objects first.
            if (progGas!=0)  { GLES20.glDeleteProgram(progGas);  progGas=0; }
            if (progComp!=0) { GLES20.glDeleteProgram(progComp); progComp=0; }
            gasFbo=0; gasTex=0; // ids are stale on a new context; recreated in onSurfaceChanged
            lastDrawMs=0;

            progGas = buildProg(VERT_ES3, FRAG_GAS);
            gAPos   = GLES20.glGetAttribLocation(progGas,"aPos");
            gUTime  = GLES20.glGetUniformLocation(progGas,"uTime");
            gURes   = GLES20.glGetUniformLocation(progGas,"uRes");
            gUNoise = GLES20.glGetUniformLocation(progGas,"uNoise");
            gUZoom  = GLES20.glGetUniformLocation(progGas,"uZoom");
            gUSeed  = GLES20.glGetUniformLocation(progGas,"uSeed");
            gUStarScale = GLES20.glGetUniformLocation(progGas,"uStarScale");

            progComp = buildProg(VERT_ES3, FRAG_COMP);
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
            cUGas    = GLES20.glGetUniformLocation(progComp,"uGas");
            cUStarScale = GLES20.glGetUniformLocation(progComp,"uStarScale");

            noiseTex = buildNoiseTexture(64); // re-upload each context (ids go stale); CPU gen is cached
            String exts = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            hasTimerQuery = exts != null && exts.contains("GL_EXT_disjoint_timer_query");
            if (hasTimerQuery) {
                timerQuery = new int[1];
                GLES30.glGenQueries(1, timerQuery, 0);
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

            // Hold star pixel size constant relative to the reference width so a
            // narrow phone surface doesn't render tiny stars. >= reference -> 1.0.
            starScale = Math.max(STAR_SCALE_MIN, Math.min(1f, w / STAR_REF_WIDTH));
            Log.i(TAG, "star scale=" + String.format("%.3f", starScale) + " surfaceW=" + w);

            gasScaleActive = initialGasScale(w, h);
            recreateGasTarget();
            String limit = (display == null) ? null : display.surfaceLimitMessage(w, h);
            if (limit != null) Log.w(TAG, limit);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // Frame pacing: throttle the GL thread to the configured cap.
            if (frameMs > 0 && lastDrawMs != 0) {
                long sleep = frameMs - (SystemClock.elapsedRealtime() - lastDrawMs);
                if (sleep > 0) { try { Thread.sleep(sleep); } catch (InterruptedException ignored) {} }
            }
            lastDrawMs=SystemClock.elapsedRealtime();

            // Collect pending GPU timer query result (non-blocking).
            if (hasTimerQuery && timerQueryPending) {
                int[] avail = new int[1];
                GLES30.glGetQueryObjectuiv(timerQuery[0], GLES30.GL_QUERY_RESULT_AVAILABLE, avail, 0);
                if (avail[0] != 0) {
                    int[] gpuNs = new int[1];
                    GLES30.glGetQueryObjectuiv(timerQuery[0], GLES30.GL_QUERY_RESULT, gpuNs, 0);
                    workAccNs += gpuNs[0];
                    workSamples++;
                    timerQueryPending = false;
                }
            }

            // Log cadence plus sampled GPU work-time.
            fpsN++;
            if (fpsT0==0) fpsT0=lastDrawMs;
            else if (lastDrawMs-fpsT0>=2000) {
                float workMs = (workSamples > 0) ? workAccNs/(float)workSamples/1e6f : 0f;
                Log.i(TAG,"SPIKE cadence="+String.format("%.1f",fpsN*1000f/(lastDrawMs-fpsT0))
                    +"fps gpuWork="+String.format("%.1f",workMs)
                    +"ms surface="+screenW+"x"+screenH
                    +" gas="+gasW+"x"+gasH
                    +" gasScale="+String.format("%.2f",gasScaleActive)
                    +" hdr="+(hdr!=null&&hdr.hdrActive)
                    +" activeMode="+((display==null)?"unknown":display.activeMode())
                    +" requestedMode="+((display==null)?"none":display.requestedMode)
                    +" hdrCaps="+((display==null)?"unknown":display.hdrCaps)
                    +" hdrRatio="+((display==null)?"n/a":display.hdrRatioLabel()));
                if (SystemClock.elapsedRealtime() - startMs > 6000) adaptGasScale(workMs);
                fpsN=0; fpsT0=lastDrawMs; workAccNs=0; workSamples=0;
            }
            boolean sampleWork = hasTimerQuery && !timerQueryPending && (fpsN % 20) == 0;

            float t=(SystemClock.elapsedRealtime()-startMs)/1000f;

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
            quadBuf.position(0);
            GLES20.glVertexAttribPointer(gAPos,2,GLES20.GL_FLOAT,false,8,quadBuf);
            GLES20.glEnableVertexAttribArray(gAPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);

            // ── PASS 2: composite at native res (sharp stars/haze/flare) ─────
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
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,gasTex);
            GLES20.glUniform1i(cUGas,0);
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
                sfMag = (float)Math.sqrt(sfRng.nextFloat());
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
                float halfSpanX = (den / 2f) / zoom;
                float aspect = screenH > 0 ? (float)screenH / screenW : 0.5625f;
                float halfSpanY = halfSpanX * aspect;
                for (int attempt = 0; attempt < 200; attempt++) {
                    sfCellX = (float)Math.floor(cxCenter - halfSpanX + sfRng.nextFloat() * 2f * halfSpanX);
                    sfCellY = (float)Math.floor(cyCenter - halfSpanY + sfRng.nextFloat() * 2f * halfSpanY);
                    if (cpuHasStar(sfCellX, sfCellY, lox, loy)) break;
                }
                sfNext = t + sfDur + FLARE_GAP_MIN + sfRng.nextFloat() * FLARE_GAP_RNG;
                Log.i(TAG,"FLARE cell="+sfCellX+","+sfCellY+" layer="+sfLayer+" mag="+String.format("%.3f",sfMag));
            }
            sfEnv = 0f;
            if (sfStart >= 0f && t < sfStart + sfDur) {
                float p = (t - sfStart) / sfDur;
                float s = (float)Math.sin(Math.PI * p);
                sfEnv = s * s;
            }
        }

        private float initialGasScale(int w, int h) {
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
            } else if (workMs < frameMs * 0.58f && gasScaleActive < gasScaleMax - 0.005f) {
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
