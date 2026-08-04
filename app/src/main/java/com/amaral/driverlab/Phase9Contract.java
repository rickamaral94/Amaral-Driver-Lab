package com.amaral.driverlab;

import com.amaral.driverlab.telemetry.TelemetryContract;

import org.json.JSONObject;

final class Phase9Contract {
    static final int TELEMETRY_IMPORT_VERSION = 1;
    static final int TELEMETRY_SUMMARY_VERSION = 1;
    static final int TELEMETRY_COMPARISON_VERSION = 1;
    static final int TELEMETRY_LINK_VERSION = 1;
    static final long MAX_IMPORT_BYTES = 20L * 1024L * 1024L;
    static final double DESCRIPTIVE_MARGIN_PERCENT = 3.0;

    static final String LIMITATION =
            "Sessões de emuladores são observações locais e opt-in. Elas podem variar por cena, "
                    + "cache, configuração, entrada, CPU, temperatura e processos em segundo plano. "
                    + "A comparação é descritiva, sem bootstrap por frame, e não altera ranking, "
                    + "campanhas nem o índice Full Qualification.";

    private Phase9Contract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("telemetry_import_version", TELEMETRY_IMPORT_VERSION)
                .put("telemetry_summary_version", TELEMETRY_SUMMARY_VERSION)
                .put("telemetry_comparison_version", TELEMETRY_COMPARISON_VERSION)
                .put("telemetry_link_version", TELEMETRY_LINK_VERSION)
                .put("maximum_import_bytes", MAX_IMPORT_BYTES)
                .put("descriptive_margin_percent", DESCRIPTIVE_MARGIN_PERCENT)
                .put("sdk_contract", TelemetryContract.contractJson())
                .put("game_identity_sha256_only", true)
                .put("source_session_immutable", true)
                .put("suite_link_sidecar_only", true)
                .put("automatic_upload", false)
                .put("included_in_full_qualification_score", false)
                .put("limitations", LIMITATION);
    }
}
