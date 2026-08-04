package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

final class QualificationReport {
    private QualificationReport() {}

    static JSONObject build(File filesDir, JSONObject manifest,
                            JSONObject finalEnvironment,
                            JSONObject environmentComparison) throws Exception {
        if (!QualificationStore.verify(manifest)) {
            throw new IllegalArgumentException("Manifesto Full inválido");
        }
        int profileVersion = manifest.getJSONObject("profile").getInt("profile_version");
        JSONArray scoredSteps = new JSONArray();
        JSONArray compactSteps = new JSONArray();
        JSONObject hardware = null;
        JSONArray states = manifest.getJSONObject("execution").getJSONArray("steps");
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.getJSONObject(index);
            String stepId = state.getString("step_id");
            QualificationProfile.Step definition = QualificationProfile.step(profileVersion, stepId);
            JSONObject scored = new JSONObject()
                    .put("step_id", stepId)
                    .put("status", state.optString("status"));
            JSONObject compact = new JSONObject()
                    .put("step_id", stepId)
                    .put("step_kind", definition == null ? state.optString("step_kind", "unknown")
                            : definition.kind)
                    .put("label", definition == null ? stepId : definition.label)
                    .put("status", state.optString("status"))
                    .put("attempt_count", state.optInt("attempt_count", 0))
                    .put("suite_id", state.opt("suite_id"))
                    .put("suite_relative_path", state.opt("suite_relative_path"))
                    .put("artifact_id", state.opt("artifact_id"))
                    .put("artifact_relative_path", state.opt("artifact_relative_path"))
                    .put("result_type", state.opt("result_type"))
                    .put("failure", state.opt("failure"));
            if ("completed".equals(state.optString("status"))) {
                File resultFile = QualificationStore.suiteFile(filesDir, state);
                if (resultFile != null && resultFile.isFile()) {
                    JSONObject result = new JSONObject(ResultFiles.readUtf8(resultFile));
                    scored.put("report", result);
                    if (definition != null && QualificationProfile.KIND_SUITE.equals(definition.kind)) {
                        compact.put("workload_id", result.optString("workload_id"))
                                .put("workload_version", result.optInt("workload_version", 1))
                                .put("verdict", result.optString("verdict", "unknown"))
                                .put("validity_warnings", result.optJSONArray("validity_warnings"))
                                .put("failure_catalog", result.optJSONArray("failure_catalog"))
                                .put("statistical_analysis", result.opt("statistical_analysis"))
                                .put("render_correctness", result.opt("render_correctness"))
                                .put("trace_replay", result.opt("trace_replay"))
                                .put("visual_scene", result.opt("visual_scene"));
                        if (hardware == null) hardware = result.optJSONObject("hardware_identity");
                    } else {
                        compact.put("report_id", result.optString("report_id"))
                                .put("mode", result.optString("mode"))
                                .put("comparison", result.opt("comparison"))
                                .put("cycles", result.opt("cycles"))
                                .put("memory_mib", result.opt("memory_mib"));
                    }
                }
            }
            scoredSteps.put(scored);
            compactSteps.put(compact);
        }
        if (hardware == null) {
            JSONObject device = manifest.optJSONObject("preflight") == null ? null
                    : manifest.optJSONObject("preflight").optJSONObject("device");
            hardware = device == null ? new JSONObject() : new JSONObject()
                    .put("manufacturer", device.optString("manufacturer", "unknown"))
                    .put("model", device.optString("model", "unknown"))
                    .put("soc_model", device.optString("soc_model", "unknown"))
                    .put("gpu_model", "unknown")
                    .put("device_key", "unknown");
        }

