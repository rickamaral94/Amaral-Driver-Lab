package com.amaral.driverlab;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent UX preferences introduced by Phase 13. */
final class UxPreferenceStore {
    private static final String PREFS = "driver_lab_ux";
    private static final String ADVANCED_MODE = "advanced_mode";
    private static final String GUIDED_DRIVER_SHA = "guided_driver_sha";
    private static final String LAST_GUIDED_STEP = "last_guided_step";

    private final SharedPreferences preferences;

    UxPreferenceStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean advancedMode() {
        return preferences.getBoolean(ADVANCED_MODE, false);
    }

    void setAdvancedMode(boolean enabled) {
        preferences.edit().putBoolean(ADVANCED_MODE, enabled).apply();
    }

    String guidedDriverSha() {
        return preferences.getString(GUIDED_DRIVER_SHA, "");
    }

    void setGuidedDriverSha(String sha) {
        preferences.edit().putString(GUIDED_DRIVER_SHA, sha == null ? "" : sha).apply();
    }

    int lastGuidedStep() {
        return preferences.getInt(LAST_GUIDED_STEP, 0);
    }

    void setLastGuidedStep(int step) {
        preferences.edit().putInt(LAST_GUIDED_STEP, Math.max(0, Math.min(4, step))).apply();
    }
}
