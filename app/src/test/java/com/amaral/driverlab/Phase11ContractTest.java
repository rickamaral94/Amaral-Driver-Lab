package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Phase11ContractTest {
    @Test
    public void phaseElevenContractIsAdditiveAndExplicit() throws Exception {
        JSONObject contract = Phase11Contract.contractJson();
        assertEquals(3, contract.getInt("current_full_profile_version"));
        assertEquals(3, contract.getInt("qualification_schema_version"));
        assertEquals(15, contract.getInt("automated_orchestrated_steps"));
        assertEquals(20, contract.getInt("automated_logical_tests"));
        assertEquals(5, contract.getInt("full_soak_cycles"));
        assertEquals(128, contract.getInt("recommended_memory_mib"));
        assertTrue(contract.getBoolean("telemetry_attachment_optional"));
        assertFalse(contract.getBoolean("telemetry_absence_blocks_qualification"));
        assertTrue(contract.getBoolean("performance_and_compatibility_indices_separate"));
    }
}
