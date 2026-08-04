package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class QualificationPreflightTest {
    @Test
    public void safeEnvironmentIsEligible() throws Exception {
        JSONObject evaluation = QualificationPreflight.evaluate(new JSONObject()
                .put("battery_level_percent", 80.0)
                .put("battery_temperature_c", 32.0)
                .put("thermal_status", 0)
                .put("low_memory", false)
                .put("available_memory_bytes", 4_000_000_000L)
                .put("battery_status", 3));
        assertTrue(evaluation.getBoolean("eligible_to_start"));
        assertFalse(evaluation.getBoolean("ranking_blocked"));
    }

    @Test
    public void hotLowBatteryEnvironmentBlocksRanking() throws Exception {
        JSONObject evaluation = QualificationPreflight.evaluate(new JSONObject()
                .put("battery_level_percent", 10.0)
                .put("battery_temperature_c", 46.0)
                .put("thermal_status", 5)
                .put("low_memory", true)
                .put("available_memory_bytes", 100L));
        assertFalse(evaluation.getBoolean("eligible_to_start"));
        assertTrue(evaluation.getBoolean("ranking_blocked"));
        assertTrue(evaluation.getJSONArray("blockers").length() >= 4);
    }
}
