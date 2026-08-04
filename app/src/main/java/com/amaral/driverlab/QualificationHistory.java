package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class QualificationHistory {
    private QualificationHistory() {}

    static JSONObject leaderboard(File filesDir, JSONObject reference) throws Exception {
        String profileSha = reference.optString("profile_sha256");
        String hardwareKey = reference.optJSONObject("hardware_identity") == null ? "unknown"
                : reference.optJSONObject("hardware_identity").optString("device_key", "unknown");
        String comparisonMode = reference.optString("comparison_mode", "system_vs_turnip");
        JSONObject referenceDriver = reference.optJSONObject("reference_driver");
        String referenceDriverSha = referenceDriver == null ? "system"
                : referenceDriver.optString("sha256", "unknown");
        File root = new File(filesDir, "qualifications");
        File[] directories = root.listFiles(File::isDirectory);
        List<JSONObject> entries = new ArrayList<>();
        if (directories != null) {
            for (File directory : directories) {
                File reportFile = new File(directory, "report.json");
                if (!reportFile.isFile()) continue;
                try {
                    JSONObject report = new JSONObject(ResultFiles.readUtf8(reportFile));
                    if (!profileSha.equals(report.optString("profile_sha256"))) continue;
                    JSONObject hardware = report.optJSONObject("hardware_identity");
                    if (hardware == null || !hardwareKey.equals(
                            hardware.optString("device_key", "unknown"))) continue;
                    if (!comparisonMode.equals(report.optString(
                            "comparison_mode", "system_vs_turnip"))) continue;
                    JSONObject reportReference = report.optJSONObject("reference_driver");
                    String reportReferenceSha = reportReference == null ? "system"
                            : reportReference.optString("sha256", "unknown");
                    if (!referenceDriverSha.equals(reportReferenceSha)) continue;
                    JSONObject score = report.optJSONObject("score");
                    if (score == null || !score.optBoolean("eligible_for_recommendation", false)) {
                        continue;
                    }
                    double index = score.optDouble("overall_index", Double.NaN);
                    if (!Double.isFinite(index)) continue;
                    JSONObject driver = report.optJSONObject("driver");
                    entries.add(new JSONObject()
                            .put("qualification_id", report.optString("qualification_id"))
                            .put("driver_sha256", driver == null ? "unknown"
                                    : driver.optString("sha256", "unknown"))
                            .put("driver_label", driverLabel(driver))
                            .put("overall_index", index)
                            .put("weighted_improvement_percent",
                                    score.opt("weighted_improvement_percent"))
                            .put("confidence", score.optString("confidence"))
                            .put("finished_at_ms", report.optLong("finished_at_ms")));
                } catch (Exception ignored) {
                    // Invalid reports do not participate.
                }
            }
        }
        entries.sort(Comparator.comparingDouble(
                item -> -item.optDouble("overall_index", Double.NEGATIVE_INFINITY)));
        JSONArray encoded = new JSONArray();
        int referenceRank = 0;
        for (int index = 0; index < entries.size(); ++index) {
            JSONObject entry = entries.get(index).put("rank", index + 1);
            if (reference.optString("qualification_id").equals(
                    entry.optString("qualification_id"))) referenceRank = index + 1;
            encoded.put(entry);
        }
        return new JSONObject()
                .put("qualification_score_version", reference.optJSONObject("score") == null
                        ? Phase7Contract.SCORE_VERSION
                        : reference.optJSONObject("score").optInt(
                                "qualification_score_version", Phase7Contract.SCORE_VERSION))
                .put("profile_sha256", profileSha)
                .put("hardware_key", hardwareKey)
                .put("comparison_mode", comparisonMode)
                .put("reference_driver_sha256", referenceDriverSha)
                .put("eligible_entry_count", entries.size())
                .put("current_rank", referenceRank == 0 ? JSONObject.NULL : referenceRank)
                .put("entries", encoded)
                .put("limitations", "Compara somente Full Qualification com mesmo perfil, hardware, modo de comparação e driver de referência.");
    }

    private static String driverLabel(JSONObject driver) {
        if (driver == null) return "driver desconhecido";
        String name = driver.optString("name", "candidato");
        String version = driver.optString("packageVersion", driver.optString("driverVersion", ""));
        return version.isEmpty() ? name : name + " · " + version;
    }
}