        JSONObject score = QualificationScore.evaluate(
                manifest.getJSONObject("profile"), scoredSteps,
                manifest.getJSONObject("preflight"), environmentComparison);
        JSONObject driver = manifest.getJSONObject("driver");
        String comparisonMode = manifest.optString("comparison_mode", "system_vs_turnip");
        JSONObject referenceDriver = manifest.optJSONObject("reference_driver");
        JSONObject telemetry = profileVersion >= 3
                ? FullTelemetryAttachment.inspect(filesDir, driver.getString("sha256"))
                : new JSONObject().put("status", "not_part_of_profile")
                .put("optional", true).put("automatically_changes_score", false);
        JSONObject human = humanSummary(driver, referenceDriver, comparisonMode, score);
        int reportVersion = profileVersion >= 3 ? Phase11Contract.REPORT_VERSION
                : profileVersion >= 2 ? Phase8Contract.CURRENT_QUALIFICATION_REPORT_VERSION
                : Phase7Contract.REPORT_VERSION;
        String limitation = profileVersion >= 4 ? Phase13ValidationContract.LIMITATION
                : profileVersion >= 3 ? Phase11Contract.LIMITATION
                : profileVersion >= 2 ? Phase8Contract.LIMITATION : Phase7Contract.LIMITATION;
        return new JSONObject()
                .put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION)
                .put("qualification_report_version", reportVersion)
                .put("qualification_id", manifest.getString("qualification_id"))
                .put("created_at_ms", manifest.getLong("created_at_ms"))
                .put("finished_at_ms", System.currentTimeMillis())
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("phase7_contract", Phase7Contract.contractJson())
                .put("phase8_contract", profileVersion >= 2
                        ? Phase8Contract.contractJson() : JSONObject.NULL)
                .put("phase9_contract", Phase9Contract.contractJson())
                .put("phase10_contract", Phase10Contract.contractJson())
                .put("phase11_contract", profileVersion >= 3
                        ? Phase11Contract.contractJson() : JSONObject.NULL)
                .put("phase12_contract", Phase12Contract.contractJson())
                .put("phase13_validation_contract", profileVersion >= 4
                        ? Phase13ValidationContract.contractJson() : JSONObject.NULL)
                .put("profile_id", Phase7Contract.PROFILE_ID)
                .put("profile_version", profileVersion)
                .put("profile_sha256", manifest.getString("profile_sha256"))
                .put("driver", driver)
                .put("comparison_mode", comparisonMode)
                .put("reference_driver", referenceDriver == null
                        ? JSONObject.NULL : referenceDriver)
                .put("hardware_identity", hardware)
                .put("preflight", manifest.getJSONObject("preflight"))
                .put("final_environment", finalEnvironment)
                .put("environment_comparison", environmentComparison)
                .put("execution_state", QualificationStore.countStatus(manifest, "failed") > 0
                        ? "completed_with_failures" : "completed")
                .put("completed_step_count", QualificationStore.countStatus(manifest, "completed"))
                .put("failed_step_count", QualificationStore.countStatus(manifest, "failed"))
                .put("steps", compactSteps)
                .put("score", score)
                .put("telemetry_attachment", telemetry)
                .put("human_summary", human)
                .put("local_leaderboard", JSONObject.NULL)
                .put("limitations", limitation);
    }

    static JSONObject humanSummary(JSONObject driver, JSONObject score) throws Exception {
        return humanSummary(driver, null, "system_vs_turnip", score);
    }

    static JSONObject humanSummary(JSONObject driver, JSONObject referenceDriver,
                                   String comparisonMode, JSONObject score) throws Exception {
        String label = driverLabel(driver, "Driver candidato");
        boolean turnipVsTurnip = "turnip_vs_turnip".equals(comparisonMode)
                && referenceDriver != null;
        String referenceLabel = turnipVsTurnip
                ? driverLabel(referenceDriver, "Driver de referência")
                : "driver do sistema";
        String recommendation = score.optString("recommendation");
        String headline;
        String detail;
        if ("candidate_recommended_over_system".equals(recommendation)) {
            headline = "Driver candidato recomendado";
            detail = label + " foi superior a " + referenceLabel + " no perfil de validação v"
                    + score.optInt("profile_version", 1) + ".";
        } else if ("system_recommended_over_candidate".equals(recommendation)) {
            headline = turnipVsTurnip
                    ? "Driver de referência recomendado" : "Driver do sistema recomendado";
            detail = label + " apresentou resultado geral inferior a " + referenceLabel + ".";
        } else if ("technical_tie_no_clear_winner".equals(recommendation)) {
            headline = "Empate técnico";
            detail = "A diferença geral entre " + referenceLabel + " e " + label
                    + " ficou dentro da margem prática de ±"
                    + Phase7Contract.PRACTICAL_WIN_MARGIN_PERCENT + "%.";
        } else {
            headline = "Driver candidato não recomendado";
            detail = "Falhas de compatibilidade, validade ou cobertura impediram uma recomendação.";
        }
        JSONObject best = score.optJSONObject("best_category");
        JSONObject worst = score.optJSONObject("worst_category");
        return new JSONObject()
                .put("headline", headline)
                .put("detail", detail)
                .put("driver_label", label)
                .put("reference_label", referenceLabel)
                .put("comparison_mode", turnipVsTurnip
                        ? "turnip_vs_turnip" : "system_vs_turnip")
                .put("winner", score.optString("winner"))
                .put("confidence", score.optString("confidence"))
                .put("performance_index", score.opt("performance_index"))
                .put("compatibility_index", score.opt("compatibility_index"))
                .put("overall_index", score.opt("overall_index"))
                .put("weighted_improvement_percent", score.opt("weighted_improvement_percent"))
                .put("best_area", best == null ? JSONObject.NULL : best.optString("label"))
                .put("worst_area", worst == null ? JSONObject.NULL : worst.optString("label"));
    }

    private static String driverLabel(JSONObject driver, String fallback) {
        if (driver == null) return fallback;
        String name = driver.optString("name", fallback);
        String version = driver.optString("packageVersion",
                driver.optString("driverVersion", ""));
        return version.isEmpty() ? name : name + " · " + version;
    }
}