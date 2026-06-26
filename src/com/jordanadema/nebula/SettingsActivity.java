package com.jordanadema.nebula;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ListView;

/**
 * Settings screen for the Nebula daydream. Launched from the system
 * screensaver settings "gear", wired via res/xml/dream_info.xml.
 *
 * Uses the platform PreferenceFragment so it needs no support/leanback
 * libraries — the list is d-pad navigable on Android TV out of the box.
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager()
            .beginTransaction()
            .replace(android.R.id.content, new PrefsFragment())
            .commit();
    }

    public static class PrefsFragment extends PreferenceFragment
            implements Preference.OnPreferenceClickListener, View.OnApplyWindowInsetsListener {

        private int basePaddingTop;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);
            findPreference("start_now").setOnPreferenceClickListener(this);
        }

        // On edge-to-edge layouts (Android 15+ with targetSdk 35, e.g. One UI)
        // the preference list is drawn under the system bars and the title is
        // overlaid onto its first row. Pad the list down by exactly the system
        // top inset, which One UI reports large enough to also clear the
        // collapsing title. Non-edge-to-edge platforms (Android TV / the Shield)
        // report a zero top inset and already lay the list out below the action
        // bar, so they get no padding and no gap. See onApplyWindowInsets.
        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            View root = getView();
            if (root == null) return;
            View list = root.findViewById(android.R.id.list);
            if (!(list instanceof ListView)) return;

            basePaddingTop = list.getPaddingTop();
            ((ListView) list).setClipToPadding(false);
            list.setOnApplyWindowInsetsListener(this);
            list.requestApplyInsets();
        }

        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            int sysTop = insets.getSystemWindowInsetTop();
            Log.i("NebulaDream", "settings list top inset=" + sysTop);
            v.setPadding(v.getPaddingLeft(), basePaddingTop + sysTop,
                v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        }

        @Override
        public boolean onPreferenceClick(Preference preference) {
            // The AOSP "Somnambulator" launches the dream instantly on
            // Shield/stock Android. One UI accepts the intent but silently
            // refuses to bind the dream (system-gated, needs root), so on
            // Samsung route to the system Screen saver page where the built-in
            // Preview reliably starts it instead.
            boolean samsung = "samsung".equalsIgnoreCase(Build.MANUFACTURER);
            if (!samsung && startSomnambulator()) return true;
            try {
                startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startSomnambulator(); // last resort on the rare device lacking the page
            }
            return true;
        }

        private boolean startSomnambulator() {
            try {
                startActivity(new Intent().setComponent(new ComponentName(
                    "com.android.systemui", "com.android.systemui.Somnambulator")));
                return true;
            } catch (ActivityNotFoundException e) {
                return false;
            }
        }
    }
}
