package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class VisualSceneAnalysisTest {
    @Test
    public void mismatchHasPriorityOverPerformance() throws Exception {
        JSONObject visual = baseVisual()
                .put("passed_correctness_gate", false)
                .put("checkpoint_mismatch_count", 1);
        assertEquals("failed_visual_scene_checkpoint_mismatch",
                VisualSceneAnalysis.verdictFor(visual, candidateBetter(), RunCoordinator.MODE_AB));
    }

    @Test
    public void nondeterminismHasPriorityOverMismatch() throws Exception {
        JSONObject visual = baseVisual()
                .put("passed_correctness_gate", false)
                .put("candidate_nondeterministic", true);
        assertEquals("failed_visual_scene_nondeterminism",
                VisualSceneAnalysis.verdictFor(visual, candidateBetter(), RunCoordinator.MODE_AB));
    }

    @Test
    public void passedGateDelegatesToStatisticalVerdict() throws Exception {
        JSONObject visual = baseVisual().put("passed_correctness_gate", true);
        assertEquals("candidate_better_with_confidence",
                VisualSceneAnalysis.verdictFor(visual, candidateBetter(), RunCoordinator.MODE_AB));
    }

    private static JSONObject baseVisual() throws Exception {
        return new JSONObject()
                .put("failed_phase_count", 0)
                .put("system_nondeterministic", false)
                .put("candidate_nondeterministic", false)
                .put("comparison_available", true);
    }

    private static JSONObject candidateBetter() throws Exception {
        return new JSONObject()
                .put("available", true)
                .put("classification", "candidate_better")
                .put("paired_sample_count", 5);
    }
}
