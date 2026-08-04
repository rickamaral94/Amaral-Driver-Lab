package com.amaral.driverlab;

import com.amaral.driverlab.telemetry.TelemetryContract;

import org.json.JSONObject;

final class Phase7Contract {
    static final int TELEMETRY_REPORT_SCHEMA_VERSION = 1;
    static final int TELEMETRY_ANALYSIS_VERSION = 1;
    static final int MAX_IMPORT_BYTES = 32 * 1024 * 1024;

    static final String LIMITATION =
            "A telemetria descreve apenas os eventos que o emulador decidiu registrar. Frametime "
                    + "é reportado pelo produtor, não medido externamente pelo Driver Lab; cobertura, "
                    + "V-Sync, pacing do compositor, throttling, estado térmico e processos em segundo "
                    + "plano podem alterar a sessão. Correlação com um hash de driver não prova "
                    + "causalidade, não substitui reprodução visual e não garante compatibilidade.";

    private Phase7Contract() {}

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("telemetry_schema_version", TelemetryContract.TELEMETRY_SCHEMA_VERSION)
                .put("telemetry_sdk_version", TelemetryContract.SDK_VERSION)
                .put("telemetry_report_schema_version", TELEMETRY_REPORT_SCHEMA_VERSION)
                .put("telemetry_analysis_version", TELEMETRY_ANALYSIS_VERSION)
                .put("maximum_import_bytes", MAX_IMPORT_BYTES)
                .put("collection_policy", "explicit_opt_in_local_only")
                .put("content_identity_policy", "salted_sha256_only")
                .put("driver_binding_policy", "declared_hash_must_match_selected_local_package")
                .put("cross_session_score", false)
                .put("automatic_upload", false)
                .put("limitations", LIMITATION);
    }
}
