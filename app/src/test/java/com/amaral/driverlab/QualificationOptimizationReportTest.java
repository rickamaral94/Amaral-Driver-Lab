package com.amaral.driverlab;

import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class QualificationOptimizationReportTest {
    @Test
    public void issueMarkdownContainsHardwareAndAbsoluteMetrics() throws Exception {
        JSONObject optimization = new JSONObject()
                .put("hardware_target", new JSONObject()
                        .put("console", "AYN Odin 2 Portal")
                        .put("manufacturer", "AYN")
                        .put("model", "Odin 2 Portal")
                        .put("soc_manufacturer", "Qualcomm")
                        .put("soc_model", "Snapdragon 8 Gen 2")
                        .put("gpu_model", "Adreno 740")
                        .put("android_release", "13")
                        .put("android_sdk", 33)
                        .put("device", "odin2portal")
                        .put("product", "odin2portal")
                        .put("board", "kalama")
                        .put("public_hardware_key", "snapdragon-8-gen-2/adreno-740"))
                .put("metrics", new JSONArray().put(new JSONObject()
                        .put("step_id", "stable_scene")
                        .put("label", "Stable scene frametime")
                        .put("kind", "performance")
                        .put("metric", "gpu_frame_time_ms")
                        .put("unit", "ms")
                        .put("reference", new JSONObject()
                                .put("median", 10.0).put("p95", 12.0)
                                .put("coefficient_of_variation_percent", 2.0))
                        .put("candidate", new JSONObject()
                                .put("median", 9.0).put("p95", 11.0)
                                .put("coefficient_of_variation_percent", 1.5))
                        .put("candidate_improvement_percent", 11.11)
                        .put("classification", "candidate_better")))
                .put("loader_audit", new JSONArray());
        JSONObject manifest = new JSONObject().put("report",
                new JSONObject().put("optimization_report", optimization));

        String hardware = QualificationOptimizationReport.hardwareMarkdown(manifest);
        String metrics = QualificationOptimizationReport.metricsMarkdown(manifest);
        assertTrue(hardware.contains("AYN Odin 2 Portal"));
        assertTrue(hardware.contains("Snapdragon 8 Gen 2"));
        assertTrue(hardware.contains("Adreno 740"));
        assertTrue(metrics.contains("10.000"));
        assertTrue(metrics.contains("9.000"));
        assertTrue(metrics.contains("+11.11%"));
        assertTrue(metrics.contains("candidate_better"));
    }
}
