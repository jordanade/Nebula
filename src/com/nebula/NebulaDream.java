package com.nebula;

import android.opengl.GLES20;
import android.opengl.GLES30;
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
        sv.setEGLContextClientVersion(3); // v4: GLES 3.0 for sampler3D + glTexImage3D
        // HDR is opt-in and feature-detected; falls back to an 8-bit SDR
        // config when unsupported or disabled. The same object chooses the
        // config and creates the (optionally HDR-colorspace) window surface.
        HdrSurface hdr = new HdrSurface(prefs.hdrMode());
        sv.setEGLConfigChooser(hdr);
        sv.setEGLWindowSurfaceFactory(hdr);
        // v4 split-resolution: the WINDOW stays at native panel resolution so the
        // composite pass draws pin-sharp stars/spikes; the render-scale pref now
        // governs only the low-res gas FBO inside the renderer.
        sv.setRenderer(new NebulaRenderer(
            prefs.zoomMul(), prefs.writheRate(), prefs.frameCapFps(), hdr,
            prefs.renderScale()));
        sv.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(sv);
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
        private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040; // v4: request an ES3-capable config
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
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
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
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
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
            "precision highp float;\n" +
            "precision highp sampler3D;\n" +
            "uniform float uTime;\n" +
            "uniform vec2  uRes;\n" +       // gas FBO resolution (same aspect as the panel)
            "uniform float uZoom;\n" +      // star zoom speed (haze rides the star grid)
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
            "  float gdn=vn(gp*0.02+vec2(ox*0.1,oy*0.1))*0.55+vn(gp*0.055+11.0)*0.30+vn(gp*0.13+5.0)*0.15;\n" +
            "  float galaxy=smoothstep(0.50,0.82,gdn); galaxy*=galaxy;\n" +
            "  float gdet=0.5+0.5*vn(gp*0.20+vec2(3.0,7.0));\n" +
            "  gdet*=0.6+0.4*vn(gp*0.42+vec2(9.0,2.0));\n" +
            "  float grain=vn(gp*0.55+vec2(5.0,1.0))*0.55+vn(gp*1.20+vec2(2.0,8.0))*0.30+vn(gp*2.60+vec2(7.0,3.0))*0.15;\n" +
            "  grain=smoothstep(0.46,0.78,grain); grain*=grain;\n" +
            "  galaxy*=0.30+0.55*gdet+0.85*grain;\n" +
            "  vec3 gcol=mix(vec3(0.52,0.42,0.50),vec3(0.82,0.62,0.52),smoothstep(0.62,0.96,gdn));\n" +
            "  return gcol*galaxy*0.11;\n" +
            "}\n" +
            "float rm(float v,float l,float h){ return clamp((v-l)/(h-l),0.0,1.0); }\n" +
                        // ── Phase 3: density from the precomputed 3D noise TEXTURE (uniform, no
            // analytic noise / branching) — R=value fbm, G=inverted Worley billow. ──
            "float dens(vec3 p){\n" +
            "  float base=texture(uNoise,p*0.075).r;\n" +             // larger billow base
            // Coverage: low-frequency gate makes fewer, larger cloud masses while
            // still rejecting the weakest saddles between them.
            "  float cov=smoothstep(0.39,0.68,texture(uNoise,p*0.027+0.31).r);\n" +
            "  float d=rm(base,1.0-cov,1.0)*cov;\n" +
            "  float ero=texture(uNoise,p*0.22).g;\n" +              // broad Worley erosion
            "  d=rm(d,ero*0.24,1.0);\n" +
            "  float ero2=texture(uNoise,p*0.58).g;\n" +             // restrained fine erosion; avoids clumpy breakup
            "  d=rm(d,ero2*0.26,1.0);\n" +
            "  return pow(d,2.45);\n" +                              // fuller interiors for larger readable masses
            "}\n" +
            // Mid-distance density (3 fetches): keeps the coarse Worley erosion
            // for readable cloud edges but drops the fine ero2 term (p*0.58) whose
            // sub-pixel detail aliases into shimmer at this range.
            "float densMid(vec3 p){\n" +
            "  float base=texture(uNoise,p*0.075).r;\n" +
            "  float cov=smoothstep(0.39,0.68,texture(uNoise,p*0.027+0.31).r);\n" +
            "  float d=rm(base,1.0-cov,1.0)*cov;\n" +
            "  float ero=texture(uNoise,p*0.22).g;\n" +
            "  d=rm(d,ero*0.24,1.0);\n" +
            "  d=rm(d,0.20,1.0);\n" +                                // approximate the fine erosion's average
            "  return pow(d,2.45)*0.95;\n" +
            "}\n" +
            // Cheap far-field density (2 low-freq fetches, no erosion detail): used
            // beyond the detail horizon where cauliflower edges are sub-pixel anyway.
            // Low-freq coords are also cache-friendly — big strides were thrashing
            // the texture cache with the full 4-fetch dens().
            "float densFar(vec3 p){\n" +
            "  float base=texture(uNoise,p*0.075).r;\n" +
            "  float cov=smoothstep(0.39,0.68,texture(uNoise,p*0.027+0.31).r);\n" +
            "  float d=rm(base,1.0-cov,1.0)*cov;\n" +
            "  d=rm(d,0.18,1.0);\n" +                                // approximate the erosion's average bite
            "  return pow(d,2.45)*0.9;\n" +
            "}\n" +
            // ── Phase 2: HG phase (silver lining) + powder + violet ambient + palette ─
            "float hg(float c,float g){ float g2=g*g; return (1.0-g2)/pow(max(1.0+g2-2.0*g*c,1e-3),1.5); }\n" +
            "void main(){\n" +
            "  vec2 uv=vUv*2.0-1.0; uv.x*=uRes.x/uRes.y;\n" +
            "  vec3 ro=vec3(sin(uTime*0.05)*0.7,cos(uTime*0.037)*0.5,uTime*0.40);\n" + // fly forward + gentle drift (off-axis)
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
            "  float t=fract(sin(dot(gl_FragCoord.xy,vec2(41.3,289.1))+uTime)*43758.5)*0.08;\n" + // jittered start (halved — 0.16 was >1 step at close range, scattering adjacent pixels across different density slices)
            "  float T=1.0; vec3 col=vec3(0.0);\n" +
            // ── NEBULA shading: highly-transparent EMISSIVE gas. No light march,
            // no phase — the gas GLOWS (emission nebula), it is not sunlit cloud.
            // Very low extinction: rays cross whole masses; stars shine through.
            // 56 steps + faster growth + bigger empty strides: spends the banked
            // perf headroom on REACH (~3x the old effective depth).
            "  float dPrev=0.0;\n" +                                 // previous sample's density (for boundary rims)
            "  for(int i=0;i<46;i++){\n" +
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
            // Dither breaks far-field 8-bit texture banding; near gas is sampled
            // densely enough that banding is invisible, so kill the dither there
            // (it accumulated into visible static when the nebula filled the screen).
            "    float dith=mix(0.010,0.0,nearAmt);\n" +
            "    d=max(d+(fract(sin(dot(p.xy+vec2(p.z),vec2(12.9898,78.233)))*43758.55)-0.5)*dith,0.0);\n" +
            "    if(d>0.01){\n" +
            "      float dt=0.11*g;\n" +
            "      vec3 emit=tcol*d*0.34+ambCol*d*0.18;\n" +        // dimmer interiors; rims carry more of the shape
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
            "      }\n" +
            // Distance falloff: deep gas contributes progressively less, so the
            // mid/rear stack reads as faint depth, not an accumulated bright wall.
            "      emit*=1.0/(1.0+t*0.055);\n" +
            "      col+=T*emit*dt;\n" +
            "      T*=exp(-d*dt*0.08);\n" +                         // ultra-transparent: stars through everything; also pins cost (no early-outs => constant frame time)
            "      t+=dt;\n" +
            "    } else { t+=0.28*g; }\n" +
            "    dPrev=d;\n" +
            "    if(t>48.0) break;\n" +                              // deep march range
            "  }\n" +
            "  col*=0.95;\n" + // gain for the tonemap (slightly dimmer overall)

            // Galaxy haze + deep-space floor BEHIND the gas (same three-phase star
            // zoom as the comp pass, so haze and stars move as one entity).
            "  vec3 hz=vec3(0.0);\n" +
            "  float hzSP=0.0120*uZoom;\n" +
            "  float hzPh=uTime*hzSP;\n" +
            "  float t1=fract(hzPh+0.000);\n" +
            "  float t2=fract(hzPh+0.333);\n" +
            "  float t3=fract(hzPh+0.667);\n" +
            "  float f1=smoothstep(0.00,0.40,t1)*(1.0-smoothstep(0.60,1.00,t1));\n" +
            "  float f2=smoothstep(0.00,0.40,t2)*(1.0-smoothstep(0.60,1.00,t2));\n" +
            "  float f3=smoothstep(0.00,0.40,t3)*(1.0-smoothstep(0.60,1.00,t3));\n" +
            "  vec2 sc=vUv-0.5;\n" +
            "  vec2 sr1=vec2(sc.x*0.951-sc.y*0.309, sc.x*0.309+sc.y*0.951);\n" +
            "  vec2 sr2=vec2(sc.x*0.423-sc.y*0.906, sc.x*0.906+sc.y*0.423);\n" +
            "  vec2 sr3=vec2(-sc.x*0.602-sc.y*0.799, sc.x*0.799-sc.y*0.602);\n" +
            "  hz+=hazeLayer(sr1/exp(t1*0.75)+0.5,80.0,0.00,0.00)*f1;\n" +
            "  hz+=hazeLayer(sr2/exp(t2*0.75)+0.5,80.0,0.37,0.21)*f2*0.85;\n" +
            "  hz+=hazeLayer(sr3/exp(t3*0.75)+0.5,80.0,0.71,0.53)*f3*0.70;\n" +
            "  hz+=vec3(0.012,0.012,0.040);\n" +
            "  vec3 pFar=ro+rd*55.0;\n" +
            "  float farBase=texture(uNoise,pFar*0.075).r;\n" +
            "  float farCov=smoothstep(0.39,0.68,texture(uNoise,pFar*0.027+0.31).r);\n" +
            "  float farD=rm(farBase,1.0-farCov,1.0)*farCov;\n" +
            "  float farEro=texture(uNoise,pFar*0.22).g;\n" +
            "  farD=rm(farD,farEro*0.24,1.0);\n" +
            "  farD=pow(farD,2.0);\n" +
            "  vec3 pFar2=ro+rd*72.0;\n" +
            "  float farBase2=texture(uNoise,pFar2*0.075).r;\n" +
            "  float farCov2=smoothstep(0.39,0.68,texture(uNoise,pFar2*0.027+0.31).r);\n" +
            "  float farD2=rm(farBase2,1.0-farCov2,1.0)*farCov2;\n" +
            "  float farEro2=texture(uNoise,pFar2*0.22).g;\n" +
            "  farD2=rm(farD2,farEro2*0.24,1.0);\n" +
            "  farD2=pow(farD2,2.0);\n" +
            "  vec3 pFar3=ro+rd*95.0;\n" +
            "  float farBase3=texture(uNoise,pFar3*0.075).r;\n" +
            "  float farCov3=smoothstep(0.39,0.68,texture(uNoise,pFar3*0.027+0.31).r);\n" +
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
            "precision highp float;\n" +
            "uniform float uTime;\n" +
            "uniform vec2  uRes;\n" +
            "uniform float uZoom;\n" +
            "uniform float uHdr;\n" +
            "uniform float uHdrKnee;\n" +
            "uniform float uHdrGain;\n" +
            "uniform float uHdrMax;\n" +
            "uniform vec4 uStarFlare;\n" +
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
            "vec3 starLayer(vec2 uv,float den,float ox,float oy,float ca,float sa,float lid){\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float dn=vn(cell*0.02+vec2(ox*0.1,oy*0.1))*0.65+vn(cell*0.06+11.0)*0.35;\n" +
            "  float dens=smoothstep(0.44,0.64,dn);\n" +
            "  float thresh=0.972-dens*0.53;\n" +
            "  vec3 res=vec3(0.0);\n" +
            "  float h=h1(cell);\n" +
            "  if(h>thresh){\n" +
            "    vec2 df=f-h2(cell+3.7);\n" +
            "    float d=length(df);\n" +
            "    float mag=0.18+0.82*pow(h1(cell+7.7),3.0);\n" +
            "    float isFlare=step(abs(lid-uStarFlare.w),0.5)*(1.0-step(0.5,length(cell-vec2(uStarFlare.x,uStarFlare.y))));\n" +
            "    float fl=isFlare*uStarFlare.z;\n" +
            "    float fl2=fl*fl;\n" +
            "    float bri=mag+fl2*8.0;\n" +
            "    float rad=length(uv-0.5);\n" +
            "    float soft=1.0+rad*rad*16.0+fl2*18.0;\n" +
            "    float core=exp(-d*d*2500.0/soft)*bri;\n" +
            "    float halo=exp(-d*d*100.0)*mag*0.15+exp(-d*d*40.0)*fl2*0.6;\n" +
            "    vec2 sdf=vec2(ca*df.x+sa*df.y,-sa*df.x+ca*df.y);\n" +
            "    float spTight=32.0/(1.0+fl2*1.0);\n" +
            "    float spH=exp(-sdf.y*sdf.y*5000.0)*exp(-sdf.x*sdf.x*spTight);\n" +
            "    float spV=exp(-sdf.x*sdf.x*5000.0)*exp(-sdf.y*sdf.y*spTight);\n" +
            "    float spike=(spH+spV)*bri*bri*0.34;\n" +
            "    res+=starCol(h1(cell+9.1))*(core+halo+spike);\n" +
            "  }\n" +
            "  return res;\n" +
            "}\n" +
            "void main(){\n" +
            "  vec4 gas=texture(uGas,vUv);\n" +
            "  vec3 col=gas.rgb;\n" +
            "  float T=gas.a;\n" +
            // ── Stars + galaxy haze BEHIND the clouds (v3.1 three-phase zooming
            // star system), weighted by the ray's remaining transmittance T so
            // dense masses occlude them and they shine through voids/thin gas. ──
            "  vec3 bg=vec3(0.0);\n" +
            "  float SZSP=0.0120*uZoom;\n" +
            "  float SZMAX=0.75;\n" +
            "  float ph=uTime*SZSP;\n" +
            "  float t1=fract(ph+0.000);\n" +
            "  float t2=fract(ph+0.333);\n" +
            "  float t3=fract(ph+0.667);\n" +
            "  float f1=smoothstep(0.00,0.40,t1)*(1.0-smoothstep(0.60,1.00,t1));\n" +
            "  float f2=smoothstep(0.00,0.40,t2)*(1.0-smoothstep(0.60,1.00,t2));\n" +
            "  float f3=smoothstep(0.00,0.40,t3)*(1.0-smoothstep(0.60,1.00,t3));\n" +
            "  vec2 sc=vUv-0.5;\n" +
            "  vec2 sr1=vec2(sc.x*0.951-sc.y*0.309, sc.x*0.309+sc.y*0.951);\n" +
            "  vec2 sr2=vec2(sc.x*0.423-sc.y*0.906, sc.x*0.906+sc.y*0.423);\n" +
            "  vec2 sr3=vec2(-sc.x*0.602-sc.y*0.799, sc.x*0.799-sc.y*0.602);\n" +
            "  vec2 s1=sr1/exp(t1*SZMAX)+0.5;\n" +
            "  vec2 s2=sr2/exp(t2*SZMAX)+0.5;\n" +
            "  vec2 s3=sr3/exp(t3*SZMAX)+0.5;\n" +
            "  bg+=starLayer(s1,80.0,0.00,0.00,0.951,0.309,0.0)*f1;\n" +
            "  bg+=starLayer(s2,80.0,0.37,0.21,0.423,0.906,1.0)*f2*0.85;\n" +
            "  bg+=starLayer(s3,80.0,0.71,0.53,-0.602,0.799,2.0)*f3*0.70;\n" +
            // (deep-space floor moved to the gas pass with the haze)
            "  col+=T*bg;\n" +

            // ── v3.1 output chain: hue drift, fade-in, desat rolloff, tonemap, HDR ─
            "  float drift=uTime*0.0035;\n" +
            "  col*=vec3(1.0)+0.10*vec3(sin(drift),sin(drift+2.0944),sin(drift+4.1888));\n" +
            "  col*=smoothstep(0.0,10.0,uTime);\n" +
            "  float pk=max(max(col.r,col.g),col.b);\n" +
            "  float luma=dot(col,vec3(0.30,0.40,0.30));\n" +
            "  col=mix(col,vec3(luma),smoothstep(1.6,3.4,pk)*0.45);\n" + // keep hue much longer — dense gas columns were rolling to white
            "  vec3 base=col/(col+vec3(0.85));\n" +
            "  base=pow(max(base,vec3(0.0)),vec3(0.92))*1.12;\n" +
            "  if(uHdr>0.5){\n" +
            "    vec3 lin=pow(max(base,vec3(0.0)),vec3(2.2));\n" +
            "    float lum=max(max(col.r,col.g),col.b);\n" +
            "    float hi=max(lum-uHdrKnee,0.0);\n" +
            "    float boost=uHdrGain*hi/(1.0+(uHdrGain/uHdrMax)*hi);\n" +
            "    fragColor=vec4(lin+lin*boost,1.0);\n" +
            "  } else {\n" +
            "    base+=(h1(gl_FragCoord.xy)-0.5)/255.0;\n" +
            "    fragColor=vec4(clamp(base,0.0,1.0),1.0);\n" +
            "  }\n" +
            "}\n";

        // Pass 1 (gas, low-res FBO): program + locations
        private int progGas, gAPos, gUTime, gURes, gUNoise, gUZoom;
        // Pass 2 (composite, native res): program + locations
        private int progComp, cAPos, cUTime, cURes, cUZoom, cUHdr, cUHdrKnee, cUHdrGain, cUHdrMax, cUStarFlare, cUGas;
        private int noiseTex;            // v4: 3D noise texture
        private int gasFbo, gasTex;      // v4: low-res gas render target
        private int gasW, gasH;
        private final java.util.Random sfRng = new java.util.Random();
        private int sfSlot;
        private float sfStart = -1f, sfDur, sfMag, sfNext = 3f;
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
            float dn = cpuVn(cellX*0.02f+ox*0.1f, cellY*0.02f+oy*0.1f)*0.65f
                      + cpuVn(cellX*0.06f+11f, cellY*0.06f+11f)*0.35f;
            float dens = Math.max(0f, Math.min(1f, (dn-0.44f)/(0.64f-0.44f)));
            dens = dens*dens*(3f-2f*dens);
            float thresh = 0.972f - dens*0.53f;
            float h = cpuH1(cellX, cellY);
            return h > thresh;
        }
        private FloatBuffer quadBuf;
        private long startMs;
        private long lastDrawMs;
        private long fpsT0; private int fpsN; private long workAccNs; // PHASE 0 instrumentation
        private int screenW, screenH;

        private final float zoomMul;     // zoom-speed multiplier (1.0 = default)
        private final float writheRate;  // slowT rate
        private final float gasScale;    // gas FBO scale relative to the native window
        // Minimum ms between frames; 0 = uncapped. A frame cap roughly halves
        // GPU load since the motion is slow enough that ~30fps is invisible.
        private final long frameMs;
        private final HdrSurface hdr;

        // HDR highlight tuning. Only the linear signal above HDR_KNEE extends
        // into the headroom (so glow/halos keep their SDR look). HDR_GAIN is the
        // initial slope; the boost soft-saturates toward HDR_MAX so overlapping
        // filaments roll off to the panel peak instead of hard-clipping harshly.
        private static final float HDR_KNEE = 2.4f;  // v4: above gas-column peaks — only star flares/cores extend into headroom
        private static final float HDR_GAIN = 30.0f;
        private static final float HDR_MAX  = 30.0f; // high ceiling: cores drive to the panel peak

        NebulaRenderer(float zoomMul, float writheRate, int frameCapFps, HdrSurface hdr, float gasScale) {
            this.zoomMul = zoomMul;
            this.writheRate = writheRate;
            this.frameMs = (frameCapFps > 0) ? Math.round(1000.0 / frameCapFps) : 0L;
            this.hdr = hdr;
            this.gasScale = gasScale;
        }

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

            progComp = buildProg(VERT_ES3, FRAG_COMP);
            cAPos    = GLES20.glGetAttribLocation(progComp,"aPos");
            cUTime   = GLES20.glGetUniformLocation(progComp,"uTime");
            cURes    = GLES20.glGetUniformLocation(progComp,"uRes");
            cUZoom   = GLES20.glGetUniformLocation(progComp,"uZoom");
            cUHdr    = GLES20.glGetUniformLocation(progComp,"uHdr");
            cUHdrKnee= GLES20.glGetUniformLocation(progComp,"uHdrKnee");
            cUHdrGain= GLES20.glGetUniformLocation(progComp,"uHdrGain");
            cUHdrMax = GLES20.glGetUniformLocation(progComp,"uHdrMax");
            cUStarFlare = GLES20.glGetUniformLocation(progComp,"uStarFlare");
            cUGas    = GLES20.glGetUniformLocation(progComp,"uGas");

            noiseTex = buildNoiseTexture(64); // re-upload each context (ids go stale); CPU gen is cached
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

            // (Re)create the low-res gas FBO. RGBA16F (needs EXT_color_buffer_float,
            // present on the Tegra X1); falls back to RGBA8 if incomplete — gas
            // highlights then clamp at 1.0, acceptable degradation.
            if (gasFbo!=0) { GLES20.glDeleteFramebuffers(1,new int[]{gasFbo},0); gasFbo=0; }
            if (gasTex!=0) { GLES20.glDeleteTextures(1,new int[]{gasTex},0); gasTex=0; }
            gasW=Math.max(1,Math.round(w*gasScale));
            gasH=Math.max(1,Math.round(h*gasScale));
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
            Log.i(TAG,"Gas FBO "+gasW+"x"+gasH+" (window "+w+"x"+h+")");
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // Frame pacing: throttle the GL thread to the configured cap.
            if (frameMs > 0 && lastDrawMs != 0) {
                long sleep = frameMs - (SystemClock.elapsedRealtime() - lastDrawMs);
                if (sleep > 0) { try { Thread.sleep(sleep); } catch (InterruptedException ignored) {} }
            }
            lastDrawMs=SystemClock.elapsedRealtime();

            // PHASE 0: log cadence fps + real GPU work-time per frame (glFinish).
            fpsN++;
            if (fpsT0==0) fpsT0=lastDrawMs;
            else if (lastDrawMs-fpsT0>=2000) {
                Log.i(TAG,"SPIKE cadence="+String.format("%.1f",fpsN*1000f/(lastDrawMs-fpsT0))
                    +"fps gpuWork="+String.format("%.1f",workAccNs/(float)fpsN/1e6f)
                    +"ms res="+screenW+"x"+screenH+" hdr="+(hdr!=null&&hdr.hdrActive));
                fpsN=0; fpsT0=lastDrawMs; workAccNs=0;
            }
            long drawStartNs=System.nanoTime();

            float t=(SystemClock.elapsedRealtime()-startMs)/1000f;

            // ── PASS 1: raymarch the gas into the low-res FBO ────────────────
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,gasFbo);
            GLES20.glViewport(0,0,gasW,gasH);
            GLES20.glUseProgram(progGas);
            GLES20.glUniform1f(gUTime,t);
            GLES20.glUniform2f(gURes,(float)gasW,(float)gasH);
            GLES20.glUniform1f(gUZoom,zoomMul);
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
            GLES20.glUniform1f(cUHdr,(hdr!=null && hdr.hdrActive)?1f:0f);
            GLES20.glUniform1f(cUHdrKnee,HDR_KNEE);
            GLES20.glUniform1f(cUHdrGain,HDR_GAIN);
            GLES20.glUniform1f(cUHdrMax,HDR_MAX);
            if (t >= sfNext) {
                float r = sfRng.nextFloat();
                sfMag = r * r;
                sfDur = 1.0f + sfRng.nextFloat() * 1.5f;
                sfStart = t;
                float szsp = 0.0120f * zoomMul;
                float sph = t * szsp;
                float bestF = -1f;
                float[][] layerOff = {{0f,0f},{0.37f,0.21f},{0.71f,0.53f}};
                float[] phOff = {0f, 0.333f, 0.667f};
                for (int i = 0; i < 3; i++) {
                    float ti = sph + phOff[i];
                    ti = ti - (float)Math.floor(ti);
                    float fi = Math.max(0f, Math.min(1f, (ti - 0f) / 0.40f))
                             * Math.max(0f, Math.min(1f, (1f - ti) / 0.40f));
                    if (fi > bestF) { bestF = fi; sfLayer = i; }
                }
                float lox = layerOff[sfLayer][0], loy = layerOff[sfLayer][1];
                for (int attempt = 0; attempt < 200; attempt++) {
                    sfCellX = (float)Math.floor(sfRng.nextFloat() * 80f + lox);
                    sfCellY = (float)Math.floor(sfRng.nextFloat() * 80f + loy);
                    if (cpuHasStar(sfCellX, sfCellY, lox, loy)) break;
                }
                sfNext = t + sfDur + 0.05f + sfRng.nextFloat() * 0.15f;
                Log.i(TAG,"FLARE cell="+sfCellX+","+sfCellY+" layer="+sfLayer+" mag="+String.format("%.3f",sfMag));
            }
            float sfEnv = 0f;
            if (sfStart >= 0f && t < sfStart + sfDur) {
                float p = (t - sfStart) / sfDur;
                float s = (float)Math.sin(Math.PI * p);
                sfEnv = s * s;
            }
            GLES20.glUniform4f(cUStarFlare, sfCellX, sfCellY, sfEnv * sfMag, (float)sfLayer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,gasTex);
            GLES20.glUniform1i(cUGas,0);
            quadBuf.position(0);
            GLES20.glVertexAttribPointer(cAPos,2,GLES20.GL_FLOAT,false,8,quadBuf);
            GLES20.glEnableVertexAttribArray(cAPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);

            GLES20.glFinish(); // PHASE 0: force GPU completion to time real work
            workAccNs += System.nanoTime()-drawStartNs;
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
