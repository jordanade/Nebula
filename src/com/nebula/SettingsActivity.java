package com.nebula;

import android.app.Activity;
import android.os.Bundle;
import android.preference.PreferenceFragment;

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

    public static class PrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);
        }
    }
}
