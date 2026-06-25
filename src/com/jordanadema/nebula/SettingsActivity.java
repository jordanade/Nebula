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
import android.util.TypedValue;
import android.view.View;
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
            implements Preference.OnPreferenceClickListener {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);
            findPreference("start_now").setOnPreferenceClickListener(this);
        }

        // One UI overlays a tall collapsing title on top of the list, hiding
        // the first category header and preference. Inset the list below the
        // status bar + action bar so the top row (HDR output) is visible. No-op
        // cost on AOSP/Android TV, where the insets resolve to ~0 overlap.
        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            View root = getView();
            if (root == null) return;
            View list = root.findViewById(android.R.id.list);
            if (!(list instanceof ListView)) return;

            int top = 0;
            TypedValue tv = new TypedValue();
            if (getActivity().getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                top += TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
            }
            int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (sbId > 0) top += getResources().getDimensionPixelSize(sbId);

            ListView lv = (ListView) list;
            lv.setClipToPadding(false);
            lv.setPadding(lv.getPaddingLeft(), lv.getPaddingTop() + top,
                lv.getPaddingRight(), lv.getPaddingBottom());
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
