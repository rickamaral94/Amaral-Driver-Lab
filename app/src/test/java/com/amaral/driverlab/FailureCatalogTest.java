package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FailureCatalogTest {
    @Test
    public void classifiesTimeoutDeviceLostAndValidationErrors() throws Exception {
        JSONArray phases = new JSONArray();
        phases.put(new JSONObject()
                .put("success", false)
                .put("phase", "system")
                .put("driver_mode", "system")
                .put("round", 1)
                .put("failure_type", "timeout")
                .put("error", "runner_timeout"));

        JSONObject nativeFailure = new JSONObject()
                .put("success", false)
                .put("failure_type", "vk_error_device_lost")
                .put("failure_stage", "queue_wait_idle")
                .put("vk_result", -4)
                .put("error", "device lost");
        phases.put(new JSONObject()
                .put("success", false)
                .put("phase", "candidate")
                .put("driver_mode", "custom")
                .put("round", 1)
                .put("native", nativeFailure));

        phases.put(new JSONObject()
                .put("success", true)
                .put("phase", "candidate")
                .put("driver_mode", "custom")
                .put("round", 2)
                .put("validation_errors", new JSONArray().put("VUID-example")));

        JSONArray failures = FailureCatalog.fromPhases(phases);
        Set<String> types = new HashSet<>();
        for (int index = 0; index < failures.length(); ++index) {
            types.add(failures.getJSONObject(index).getString("failure_type"));
        }

        assertEquals(3, failures.length());
        assertTrue(types.contains("timeout"));
        assertTrue(types.contains("vk_error_device_lost"));
        assertTrue(types.contains("validation_error"));
    }

    @Test
    public void renderMismatchIsPersistedAsItsOwnFailure() throws Exception {
        JSONArray failures = new JSONArray();
        JSONObject comparison = new JSONObject()
                .put("pixel_match_percent", 91.5)
                .put("divergent_block_count", 12);

        FailureCatalog.appendRenderMismatch(failures, 3, comparison);

        assertEquals("render_mismatch",
                failures.getJSONObject(0).getString("failure_type"));
        assertEquals(3, failures.getJSONObject(0).getInt("round"));
    }
}
