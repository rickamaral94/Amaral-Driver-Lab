package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SuiteDiffTest {
    @Test
    public void comparesOnlyHistoricallyCompatibleSuites() throws Exception {
        SuiteRecord left = SuiteRecord.parse(null, Phase4TestData.report("a",
                Phase4TestData.sha('a'), "driver-a", 4.0, "candidate_better", 1L));
        SuiteRecord right = SuiteRecord.parse(null, Phase4TestData.report("b",
                Phase4TestData.sha('b'), "driver-b", 7.5, "candidate_better", 2L));
        JSONObject diff = SuiteDiff.compare(left, right);
        assertTrue(diff.getBoolean("historically_comparable"));
        assertEquals(3.5, diff.getJSONObject("metric_diff")
                .getDouble("right_minus_left_percentage_points"), 0.0001);
        assertTrue(diff.getBoolean("candidate_changed"));
    }

    @Test
    public void refusesNumericDeltaAcrossDifferentWorkloadConfig() throws Exception {
        JSONObject changed = Phase4TestData.report("b", Phase4TestData.sha('b'),
                "driver-b", 7.5, "candidate_better", 2L);
        changed.getJSONObject("workload_config").put("measure_seconds", 20);
        SuiteRecord left = SuiteRecord.parse(null, Phase4TestData.report("a",
                Phase4TestData.sha('a'), "driver-a", 4.0, "candidate_better", 1L));
        SuiteRecord right = SuiteRecord.parse(null, changed);
        JSONObject diff = SuiteDiff.compare(left, right);
        assertFalse(diff.getBoolean("historically_comparable"));
        assertTrue(diff.getJSONObject("metric_diff")
                .isNull("right_minus_left_percentage_points"));
    }
}
