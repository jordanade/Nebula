package com.jordanadema.nebula;

import android.opengl.GLSurfaceView;
import android.util.Log;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

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
 * Legacy stored "on" values behave like auto: we can't synthesise HDR the
 * driver doesn't expose, so anything except "off" means "HDR if available".
 */
class HdrSurface
        implements GLSurfaceView.EGLConfigChooser, GLSurfaceView.EGLWindowSurfaceFactory {

    private static final String TAG = "NebulaDream";

    private static final int EGL_GL_COLORSPACE_KHR             = 0x309D;
    private static final int EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT = 0x3350;
    private static final int EGL_COLOR_COMPONENT_TYPE_EXT       = 0x3339;
    private static final int EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int EGL_WINDOW_BIT     = 0x0004;

    private final String mode;
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
            hdrActive = false;
        }
        return egl.eglCreateWindowSurface(display, config, nativeWindow, null);
    }

    @Override
    public void destroySurface(EGL10 egl, EGLDisplay display, EGLSurface surface) {
        egl.eglDestroySurface(display, surface);
    }
}
