package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class QualificationReportComparisonTest {
    @Test
    public void turnipReferenceIsNamedInHumanSummary() throws Exception {
        JSONObject candidate = driver("Candidate", "candidate-sha");
        JSONObject reference = driver("Reference", "reference-sha");
        JSONObject score = new JSONObject()
                .put("recommendation", "system_recommended_over_candidate")
                .put("profile_version", 3)
                .put("winner", "system")
                .put("confidence", "high")
                .put("performance_index", 75)
                .put("compatibility_index", 80)
                .put("overall_index", 77)
                .put("weighted_improvement_percent", -4.5);

        JSONObject summary = QualificationReport.humanSummary(
                candidate, reference, "turnip_vs_turnip", score);

        assertEquals("turnip_vs_turnip", summary.getString("comparison_mode"));
        assertEquals("Driver de referência recomendado", summary.getString("headline"));
        assertTrue(summary.getString("detail").contains("Reference"));
    }

    @Test
    public void stockBaselineRemainsCompatible() throws Exception {
        JSONObject score = new JSONObject()
                .put("recommendation", "candidate_recommended_over_system")
                .put("profile_version", 3)
                .put("winner", "candidate")
                .put("confidence", "medium")
                .put("weighted_improvement_percent", 5.0);
        JSONObject summary = QualificationReport.humanSummary(driver("Candidate", "sha"), score);
        assertEquals("system_vs_turnip", summary.getString("comparison_mode"));
        assertTrue(summary.getString("detail").contains("driver do sistema"));
    }

    private static JSONObject driver(String name, String sha) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("packageVersion", "v1")
                .put("sha256", sha);
    }
}
