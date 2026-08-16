package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class QualificationProfileTest {
    @Test
    public void profileSevenStaysReadableAndPinsTheOldEmulatorFrameVersion() throws Exception {
        JSONObject seven = QualificationProfile.definitionForVersion(
                Phase15DynamicRangeContract.EMULATOR_V7_PROFILE_VERSION);
        assertTrue(QualificationProfile.verify(seven));
        assertEquals(7, seven.getInt("profile_version"));

        // v7 must keep declaring emulator_frame_pattern at 2. Runs recorded under
        // v7 used the pooled-median metric; letting v7 drift to 3 would make two
        // incomparable measurements share one profile identity.
        JSONArray steps = seven.getJSONArray("steps");
        int checked = 0;
        for (int index = 0; index < steps.length(); ++index) {
            JSONObject step = steps.getJSONObject(index);
            if (WorkloadContract.EMULATOR_FRAME_ID.equals(step.getString("workload_id"))) {
                assertEquals(2, step.getInt("workload_version"));
                checked++;
            }
        }
        assertEquals(1, checked);

        JSONObject eight = QualificationProfile.definition();
        assertFalse("v7 and v8 must not share an identity",
                seven.getString("profile_sha256").equals(eight.getString("profile_sha256")));
    }

    @Test
    public void currentRecommendedProfileIsFocusedAndWeightsSumToOneHundred() throws Exception {
        JSONObject profile = QualificationProfile.definition();
        assertTrue(QualificationProfile.verify(profile));
        assertEquals(Phase7Contract.PROFILE_ID, profile.getString("profile_id"));
        assertEquals(8, profile.getInt("profile_version"));
        assertEquals(9, profile.getInt("step_count"));
        assertEquals(9, profile.getInt("automated_logical_test_count"));
        assertEquals(1, profile.getInt("optional_evidence_slot_count"));
        assertEquals(100, profile.getInt("performance_weight_total"));
        assertEquals(64, profile.getString("profile_sha256").length());

        Set<String> ids = new HashSet<>();
        Set<String> visualWorkloads = new HashSet<>();
        JSONArray steps = profile.getJSONArray("steps");
        for (int index = 0; index < steps.length(); ++index) {
            JSONObject step = steps.getJSONObject(index);
            assertTrue(ids.add(step.getString("step_id")));
            if (QualificationProfile.KIND_SUITE.equals(step.getString("step_kind"))
                    && !step.isNull("workload_id")
                    && VisualSceneContract.isVisualScene(step.getString("workload_id"))) {
                visualWorkloads.add(step.getString("workload_id"));
                assertTrue(step.getBoolean("compatibility_gate"));
            }
        }
        assertEquals(new HashSet<>(VisualSceneContract.RECOMMENDED_QUALIFICATION_IDS),
                visualWorkloads);
        for (int index = 0; index < steps.length(); index++) {
            assertEquals(QualificationProfile.KIND_SUITE,
                    steps.getJSONObject(index).getString("step_kind"));
        }
    }

    @Test
    public void legacyProfilesRemainVerifiableAndSeparate() throws Exception {
        JSONObject v1 = QualificationProfile.definitionForVersion(1);
        JSONObject v2 = QualificationProfile.definitionForVersion(2);
        JSONObject v3 = QualificationProfile.definitionForVersion(3);
        JSONObject v4 = QualificationProfile.definitionForVersion(4);
        JSONObject v5 = QualificationProfile.definitionForVersion(5);
        JSONObject v6 = QualificationProfile.definitionForVersion(6);
        assertTrue(QualificationProfile.verify(v1));
        assertTrue(QualificationProfile.verify(v2));
        assertTrue(QualificationProfile.verify(v3));
        assertTrue(QualificationProfile.verify(v4));
        assertTrue(QualificationProfile.verify(v5));
        assertTrue(QualificationProfile.verify(v6));
        assertEquals(10, v1.getInt("step_count"));
        assertEquals(13, v2.getInt("step_count"));
        assertEquals(15, v3.getInt("step_count"));
        assertEquals(8, v4.getInt("step_count"));
        assertEquals(9, v5.getInt("step_count"));
        assertEquals(9, v6.getInt("step_count"));
        assertNotEquals(v1.getString("profile_sha256"), v2.getString("profile_sha256"));
        assertNotEquals(v2.getString("profile_sha256"), v3.getString("profile_sha256"));
        assertNotEquals(v3.getString("profile_sha256"), v4.getString("profile_sha256"));
        assertNotEquals(v4.getString("profile_sha256"), v5.getString("profile_sha256"));
        assertNotEquals(v5.getString("profile_sha256"), v6.getString("profile_sha256"));

        for (int version = 1; version <= 5; ++version) {
            JSONArray historical = QualificationProfile.definitionForVersion(version)
                    .getJSONArray("steps");
            for (int index = 0; index < historical.length(); ++index) {
                JSONObject step = historical.getJSONObject(index);
                if (QualificationProfile.KIND_SUITE.equals(step.getString("step_kind"))) {
                    assertEquals(1, step.getInt("workload_version"));
                }
            }
        }
    }

    @Test
    public void changingAnyProtocolValueInvalidatesProfileHash() throws Exception {
        JSONObject profile = QualificationProfile.definition();
        String original = profile.getString("profile_sha256");
        profile.getJSONArray("steps").getJSONObject(2).put("measure_seconds", 11);
        assertNotEquals(original,
                JsonCanonicalizer.sha256WithoutKey(profile, "profile_sha256"));
        assertTrue(!QualificationProfile.verify(profile));
    }
}
