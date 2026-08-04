package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class QualificationScoreTest {
    @Test
    public void compatibleFasterDriverIsRecommendedInV2() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(2);
        JSONObject score = QualificationScore.evaluate(profile,
                completedForProfile(profile, 8.0, false, false), preflight(), environment());
        assertTrue(score.getBoolean("eligible_for_recommendation"));
        assertEquals("candidate", score.getString("winner"));
        assertEquals("candidate_recommended_over_system",
                score.getString("recommendation"));
        assertTrue(score.getDouble("overall_index") > 50.0);
        assertEquals(11, score.getInt("valid_performance_steps"));
        assertEquals(2, score.getInt("qualification_score_version"));
    }

    @Test
    public void finalCorrectionFailureBlocksEvenFastDriver() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(2);
        JSONObject score = QualificationScore.evaluate(profile,
                completedForProfile(profile, 15.0, true, false), preflight(), environment());
        assertTrue(!score.getBoolean("eligible_for_recommendation"));
        assertEquals("none", score.getString("winner"));
        assertEquals("not_recommended_incompatible_or_invalid",
                score.getString("recommendation"));
        assertTrue(score.getJSONArray("gate_reasons").length() > 0);
    }

    @Test
    public void visualCheckpointMismatchBlocksFastDriver() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(2);
        JSONObject score = QualificationScore.evaluate(profile,
                completedForProfile(profile, 18.0, false, true), preflight(), environment());
        assertTrue(!score.getBoolean("eligible_for_recommendation"));
        assertEquals("none", score.getString("winner"));
        assertTrue(score.getJSONArray("gate_reasons").toString()
                .contains("visual_scene_gate_failed:visual_materials"));
    }

    @Test
    public void legacyV1ScoreStillUsesVersionOneContract() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(1);
        JSONObject score = QualificationScore.evaluate(profile,
                completedForProfile(profile, 5.0, false, false), preflight(), environment());
        assertEquals(1, score.getInt("qualification_score_version"));
        assertEquals(1, score.getInt("profile_version"));
        assertTrue(score.getBoolean("eligible_for_recommendation"));
    }

    private static JSONArray completedForProfile(JSONObject profile, double improvement,
                                                 boolean failFinalCorrection,
                                                 boolean failVisualMaterials) throws Exception {
        JSONArray output = new JSONArray();
        int version = profile.getInt("profile_version");
        for (QualificationProfile.Step step : QualificationProfile.stepsForVersion(version)) {
            JSONObject report;
            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(step.workloadId)) {
                boolean pass = !(failFinalCorrection && "correctness_post".equals(step.stepId));
                report = correctionReport(pass);
            } else {
                boolean visualPass = !(failVisualMaterials
                        && "visual_materials".equals(step.stepId));
                report = performanceReport(step, improvement, visualPass);
            }
            output.put(new JSONObject()
                    .put("step_id", step.stepId)
                    .put("status", "completed")
                    .put("report", report));
        }
        return output;
    }

    private static JSONObject performanceReport(QualificationProfile.Step step,
                                                double improvement,
                                                boolean visualPass) throws Exception {
        JSONObject report = new JSONObject()
                .put("schema_version", 9)
                .put("suite_id", "suite-" + step.stepId)
                .put("workload_id", step.workloadId)
                .put("workload_version", 1)
                .put("workload_config", new JSONObject().put("step", step.stepId))
                .put("candidate", new JSONObject()
                        .put("sha256", Phase4TestData.sha('a'))
                        .put("name", "A"))
                .put("failure_catalog", new JSONArray())
                .put("validity_warnings", new JSONArray())
                .put("statistical_analysis", new JSONObject()
                        .put("available", true)
                        .put("paired_sample_count", 5)
                        .put("median_paired_improvement_percent", improvement)
                        .put("classification", "candidate_better"))
                .put("verdict", "candidate_better_with_confidence");
        if (WorkloadContract.TRACE_REPLAY_ID.equals(step.workloadId)) {
            report.put("trace_replay", new JSONObject()
                    .put("passed_correctness_gate", true));
        }
        if (VisualSceneContract.isVisualScene(step.workloadId)) {
            report.put("visual_scene", new JSONObject()
                    .put("passed_correctness_gate", visualPass)
                    .put("comparison_available", true)
                    .put("minimum_pixel_match_percent", visualPass ? 100.0 : 97.0)
                    .put("checkpoint_mismatch_count", visualPass ? 0 : 1));
        }
        return report;
    }

    private static JSONObject correctionReport(boolean pass) throws Exception {
        return new JSONObject()
                .put("schema_version", 9)
                .put("suite_id", "suite-correction")
                .put("workload_id", WorkloadContract.RENDER_CORRECTNESS_ID)
                .put("workload_version", 1)
                .put("workload_config", new JSONObject())
                .put("candidate", new JSONObject()
                        .put("sha256", Phase4TestData.sha('a'))
                        .put("name", "A"))
                .put("failure_catalog", pass ? new JSONArray()
                        : new JSONArray().put(new JSONObject().put("failure_type", "render_mismatch")))
                .put("validity_warnings", new JSONArray())
                .put("render_correctness", new JSONObject()
                        .put("passed", pass)
                        .put("comparison_available", true))
                .put("verdict", pass ? "passed_render_correctness"
                        : "failed_render_correctness");
    }

    private static JSONObject preflight() throws Exception {
        return new JSONObject().put("evaluation", new JSONObject()
                .put("warnings", new JSONArray())
                .put("blockers", new JSONArray()));
    }

    private static JSONObject environment() throws Exception {
        return new JSONObject()
                .put("warnings", new JSONArray())
                .put("blockers", new JSONArray());
    }
}
