package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecommendedTurnipProfileTest {
    @Test
    public void currentRecommendedProfileIncludesGpuStressAndKeepsWeightsNormalized()
            throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(5);
        assertEquals(9, profile.getInt("step_count"));
        assertEquals(100, profile.getInt("performance_weight_total"));
        Set<String> stepIds = new HashSet<>();
        JSONArray steps = profile.getJSONArray("steps");
        for (int index = 0; index < steps.length(); index++) {
            JSONObject step = steps.getJSONObject(index);
            stepIds.add(step.getString("step_id"));
            assertTrue(step.getInt("cooldown_seconds") <= 2);
        }
        assertTrue(stepIds.contains("visual_gpu_stress"));
        JSONObject gpuStress = null;
        for (int index = 0; index < steps.length(); index++) {
            if ("visual_gpu_stress".equals(steps.getJSONObject(index).getString("step_id"))) {
                gpuStress = steps.getJSONObject(index);
                break;
            }
        }
        assertTrue(gpuStress != null);
        assertEquals(VisualSceneContract.GPU_STRESS_ID,
                gpuStress.getString("workload_id"));
        assertEquals(20, gpuStress.getInt("score_weight"));
        assertTrue(gpuStress.getBoolean("compatibility_gate"));
        JSONObject contract = Phase13ValidationContract.contractJson(5);
        assertEquals(9, contract.getInt("automated_orchestrated_steps"));
        assertEquals(9, contract.getInt("automated_logical_tests"));
        assertEquals(6, contract.getInt("minimum_valid_performance_categories"));
        assertTrue(contract.getJSONArray("kept_tests").toString()
                .contains("visual_gpu_stress_3d_720p"));
    }

    @Test
    public void legacyRecommendedV4RemainsImmutableAndFocused() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(4);
        assertEquals(8, profile.getInt("step_count"));
        assertEquals(100, profile.getInt("performance_weight_total"));
        Set<String> stepIds = new HashSet<>();
        JSONArray steps = profile.getJSONArray("steps");
        for (int index = 0; index < steps.length(); index++) {
            JSONObject step = steps.getJSONObject(index);
            stepIds.add(step.getString("step_id"));
            assertTrue(step.getInt("cooldown_seconds") <= 2);
        }
        assertTrue(stepIds.contains("correctness_pre"));
        assertTrue(stepIds.contains("visual_geometry"));
        assertTrue(stepIds.contains("visual_materials"));
        assertTrue(stepIds.contains("visual_postprocess"));
        assertTrue(stepIds.contains("shader_compile"));
        assertTrue(stepIds.contains("stable_scene"));
        assertTrue(stepIds.contains("trace_mixed"));
        assertTrue(stepIds.contains("correctness_post"));
        assertFalse(stepIds.contains("renderpass_tiling"));
        assertFalse(stepIds.contains("compute_arithmetic"));
        assertFalse(stepIds.contains("transfer"));
        assertFalse(stepIds.contains("trace_compute"));
        assertFalse(stepIds.contains("thermal_sustain"));
        assertFalse(stepIds.contains("deep_diagnostics"));
        assertFalse(stepIds.contains("short_soak"));
        JSONObject contract = Phase13ValidationContract.contractJson(4);
        assertEquals(8, contract.getInt("automated_orchestrated_steps"));
        assertEquals(8, contract.getInt("automated_logical_tests"));
        assertEquals(5, contract.getInt("minimum_valid_performance_categories"));
        assertFalse(contract.getJSONArray("kept_tests").toString()
                .contains("visual_gpu_stress_3d_720p"));
    }

    @Test
    public void fiveValidCategoriesAreEnoughForAQuickRecommendation() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(4);
        JSONArray completed = new JSONArray();
        int performanceSeen = 0;
        for (QualificationProfile.Step step : QualificationProfile.stepsForVersion(4)) {
            JSONObject report;
            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(step.workloadId)) {
                report = new JSONObject()
                        .put("verdict", "passed_render_correctness")
                        .put("failure_catalog", new JSONArray())
                        .put("render_correctness", new JSONObject().put("passed", true));
            } else {
                performanceSeen++;
                int samples = performanceSeen <= 5 ? 5 : 0;
                report = new JSONObject()
                        .put("verdict", "candidate_better_with_confidence")
                        .put("failure_catalog", new JSONArray())
                        .put("statistical_analysis", new JSONObject()
                                .put("paired_sample_count", samples)
                                .put("median_paired_improvement_percent", 7.0)
                                .put("classification", "candidate_better"));
                if (VisualSceneContract.isVisualScene(step.workloadId)) {
                    report.put("visual_scene", new JSONObject()
                            .put("passed_correctness_gate", true)
                            .put("minimum_pixel_match_percent", 100.0)
                            .put("checkpoint_mismatch_count", 0));
                }
                if (WorkloadContract.TRACE_REPLAY_ID.equals(step.workloadId)) {
                    report.put("trace_replay", new JSONObject()
                            .put("passed_correctness_gate", true));
                }
            }
            completed.put(new JSONObject().put("step_id", step.stepId)
                    .put("status", "completed").put("report", report));
        }
        JSONObject score = QualificationScore.evaluate(profile, completed,
                cleanPreflight(), cleanEnvironment());
        assertEquals(5, score.getInt("minimum_valid_performance_steps"));
        assertEquals(5, score.getInt("valid_performance_steps"));
        assertTrue(score.getBoolean("eligible_for_recommendation"));
    }

    @Test
    public void sixValidCategoriesAreRequiredForGpuStressProfile() throws Exception {
        JSONObject profile = QualificationProfile.definitionForVersion(5);
        JSONArray completed = completedPerformanceCategories(5, 6);
        JSONObject score = QualificationScore.evaluate(profile, completed,
                cleanPreflight(), cleanEnvironment());
        assertEquals(6, score.getInt("minimum_valid_performance_steps"));
        assertEquals(6, score.getInt("valid_performance_steps"));
        assertTrue(score.getBoolean("eligible_for_recommendation"));
    }

    private static JSONArray completedPerformanceCategories(int version, int validCategories)
            throws Exception {
        JSONArray completed = new JSONArray();
        int performanceSeen = 0;
        for (QualificationProfile.Step step : QualificationProfile.stepsForVersion(version)) {
            JSONObject report;
            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(step.workloadId)) {
                report = new JSONObject()
                        .put("verdict", "passed_render_correctness")
                        .put("failure_catalog", new JSONArray())
                        .put("render_correctness", new JSONObject().put("passed", true));
            } else {
                performanceSeen++;
                int samples = performanceSeen <= validCategories ? 5 : 0;
                report = new JSONObject()
                        .put("verdict", "candidate_better_with_confidence")
                        .put("failure_catalog", new JSONArray())
                        .put("statistical_analysis", new JSONObject()
                                .put("paired_sample_count", samples)
                                .put("median_paired_improvement_percent", 7.0)
                                .put("classification", "candidate_better"));
                if (VisualSceneContract.isVisualScene(step.workloadId)) {
                    report.put("visual_scene", new JSONObject()
                            .put("passed_correctness_gate", true)
                            .put("minimum_pixel_match_percent", 100.0)
                            .put("checkpoint_mismatch_count", 0));
                }
                if (WorkloadContract.TRACE_REPLAY_ID.equals(step.workloadId)) {
                    report.put("trace_replay", new JSONObject()
                            .put("passed_correctness_gate", true));
                }
            }
            completed.put(new JSONObject().put("step_id", step.stepId)
                    .put("status", "completed").put("report", report));
        }
        return completed;
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
