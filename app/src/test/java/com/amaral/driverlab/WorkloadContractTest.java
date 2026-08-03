package com.amaral.driverlab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class WorkloadContractTest {
    @Test
    public void legacyTransferMetricAndWorkloadRemainStable() {
        assertEquals("vulkan_transfer_stress", WorkloadContract.TRANSFER_ID);
        assertEquals(1, WorkloadContract.TRANSFER_VERSION);
        assertEquals("vulkan_transfer_stress_v1", WorkloadContract.TRANSFER_NATIVE_NAME);
        assertEquals("transfer_payload_gib_s", WorkloadContract.TRANSFER_METRIC);
    }

    @Test
    public void correctionSceneStartsANewIndependentSeries() {
        assertEquals("render_correctness_offscreen", WorkloadContract.RENDER_CORRECTNESS_ID);
        assertEquals(1, WorkloadContract.RENDER_CORRECTNESS_VERSION);
        assertEquals(2, WorkloadContract.RESULT_SCHEMA_VERSION);
    }
}
