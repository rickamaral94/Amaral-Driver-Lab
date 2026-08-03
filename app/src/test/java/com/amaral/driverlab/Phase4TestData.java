package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

final class Phase4TestData {
    private Phase4TestData() {}

    static JSONObject report(String suiteId, String sha, String name, double score,
                             String classification, long finishedAtMs) throws Exception {
        JSONObject report = new JSONObject();
        report.put("schema_version", 5);
        report.put("suite_id", suiteId);
        report.put("app_version", "0.4.0-alpha1");
        report.put("finished_at_ms", finishedAtMs);
        report.put("mode", "ab_system_vs_candidate");
        report.put("workload_id", WorkloadContract.COMPUTE_ARITHMETIC_ID);
        report.put("workload_version", 1);
        report.put("workload_config", new JSONObject()
                .put("warmup_seconds", 3)
                .put("measure_seconds", 10)
                .put("primary_metric", WorkloadContract.COMPUTE_ARITHMETIC_METRIC));
        report.put("host_device", new JSONObject()
                .put("manufacturer", "AYN")
                .put("model", "Odin 2 Portal")
                .put("soc_manufacturer", "Qualcomm")
                .put("soc_model", "SM8550")
                .put("hardware", "qcom")
                .put("android_sdk", 35));
        JSONObject capabilities = new JSONObject().put("gpu_name", "Adreno 740");
        report.put("phases", new JSONArray()
                .put(new JSONObject()
                        .put("success", true)
                        .put("driver_mode", "system")
                        .put("round", 1)
                        .put("native", new JSONObject()
                                .put("success", true)
                                .put("throughput_gops", 100.0)
                                .put("capabilities", capabilities)))
                .put(new JSONObject()
                        .put("success", true)
                        .put("driver_mode", "custom")
                        .put("round", 1)
                        .put("native", new JSONObject()
                                .put("success", true)
                                .put("throughput_gops", 100.0 + score)
                                .put("capabilities", capabilities))));
        report.put("candidate", new JSONObject()
                .put("sha256", sha)
                .put("name", name)
                .put("packageVersion", "1.0"));
        report.put("summary", new JSONObject()
                .put("system", new JSONObject().put("median", 100.0))
                .put("candidate", new JSONObject().put("median", 100.0 + score))
                .put("failed_phases", 0));
        report.put("analysis_contract", new JSONObject().put("analysis_version", 1));
        report.put("statistical_analysis", new JSONObject()
                .put("available", true)
                .put("paired_sample_count", 5)
                .put("median_paired_improvement_percent", score)
                .put("classification", classification)
                .put("confidence_interval_95_percent", new JSONObject()
                        .put("lower", score - 1.0)
                        .put("upper", score + 1.0))
                .put("probability_of_superiority_percent", score >= 0.0 ? 100.0 : 0.0));
        report.put("validity_warnings", new JSONArray());
        report.put("failure_catalog", new JSONArray());
        report.put("verdict", verdict(classification));
        report.put("hardware_identity", HardwareIdentity.fromReport(report));
        return report;
    }

    static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static String verdict(String classification) {
        if ("candidate_better".equals(classification)) return "candidate_better_with_confidence";
        if ("candidate_worse".equals(classification)) return "candidate_worse_with_confidence";
        if ("practically_equivalent".equals(classification)) {
            return "practically_equivalent_with_confidence";
        }
        return "inconclusive_statistical_comparison";
    }
}
