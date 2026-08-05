package com.amaral.driverlab;

import org.json.JSONObject;

final class Phase7Contract {
    static final int QUALIFICATION_SCHEMA_VERSION = 1;
    static final int PROFILE_VERSION = 1;
    static final int REPORT_VERSION = 1;
    static final int SCORE_VERSION = 1;
    static final int BUNDLE_VERSION = 1;
    static final String PROFILE_ID = "turnip_full_qualification";
    static final String PROFILE_LABEL = "Turnip Full Qualification v1";
    static final double PRACTICAL_WIN_MARGIN_PERCENT = 3.0;
    static final int MINIMUM_VALID_PERFORMANCE_STEPS = 6;
    static final int DEFAULT_COOLDOWN_SECONDS = 12;

    static final String LIMITATION =
            "O índice Full Qualification é uma síntese versionada de cargas sintéticas A/B. "
                    + "Ele não converte unidades físicas diferentes em uma métrica científica, "
                    + "não representa FPS e não garante compatibilidade em jogos. O gate de "
                    + "correção e estabilidade tem precedência sobre qualquer ganho de performance.";

    private Phase7Contract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("qualification_schema_version", QUALIFICATION_SCHEMA_VERSION)
                .put("qualification_profile_id", PROFILE_ID)
                .put("qualification_profile_version", PROFILE_VERSION)
                .put("qualification_report_version", REPORT_VERSION)
                .put("qualification_score_version", SCORE_VERSION)
                .put("diagnostic_bundle_version", BUNDLE_VERSION)
                .put("practical_win_margin_percent", PRACTICAL_WIN_MARGIN_PERCENT)
                .put("minimum_valid_performance_steps", MINIMUM_VALID_PERFORMANCE_STEPS)
                .put("compatibility_gate_precedes_performance", true)
                .put("cross_profile_ranking_allowed", false)
                .put("limitations", LIMITATION);
    }
}
