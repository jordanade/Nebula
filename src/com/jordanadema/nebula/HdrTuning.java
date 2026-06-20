package com.jordanadema.nebula;

import android.os.Build;
import android.util.Log;
import android.view.Display;

final class HdrTuning {

    private static final String TAG = "NebulaDream";
    private static final float SDR_WHITE_NITS = 80.0f;
    private static final float FALLBACK_HEADROOM = 8.0f;
    private static final float MIN_HEADROOM = 3.0f;
    private static final float MAX_HEADROOM = 12.5f;

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

    static HdrTuning from(Display display) {
        float headroom = FALLBACK_HEADROOM;
        if (display != null && Build.VERSION.SDK_INT >= 24) {
            Display.HdrCapabilities caps = display.getHdrCapabilities();
            if (caps != null && caps.getDesiredMaxLuminance() > 0f) {
                headroom = caps.getDesiredMaxLuminance() / SDR_WHITE_NITS;
            }
        }
        headroom = clamp(headroom, MIN_HEADROOM, MAX_HEADROOM);
        float knee = clamp(1.85f + (headroom - 4.0f) * 0.08f, 1.8f, 2.6f);
        float gain = clamp(4.5f + headroom * 0.9f, 7.0f, 16.0f);
        float starMax = clamp(headroom + Math.min(2.5f, Math.max(1.25f, headroom * 0.35f)),
            MIN_HEADROOM, MAX_HEADROOM);
        float starGain = clamp(gain * 1.35f, 8.5f, 22.0f);
        Log.i(TAG, "HDR tuning max=" + String.format("%.2f", headroom)
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
