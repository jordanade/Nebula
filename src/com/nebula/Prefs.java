package com.nebula;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Typed access to the daydream's settings, with defaults that match
 * res/xml/prefs.xml. Values are stored as strings by ListPreference, so
 * everything is parsed defensively (a malformed stored value falls back
 * to the default rather than crashing the dream).
 */
final class Prefs {

    static final String HDR_MODE     = "hdr_mode";      // auto | on | off
    static final String RENDER_SCALE = "render_scale";  // 0.50 .. 1.00
    static final String FRAME_CAP    = "frame_cap";     // fps, 0 = uncapped
    static final String ZOOM_SPEED   = "zoom_speed";    // zSpd
    static final String WRITHE_SPEED = "writhe_speed";  // slowT rate

    static final String HDR_AUTO = "auto";
    static final String HDR_ON   = "on";
    static final String HDR_OFF  = "off";

    private final SharedPreferences sp;

    private Prefs(Context ctx) {
        sp = PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());
    }

    static Prefs from(Context ctx) {
        return new Prefs(ctx);
    }

    String hdrMode() {
        // Default "auto": use HDR when the display supports it, else SDR.
        String v = sp.getString(HDR_MODE, HDR_AUTO);
        return (v == null) ? HDR_AUTO : v;
    }

    // Slider prefs are stored as ints (see SliderPreference); scale to floats.
    // Default 60%: a barely-visible softening on the low-frequency gas that cuts
    // fragment cost for cooler all-night running. Raise toward 100 for more detail
    // (the Shield has plenty of thermal headroom).
    float renderScale() { return clampF(getIntPref(RENDER_SCALE, 55) / 100f, 0.25f, 1.0f); }

    // Speeds use a 1–10 scale with 4 as the default. Zoom is a multiplier
    // (4 -> 1.0) applied to both nebula and star zoom, preserving their ratio.
    float zoomMul()    { return clampF(getIntPref(ZOOM_SPEED, 4) / 4f, 0.1f, 3.0f); }
    // Writhe maps to its rate; 4 -> 0.045. Scales linearly with the 1–10 slider.
    float writheRate() { return clampF(0.045f * getIntPref(WRITHE_SPEED, 4) / 4f, 0.0f, 0.2f); }

    // Frame cap is a ListPreference (string), to keep the "uncapped" option.
    // Default 15: the slow nebula motion reads fine at 15fps, and capping lets the
    // GPU idle between frames (cooler, lower power). 30/60/uncapped remain available.
    int frameCapFps() { return clampI(getStringInt(FRAME_CAP, 30), 0, 240); }

    private int getIntPref(String key, int def) {
        try { return sp.getInt(key, def); }
        catch (Exception e) { return def; }
    }

    private int getStringInt(String key, int def) {
        try { return Integer.parseInt(sp.getString(key, Integer.toString(def))); }
        catch (Exception e) { return def; }
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
