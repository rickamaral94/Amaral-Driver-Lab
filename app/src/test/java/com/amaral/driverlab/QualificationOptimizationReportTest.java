package com.amaral.driverlab;

import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class QualificationOptimizationReportTest {
    @Test
    public void issueMarkdownContainsRichDriverIdentityAndComparativeMetrics() throws Exception {
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
                        .put("lower_is_better", true)
                        .put("reference", new JSONObject()
                                .put("sample_count", 20)
                                .put("median", 10.0)
                                .put("mean", 10.4)
                                .put("p95", 12.0)
                                .put("p99", 13.0)
                                .put("coefficient_of_variation_percent", 2.0))
                        .put("candidate", new JSONObject()
                                .put("sample_count", 20)
                                .put("median", 9.0)
                                .put("mean", 9.2)
                                .put("p95", 11.0)
                                .put("p99", 12.0)
                                .put("coefficient_of_variation_percent", 1.5))
                        .put("absolute_difference", -1.0)
                        .put("mean_difference", -1.2)
                        .put("p95_difference", -1.0)
                        .put("p99_difference", -1.0)
                        .put("coefficient_of_variation_difference_pp", -0.5)
                        .put("candidate_to_reference_ratio", 0.9)
                        .put("candidate_improvement_percent", 11.11)
                        .put("classification", "candidate_better")
                        .put("winner", "candidate")
                        .put("paired_sample_count", 20)
                        .put("wins", 15)
                        .put("ties", 2)
                        .put("losses", 3)
                        .put("confidence_interval_95_percent", new JSONObject()
                                .put("lower", 8.0).put("upper", 13.0))))
                .put("loader_audit", new JSONArray());

        JSONObject manifest = new JSONObject()
                .put("comparison_mode", "turnip_vs_turnip")
                .put("driver", new JSONObject()
                        .put("name", "Amaral A740")
                        .put("packageVersion", "alpha6")
                        .put("sha256", "candidate-sha-256-full"))
                .put("reference_driver", new JSONObject()
                        .put("name", "Turnip R25")
                        .put("packageVersion", "25.0.0")
                        .put("sha256", "reference-sha-256-full"))
                .put("execution", new JSONObject()
                        .put("state", "completed")
                        .put("steps", new JSONArray()))
                .put("report", new JSONObject()
                        .put("hardware_identity", new JSONObject()
                                .put("model", "Odin 2 Portal")
                                .put("gpu_model", "Adreno 740"))
                        .put("optimization_report", optimization));

        String hardware = QualificationOptimizationReport.hardwareMarkdown(manifest);
        String identity = QualificationOptimizationReport.driverIdentityMarkdown(manifest);
        String summary = QualificationOptimizationReport.comparisonSummaryMarkdown(manifest);
        String metrics = QualificationOptimizationReport.metricsMarkdown(manifest);
        String details = QualificationOptimizationReport.detailedMetricsMarkdown(manifest);
        String title = GitHubIssuePublisher.qualificationIssueTitle(manifest);

        assertTrue(hardware.contains("AYN Odin 2 Portal"));
        assertTrue(hardware.contains("Snapdragon 8 Gen 2"));
        assertTrue(hardware.contains("Adreno 740"));

        assertTrue(identity.contains("DRIVER CANDIDATO"));
        assertTrue(identity.contains("Amaral A740"));
        assertTrue(identity.contains("candidate-sha-256-full"));
        assertTrue(identity.contains("DRIVER DE REFERÊNCIA"));
        assertTrue(identity.contains("Turnip R25"));
        assertTrue(identity.contains("reference-sha-256-full"));

        assertTrue(summary.contains("Placar geral da comparação"));
        assertTrue(summary.contains("Vitórias do DRIVER CANDIDATO"));
        assertTrue(summary.contains("Pares estatísticos válidos"));

        assertTrue(metrics.contains("Comparativo principal por etapa"));
        assertTrue(metrics.contains("DRIVER DE REFERÊNCIA"));
        assertTrue(metrics.contains("DRIVER CANDIDATO"));
        assertTrue(metrics.contains("Diferença absoluta"));
        assertTrue(metrics.contains("10.000"));
        assertTrue(metrics.contains("9.000"));
        assertTrue(metrics.contains("+11.11%"));

        assertTrue(details.contains("Estatística detalhada por etapa"));
        assertTrue(details.contains("Mediana"));
        assertTrue(details.contains("Média"));
        assertTrue(details.contains("P95"));
        assertTrue(details.contains("P99"));
        assertTrue(details.contains("Coeficiente de variação"));
        assertTrue(details.contains("Razão candidato/referência"));
        assertTrue(details.contains("Intervalo de confiança de 95%"));

        assertTrue(title.contains("CANDIDATO Amaral A740"));
        assertTrue(title.contains("REFERÊNCIA Turnip R25"));
    }

    @Test
    public void systemComparisonClearlyLabelsAndroidAsReference() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("comparison_mode", "system_vs_turnip")
                .put("driver", new JSONObject()
                        .put("name", "Amaral A740")
                        .put("packageVersion", "alpha6")
                        .put("sha256", "candidate-sha"))
                .put("execution", new JSONObject().put("state", "completed"))
                .put("report", new JSONObject()
                        .put("hardware_identity", new JSONObject()
                                .put("model", "Odin 2 Portal"))
                        .put("optimization_report", new JSONObject()
                                .put("hardware_target", new JSONObject())
                                .put("metrics", new JSONArray())));

        String identity = QualificationOptimizationReport.driverIdentityMarkdown(manifest);
        String title = GitHubIssuePublisher.qualificationIssueTitle(manifest);
        assertTrue(identity.contains("DRIVER DE REFERÊNCIA"));
        assertTrue(identity.contains("Driver do sistema Android"));
        assertTrue(identity.contains("system / system"));
        assertTrue(title.contains("REFERÊNCIA Sistema Android"));
    }
}
