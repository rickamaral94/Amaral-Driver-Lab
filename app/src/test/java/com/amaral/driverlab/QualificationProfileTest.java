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
    public void profileIsImmutableVersionedAndWeightsSumToOneHundred() throws Exception {
        JSONObject profile = QualificationProfile.definition();
        assertTrue(QualificationProfile.verify(profile));
        assertEquals(Phase7Contract.PROFILE_ID, profile.getString("profile_id"));
        assertEquals(1, profile.getInt("profile_version"));
        assertEquals(10, profile.getInt("step_count"));
        assertEquals(100, profile.getInt("performance_weight_total"));
        assertEquals(64, profile.getString("profile_sha256").length());

        Set<String> ids = new HashSet<>();
        JSONArray steps = profile.getJSONArray("steps");
        for (int index = 0; index < steps.length(); ++index) {
            assertTrue(ids.add(steps.getJSONObject(index).getString("step_id")));
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
