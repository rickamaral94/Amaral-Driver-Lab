package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

/** Version contract for the dynamic-range recalibration phase. */
final class Phase15DynamicRangeContract {
    static final int QUALIFICATION_SCHEMA_VERSION = 5;
    static final int LEGACY_PROFILE_VERSION = 6;
    static final int PROFILE_VERSION = 7;
    static final int REPORT_VERSION = 5;
    static final int SCORE_VERSION = 5;
    static final int RANKING_VERSION = 3;
    static final int PUBLIC_DATASET_SCHEMA_VERSION = 3;
    static final String LEGACY_PROFILE_LABEL =
            "Turnip Recommended Validation v3 · faixa dinâmica calibrada";
    static final String PROFILE_LABEL =
            "Turnip Recommended Validation v4 · carga de emulador";
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
                        .put(1).put(2).put(3).put(4).put(5))
                .put("historical_workload_identity_required", true)
                .put("limitations", LIMITATION);
    }
}
