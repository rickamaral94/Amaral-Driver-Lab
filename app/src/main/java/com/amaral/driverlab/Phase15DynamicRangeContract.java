package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

/** Version contract for the dynamic-range recalibration phase. */
final class Phase15DynamicRangeContract {
    static final int QUALIFICATION_SCHEMA_VERSION = 5;
    static final int LEGACY_PROFILE_VERSION = 6;
    /**
     * v7 declared emulator_frame_pattern at workload version 2, whose primary
     * metric was the median of the five passes pooled together. That metric moved
     * with the sample mix rather than with the driver, so v7 cannot be re-run and
     * its emulator_frame numbers must not be compared against v8.
     */
    static final int EMULATOR_V7_PROFILE_VERSION = 7;
    /**
     * v8 declared the visual scenes at workload version 2, whose GPU timestamp
     * bracket included per-sample work that does not scale with the repetition
     * count. It also put 30% of the weight on shader_compile, which three runs
     * showed does not resolve anything below roughly 10%.
     */
    static final int COMPOSITE_V8_PROFILE_VERSION = 8;
    /**
     * v9 declared emulator_frame_pattern at workload version 3, whose frame shape
     * was assumed rather than measured: 24 to 96 draws per pass against a real
     * median of 1, and no target above 960x540 when 45% of the emulator's render
     * passes are 1920x1080.
     */
    static final int LINEAR_V9_PROFILE_VERSION = 9;
    static final int PROFILE_VERSION = 10;
    static final int REPORT_VERSION = 5;
    static final int SCORE_VERSION = 5;
    static final int RANKING_VERSION = 3;
    static final int PUBLIC_DATASET_SCHEMA_VERSION = 3;
    static final String LEGACY_PROFILE_LABEL =
            "Turnip Recommended Validation v3 · faixa dinâmica calibrada";
    static final String EMULATOR_V7_PROFILE_LABEL =
            "Turnip Recommended Validation v4 · carga de emulador";
    static final String COMPOSITE_V8_PROFILE_LABEL =
            "Turnip Recommended Validation v5 · quadro de emulador composto";
    static final String LINEAR_V9_PROFILE_LABEL =
            "Turnip Recommended Validation v6 · réguas lineares";
    static final String PROFILE_LABEL =
            "Turnip Recommended Validation v7 · quadro medido do Eden";
    static final String LIMITATION =
            "Workloads v2 usam lotes calibrados por hardware, unidade de repetição explícita, "
                    + "teste de linearidade, tamanho amostral fixado após piloto independente e "
                    + "gates de deriva térmica. Multiplicadores distintos entre aparelhos tornam "
                    + "tempos absolutos normalizados incomparáveis; entre A740 e A825 compare "
                    + "somente tamanho de efeito pareado.";

    private Phase15DynamicRangeContract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("qualification_schema_version", QUALIFICATION_SCHEMA_VERSION)
                .put("profile_version", PROFILE_VERSION)
                .put("qualification_report_version", REPORT_VERSION)
                .put("qualification_score_version", SCORE_VERSION)
                .put("ranking_version", RANKING_VERSION)
                .put("public_dataset_schema_version", PUBLIC_DATASET_SCHEMA_VERSION)
                .put("result_schema_version", WorkloadContract.RESULT_SCHEMA_VERSION)
                .put("calibration", BenchmarkCalibrationContract.contractJson())
                .put("legacy_profiles_preserved", new JSONArray()
                        .put(1).put(2).put(3).put(4).put(5).put(6).put(7).put(8).put(9))
                .put("historical_workload_identity_required", true)
                .put("limitations", LIMITATION);
    }
}
