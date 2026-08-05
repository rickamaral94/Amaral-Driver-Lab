package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SuiteHistoryTest {
    @Test
    public void rankingAggregatesSameHardwareWorkloadAndDriverHash() throws Exception {
        List<SuiteRecord> records = new ArrayList<>();
        records.add(SuiteRecord.parse(null, Phase4TestData.report("a1",
                Phase4TestData.sha('a'), "driver-a", 4.0, "candidate_better", 1L)));
        records.add(SuiteRecord.parse(null, Phase4TestData.report("a2",
                Phase4TestData.sha('a'), "driver-a", 8.0, "candidate_better", 2L)));
        records.add(SuiteRecord.parse(null, Phase4TestData.report("b1",
                Phase4TestData.sha('b'), "driver-b", 3.0, "candidate_better", 3L)));
        JSONObject ranking = SuiteHistory.ranking(records, records.get(0));
        assertTrue(ranking.getBoolean("available"));
        JSONArray entries = ranking.getJSONArray("entries");
        assertEquals(2, entries.length());
        assertEquals(Phase4TestData.sha('a'),
                entries.getJSONObject(0).getString("candidate_sha256"));
        assertEquals(6.0,
                entries.getJSONObject(0).getDouble("median_improvement_percent"), 0.0001);
    }
}
