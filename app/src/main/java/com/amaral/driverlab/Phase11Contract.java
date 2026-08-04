package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

final class Phase11Contract {
    static final int QUALIFICATION_SCHEMA_VERSION = 3;
    static final int PROFILE_VERSION = 3;
    static final int REPORT_VERSION = 3;
    static final int SCORE_VERSION = 3;
    static final int BUNDLE_VERSION = 3;
    static final int DEEP_DIAGNOSTIC_SCORE_BRIDGE_VERSION = 1;
    static final int MINIMUM_VALID_PERFORMANCE_CATEGORIES = 10;
    static final int RECOMMENDED_MEMORY_MIB = 128;
    static final int FULL_SOAK_CYCLES = 5;
    static final int AUTOMATED_ORCHESTRATED_STEPS = 15;
    static final int AUTOMATED_LOGICAL_TESTS = 20;
    static final int OPTIONAL_EVIDENCE_SLOTS = 1;
    static final String PROFILE_LABEL =
            "Turnip Full Qualification v3 · diagnóstico profundo e soak curto";
    static final String LIMITATION =
            "O Full v3 combina suites sintéticas A/B, diagnóstico profundo e um soak curto. "
                    + "A telemetria de emuladores continua opcional e externa. Os índices de "
                    + "performance e compatibilidade permanecem separados; corrupção, perda de "
                    + "capacidade, falha de sincronização, não determinismo, timeout, crash ou "
                    + "device lost têm precedência sobre qualquer ganho de tempo.";

    private Phase11Contract() {}

    static JSONObject contractJson() throws Exception {
        JSONArray added = new JSONArray();
        for (String module : Phase10Contract.MODULE_IDS) added.put(module);
        added.put("short_soak_5_cycles");
        return new JSONObject()
                .put("qualification_schema_version", QUALIFICATION_SCHEMA_VERSION)
                .put("current_full_profile_version", PROFILE_VERSION)
                .put("current_qualification_report_version", REPORT_VERSION)
                .put("current_qualification_score_version", SCORE_VERSION)
                .put("current_diagnostic_bundle_version", BUNDLE_VERSION)
                .put("deep_diagnostic_score_bridge_version",
                        DEEP_DIAGNOSTIC_SCORE_BRIDGE_VERSION)
                .put("profile_label", PROFILE_LABEL)
                .put("automated_orchestrated_steps", AUTOMATED_ORCHESTRATED_STEPS)
                .put("automated_logical_tests", AUTOMATED_LOGICAL_TESTS)
                .put("optional_evidence_slots", OPTIONAL_EVIDENCE_SLOTS)
                .put("deep_diagnostic_modules_added", added)
                .put("recommended_memory_mib", RECOMMENDED_MEMORY_MIB)
                .put("full_soak_cycles", FULL_SOAK_CYCLES)
                .put("telemetry_attachment_optional", true)
                .put("telemetry_absence_blocks_qualification", false)
                .put("performance_and_compatibility_indices_separate", true)
                .put("cross_profile_ranking_allowed", false)
                .put("legacy_full_profiles_preserved", new JSONArray().put(1).put(2))
                .put("limitations", LIMITATION);
    }
}
