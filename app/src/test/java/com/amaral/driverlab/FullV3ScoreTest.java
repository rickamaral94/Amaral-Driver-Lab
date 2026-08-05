package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FullV3ScoreTest {
    @Test
    public void fullV3SeparatesPerformanceAndCompatibility() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(3);
        JSONObject score = QualificationScore.evaluate(profile, completed(false, false),
                cleanPreflight(), cleanEnvironment());
        assertEquals(3, score.getInt("qualification_score_version"));
        assertTrue(score.getBoolean("eligible_for_recommendation"));
        assertEquals("candidate", score.getString("winner"));
        assertEquals(100.0, score.getDouble("compatibility_index"), 0.0);
        assertTrue(score.getDouble("performance_index") > 50.0);
        assertEquals(13, score.getInt("valid_performance_steps"));
    }

    @Test
    public void formatRegressionBlocksOtherwiseFastDriver() throws Exception {
        JSONObject score = QualificationScore.evaluate(
                QualificationProfile.definitionForVersion(3), completed(true, false),
                cleanPreflight(), cleanEnvironment());
        assertFalse(score.getBoolean("eligible_for_recommendation"));
        assertTrue(score.getJSONArray("gate_reasons").toString()
                .contains("format_capability_regression"));
    }

    @Test
    public void failedShortSoakBlocksRecommendation() throws Exception {
        JSONObject score = QualificationScore.evaluate(
                QualificationProfile.definitionForVersion(3), completed(false, true),
                cleanPreflight(), cleanEnvironment());
        assertFalse(score.getBoolean("eligible_for_recommendation"));
        assertTrue(score.getJSONArray("gate_reasons").toString()
                .contains("candidate_soak_failed"));
    }

    private static JSONArray completed(boolean formatRegression, boolean soakFailure)
            throws Exception {
        JSONArray output = new JSONArray();
        for (QualificationProfile.Step step : QualificationProfile.stepsForVersion(3)) {
            JSONObject report;
            if (QualificationProfile.KIND_DEEP_DIAGNOSTICS.equals(step.kind)) {
                report = deepReport(formatRegression);
            } else if (QualificationProfile.KIND_SHORT_SOAK.equals(step.kind)) {
                report = soakReport(soakFailure);
            } else if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(step.workloadId)) {
                report = correctionReport();
            } else {
                report = suiteReport(step);
            }
            output.put(new JSONObject().put("step_id", step.stepId)
                    .put("status", "completed").put("report", report));
        }
        return output;
    }

    private static JSONObject deepReport(boolean regression) throws Exception {
        JSONArray blockers = new JSONArray();
        if (regression) blockers.put("format_capability_regression");
        JSONObject comparison = new JSONObject()
                .put("comparable", !regression)
                .put("blockers", blockers)
                .put("warnings", new JSONArray())
                .put("format_matrix", new JSONObject()
                        .put("regression_count", regression ? 1 : 0))
                .put("shader_pipeline_corpus", new JSONObject()
                        .put("system_successful_cases", 6)
                        .put("candidate_successful_cases", 6)
                        .put("cold_pipeline_change_percent", 8.0)
                        .put("warm_pipeline_change_percent", 10.0))
                .put("memory_pressure", new JSONObject()
                        .put("direction", "equivalent_or_mixed"))
                .put("synchronization", new JSONObject()
                        .put("candidate_passed", true)
                        .put("candidate_vs_system_fence_p99_percent", 7.0))
                .put("reliability_probe", new JSONObject()
                        .put("candidate_completed_cycles", 3));
        return new JSONObject().put("report_id", "phase10-full")
                .put("mode", "full").put("comparison", comparison);
    }

    private static JSONObject soakReport(boolean failure) throws Exception {
        JSONArray blockers = failure
                ? new JSONArray().put("candidate_soak_failed") : new JSONArray();
        return new JSONObject().put("report_id", "phase10-soak")
                .put("mode", "soak")
                .put("comparison", new JSONObject()
                        .put("comparable", !failure)
                        .put("blockers", blockers)
                        .put("warnings", new JSONArray())
                        .put("soak", new JSONObject()
                                .put("candidate_completed_cycles", failure ? 2 : 5)
                                .put("candidate_vs_system_p99_percent", 5.0)));
    }

    private static JSONObject suiteReport(QualificationProfile.Step step) throws Exception {
        JSONObject report = new JSONObject()
                .put("verdict", "candidate_better_with_confidence")
                .put("failure_catalog", new JSONArray())
                .put("statistical_analysis", new JSONObject()
                        .put("paired_sample_count", 5)
                        .put("median_paired_improvement_percent", 8.0)
                        .put("classification", "candidate_better"));
        if (WorkloadContract.TRACE_REPLAY_ID.equals(step.workloadId)) {
            report.put("trace_replay", new JSONObject()
                    .put("passed_correctness_gate", true));
        }
        if (VisualSceneContract.isVisualScene(step.workloadId)) {
            report.put("visual_scene", new JSONObject()
                    .put("passed_correctness_gate", true)
                    .put("minimum_pixel_match_percent", 100.0)
                    .put("checkpoint_mismatch_count", 0));
        }
        return report;
    }

    private static JSONObject correctionReport() throws Exception {
        return new JSONObject().put("verdict", "passed_render_correctness")
                .put("failure_catalog", new JSONArray())
                .put("render_correctness", new JSONObject().put("passed", true));
    }

    private static JSONObject cleanPreflight() throws Exception {
        return new JSONObject().put("evaluation", new JSONObject()
                .put("warnings", new JSONArray()).put("blockers", new JSONArray()));
    }

    private static JSONObject cleanEnvironment() throws Exception {
        return new JSONObject().put("warnings", new JSONArray())
                .put("blockers", new JSONArray());
    }
}
