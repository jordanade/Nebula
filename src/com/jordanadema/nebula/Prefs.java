package com.jordanadema.nebula;

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

    static final String HDR_MODE      = "hdr_mode";      // auto | off
    static final String RENDER_SCALE  = "render_scale";  // 0.10 .. 1.00 (default 0.40)
    static final String FRAME_CAP     = "frame_cap";     // fps, 0 = uncapped
    static final String ZOOM_SPEED    = "zoom_speed";    // zSpd

    static final String HDR_AUTO = "auto";
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

    float renderScale() { return clampF(getIntPref(RENDER_SCALE, 40) / 100f, 0.10f, 1.0f); }

    // Zoom multiplier over the 1..10 slider: geometric, so every notch is the
    // same PROPORTIONAL change rather than the same absolute one — a notch near
    // the slow end and a notch near the fast end feel like the same adjustment.
    //
    // ZOOM_STEP is that per-notch change, stated directly: 1.20 = +20% per
    // notch. (The curve used to be written as 2^((s-4)/2.4), where the step was
    // an exponent divisor — 2.4 meaning 33.5% per notch, which you had to work
    // out. Expressing the step itself keeps the knob honest.) Over 1..10 this
    // spans 0.64-3.32, a 5.2x range.
    //
    // Anchored at the DEFAULT slider (5), so ZOOM_STEP only tightens or widens
    // the slider around the default and can never move it. ZOOM_AT_DEFAULT is
    // the speed slider 5 has always resolved to, 2^(1/2.4) ~= 1.335, kept in
    // that form so it stays bit-identical to the original curve's value there.
    //
    // NOTE: changing ZOOM_STEP re-maps what a STORED slider value means — a
    // saved 10 was 5.66 under the original curve and is 3.32 now. Fresh
    // installs are unaffected (the default is anchored), but anyone who had
    // moved the slider silently gets a different speed.
    //
    // Default must match android:defaultValue in res/xml/prefs.xml.
    private static final double ZOOM_STEP       = 1.20;                 // per-notch change
    private static final double ZOOM_AT_DEFAULT = Math.pow(2.0, 1.0 / 2.4); // speed at slider 5

    float zoomMul() {
        int s = getIntPref(ZOOM_SPEED, 5);
        return Math.max(0.15f, (float)(ZOOM_AT_DEFAULT * Math.pow(ZOOM_STEP, s - 5)));
    }

    int frameCapFps() { return clampI(getStringInt(FRAME_CAP, 25), 10, 30); }

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
