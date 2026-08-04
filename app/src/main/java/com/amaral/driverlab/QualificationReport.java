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
            JSONObject scored = new JSONObject()
                    .put("step_id", stepId)
                    .put("status", state.optString("status"));
            JSONObject compact = new JSONObject()
                    .put("step_id", stepId)
                    .put("label", QualificationProfile.step(profileVersion, stepId).label)
                    .put("status", state.optString("status"))
                    .put("attempt_count", state.optInt("attempt_count", 0))
                    .put("suite_id", state.opt("suite_id"))
                    .put("suite_relative_path", state.opt("suite_relative_path"))
                    .put("failure", state.opt("failure"));
            if ("completed".equals(state.optString("status"))) {
                File suiteFile = QualificationStore.suiteFile(filesDir, state);
                if (suiteFile != null && suiteFile.isFile()) {
                    JSONObject suite = new JSONObject(ResultFiles.readUtf8(suiteFile));
                    scored.put("report", suite);
                    compact.put("workload_id", suite.optString("workload_id"))
                            .put("workload_version", suite.optInt("workload_version", 1))
                            .put("verdict", suite.optString("verdict", "unknown"))
                            .put("validity_warnings", suite.optJSONArray("validity_warnings"))
                            .put("failure_catalog", suite.optJSONArray("failure_catalog"))
                            .put("statistical_analysis", suite.opt("statistical_analysis"))
                            .put("render_correctness", suite.opt("render_correctness"))
                            .put("trace_replay", suite.opt("trace_replay"))
                            .put("visual_scene", suite.opt("visual_scene"));
                    if (hardware == null) hardware = suite.optJSONObject("hardware_identity");
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
        JSONObject human = humanSummary(driver, score);
        return new JSONObject()
                .put("qualification_report_version", profileVersion >= 2
                        ? Phase8Contract.CURRENT_QUALIFICATION_REPORT_VERSION
                        : Phase7Contract.REPORT_VERSION)
                .put("qualification_id", manifest.getString("qualification_id"))
                .put("created_at_ms", manifest.getLong("created_at_ms"))
                .put("finished_at_ms", System.currentTimeMillis())
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("phase7_contract", Phase7Contract.contractJson())
                .put("phase8_contract", profileVersion >= 2
                        ? Phase8Contract.contractJson() : JSONObject.NULL)
                .put("phase9_contract", Phase9Contract.contractJson())
                .put("phase10_contract", Phase10Contract.contractJson())
                .put("profile_id", Phase7Contract.PROFILE_ID)
                .put("profile_version", profileVersion)
                .put("profile_sha256", manifest.getString("profile_sha256"))
                .put("driver", driver)
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
                .put("human_summary", human)
                .put("local_leaderboard", JSONObject.NULL)
                .put("limitations", profileVersion >= 2
                        ? Phase8Contract.LIMITATION : Phase7Contract.LIMITATION);
    }

    static JSONObject humanSummary(JSONObject driver, JSONObject score) throws Exception {
        String name = driver.optString("name", "Driver candidato");
        String version = driver.optString("packageVersion",
                driver.optString("driverVersion", ""));
        String label = version.isEmpty() ? name : name + " · " + version;
        String recommendation = score.optString("recommendation");
        String headline;
        String detail;
        if ("candidate_recommended_over_system".equals(recommendation)) {
            headline = "Driver candidato recomendado";
            detail = label + " foi superior ao driver do sistema no perfil Full v"
                    + score.optInt("profile_version", 1) + ".";
        } else if ("system_recommended_over_candidate".equals(recommendation)) {
            headline = "Driver do sistema recomendado";
            detail = label + " apresentou resultado geral inferior ao driver do sistema.";
        } else if ("technical_tie_no_clear_winner".equals(recommendation)) {
            headline = "Empate técnico";
            detail = "A diferença geral ficou dentro da margem prática de ±"
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
                .put("winner", score.optString("winner"))
                .put("confidence", score.optString("confidence"))
                .put("overall_index", score.opt("overall_index"))
                .put("weighted_improvement_percent", score.opt("weighted_improvement_percent"))
                .put("best_area", best == null ? JSONObject.NULL : best.optString("label"))
                .put("worst_area", worst == null ? JSONObject.NULL : worst.optString("label"));
    }
}
