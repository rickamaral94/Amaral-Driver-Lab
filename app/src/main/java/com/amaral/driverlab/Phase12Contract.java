package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

final class Phase12Contract {
    static final int LOCALIZATION_SCHEMA_VERSION = 1;
    static final int RESULT_SCHEMA_VERSION = 13;
    static final String DEFAULT_FALLBACK_LANGUAGE = "en";
    static final String[] SUPPORTED_LANGUAGE_TAGS = {
            "pt-BR", "en", "es", "fr", "de", "it", "ja", "zh-CN"
    };
    static final String LIMITATION =
            "Localization changes labels, help text, dialogs and HTML presentation only. "
                    + "Technical JSON field names, enum values, workload IDs, metric names, "
                    + "hashes and historical qualification profiles remain stable.";

    private Phase12Contract() {}

    static JSONObject contractJson() throws Exception {
        JSONArray languages = new JSONArray();
        for (String language : SUPPORTED_LANGUAGE_TAGS) languages.put(language);
        return new JSONObject()
                .put("localization_schema_version", LOCALIZATION_SCHEMA_VERSION)
                .put("result_schema_version", RESULT_SCHEMA_VERSION)
                .put("supported_language_tags", languages)
                .put("system_language_option", true)
                .put("default_fallback_language", DEFAULT_FALLBACK_LANGUAGE)
                .put("preference_persisted_locally", true)
                .put("html_report_localized", true)
                .put("technical_json_localized", false)
                .put("technical_identifiers_stable", true)
                .put("legacy_full_profiles_preserved", new JSONArray().put(1).put(2).put(3))
                .put("limitations", LIMITATION);
    }
}
