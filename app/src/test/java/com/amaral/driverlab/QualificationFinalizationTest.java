package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class QualificationFinalizationTest {
    @Test public void fallbackPreservesCompletedWorkloadsAndFailureStage() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("qualification_id", "qualification-1786849811716")
                .put("created_at_ms", 1786849811716L)
                .put("profile_sha256", "profile")
                .put("profile", new JSONObject()
                        .put("profile_id", Phase7Contract.PROFILE_ID)
                        .put("profile_version", Phase15DynamicRangeContract.PROFILE_VERSION))
                .put("driver", new JSONObject().put("name", "candidate"))
                .put("comparison_mode", "turnip_vs_turnip")
                .put("reference_driver", new JSONObject().put("name", "reference"))
                .put("preflight", new JSONObject())
                .put("execution", new JSONObject().put("steps", new JSONArray()
                        .put(step("completed"))
                        .put(step("completed"))));
        RuntimeException cause = new RuntimeException("report failed");
        JSONArray warnings = new JSONArray()
                .put(QualificationFinalization.warning("build_report", cause));

        JSONObject fallback = QualificationFinalization.fallbackReport(manifest,
                new JSONObject(), new JSONObject(), "build_report", cause, warnings);

        assertEquals("completed_with_finalization_warnings",
                fallback.getString("execution_state"));
        assertEquals(2, fallback.getInt("completed_step_count"));
        assertEquals("build_report", fallback.getString("finalization_failed_stage"));
        assertTrue(fallback.isNull("score"));
        assertEquals(1, fallback.getJSONArray("finalization_warnings").length());
    }

    @Test public void completedManifestMayPersistBeforeBundleExists() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("execution", new JSONObject()
                        .put("state", "running")
                        .put("steps", new JSONArray().put(step("completed"))));

        QualificationStore.finish(manifest, new JSONObject(), new JSONObject(),
                new JSONObject(), null);

        assertEquals("completed", manifest.getJSONObject("execution").getString("state"));
        assertTrue(manifest.isNull("diagnostic_bundle"));
    }

    private static JSONObject step(String status) throws Exception {
        return new JSONObject().put("status", status);
    }
}
