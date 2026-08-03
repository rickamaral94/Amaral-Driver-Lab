package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HardwareIdentityTest {
    @Test
    public void createsStableDeviceAndPublicHardwareKeys() throws Exception {
        JSONObject report = Phase4TestData.report("suite-a", Phase4TestData.sha('a'),
                "driver-a", 5.0, "candidate_better", 1L);
        JSONObject identity = HardwareIdentity.fromReport(report);
        assertEquals("SM8550", identity.getString("soc_model"));
        assertEquals("Adreno 740", identity.getString("gpu_model"));
        assertTrue(identity.getString("device_key").contains("odin-2-portal"));
        assertEquals("sm8550/adreno-740", identity.getString("public_hardware_key"));
        assertFalse(identity.toString().contains("fingerprint"));
    }
}
