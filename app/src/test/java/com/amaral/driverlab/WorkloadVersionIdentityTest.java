package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WorkloadVersionIdentityTest {
    @Test
    public void matchingDeclaredAndRuntimeVersionsAreConfirmed() throws Exception {
        JSONObject audit = WorkloadVersionIdentity.audit(report(2, 2, 2));
        assertEquals(WorkloadVersionIdentity.CONFIRMED, audit.getString("status"));
        assertTrue(audit.getBoolean("eligible_for_aggregation"));
    }

    @Test
    public void mismatchIsBlocking() throws Exception {
        JSONObject audit = WorkloadVersionIdentity.audit(report(1, 1, 2));
        assertEquals(WorkloadVersionIdentity.MISMATCH, audit.getString("status"));
        assertFalse(audit.getBoolean("eligible_for_aggregation"));
    }

    @Test
    public void missingRuntimeVersionIsUndeterminable() throws Exception {
        JSONObject report = report(1, 1, 1);
        report.getJSONArray("phases").getJSONObject(0)
                .getJSONObject("native").remove("workload_version");
        JSONObject audit = WorkloadVersionIdentity.audit(report);
        assertEquals(WorkloadVersionIdentity.UNDETERMINABLE,
                audit.getString("status"));
        assertFalse(audit.getBoolean("eligible_for_aggregation"));
    }

    private static JSONObject report(int suiteVersion, int phaseVersion,
                                     int runtimeVersion) throws Exception {
        return new JSONObject()
                .put("workload_version", suiteVersion)
                .put("phases", new JSONArray().put(new JSONObject()
                        .put("workload_version", phaseVersion)
                        .put("native", new JSONObject()
                                .put("workload_version", runtimeVersion))));
    }
}
