package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class Phase12ContractTest {
    @Test
    public void contractPreservesTechnicalJsonAndHistoricalProfiles() throws Exception {
        JSONObject contract = Phase12Contract.contractJson();
        assertEquals(1, contract.getInt("localization_schema_version"));
        assertEquals(13, contract.getInt("result_schema_version"));
        assertEquals("en", contract.getString("default_fallback_language"));
        assertTrue(contract.getBoolean("system_language_option"));
        assertTrue(contract.getBoolean("html_report_localized"));
        assertFalse(contract.getBoolean("technical_json_localized"));
        assertTrue(contract.getBoolean("technical_identifiers_stable"));
        JSONArray languages = contract.getJSONArray("supported_language_tags");
        assertEquals(8, languages.length());
        assertEquals("pt-BR", languages.getString(0));
        assertEquals("zh-CN", languages.getString(7));
        JSONArray profiles = contract.getJSONArray("legacy_full_profiles_preserved");
        assertEquals(3, profiles.length());
    }

    @Test
    public void storedLanguageFallsBackSafely() {
        assertEquals(LanguagePreference.SYSTEM,
                LanguagePreference.fromStoredValue(null));
        assertEquals(LanguagePreference.SYSTEM,
                LanguagePreference.fromStoredValue("system"));
        assertEquals(LanguagePreference.PORTUGUESE_BRAZIL,
                LanguagePreference.fromStoredValue("pt-BR"));
        assertEquals(LanguagePreference.CHINESE_SIMPLIFIED,
                LanguagePreference.fromStoredValue("zh-CN"));
        assertEquals(LanguagePreference.ENGLISH,
                LanguagePreference.fromStoredValue("unsupported"));
    }
}
