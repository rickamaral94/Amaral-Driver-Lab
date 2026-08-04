package com.amaral.driverlab;

import com.amaral.driverlab.telemetry.TelemetryContract;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Phase9ContractTest {
    @Test
    public void contractIsLocalPrivateAndExcludedFromFullScore() throws Exception {
        JSONObject contract = Phase9Contract.contractJson();
        assertEquals(1, contract.getInt("telemetry_import_version"));
        assertEquals(TelemetryContract.TELEMETRY_SCHEMA_VERSION,
                contract.getJSONObject("sdk_contract")
                        .getInt("emulator_telemetry_schema_version"));
        assertTrue(contract.getBoolean("game_identity_sha256_only"));
        assertTrue(contract.getBoolean("source_session_immutable"));
        assertFalse(contract.getBoolean("automatic_upload"));
        assertFalse(contract.getBoolean("included_in_full_qualification_score"));
        assertEquals(13, WorkloadContract.RESULT_SCHEMA_VERSION);
    }
}
