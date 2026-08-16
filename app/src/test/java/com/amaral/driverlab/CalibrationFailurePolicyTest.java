package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalibrationFailurePolicyTest {
    @Test public void controlledVulkanFailureDoesNotPauseWholeQualification() throws Exception {
        assertFalse(RunCoordinator.calibrationRequiresQualificationAbort(new JSONObject()
                .put("failure_type", "visual_scene_failure")
                .put("failure_stage", "create_stress_scene_pipeline")));
    }

    @Test public void realRunnerDeathStillPausesSafely() throws Exception {
        assertTrue(RunCoordinator.calibrationRequiresQualificationAbort(new JSONObject()
                .put("failure_type", "crash")));
        assertTrue(RunCoordinator.calibrationRequiresQualificationAbort(new JSONObject()
                .put("failure_type", "timeout")));
    }
}
