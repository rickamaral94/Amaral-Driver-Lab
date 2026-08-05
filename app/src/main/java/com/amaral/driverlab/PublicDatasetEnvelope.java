package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

final class PublicDatasetEnvelope {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final String SIGNATURE_ALGORITHM = "SHA-256-canonical-json-v1";

    private PublicDatasetEnvelope() {}

    static JSONObject create(SuiteRecord record) throws Exception {
        validatePublishable(record);
        JSONObject hardware = record.report.optJSONObject("hardware_identity");
        if (hardware == null) hardware = HardwareIdentity.fromReport(record.report);
        JSONObject payload = new JSONObject();
        payload.put("source_suite_schema_version", record.schemaVersion);
        payload.put("source_app_version", record.report.optString("app_version", "unknown"));
        payload.put("hardware", new JSONObject()
                .put("public_hardware_key", hardware.optString("public_hardware_key"))
                .put("soc_manufacturer", hardware.optString("soc_manufacturer", "unknown"))
                .put("soc_model", hardware.optString("soc_model", "unknown"))
                .put("gpu_model", hardware.optString("gpu_model", "unknown"))
                .put("android_sdk", record.report.optJSONObject("host_device") == null
                        ? JSONObject.NULL
                        : record.report.optJSONObject("host_device").optInt("android_sdk", -1)));
        payload.put("workload", new JSONObject()
                .put("workload_id", record.workloadId)
                .put("workload_version", record.workloadVersion)
                .put("workload_config_sha256", JsonCanonicalizer.sha256(
                        record.report.optJSONObject("workload_config")))
                .put("primary_metric", record.primaryMetric));
        JSONObject candidate = record.report.optJSONObject("candidate");
        payload.put("candidate", new JSONObject()
                .put("zip_sha256", record.candidateSha256)
                .put("package_version", candidate == null ? JSONObject.NULL
                        : candidate.optString("packageVersion",
                                candidate.optString("driverVersion", ""))));
        payload.put("result", resultPayload(record));
        payload.put("validity", new JSONObject()
                .put("blocking_warnings", false)
                .put("failure_count", 0));

        JSONObject envelope = new JSONObject();
        envelope.put("public_dataset_schema_version",
                Phase4Contract.PUBLIC_DATASET_SCHEMA_VERSION);
        envelope.put("signature_algorithm", SIGNATURE_ALGORITHM);
        envelope.put("payload", payload);
        envelope.put("payload_sha256", JsonCanonicalizer.sha256(payload));
        envelope.put("privacy_note", Phase4Contract.LIMITATION);
        return envelope;
    }

    static boolean verify(JSONObject envelope) {
        try {
            if (envelope.optInt("public_dataset_schema_version", -1)
                    != Phase4Contract.PUBLIC_DATASET_SCHEMA_VERSION) return false;
            if (!SIGNATURE_ALGORITHM.equals(envelope.optString("signature_algorithm"))) return false;
            JSONObject payload = envelope.optJSONObject("payload");
            if (payload == null) return false;
            String signature = envelope.optString("payload_sha256", "");
            return SHA256.matcher(signature).matches()
                    && signature.equalsIgnoreCase(JsonCanonicalizer.sha256(payload));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JSONObject resultPayload(SuiteRecord record) throws Exception {
        JSONObject output = new JSONObject();
        output.put("verdict", record.verdict);
        output.put("classification", record.classification);
        output.put("ranking_score_percent", Double.isFinite(record.rankingScorePercent)
                ? record.rankingScorePercent : JSONObject.NULL);
        JSONObject analysis = record.report.optJSONObject("statistical_analysis");
        if (analysis != null) {
            output.put("paired_sample_count", analysis.optInt("paired_sample_count", 0));
            output.put("confidence_interval_95_percent",
                    analysis.has("confidence_interval_95_percent")
                            ? analysis.opt("confidence_interval_95_percent") : JSONObject.NULL);
            output.put("probability_of_superiority_percent",
                    analysis.has("probability_of_superiority_percent")
                            ? analysis.opt("probability_of_superiority_percent") : JSONObject.NULL);
        }
        JSONObject trace = record.report.optJSONObject("trace_replay");
        if (trace != null) {
            output.put("trace_analysis_version",
                    trace.optInt("trace_analysis_version", 0));
            output.put("trace_complete_pair_count",
                    trace.optInt("complete_pair_count", 0));
            output.put("trace_output_mismatch_count",
                    trace.optInt("output_mismatch_count", 0));
            output.put("trace_correctness_gate_passed",
                    trace.optBoolean("passed_correctness_gate", false));
        }
        JSONObject summary = record.report.optJSONObject("summary");
        if (summary != null) {
            output.put("system", summary.has("system") ? summary.opt("system") : JSONObject.NULL);
            output.put("candidate", summary.has("candidate")
                    ? summary.opt("candidate") : JSONObject.NULL);
        }
        return output;
    }

    private static void validatePublishable(SuiteRecord record) {
        if (record.blockingValidity) {
            throw new IllegalArgumentException("Suíte possui aviso ou falha bloqueante");
        }
        if (!SHA256.matcher(record.candidateSha256).matches()) {
            throw new IllegalArgumentException("Hash do ZIP candidato inválido");
        }
        if ("unknown".equals(record.publicHardwareKey)
                || record.socModel.isEmpty() || record.gpuModel.isEmpty()
                || "unknown".equalsIgnoreCase(record.socModel)
                || "unknown".equalsIgnoreCase(record.gpuModel)) {
            throw new IllegalArgumentException("SoC/GPU não identificados");
        }
        JSONArray failures = record.report.optJSONArray("failure_catalog");
        if (failures != null && failures.length() > 0) {
            throw new IllegalArgumentException("Suíte possui falhas registradas");
        }
        if (WorkloadContract.isPerformance(record.workloadId)) {
            JSONObject analysis = record.report.optJSONObject("statistical_analysis");
            if (analysis == null || analysis.optInt("paired_sample_count", 0)
                    < WorkloadContract.MINIMUM_PAIRED_SAMPLES) {
                throw new IllegalArgumentException("Amostra A/B insuficiente");
            }
        }
    }
}
