package com.amaral.driverlab;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** Applies and persists the app locale without modifying technical JSON contracts. */
final class LanguageManager {
    static final String PREFERENCES_NAME = "driver_lab_language";
    static final String PREFERENCE_KEY = "language_preference";
    private static volatile Context applicationContext;

    private LanguageManager() {}

    static void initialize(Context context) {
        applicationContext = context.getApplicationContext();
        LanguagePreference preference = current(context);
        Locale.setDefault(preference.followsSystem() ? systemLocale() : preference.locale());
    }

    static Context wrap(Context base) {
        LanguagePreference preference = current(base);
        if (preference.followsSystem()) return base;
        Locale locale = preference.locale();
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return base.createConfigurationContext(configuration);
    }

    static LanguagePreference current(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        return LanguagePreference.fromStoredValue(
                preferences.getString(PREFERENCE_KEY, "system"));
    }

    static String effectiveLanguageTag(Context context) {
        LanguagePreference preference = current(context);
        if (!preference.followsSystem()) return preference.languageTag;
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String tag = locale.toLanguageTag();
        return tag == null || tag.isEmpty() ? "en" : tag;
    }

    static void set(Activity activity, LanguagePreference preference) {
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREFERENCE_KEY,
                        preference.followsSystem() ? "system" : preference.languageTag)
                .apply();
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager localeManager = activity.getSystemService(LocaleManager.class);
            if (localeManager != null) {
                LocaleList locales = preference.followsSystem()
                        ? LocaleList.getEmptyLocaleList()
                        : LocaleList.forLanguageTags(preference.languageTag);
                localeManager.setApplicationLocales(locales);
            }
        } else {
            Locale.setDefault(preference.followsSystem() ? systemLocale() : preference.locale());
        }
        LegacyUiTranslations.clearCache();
        initialize(activity);
        activity.recreate();
    }

    static String get(int resourceId) {
        Context context = applicationContext;
        if (context == null) throw new IllegalStateException("LanguageManager not initialized");
        return wrap(context).getString(resourceId);
    }

    static String get(Context context, int resourceId) {
        return wrap(context).getString(resourceId);
    }

    static String format(Context context, int resourceId, Object... arguments) {
        return wrap(context).getString(resourceId, arguments);
    }

    private static Locale systemLocale() {
        Configuration configuration = Resources.getSystem().getConfiguration();
        if (Build.VERSION.SDK_INT >= 24) return configuration.getLocales().get(0);
        // minSdk is 28; this branch only documents the intended fallback.
        return configuration.locale;
    }

    static CharSequence translateLegacy(Context context, CharSequence value) {
        if (value == null || value.length() == 0) return value;
        return LegacyUiTranslations.translate(wrap(context), value.toString());
    }
}
