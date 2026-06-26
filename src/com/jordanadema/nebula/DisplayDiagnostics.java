package com.jordanadema.nebula;

import android.content.Context;
import android.os.Build;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

final class DisplayDiagnostics {

    private static final String TAG = "NebulaDream";

    final Display display;
    final String requestedMode;
    final String appMetrics;
    final String realMetrics;
    final String hdrCaps;
    final int targetWidth;
    final int targetHeight;
    final int densityDpi;

    private DisplayDiagnostics(Display display, String requestedMode, String appMetrics,
                               String realMetrics, String hdrCaps, int targetWidth, int targetHeight,
                               int densityDpi) {
        this.display = display;
        this.requestedMode = requestedMode;
        this.appMetrics = appMetrics;
        this.realMetrics = realMetrics;
        this.hdrCaps = hdrCaps;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.densityDpi = densityDpi;
    }

    static DisplayDiagnostics configure(DreamService service) {
        Display display = defaultDisplay(service);
        String appMetrics = metrics(display, false);
        String realMetrics = metrics(display, true);
        String hdrCaps = hdrCaps(display);
        String requested = "none";
        int targetWidth = 0;
        int targetHeight = 0;

        if (display != null && Build.VERSION.SDK_INT >= 23) {
            Display.Mode mode = chooseMode(display.getSupportedModes());
            if (mode != null) {
                requested = modeLabel(mode);
                targetWidth = mode.getPhysicalWidth();
                targetHeight = mode.getPhysicalHeight();
                Window window = service.getWindow();
                if (window != null) {
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.preferredDisplayModeId = mode.getModeId();
                    window.setAttributes(lp);
                }
            }
        }

        if (display != null && Build.VERSION.SDK_INT >= 23) {
            Display.Mode active = display.getMode();
            if (active != null) {
                targetWidth = Math.max(targetWidth, active.getPhysicalWidth());
                targetHeight = Math.max(targetHeight, active.getPhysicalHeight());
            }
        }

        DisplayDiagnostics diag = new DisplayDiagnostics(
            display, requested, appMetrics, realMetrics, hdrCaps, targetWidth, targetHeight,
            densityDpi(display));
        Log.i(TAG, "DISPLAY startup requested=" + requested
            + " active=" + diag.activeMode()
            + " appMetrics=" + appMetrics
            + " realMetrics=" + realMetrics
            + " hdrCaps=" + hdrCaps);
        return diag;
    }

    String activeMode() {
        if (display == null || Build.VERSION.SDK_INT < 23) return "unknown";
        return modeLabel(display.getMode());
    }

    /** Live HDR/SDR headroom the compositor is granting, or why it's absent. */
    String hdrRatioLabel() {
        if (display == null || Build.VERSION.SDK_INT < 34) return "n/a";
        if (!display.isHdrSdrRatioAvailable()) return "unavail";
        return String.format("%.2f", display.getHdrSdrRatio());
    }

    String surfaceLimitMessage(int w, int h) {
        if (display == null || Build.VERSION.SDK_INT < 23) return null;
        Display.Mode active = display.getMode();
        int aw = targetWidth;
        int ah = targetHeight;
        if (active != null) {
            aw = Math.max(aw, active.getPhysicalWidth());
            ah = Math.max(ah, active.getPhysicalHeight());
        }
        if (aw <= 0 || ah <= 0) return null;
        if (w + 1 < aw || h + 1 < ah) {
            return "App surface " + w + "x" + h + " is below active display mode "
                + aw + "x" + ah + "; Android display-size override or system scaling is limiting 4K.";
        }
        return null;
    }

    private static Display defaultDisplay(DreamService service) {
        WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        return wm == null ? null : wm.getDefaultDisplay();
    }

    private static Display.Mode chooseMode(Display.Mode[] modes) {
        if (modes == null || modes.length == 0) return null;
        Display.Mode best = null;
        for (Display.Mode mode : modes) {
            if (mode == null || mode.getRefreshRate() > 60.5f) continue;
            if (best == null || betterMode(mode, best)) best = mode;
        }
        if (best != null) return best;
        for (Display.Mode mode : modes) {
            if (mode == null) continue;
            if (best == null || betterMode(mode, best)) best = mode;
        }
        return best;
    }

    private static boolean betterMode(Display.Mode candidate, Display.Mode current) {
        long ca = (long) candidate.getPhysicalWidth() * candidate.getPhysicalHeight();
        long cb = (long) current.getPhysicalWidth() * current.getPhysicalHeight();
        if (ca != cb) return ca > cb;
        float cf = Math.min(candidate.getRefreshRate(), 60.0f);
        float bf = Math.min(current.getRefreshRate(), 60.0f);
        return cf > bf;
    }

    private static String metrics(Display display, boolean real) {
        if (display == null) return "unknown";
        DisplayMetrics dm = new DisplayMetrics();
        if (real) display.getRealMetrics(dm);
        else display.getMetrics(dm);
        return dm.widthPixels + "x" + dm.heightPixels + "@" + dm.densityDpi + "dpi";
    }

    private static int densityDpi(Display display) {
        if (display == null) return 0;
        DisplayMetrics dm = new DisplayMetrics();
        display.getMetrics(dm);
        return dm.densityDpi;
    }

    private static String modeLabel(Display.Mode mode) {
        if (mode == null) return "none";
        return mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
            + "@" + String.format("%.2f", mode.getRefreshRate())
            + "#" + mode.getModeId();
    }

    private static String hdrCaps(Display display) {
        if (display == null || Build.VERSION.SDK_INT < 24) return "unknown";
        Display.HdrCapabilities caps = display.getHdrCapabilities();
        if (caps == null) return "none";
        return "types=" + hdrTypes(caps.getSupportedHdrTypes())
            + " max=" + String.format("%.1f", caps.getDesiredMaxLuminance())
            + " avg=" + String.format("%.1f", caps.getDesiredMaxAverageLuminance())
            + " min=" + String.format("%.4f", caps.getDesiredMinLuminance());
    }

    private static String hdrTypes(int[] types) {
        if (types == null || types.length == 0) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) out.append(',');
            out.append(hdrType(types[i]));
        }
        return out.append(']').toString();
    }

    private static String hdrType(int type) {
        switch (type) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: return "DolbyVision";
            case Display.HdrCapabilities.HDR_TYPE_HDR10: return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HLG: return "HLG";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS: return "HDR10+";
            default: return Integer.toString(type);
        }
    }
}
