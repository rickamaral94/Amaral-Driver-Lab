package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class QualificationProfileTest {
    @Test
    public void currentProfileV3IsImmutableAndWeightsSumToOneHundred() throws Exception {
        JSONObject profile = QualificationProfile.definition();
        assertTrue(QualificationProfile.verify(profile));
        assertEquals(Phase7Contract.PROFILE_ID, profile.getString("profile_id"));
        assertEquals(3, profile.getInt("profile_version"));
        assertEquals(15, profile.getInt("step_count"));
        assertEquals(20, profile.getInt("automated_logical_test_count"));
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
        assertEquals(new HashSet<>(VisualSceneContract.IDS), visualWorkloads);
        assertEquals(QualificationProfile.KIND_DEEP_DIAGNOSTICS,
                steps.getJSONObject(13).getString("step_kind"));
        assertEquals(QualificationProfile.KIND_SHORT_SOAK,
                steps.getJSONObject(14).getString("step_kind"));
    }

    @Test
    public void legacyProfilesRemainVerifiableAndSeparate() throws Exception {
        JSONObject v1 = QualificationProfile.definitionForVersion(1);
        JSONObject v2 = QualificationProfile.definitionForVersion(2);
        JSONObject v3 = QualificationProfile.definitionForVersion(3);
        assertTrue(QualificationProfile.verify(v1));
        assertTrue(QualificationProfile.verify(v2));
        assertTrue(QualificationProfile.verify(v3));
        assertEquals(10, v1.getInt("step_count"));
        assertEquals(13, v2.getInt("step_count"));
        assertEquals(15, v3.getInt("step_count"));
        assertNotEquals(v1.getString("profile_sha256"), v2.getString("profile_sha256"));
        assertNotEquals(v2.getString("profile_sha256"), v3.getString("profile_sha256"));
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
