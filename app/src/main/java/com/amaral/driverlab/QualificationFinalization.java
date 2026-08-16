package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure helpers used when optional qualification consolidation artifacts fail. */
final class QualificationFinalization {
    private QualificationFinalization() {}

    static JSONObject warning(String stage, Throwable error) throws Exception {
        return new JSONObject()
                .put("stage", stage)
                .put("error_type", error == null ? "unknown" : error.getClass().getName())
                .put("message", error == null || error.getMessage() == null
                        ? "unknown finalization error" : error.getMessage())
                .put("recorded_at_ms", System.currentTimeMillis());
    }

    static JSONObject fallbackReport(JSONObject manifest, JSONObject finalEnvironment,
                                     JSONObject environmentComparison, String failedStage,
                                     Throwable error, JSONArray warnings) throws Exception {
        JSONObject driver = manifest.getJSONObject("driver");
        JSONObject reference = manifest.optJSONObject("reference_driver");
        String candidateName = driver.optString("name", "Driver candidato");
        String referenceName = reference == null ? "Driver do sistema"
                : reference.optString("name", "Driver de referência");
        JSONArray recordedWarnings = warnings == null ? new JSONArray() : warnings;
        if (recordedWarnings.length() == 0) {
            recordedWarnings.put(warning(failedStage, error));
        }
        int profileVersion = manifest.getJSONObject("profile")
                .optInt("profile_version", QualificationProfile.currentVersion());
        int reportVersion = profileVersion >= Phase15DynamicRangeContract.PROFILE_VERSION
                ? Phase15DynamicRangeContract.REPORT_VERSION
                : profileVersion >= 3 ? Phase11Contract.REPORT_VERSION
                : profileVersion >= 2 ? Phase8Contract.CURRENT_QUALIFICATION_REPORT_VERSION
                : Phase7Contract.REPORT_VERSION;
        return new JSONObject()
                .put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION)
                .put("qualification_report_version", reportVersion)
                .put("qualification_id", manifest.getString("qualification_id"))
                .put("created_at_ms", manifest.getLong("created_at_ms"))
                .put("finished_at_ms", System.currentTimeMillis())
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("profile_id", manifest.getJSONObject("profile")
                        .optString("profile_id", Phase7Contract.PROFILE_ID))
                .put("profile_version", profileVersion)
                .put("profile_sha256", manifest.optString("profile_sha256"))
                .put("driver", driver)
                .put("comparison_mode", manifest.optString(
                        "comparison_mode", "system_vs_turnip"))
                .put("reference_driver", reference == null ? JSONObject.NULL : reference)
                .put("preflight", manifest.opt("preflight"))
                .put("final_environment", finalEnvironment)
                .put("environment_comparison", environmentComparison)
                .put("execution_state", "completed_with_finalization_warnings")
                .put("completed_step_count",
                        QualificationStore.countStatus(manifest, "completed"))
                .put("failed_step_count", QualificationStore.countStatus(manifest, "failed"))
                .put("score", JSONObject.NULL)
                .put("human_summary", new JSONObject()
                        .put("headline", "Testes executados; consolidação parcial")
                        .put("detail", "Todos os workloads registrados foram preservados, mas "
                                + "o relatório analítico completo falhou em " + failedStage + ".")
                        .put("driver_label", candidateName)
                        .put("reference_label", referenceName)
                        .put("winner", "unavailable")
                        .put("confidence", "unavailable"))
                .put("finalization_status", "partial")
                .put("finalization_failed_stage", failedStage)
                .put("finalization_warnings", recordedWarnings)
                .put("limitations", "Relatório mínimo de contingência; métricas brutas permanecem "
                        + "nos arquivos suite.json e no bundle quando disponível.");
    }
}
