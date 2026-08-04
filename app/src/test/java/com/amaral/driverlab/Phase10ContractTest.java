package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Phase10ContractTest {
    @Test
    public void deepDiagnosticProfileIsVersionedAndSeparate() throws Exception {
        JSONObject contract = Phase10Contract.contractJson();
        assertEquals(1, contract.getInt("deep_diagnostic_schema_version"));
        assertEquals(1, contract.getInt("deep_diagnostic_report_version"));
        assertEquals(1, contract.getInt("format_matrix_version"));
        assertEquals(1, contract.getInt("shader_corpus_version"));
        assertEquals(1, contract.getInt("synchronization_version"));
        assertEquals(5, contract.getJSONObject("profile").getJSONArray("modules").length());
        assertEquals(64, contract.getString("profile_sha256").length());
        assertTrue(contract.getBoolean("historical_series_separate"));
        assertFalse(contract.getBoolean("changes_existing_workload_definitions"));
        assertEquals(11, WorkloadContract.RESULT_SCHEMA_VERSION);
    }

    @Test
    public void profileHashIsDeterministic() throws Exception {
        assertEquals(Phase10Contract.profileSha256(), Phase10Contract.profileSha256());
    }
}
