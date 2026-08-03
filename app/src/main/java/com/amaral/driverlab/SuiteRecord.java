package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SuiteRecord {
    final File file;
    final JSONObject report;
    final int schemaVersion;
    final String suiteId;
    final long finishedAtMs;
    final String workloadId;
    final int workloadVersion;
    final String hardwareKey;
    final String publicHardwareKey;
    final String deviceLabel;
    final String socModel;
    final String gpuModel;
    final String candidateSha256;
    final String candidateLabel;
    final String primaryMetric;
    final double rankingScorePercent;
    final String classification;
    final String verdict;
    final boolean blockingValidity;
    final List<String> warnings;

    private SuiteRecord(File file, JSONObject report, JSONObject hardware,
                        List<String> warnings, boolean blockingValidity) {
        this.file = file;
        this.report = report;
        schemaVersion = report.optInt("schema_version", 1);
        suiteId = report.optString("suite_id", file == null ? "imported" : file.getName());
        finishedAtMs = report.optLong("finished_at_ms", report.optLong("started_at_ms", 0L));
        workloadId = report.optString("workload_id", WorkloadContract.TRANSFER_ID);
        workloadVersion = report.optInt("workload_version", 1);
        hardwareKey = hardware.optString("device_key", "unknown");
        publicHardwareKey = hardware.optString("public_hardware_key", "unknown");
        socModel = hardware.optString("soc_model", "unknown");
        gpuModel = hardware.optString("gpu_model", "unknown");
        deviceLabel = hardware.optString("manufacturer", "unknown") + " "
                + hardware.optString("model", "unknown");
        JSONObject candidate = report.optJSONObject("candidate");
        candidateSha256 = candidate == null ? "system"
                : candidate.optString("sha256", "unknown");
        candidateLabel = candidateLabel(candidate);
        primaryMetric = WorkloadContract.isPerformance(workloadId)
                ? WorkloadContract.primaryMetricFor(workloadId) : "render_correctness";
        JSONObject analysis = report.optJSONObject("statistical_analysis");
        rankingScorePercent = analysis == null ? Double.NaN
                : analysis.optDouble("median_paired_improvement_percent", Double.NaN);
        classification = analysis == null ? "not_applicable"
                : analysis.optString("classification", "inconclusive");
        verdict = report.optString("verdict", "unknown");
        this.blockingValidity = blockingValidity;
        this.warnings = warnings;
    }

    static SuiteRecord parse(File file, JSONObject report) throws Exception {
        validateBasic(report);
        JSONObject hardware = report.optJSONObject("hardware_identity");
        if (hardware == null) hardware = HardwareIdentity.fromReport(report);
        List<String> warnings = warningList(report.optJSONArray("validity_warnings"));
        boolean blocking = hasBlockingValidity(report, warnings);
        return new SuiteRecord(file, report, hardware, warnings, blocking);
    }

    String comparisonKey() {
        int analysisVersion = report.optJSONObject("analysis_contract") == null ? 0
                : report.optJSONObject("analysis_contract").optInt("analysis_version", 0);
        String configHash;
        try {
            configHash = JsonCanonicalizer.sha256(report.optJSONObject("workload_config"));
        } catch (Exception ignored) {
            configHash = "invalid";
        }
        return hardwareKey + "|" + workloadId + "|" + workloadVersion + "|"
                + analysisVersion + "|" + configHash;
    }

    JSONObject compactJson() throws Exception {
        JSONObject output = new JSONObject();
        output.put("suite_id", suiteId);
        output.put("finished_at_ms", finishedAtMs);
        output.put("schema_version", schemaVersion);
        output.put("workload_id", workloadId);
        output.put("workload_version", workloadVersion);
        output.put("hardware_key", hardwareKey);
        output.put("soc_model", socModel);
        output.put("gpu_model", gpuModel);
        output.put("candidate_sha256", candidateSha256);
        output.put("candidate_label", candidateLabel);
        output.put("primary_metric", primaryMetric);
        output.put("ranking_score_percent", Double.isFinite(rankingScorePercent)
                ? rankingScorePercent : JSONObject.NULL);
        output.put("classification", classification);
        output.put("verdict", verdict);
        output.put("blocking_validity", blockingValidity);
        return output;
    }

    String displayLabel() {
        String score = Double.isFinite(rankingScorePercent)
                ? String.format(Locale.US, "%+.2f%%", rankingScorePercent) : "sem score";
        return candidateLabel + " · " + WorkloadContract.labelFor(workloadId)
                + " · " + score + " · " + suiteId;
    }

    private static void validateBasic(JSONObject report) {
        int schema = report.optInt("schema_version", 1);
        if (schema < 1 || schema > WorkloadContract.RESULT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version não suportado: " + schema);
        }
        String workloadId = report.optString("workload_id", "");
        if (!WorkloadContract.isSupported(workloadId)) {
            throw new IllegalArgumentException("workload_id não suportado: " + workloadId);
        }
        if (report.optInt("workload_version", -1) <= 0) {
            throw new IllegalArgumentException("workload_version ausente");
        }
    }

    private static boolean hasBlockingValidity(JSONObject report, List<String> warnings) {
        JSONArray failures = report.optJSONArray("failure_catalog");
        if (failures != null && failures.length() > 0) return true;
        String verdict = report.optString("verdict", "");
        if (verdict.startsWith("failed_")) return true;
        for (String warning : warnings) {
            String lower = warning.toLowerCase(Locale.US);
            if (lower.contains("falharam") || lower.contains("expiraram")
                    || lower.contains("erro") || lower.contains("device lost")
                    || lower.contains("não determin") || lower.contains("menos de cinco")
                    || lower.contains("insuficiente") || lower.contains("sem braço")
                    || lower.contains("temperatura inicial variou")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> warningList(JSONArray input) {
        List<String> output = new ArrayList<>();
        if (input == null) return output;
        for (int index = 0; index < input.length(); ++index) {
            String value = input.optString(index, "").trim();
            if (!value.isEmpty()) output.add(value);
        }
        return output;
    }

    private static String candidateLabel(JSONObject candidate) {
        if (candidate == null) return "driver do sistema";
        String name = candidate.optString("name", "candidato");
        String version = candidate.optString("packageVersion",
                candidate.optString("driverVersion", ""));
        return version.isEmpty() ? name : name + " · " + version;
    }
}
