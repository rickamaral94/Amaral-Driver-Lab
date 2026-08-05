package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CapabilityDiffTest {
    @Test
    public void reportsGainedAndLostCapabilities() throws Exception {
        JSONObject system = new JSONObject();
        system.put("driver_id", 3);
        system.put("driver_name", "system");
        system.put("extensions", new JSONArray().put("EXT_alpha").put("EXT_shared"));
        system.put("features", new JSONObject()
                .put("geometryShader", true)
                .put("shaderInt16", false));
        system.put("limits", new JSONObject()
                .put("max_bound_descriptor_sets", 8)
                .put("max_image_dimension_2d", 16384));

        JSONObject candidate = new JSONObject();
        candidate.put("driver_id", 18);
        candidate.put("driver_name", "turnip");
        candidate.put("extensions", new JSONArray().put("EXT_shared").put("EXT_beta"));
        candidate.put("features", new JSONObject()
                .put("geometryShader", false)
                .put("shaderInt16", true));
        candidate.put("limits", new JSONObject()
                .put("max_bound_descriptor_sets", 16)
                .put("max_image_dimension_2d", 8192));

        JSONObject diff = CapabilityDiff.compare(system, candidate);

        assertEquals("EXT_beta", diff.getJSONArray("extensions_gained").getString(0));
        assertEquals("EXT_alpha", diff.getJSONArray("extensions_lost").getString(0));
        assertEquals("shaderInt16", diff.getJSONArray("features_gained").getString(0));
        assertEquals("geometryShader", diff.getJSONArray("features_lost").getString(0));
        assertEquals("max_bound_descriptor_sets",
                diff.getJSONArray("limits_increased").getJSONObject(0).getString("name"));
        assertEquals("max_image_dimension_2d",
                diff.getJSONArray("limits_decreased").getJSONObject(0).getString("name"));
        assertTrue(diff.getBoolean("driver_identity_changed"));
    }
}
