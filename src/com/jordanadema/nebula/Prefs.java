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

    static final String HDR_MODE     = "hdr_mode";      // auto | off
    static final String RENDER_SCALE = "render_scale";  // 0.50 .. 1.00
    static final String FRAME_CAP    = "frame_cap";     // fps, 0 = uncapped
    static final String ZOOM_SPEED   = "zoom_speed";    // zSpd

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

    float renderScale() { return clampF(getIntPref(RENDER_SCALE, 45) / 100f, 0.10f, 1.0f); }

    float zoomMul() {
        int s = getIntPref(ZOOM_SPEED, 4);
        return Math.max(0.15f, (float)Math.pow(2.0, (s - 4) / 2.4));
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
