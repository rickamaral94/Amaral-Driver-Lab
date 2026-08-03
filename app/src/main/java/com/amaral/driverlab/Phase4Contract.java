package com.amaral.driverlab;

import org.json.JSONObject;

final class Phase4Contract {
    static final int CATALOG_VERSION = 1;
    static final int SUITE_DIFF_VERSION = 1;
    static final int RANKING_VERSION = 1;
    static final int BISECT_VERSION = 1;
    static final int PUBLIC_DATASET_SCHEMA_VERSION = 1;
    static final int MAX_LOCAL_SUITES = 1_000;
    static final long MAX_IMPORTED_SUITE_BYTES = 2L * 1024L * 1024L;

    static final String LIMITATION =
            "O histórico reflete somente suítes presentes no aparelho. Rankings exigem o mesmo "
                    + "hardware, workload, versão, configuração e analysis_version; bisect assume "
                    + "uma sequência ordenada e regressão monotônica. O envelope público remove "
                    + "identificadores diretos, mas SoC, GPU, workload e hash do ZIP continuam "
                    + "sendo identificadores técnicos potencialmente correlacionáveis.";

    private Phase4Contract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("catalog_version", CATALOG_VERSION)
                .put("suite_diff_version", SUITE_DIFF_VERSION)
                .put("ranking_version", RANKING_VERSION)
                .put("bisect_version", BISECT_VERSION)
                .put("public_dataset_schema_version", PUBLIC_DATASET_SCHEMA_VERSION)
                .put("maximum_local_suites", MAX_LOCAL_SUITES)
                .put("blocking_validity_policy", "failures_or_blocking_warnings")
                .put("limitations", LIMITATION);
    }
}
