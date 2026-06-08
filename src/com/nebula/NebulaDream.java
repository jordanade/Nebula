package com.nebula;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.Log;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class NebulaDream extends DreamService {

    static final String TAG = "NebulaDream";

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        Prefs prefs = Prefs.from(this);

        GLSurfaceView sv = new GLSurfaceView(this);
        sv.setEGLContextClientVersion(2);
        // HDR is opt-in and feature-detected; falls back to an 8-bit SDR
        // config when unsupported or disabled. The same object chooses the
        // config and creates the (optionally HDR-colorspace) window surface.
        HdrSurface hdr = new HdrSurface(prefs.hdrMode());
        sv.setEGLConfigChooser(hdr);
        sv.setEGLWindowSurfaceFactory(hdr);
        sv.setRenderer(new NebulaRenderer(
            prefs.zoomMul(), prefs.writheRate(), prefs.frameCapFps(), hdr));
        sv.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(sv);

        // The nebula is low-frequency, so we can render below panel resolution
        // and let the display scaler upscale — a big fragment-cost win for a
        // barely visible softening.
        float renderScale = prefs.renderScale();
        if (renderScale < 0.999f) {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int rw = Math.max(1, Math.round(dm.widthPixels * renderScale));
            int rh = Math.max(1, Math.round(dm.heightPixels * renderScale));
            sv.getHolder().setFixedSize(rw, rh);
        }
    }

    /**
     * Chooses the EGL config and creates the window surface, opting into an
     * HDR (FP16 + scRGB-linear extended-range) surface when the driver
     * advertises the required extensions and HDR isn't disabled. Otherwise it
     * transparently falls back to a standard 8-bit SDR surface, so the dream
     * never black-screens on hardware that can't do GPU HDR.
     *
     * scRGB-linear semantics: output is linear light where 1.0 == ~80 nits
     * (SDR white); values above 1.0 extend into HDR headroom.
     *
     * Note: "on" and "auto" behave identically here — we can't synthesise HDR
     * the driver doesn't expose, so both mean "HDR if the extensions exist".
     */
    static class HdrSurface
            implements GLSurfaceView.EGLConfigChooser, GLSurfaceView.EGLWindowSurfaceFactory {

        // EGL extension enums (not in EGL10).
        private static final int EGL_GL_COLORSPACE_KHR             = 0x309D;
        private static final int EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT = 0x3350;
        private static final int EGL_COLOR_COMPONENT_TYPE_EXT       = 0x3339;
        private static final int EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B;
        private static final int EGL_OPENGL_ES2_BIT = 0x0004;
        private static final int EGL_WINDOW_BIT     = 0x0004;

        private final String mode; // auto | on | off
        volatile boolean hdrActive;

        HdrSurface(String mode) { this.mode = mode; }

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            String exts = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS);
            boolean wantHdr = !Prefs.HDR_OFF.equals(mode);
            boolean canHdr = wantHdr && exts != null
                && exts.contains("EGL_EXT_pixel_format_float")
                && exts.contains("EGL_EXT_gl_colorspace_scrgb_linear");

            if (canHdr) {
                EGLConfig c = pick(egl, display, new int[] {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_COLOR_COMPONENT_TYPE_EXT, EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT,
                    EGL10.EGL_RED_SIZE, 16, EGL10.EGL_GREEN_SIZE, 16,
                    EGL10.EGL_BLUE_SIZE, 16, EGL10.EGL_ALPHA_SIZE, 16,
                    EGL10.EGL_NONE
                });
                if (c != null) {
                    hdrActive = true;
                    Log.i(TAG, "HDR surface selected (FP16 + scRGB-linear).");
                    return c;
                }
                Log.w(TAG, "HDR requested but no FP16 config; falling back to SDR.");
            }

            hdrActive = false;
            EGLConfig c = pick(egl, display, new int[] {
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_NONE
            });
            if (c == null) throw new RuntimeException("No suitable EGL config (SDR fallback failed).");
            return c;
        }

        private static EGLConfig pick(EGL10 egl, EGLDisplay display, int[] attribs) {
            int[] num = new int[1];
            if (!egl.eglChooseConfig(display, attribs, null, 0, num) || num[0] <= 0) return null;
            EGLConfig[] cfgs = new EGLConfig[num[0]];
            if (!egl.eglChooseConfig(display, attribs, cfgs, num[0], num)) return null;
            return cfgs[0];
        }

        @Override
        public EGLSurface createWindowSurface(EGL10 egl, EGLDisplay display,
                                              EGLConfig config, Object nativeWindow) {
            if (hdrActive) {
                try {
                    EGLSurface s = egl.eglCreateWindowSurface(display, config, nativeWindow,
                        new int[] { EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT,
                                    EGL10.EGL_NONE });
                    if (s != null && s != EGL10.EGL_NO_SURFACE) return s;
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "scRGB surface failed; using default colorspace.", e);
                }
                hdrActive = false; // colorspace didn't take — treat as SDR output
            }
            return egl.eglCreateWindowSurface(display, config, nativeWindow, null);
        }

        @Override
        public void destroySurface(EGL10 egl, EGLDisplay display, EGLSurface surface) {
            egl.eglDestroySurface(display, surface);
        }
    }

    static class NebulaRenderer implements GLSurfaceView.Renderer {

        private static final float[] QUAD = {
            -1f,-1f,  1f,-1f,  -1f,1f,
             1f,-1f,  1f, 1f,  -1f,1f
        };

        private static final String VERT =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUv;\n" +
            "void main(){\n" +
            "  vUv=aPos*0.5+0.5;\n" +
            "  gl_Position=vec4(aPos,0.0,1.0);\n" +
            "}\n";

        private static final String FRAG =
            "#extension GL_OES_standard_derivatives : enable\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "  precision highp float;\n" +
            "#else\n" +
            "  precision mediump float;\n" +
            "#endif\n" +
            "uniform float uTime;\n" +
            "uniform vec2  uRes;\n" +
            "uniform float uZoom;\n" +   // zoom-speed multiplier (1.0 = default; scales nebula+stars)
            "uniform float uWrithe;\n" + // writhe rate (slowT/uTime)
            "uniform float uHdr;\n" +     // 1.0 = HDR (scRGB-linear) output, 0.0 = SDR
            "uniform float uHdrKnee;\n" + // linear level above which highlights extend
            "uniform float uHdrGain;\n" + // initial slope of the highlight boost
            "uniform float uHdrMax;\n" +  // ceiling the boost saturates toward (headroom)
            "varying vec2  vUv;\n" +

            // ── Hash ──────────────────────────────────────────────────────────
            "vec2 h2(vec2 i){\n" +
            "  vec2 p=fract(i*vec2(0.1031,0.1030));\n" +
            "  p+=dot(p,p.yx+19.19);\n" +
            "  return fract((p.xx+p.yx)*p.xy);\n" +
            "}\n" +
            "float h1(vec2 i){\n" +
            "  vec2 p=fract(i*vec2(0.1031,0.1030));\n" +
            "  p+=dot(p,p+19.19);\n" +
            "  return fract(p.x*p.y);\n" +
            "}\n" +

            // ── Value noise ───────────────────────────────────────────────────
            "float vn(vec2 p){\n" +
            "  vec2 i=floor(p),f=fract(p);\n" +
            "  vec2 u=f*f*f*(f*(f*6.0-15.0)+10.0);\n" + // quintic: kills the square-grid artifacts that became straight relief ridges

            "  return mix(mix(h1(i),h1(i+vec2(1,0)),u.x),\n" +
            "             mix(h1(i+vec2(0,1)),h1(i+vec2(1,1)),u.x),u.y);\n" +
            "}\n" +

            // ── Smooth FBM — 6 octaves (quintic value noise underneath keeps the
            // grain from showing square-grid artifacts under strong relief). ──────
            "float sfbm(vec2 p){\n" +
            "  float v=0.0,a=0.55;\n" +
            "  mat2 rot=mat2(0.8,-0.6,0.6,0.8);\n" +
            "  for(int i=0;i<6;i++){\n" +
            "    v+=a*(vn(p)*2.0-1.0); p=rot*p*2.0; a*=0.52;\n" +
            "  }\n" +
            "  return v;\n" +
            "}\n" +
            // Cheap 4-octave SMOOTH fBm — filled rounded cloud masses + the shadow
            // field (kept; the swap to it for the WARP is what straightened things).
            "float sfbm4(vec2 p){\n" +
            "  float v=0.0,a=0.5;\n" +
            "  mat2 rot=mat2(0.8,-0.6,0.6,0.8);\n" +
            "  for(int i=0;i<4;i++){\n" +
            "    v+=a*(vn(p)*2.0-1.0); p=rot*p*2.0; a*=0.5;\n" +
            "  }\n" +
            "  return v;\n" +
            "}\n" +
            // ── Anti-aliased fBm. Each octave fades out as its frequency nears the
            // pixel Nyquist rate (fwidth), so we can stack many octaves of mottle
            // for rich 3D texture/form WITHOUT the high ones aliasing into shimmer
            // when fed through the relief gradient. ─────
            "float albm(vec2 p){\n" +
            "  float fw=1.4*(fwidth(p.x)+fwidth(p.y))+1e-5;\n" +
            "  float v=0.0,a=0.58,freq=1.0;\n" +
            "  mat2 rot=mat2(0.8,-0.6,0.6,0.8);\n" +
            "  for(int i=0;i<6;i++){\n" +
            "    float fade=clamp(1.0-fw*freq,0.0,1.0);\n" +
            "    v+=a*fade*(vn(p)*2.0-1.0); p=rot*p*2.0; a*=0.56; freq*=2.0;\n" +
            "  }\n" +
            "  return v;\n" +
            "}\n" +
            // ── Filament brightness from precomputed noise values ──────────────
            // n values passed in — computed ONCE in main, reused here and in color
            "float filamentVal(float n1,float n2,float n3,float n4,float n5){\n" +
            "  float f=exp(-abs(n1)*16.0)*0.55\n" +
            "         +exp(-abs(n2)*20.0)*0.40\n" +
            "         +exp(-abs(n3)*26.0)*0.25\n" +
            "         +exp(-abs(n4)*14.0)*0.35\n" +
            "         +exp(-abs(n5)*24.0)*0.20;\n" +
            "  return clamp(f,0.0,1.0);\n" +
            "}\n" +
            // ── Per-filament color from same precomputed values ────────────────
            "vec3 filamentCol(float n1,float n2,float n4){\n" +
            "  float w1=exp(-abs(n1)*16.0);\n" +
            "  float w2=exp(-abs(n2)*20.0);\n" +
            "  float w4=exp(-abs(n4)*14.0);\n" +
            "  float wt=max(w1+w2+w4,0.001);\n" +
            "  return vec3(0.55,0.05,0.90)*w1/wt\n" +
            "        +vec3(0.10,0.15,0.90)*w2/wt\n" +
            "        +vec3(0.80,0.05,0.55)*w4/wt;\n" +
            "}\n" +

            // ── Stars ─────────────────────────────────────────────────────────
            "vec3 starCol(float h){\n" +
            "  if(h<0.2) return vec3(0.55,0.78,1.00);\n" +
            "  if(h<0.4) return vec3(0.45,1.00,1.00);\n" +
            "  if(h<0.6) return vec3(1.00,1.00,1.00);\n" +
            "  if(h<0.8) return vec3(1.00,0.62,0.88);\n" +
            "  return      vec3(0.55,1.00,0.82);\n" +
            "}\n" +
            "vec3 starLayer(vec2 uv,float den,float ox,float oy){\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            // Large-scale density field, sharpened into clear galaxy-like bands
            // and voids. High threshold in voids = almost no stars there.
            // Two octaves for filamentary (less blobby) structure, sharpened
            // hard into bands vs near-empty voids for a strong galaxy look.
            "  float dn=vn(cell*0.02+vec2(ox*0.1,oy*0.1))*0.65+vn(cell*0.06+11.0)*0.35;\n" +
            "  float dens=smoothstep(0.44,0.64,dn);\n" +
            "  float thresh=0.972-dens*0.53;\n" + // lower void floor = a few more faint stars in the dark/empty areas
            // Galaxy haze: a soft warm glow coincident with the densest star bands,
            // so a rich cluster of stars reads as a distant galaxy. Uses a smooth
            // (continuous) version of the SAME density field as the stars — not the
            // per-cell one — for a soft fuzz, peaking only in the richest cores. It
            // is built from gp, so it zooms/streams with this exact star grid.
            // Three octaves for a shaped (not blobby) galaxy form, plus an internal
            // detail octave so the haze has visible structure/mottle — reads as a
            // resolved distant galaxy rather than a smooth smudge.
            "  float gdn=vn(gp*0.02+vec2(ox*0.1,oy*0.1))*0.55+vn(gp*0.055+11.0)*0.30+vn(gp*0.13+5.0)*0.15;\n" +
            "  float galaxy=smoothstep(0.50,0.82,gdn); galaxy*=galaxy;\n" +
            "  float gdet=0.5+0.5*vn(gp*0.20+vec2(3.0,7.0));\n" +
            "  gdet*=0.6+0.4*vn(gp*0.42+vec2(9.0,2.0));\n" +
            "  galaxy*=0.45+0.75*gdet;\n" + // internal definition/detail
            // Brighter, warmer toward the dense core of each galaxy patch.
            "  vec3 gcol=mix(vec3(0.52,0.42,0.50),vec3(0.82,0.62,0.52),smoothstep(0.62,0.96,gdn));\n" +
            "  vec3 res=gcol*galaxy*0.11;\n" +
            "  float h=h1(cell);\n" +
            "  if(h>thresh){\n" +
            "    vec2 df=f-h2(cell+3.7);\n" +
            "    float d=length(df);\n" +
            // Base magnitude skewed dim: most stars are faint, a few brighter.
            "    float mag=0.18+0.82*pow(h1(cell+7.7),3.0);\n" +
            // Rare, brief flare. Per-star rate + phase tied to uTime so DIFFERENT
            // stars flare over time (not always the same ones), and only a few
            // sparkle at any instant; flare^2 keeps it sharp and pushes the HDR knee.
            "    float canFlare=step(0.95,h1(cell+3.3));\n" + // only ~5% of stars ever flare
            // Noise-driven (aperiodic) timing so flares appear at irregular,
            // non-repeating moments rather than on a fixed cycle.
            "    float sid=h1(cell+1.9);\n" +
            "    float ft=uTime*(0.012+0.025*h1(cell+5.3))+sid*40.0;\n" +
            "    float flare=canFlare*smoothstep(0.88,1.0,vn(vec2(ft,sid*23.0)));\n" +
            "    float bri=mag+flare*flare*3.5;\n" +
            // Soften (widen) stars by how fast they stream (distance from centre)
            // so fast edge stars don't temporally alias — zoom without flicker.
            "    float rad=length(uv-0.5);\n" +
            "    float soft=1.0+rad*rad*16.0;\n" +
            "    float core=exp(-d*d*2500.0/soft)*bri;\n" +
            "    float halo=exp(-d*d*100.0)*mag*0.15;\n" +
            // Diffraction spikes (4-point cross), scaled by brightness so only
            // the bright/flaring stars show them — the astrophoto signature look.
            "    float spH=exp(-df.y*df.y*5000.0)*exp(-df.x*df.x*32.0);\n" +
            "    float spV=exp(-df.x*df.x*5000.0)*exp(-df.y*df.y*32.0);\n" +
            "    float spike=(spH+spV)*bri*bri*0.34;\n" +
            "    res+=starCol(h1(cell+9.1))*(core+halo+spike);\n" +
            "  }\n" +
            "  return res;\n" +
            "}\n" +

            // ── Cheap parallax nebula layer (a depth plane). Own seamless
            // crossfade; zoom rate `spd` sets its depth (slower = deeper).
            "vec3 nebLayer(vec2 q0,float spd,float scl,vec3 c1,vec3 c2,float bri){\n" +
            "  float tF=fract(uTime*spd);\n" +
            "  float zf=exp(tF*0.693);\n" +
            "  float cyc=floor(uTime*spd);\n" +
            "  vec2 oA=cyc*vec2(0.04,0.03), oB=(cyc+1.0)*vec2(0.04,0.03);\n" +
            "  vec2 pa=q0/zf*scl+oA, pb=q0/zf*scl*2.0+oB;\n" +
            "  float a1=sfbm(pa), a2=sfbm(pa*1.8+vec2(4.0,2.0));\n" +
            "  float b1=sfbm(pb), b2=sfbm(pb*1.8+vec2(4.0,2.0));\n" +
            // Soft, broad falloff (vs the main's sharp filaments) so these read
            // as distant diffuse haze, not copies of the foreground gas.
            "  float da=exp(-abs(a1)*7.0)*0.6+exp(-abs(a2)*11.0)*0.4;\n" +
            "  float db=exp(-abs(b1)*7.0)*0.6+exp(-abs(b2)*11.0)*0.4;\n" +
            "  float dd=pow(clamp(mix(da,db,tF),0.0,1.0),2.2);\n" + // high power keeps voids despite soft edges
            "  return mix(c1,c2,vn(pa*0.6))*dd*bri;\n" +
            "}\n" +

            "void main(){\n" +
            "  float aspect=uRes.x/uRes.y;\n" +
            "  vec2  uv=vUv;\n" +
            "  vec2  p0=(uv-0.5)*vec2(aspect,1.0);\n" +

            // slowT drives writhing
            "  float slowT=uTime*uWrithe;\n" +

            // ── SCALE-SPACE FRACTAL ZOOM — guaranteed no jump ─────────────────
            "  float zSpd=0.040*uZoom;\n" +
            "  float t=fract(uTime*zSpd);\n" +
            "  float S=0.50;\n" +
            "  float zoom=exp(t*0.693);\n" +
            "  vec2 pAs=p0/zoom*S;\n" +
            "  vec2 pBs=pAs*2.0;\n" +
            "  float ang1=slowT*0.32;\n" +
            "  float ang2=slowT*0.16;\n" +
            "  float ca1=cos(ang1),sa1=sin(ang1);\n" +
            "  float ca2=cos(ang2),sa2=sin(ang2);\n" +
            "  float cyc=floor(uTime*zSpd);\n" +
            "  vec2 offA=cyc*vec2(0.05,0.037);\n" +
            "  vec2 offB=(cyc+1.0)*vec2(0.05,0.037);\n" +
            "  vec2 rA=vec2(ca1*pAs.x-sa1*pAs.y,sa1*pAs.x+ca1*pAs.y);\n" +
            "  vec2 pA=vec2(ca2*rA.x-sa2*rA.y,sa2*rA.x+ca2*rA.y)+offA;\n" +
            "  vec2 rB=vec2(ca1*pBs.x-sa1*pBs.y,sa1*pBs.x+ca1*pBs.y);\n" +
            "  vec2 pB=vec2(ca2*rB.x-sa2*rB.y,sa2*rB.x+ca2*rB.y)+offB;\n" +
            // Writhing: curl-noise warp (divergence-free), speed scales with the
            // slider. Amount halved — a real nebula doesn't churn; this is now just
            // a faint structural warp, not a living-tissue writhe.
            "  float wt=uTime*uWrithe*4.0;\n" +
            "  float wa=uWrithe*2.0;\n" +
            "  float ce=0.05;\n" +
            "  vec2 qA=pA*2.0+vec2(wt*0.8,7.0);\n" +
            "  pA+=wa*vec2(vn(qA+vec2(0.0,ce))-vn(qA-vec2(0.0,ce)), vn(qA-vec2(ce,0.0))-vn(qA+vec2(ce,0.0)));\n" +
            "  vec2 qB=pB*2.0+vec2(wt*0.8,7.0);\n" +
            "  pB+=wa*vec2(vn(qB+vec2(0.0,ce))-vn(qB-vec2(0.0,ce)), vn(qB-vec2(ce,0.0))-vn(qB+vec2(ce,0.0)));\n" +
            // ── Static structural domain warp: bends straight filaments into
            // shapely, curled, billowing lobes. Position-based (NO uTime) so it is
            // pure SHAPE, not motion — adds form without re-introducing writhe. ──
            "  vec2 swA=vec2(sfbm(pA*0.55+vec2(1.7,4.0)),sfbm(pA*0.55+vec2(5.2,1.3)));\n" +
            "  pA+=0.64*swA;\n" +
            "  vec2 swB=vec2(sfbm(pB*0.55+vec2(1.7,4.0)),sfbm(pB*0.55+vec2(5.2,1.3)));\n" +
            "  pB+=0.64*swB;\n" +
            // Modest finer warp octave: gently curls locally-straight ridges without
            // over-shearing the dense gas into sheets (a strong warp made strata).
            "  pA+=0.16*vec2(sfbm(pA*1.7+vec2(3.0,8.0)),sfbm(pA*1.7+vec2(9.0,2.0)));\n" +
            "  pB+=0.16*vec2(sfbm(pB*1.7+vec2(3.0,8.0)),sfbm(pB*1.7+vec2(9.0,2.0)));\n" +
            "  float blend=t;\n" +

            "  float n1a=sfbm(pA),       n1b=sfbm(pB);\n" +
            "  float n2a=sfbm(pA*1.5+vec2(3.2,1.8)), n2b=sfbm(pB*1.5+vec2(3.2,1.8));\n" +
            "  float n3a=sfbm(pA*2.3+vec2(7.1,4.3)), n3b=sfbm(pB*2.3+vec2(7.1,4.3));\n" +
            "  float n4a=sfbm(pA*0.7+vec2(1.4,6.2)), n4b=sfbm(pB*0.7+vec2(1.4,6.2));\n" +
            "  float n5a=sfbm(pA*3.1+vec2(5.5,2.7)), n5b=sfbm(pB*3.1+vec2(5.5,2.7));\n" +
            "  float rawA=filamentVal(n1a,n2a,n3a,n4a,n5a);\n" +
            "  float rawB=filamentVal(n1b,n2b,n3b,n4b,n5b);\n" +
            "  float raw=mix(rawA,rawB,blend);\n" +
            "  vec3 fCol=mix(filamentCol(n1a,n2a,n4a),filamentCol(n1b,n2b,n4b),blend);\n" +
            "  vec2 p=mix(pA,pB,blend);\n" +

            // ── Soft, FILLED billowy cloud masses from SMOOTH fBm. Rounded bodies
            // with no cellular dark veins (which is what turbulence/billow gave). ─
            "  float cform=sfbm4(p*0.115+vec2(2.0,5.0));\n" + // lower freq = BIGGER, more voluminous masses
            "  float cloud=smoothstep(-0.28,0.56,cform);\n" + // wider range = fuller, more filled-in cloud bodies
            // ── Volumetric self-shadow: march toward the light through the SAME
            // smooth cloud field, accumulating optical depth. The lit side of each
            // mass stays bright while the far side dims — a soft directional
            // gradient ACROSS a filled body, the real volumetric-cloud cue. ──────
            "  vec2 lcs=p*0.115;\n" +                             // cloud-space coord (matches cform)
            "  vec2 lstp=normalize(vec2(0.55,0.45))*0.30;\n" +    // toward the light
            "  float od=smoothstep(-0.28,0.56,sfbm4(lcs+lstp))\n" +
            "          +smoothstep(-0.28,0.56,sfbm4(lcs+lstp*2.3))*0.7\n" +
            "          +smoothstep(-0.28,0.56,sfbm4(lcs+lstp*4.0))*0.45;\n" +
            "  float shadow=exp(-od*1.15);\n" +                   // gentle extinction → directional depth, body stays filled
            // ── Large-scale form: big bright lobes vs big voids, so the frame has
            // overall sculptural composition (like a real nebula's massing) instead
            // of uniform filament coverage everywhere. Low frequency = big shapes. ─
            "  float bigF=sfbm(p*0.28+vec2(11.0,6.0));\n" + // lower freq = bigger lobes
            "  float bigShape=smoothstep(-0.40,0.45,bigF);\n" +
            "  float dCore=pow(raw,1.4)*(0.42+0.62*bigShape);\n" + // lower floor = deeper, darker voids between masses
            // Macro density = smooth gas form + MULTI-SCALE band-limited mottle.
            // The relief below reads this, so the mottle becomes real 3D texture
            // and form (curds, billows, crevices) rather than flat emboss. albm
            // dissolves octaves before the pixel Nyquist, so this rich mottling
            // never shimmers/aliases the way raw high-freq detail did.
            "  float dMacro=pow(clamp(raw*0.42+cloud*1.45,0.0,1.0),0.78)*(0.46+0.58*bigShape);\n" + // fuller voluminous body; deeper voids carve sculptural gaps between masses
            "  float mott=albm(p*1.7+vec2(uTime*0.01,3.0))*0.5+0.5;\n" +  // 0..1 multi-scale form
            // Centered near the old mean (~0.92) so the mottle adds CONTRAST/texture,
            // not extra brightness — pushing density >1 here was clipping cores white.
            "  dMacro*=0.66+0.50*mott;\n" +                               // sculpted billows/curds
            // Dusty fine grain on DISPLAYED brightness only (kept OUT of the relief
            // gradient). albm self-band-limits so it adds surface texture without
            // amplifying into aliased edges.
            "  float grain=albm(p*9.5+vec2(3.0,1.0))*0.5+0.5;\n" +
            "  float d=dMacro*(0.84+0.26*grain);\n" +

            // ── Large-scale "lit front": a slow, very-low-frequency field giving
            // one drifting region that is illuminated (warmer + brighter), so the
            // frame has a directional composition (a glowing ridge) instead of
            // isotropic marbling — closer to a real nebula lit by nearby stars. ──
            "  float litFront=smoothstep(0.28,0.86,sfbm(p*0.12+vec2(20.0,3.0))*0.5+0.5);\n" + // lower freq = larger lit region

            // ── Spatial temperature: orange → pink/magenta → purple → blue. Biased
            // warm and range-widened so the signature pink and the orange/blue
            // extremes actually appear, not just purple. Lit front pulls warmer. ──
            "  float temp=vn(p*0.55+vec2(9.0,4.0)+vec2(uTime*0.0015,0.0));\n" +
            "  temp=clamp((temp-0.5)*1.5+0.40-litFront*0.18,0.0,1.0);\n" + // warm bias + wider spread
            "  vec3 warm=vec3(1.00,0.44,0.16);\n" +  // orange/red
            "  vec3 pink=vec3(1.00,0.30,0.58);\n" +  // magenta-pink (the reference's signature)
            "  vec3 midc=vec3(0.60,0.18,0.92);\n" +  // purple
            "  vec3 cool=vec3(0.32,0.52,1.00);\n" +  // blue
            "  vec3 tcol = (temp<0.33) ? mix(warm,pink,temp/0.33)\n" +
            "             : (temp<0.66) ? mix(pink,midc,(temp-0.33)/0.33)\n" +
            "                           : mix(midc,cool,(temp-0.66)/0.34);\n" +
            "  vec3 ncol=mix(tcol,fCol,0.3);\n" +
            "  vec3 col=ncol*d*d*0.5 + tcol*d*d*0.16;\n" +
            // Large-scale illumination: lift brightness in the lit front (modest, so
            // it composes a glowing ridge without the old harsh-white blowout).
            "  col*=1.0+0.45*litFront*d;\n" +

            "  col+=tcol*0.06*d*d;\n" +

            // ── Volumetric relief (3D-form lighting from the density gradient) ─
            // Gradient taken from the SMOOTH macro density so the lighting can't
            // amplify fine grain into aliased, shimmering edges.
            "  vec2 grad=vec2(dFdx(dMacro),dFdy(dMacro));\n" +
            "  vec3 nrm=normalize(vec3(-grad*120.0,1.0));\n" + // softened: high relief was amplifying the noise grain into straight strata
            "  float ndl=clamp(dot(nrm,normalize(vec3(0.55,0.45,0.62))),0.0,1.0);\n" +
            "  col*=0.36+1.05*ndl;\n" + // deeper light/dark swing for pronounced 3D relief
            // ── Volumetric body shading: ambient + transmittance through the cloud.
            // This is the big-scale lit-crest / shadowed-underside cue that makes the
            // gas read as a 3D volume. Lit crests can lift slightly above 1.0.
            "  col*=0.42+0.85*shadow;\n" + // high ambient floor: shadowed gas still EMITS (filled body), lit side brighter
            "  col*=mix(vec3(0.74,0.82,1.12),vec3(1.06,0.95,0.80),clamp(d*1.3,0.0,1.0));\n" +
            // Micro-occlusion (lightened — the volume shading now carries the depth):
            // just deepen the fine mottle valleys a touch.
            "  col*=0.76+0.24*mott;\n" +
            // Color mottle: chromatic texture tied to the form (cool/dim in the
            // valleys, warm/full on the crests) so richness comes from hue, not
            // just luminance embossing.
            "  col*=mix(vec3(0.82,0.80,0.98),vec3(1.12,1.02,0.90),mott);\n" +

            // ── Bright filament-core highlights ───────────────────────────────
            "  col+=mix(ncol,vec3(1.0),0.4)*pow(dCore,6.0)*0.3;\n" + // tighter, dimmer cores so bright filament LINES don't dominate the cloud body

            // ── Occasional bright HDR flashes at the brightest nebula cores ────
            // Like a hot young star igniting the surrounding gas: aperiodic (noise-
            // timed), gated to the densest cores so only the brightest knots light
            // up, with a diffraction cross-hatch through the flare. Pushed well
            // above 1.0 so the HDR knee extends it into real headroom.
            "  float fcore=smoothstep(0.62,0.92,d);\n" + // gate on cloud brightness (dCore rarely peaks now the body is cloud-dominated)
            "  vec2  fcell=floor(p*3.0);\n" +
            "  float fseed=h1(fcell);\n" +
            "  float fph=uTime*0.05+fseed*30.0;\n" +
            "  float nflash=smoothstep(0.86,1.0,vn(vec2(fph,fseed*17.0)))*fcore;\n" +
            "  vec2  fdelta=p-(fcell+0.5)/3.0;\n" +
            // Tight POINT glow + diffraction cross — a star-like glint, NOT a flat
            // cell-filling white blob (which read as a harsh white shape).
            "  float fglow=exp(-dot(fdelta,fdelta)*180.0);\n" +
            "  float chH=exp(-fdelta.y*fdelta.y*1300.0)*exp(-fdelta.x*fdelta.x*10.0);\n" +
            "  float chV=exp(-fdelta.x*fdelta.x*1300.0)*exp(-fdelta.y*fdelta.y*10.0);\n" +
            "  vec3  flashCol=mix(ncol,vec3(1.0),0.45);\n" + // keep the gas hue; don't punch to pure white
            "  col+=flashCol*nflash*(fglow*1.4+(chH+chV)*1.0);\n" +

            // ── Parallax background haze layer DISABLED: its soft style clashed
            // with the main relief nebula, and dropping it helps the frame rate.
            // (Function kept below; re-enable by uncommenting.) ──────────────────
            "  // col+=nebLayer(p0,0.022*uZoom,0.70,vec3(0.55,0.32,0.85),vec3(0.88,0.44,0.52),0.40);\n" +

            // ── Stars ─────────────────────────────────────────────────────────
            "  float SZSP=0.0090*uZoom;\n" + // star zoom speed (faster than nebula); still scales with uZoom
            "  float SZMAX=0.75;\n" + // zoom depth (more pronounced; softening tames the flicker)
            "  float ph=uTime*SZSP;\n" +
            "  float t1=fract(ph+0.000);\n" +
            "  float t2=fract(ph+0.333);\n" +
            "  float t3=fract(ph+0.667);\n" +
            "  float f1=smoothstep(0.00,0.40,t1)*(1.0-smoothstep(0.60,1.00,t1));\n" +
            "  float f2=smoothstep(0.00,0.40,t2)*(1.0-smoothstep(0.60,1.00,t2));\n" +
            "  float f3=smoothstep(0.00,0.40,t3)*(1.0-smoothstep(0.60,1.00,t3));\n" +
            // Rotate each layer's grid by a distinct angle so the three square
            // star lattices don't align and form radial moiré near the centre.
            "  vec2 sc=uv-0.5;\n" +
            "  vec2 sr1=vec2(sc.x*0.951-sc.y*0.309, sc.x*0.309+sc.y*0.951);\n" + // ~18°
            "  vec2 sr2=vec2(sc.x*0.423-sc.y*0.906, sc.x*0.906+sc.y*0.423);\n" + // ~65°
            "  vec2 sr3=vec2(-sc.x*0.602-sc.y*0.799, sc.x*0.799-sc.y*0.602);\n" + // ~127°
            "  vec2 s1=sr1/exp(t1*SZMAX)+0.5;\n" +
            "  vec2 s2=sr2/exp(t2*SZMAX)+0.5;\n" +
            "  vec2 s3=sr3/exp(t3*SZMAX)+0.5;\n" +
            "  col+=starLayer(s1,80.0,0.00,0.00)*f1;\n" +
            "  col+=starLayer(s2,80.0,0.37,0.21)*f2*0.85;\n" +
            "  col+=starLayer(s3,80.0,0.71,0.53)*f3*0.70;\n" +

            // ── Slow hue drift ────────────────────────────────────────────────
            // ~30 min period, ±10% per-channel out of phase by 120°, so an
            // overnight run gently wanders rather than holding one purple.
            "  float drift=uTime*0.0035;\n" +
            "  col*=vec3(1.0)+0.10*vec3(sin(drift),sin(drift+2.0944),sin(drift+4.1888));\n" +

            // ── Fade in (linear, before output encoding) ──────────────────────
            "  col*=smoothstep(0.0,10.0,uTime);\n" +

            // Desaturate bright regions toward their luma so clipping highlights
            // (esp. overlapping filaments) roll toward white instead of harsh,
            // fully-saturated magenta. Only affects the brightest areas.
            "  float pk=max(max(col.r,col.g),col.b);\n" +
            "  float luma=dot(col,vec3(0.30,0.40,0.30));\n" +
            "  col=mix(col,vec3(luma),smoothstep(0.95,2.2,pk)*0.6);\n" + // only true clipping rolls to white; keep hue in bright mids

            // Tonemapped base — this is the SDR look (1.0 == ~80 nits in HDR).
            // Higher Reinhard knee deepens the blacks across both modes.
            "  vec3 base=col/(col+vec3(0.85));\n" +
            "  base=pow(max(base,vec3(0.0)),vec3(0.92))*1.12;\n" +

            "  if(uHdr>0.5){\n" +
            // ── HDR: scRGB-linear surface needs LINEAR light. ─────────────────
            // base is display-referred (sRGB-like), so decode it to linear —
            // this crushes the dim glow just as an SDR panel's EOTF would,
            // instead of sending gamma-encoded values straight to a linear
            // surface (which over-brightened the darks). Then extend ONLY true
            // cores: the linear signal above the knee, tinted by base to keep hue.
            "    vec3 lin=pow(max(base,0.0),vec3(2.2));\n" +
            "    float lum=max(max(col.r,col.g),col.b);\n" +
            "    float hi=max(lum-uHdrKnee,0.0);\n" +
            // Soft-saturating boost: ~uHdrGain*hi for small hi, smoothly capped
            // at uHdrMax so overlapping filaments roll off to the panel peak
            "    float boost=uHdrGain*hi/(1.0+(uHdrGain/uHdrMax)*hi);\n" +
            "    gl_FragColor=vec4(lin+lin*boost,1.0);\n" +
            "  } else {\n" +
            // ── SDR: 8-bit output + dither to break up banding. ──────────────
            "    base+=(h1(gl_FragCoord.xy)-0.5)/255.0;\n" +
            "    gl_FragColor=vec4(clamp(base,0.0,1.0),1.0);\n" +
            "  }\n" +
            "}\n";

        private int prog, aPos, uTime, uRes, uZoom, uWrithe, uHdr, uHdrKnee, uHdrGain, uHdrMax;
        private FloatBuffer quadBuf;
        private long startMs;
        private long lastDrawMs;
        private int screenW, screenH;

        private final float zoomMul;     // zoom-speed multiplier (1.0 = default)
        private final float writheRate;  // slowT rate
        // Minimum ms between frames; 0 = uncapped. A frame cap roughly halves
        // GPU load since the motion is slow enough that ~30fps is invisible.
        private final long frameMs;
        private final HdrSurface hdr;

        // HDR highlight tuning. Only the linear signal above HDR_KNEE extends
        // into the headroom (so glow/halos keep their SDR look). HDR_GAIN is the
        // initial slope; the boost soft-saturates toward HDR_MAX so overlapping
        // filaments roll off to the panel peak instead of hard-clipping harshly.
        private static final float HDR_KNEE = 1.0f;
        private static final float HDR_GAIN = 30.0f;
        private static final float HDR_MAX  = 30.0f; // high ceiling: cores drive to the panel peak

        NebulaRenderer(float zoomMul, float writheRate, int frameCapFps, HdrSurface hdr) {
            this.zoomMul = zoomMul;
            this.writheRate = writheRate;
            this.frameMs = (frameCapFps > 0) ? Math.round(1000.0 / frameCapFps) : 0L;
            this.hdr = hdr;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
            GLES20.glClearColor(0f,0f,0f,1f);
            // On context recreation (e.g. resume), drop the stale program first.
            if (prog!=0) { GLES20.glDeleteProgram(prog); prog=0; }
            lastDrawMs=0;
            prog  = buildProg(VERT, FRAG);
            aPos  = GLES20.glGetAttribLocation(prog,"aPos");
            uTime = GLES20.glGetUniformLocation(prog,"uTime");
            uRes  = GLES20.glGetUniformLocation(prog,"uRes");
            uZoom = GLES20.glGetUniformLocation(prog,"uZoom");
            uWrithe = GLES20.glGetUniformLocation(prog,"uWrithe");
            uHdr = GLES20.glGetUniformLocation(prog,"uHdr");
            uHdrKnee = GLES20.glGetUniformLocation(prog,"uHdrKnee");
            uHdrGain = GLES20.glGetUniformLocation(prog,"uHdrGain");
            uHdrMax = GLES20.glGetUniformLocation(prog,"uHdrMax");
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
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // Frame pacing: throttle the GL thread to the configured cap.
            if (frameMs > 0 && lastDrawMs != 0) {
                long sleep = frameMs - (SystemClock.elapsedRealtime() - lastDrawMs);
                if (sleep > 0) { try { Thread.sleep(sleep); } catch (InterruptedException ignored) {} }
            }
            lastDrawMs=SystemClock.elapsedRealtime();

            float t=(SystemClock.elapsedRealtime()-startMs)/1000f;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(prog);
            GLES20.glUniform1f(uTime,t);
            GLES20.glUniform2f(uRes,(float)screenW,(float)screenH);
            GLES20.glUniform1f(uZoom,zoomMul);
            GLES20.glUniform1f(uWrithe,writheRate);
            GLES20.glUniform1f(uHdr,(hdr!=null && hdr.hdrActive)?1f:0f);
            GLES20.glUniform1f(uHdrKnee,HDR_KNEE);
            GLES20.glUniform1f(uHdrGain,HDR_GAIN);
            GLES20.glUniform1f(uHdrMax,HDR_MAX);
            quadBuf.position(0);
            GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,8,quadBuf);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);
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
