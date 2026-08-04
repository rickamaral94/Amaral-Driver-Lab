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
    public void currentProfileV2IsImmutableAndWeightsSumToOneHundred() throws Exception {
        JSONObject profile = QualificationProfile.definition();
        assertTrue(QualificationProfile.verify(profile));
        assertEquals(Phase7Contract.PROFILE_ID, profile.getString("profile_id"));
        assertEquals(2, profile.getInt("profile_version"));
        assertEquals(13, profile.getInt("step_count"));
        assertEquals(100, profile.getInt("performance_weight_total"));
        assertEquals(64, profile.getString("profile_sha256").length());

        Set<String> ids = new HashSet<>();
        Set<String> visualWorkloads = new HashSet<>();
        JSONArray steps = profile.getJSONArray("steps");
        for (int index = 0; index < steps.length(); ++index) {
            JSONObject step = steps.getJSONObject(index);
            assertTrue(ids.add(step.getString("step_id")));
            if (VisualSceneContract.isVisualScene(step.getString("workload_id"))) {
                visualWorkloads.add(step.getString("workload_id"));
                assertTrue(step.getBoolean("compatibility_gate"));
            }
        }
        assertEquals(new HashSet<>(VisualSceneContract.IDS), visualWorkloads);
    }

    @Test
    public void legacyProfileV1RemainsVerifiableAndSeparate() throws Exception {
        JSONObject legacy = QualificationProfile.definitionForVersion(1);
        JSONObject current = QualificationProfile.definitionForVersion(2);
        assertTrue(QualificationProfile.verify(legacy));
        assertTrue(QualificationProfile.verify(current));
        assertEquals(10, legacy.getInt("step_count"));
        assertEquals(13, current.getInt("step_count"));
        assertNotEquals(legacy.getString("profile_sha256"),
                current.getString("profile_sha256"));
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
