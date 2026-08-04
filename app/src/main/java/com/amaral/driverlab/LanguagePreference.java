package com.amaral.driverlab;

import java.util.Locale;

/** Persisted per-app language choice. Technical identifiers never depend on this value. */
enum LanguagePreference {
    SYSTEM("", "🌐", R.string.language_system),
    PORTUGUESE_BRAZIL("pt-BR", "🇧🇷", R.string.language_portuguese_brazil),
    ENGLISH("en", "🇺🇸", R.string.language_english),
    SPANISH("es", "🇪🇸", R.string.language_spanish),
    FRENCH("fr", "🇫🇷", R.string.language_french),
    GERMAN("de", "🇩🇪", R.string.language_german),
    ITALIAN("it", "🇮🇹", R.string.language_italian),
    JAPANESE("ja", "🇯🇵", R.string.language_japanese),
    CHINESE_SIMPLIFIED("zh-CN", "🇨🇳", R.string.language_chinese_simplified);

    final String languageTag;
    final String flag;
    final int labelRes;

    LanguagePreference(String languageTag, String flag, int labelRes) {
        this.languageTag = languageTag;
        this.flag = flag;
        this.labelRes = labelRes;
    }

    boolean followsSystem() {
        return languageTag.isEmpty();
    }

    Locale locale() {
        return followsSystem() ? Locale.getDefault() : Locale.forLanguageTag(languageTag);
    }

    static LanguagePreference fromStoredValue(String stored) {
        if (stored == null || stored.isEmpty() || "system".equals(stored)) return SYSTEM;
        for (LanguagePreference value : values()) {
            if (value.languageTag.equalsIgnoreCase(stored)) return value;
        }
        return ENGLISH;
    }
}
