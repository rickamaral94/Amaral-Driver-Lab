package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RegressionBisectTest {
    @Test
    public void identifiesAdjacentGoodToBadBoundary() throws Exception {
        List<SuiteRecord> records = new ArrayList<>();
        records.add(SuiteRecord.parse(null, Phase4TestData.report("good",
                Phase4TestData.sha('a'), "mesa-1", 2.0,
                "practically_equivalent", 1L)));
        records.add(SuiteRecord.parse(null, Phase4TestData.report("bad",
                Phase4TestData.sha('b'), "mesa-2", -6.0,
                "candidate_worse", 2L)));
        JSONObject result = RegressionBisect.analyze(records);
        assertTrue(result.getBoolean("available"));
        assertEquals("regression_boundary_identified", result.getString("status"));
        assertEquals(0, result.getInt("last_known_good_index"));
        assertEquals(1, result.getInt("first_known_bad_index"));
    }

    @Test
    public void returnsMidpointWhenUntestedGapExists() throws Exception {
        List<SuiteRecord> records = new ArrayList<>();
        records.add(SuiteRecord.parse(null, Phase4TestData.report("good",
                Phase4TestData.sha('a'), "mesa-1", 5.0,
                "candidate_better", 1L)));
        records.add(SuiteRecord.parse(null, Phase4TestData.report("unknown",
                Phase4TestData.sha('b'), "mesa-2", 0.0,
                "inconclusive", 2L)));
        records.add(SuiteRecord.parse(null, Phase4TestData.report("bad",
                Phase4TestData.sha('c'), "mesa-3", -7.0,
                "candidate_worse", 3L)));
        JSONObject result = RegressionBisect.analyze(records);
        assertEquals("probe_required", result.getString("status"));
        assertEquals(1, result.getInt("next_probe_index"));
    }

    @Test
    public void undeterminableWorkloadVersionCannotDefineRegressionBoundary() throws Exception {
        SuiteRecord good = SuiteRecord.parse(null, Phase4TestData.report("good",
                Phase4TestData.sha('a'), "mesa-1", 4.0,
                "candidate_better", 1L));
        JSONObject badReport = Phase4TestData.report("bad", Phase4TestData.sha('b'),
                "mesa-2", -8.0, "candidate_worse", 2L);
        badReport.remove("workload_version_audit");
        badReport.getJSONArray("phases").getJSONObject(0)
                .getJSONObject("native").remove("workload_version");
        SuiteRecord undeterminable = SuiteRecord.parse(null, badReport);

        JSONObject result = RegressionBisect.analyze(
                java.util.Arrays.asList(good, undeterminable));

        assertFalse(result.getBoolean("available"));
        assertEquals("no_known_bad_after_good", result.getString("reason"));
    }
}
