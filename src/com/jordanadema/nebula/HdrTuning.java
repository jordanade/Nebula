package com.jordanadema.nebula;

import android.os.Build;
import android.util.Log;
import android.view.Display;

final class HdrTuning {

    private static final String TAG = "NebulaDream";
    private static final float SDR_WHITE_NITS = 80.0f;
    static final float FALLBACK_HEADROOM = 8.0f;
    static final float MIN_HEADROOM = 3.0f;
    static final float MAX_HEADROOM = 12.5f;

    final float max;
    final float knee;
    final float gain;
    final float starMax;
    final float starGain;

    private HdrTuning(float max, float knee, float gain, float starMax, float starGain) {
        this.max = max;
        this.knee = knee;
        this.gain = gain;
        this.starMax = starMax;
        this.starGain = starGain;
    }

    /**
     * Effective HDR headroom for a display, biased toward peak brightness.
     * Prefers the live HDR/SDR ratio (API 34+) the compositor is actually
     * granting right now, but never drops below the static panel descriptor —
     * so brightness only ever opportunistically increases, never regresses.
     * The ratio is dynamic (it moves with panel brightness and thermal state),
     * which is why callers re-run this from a ratio-changed listener.
     */
    static float headroomFor(Display display) {
        float staticHeadroom = FALLBACK_HEADROOM;
        if (display != null && Build.VERSION.SDK_INT >= 24) {
            Display.HdrCapabilities caps = display.getHdrCapabilities();
            if (caps != null && caps.getDesiredMaxLuminance() > 0f) {
                staticHeadroom = caps.getDesiredMaxLuminance() / SDR_WHITE_NITS;
            }
        }
        float liveHeadroom = 0f;
        if (display != null && Build.VERSION.SDK_INT >= 34 && display.isHdrSdrRatioAvailable()) {
            liveHeadroom = display.getHdrSdrRatio();
        }
        return Math.max(staticHeadroom, liveHeadroom);
    }

    static HdrTuning from(Display display, float bias) {
        return forHeadroom(headroomFor(display), bias);
    }

    /**
     * Derives the tonemap knee/gain and star-flare peak from a raw headroom
     * value scaled by the user's manual intensity bias (1.0 = neutral). The
     * bias lets the brightest stars/flares be pushed to the panel ceiling on
     * devices that under-report, or pulled back on ones that over-report.
     */
    static HdrTuning forHeadroom(float rawHeadroom, float bias) {
        float headroom = clamp(rawHeadroom * bias, MIN_HEADROOM, MAX_HEADROOM);
        float knee = clamp(1.85f + (headroom - 4.0f) * 0.08f, 1.8f, 2.6f);
        float gain = clamp(4.5f + headroom * 0.9f, 7.0f, 16.0f);
        float starMax = clamp(headroom + Math.min(2.5f, Math.max(1.25f, headroom * 0.35f)),
            MIN_HEADROOM, MAX_HEADROOM);
        float starGain = clamp(gain * 1.35f, 8.5f, 22.0f);
        Log.i(TAG, "HDR tuning raw=" + String.format("%.2f", rawHeadroom)
            + " bias=" + String.format("%.2f", bias)
            + " max=" + String.format("%.2f", headroom)
            + " knee=" + String.format("%.2f", knee)
            + " gain=" + String.format("%.2f", gain)
            + " starMax=" + String.format("%.2f", starMax)
            + " starGain=" + String.format("%.2f", starGain));
        return new HdrTuning(headroom, knee, gain, starMax, starGain);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
