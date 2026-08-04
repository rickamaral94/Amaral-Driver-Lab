package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TraceReplayContractTest {
    @Test
    public void traceFormatAndWorkloadAreVersionedIndependently() throws Exception {
        assertEquals(1, TraceReplayContract.TRACE_FORMAT_VERSION);
        assertEquals(1, TraceReplayContract.TRACE_ANALYSIS_VERSION);
        assertEquals(1, WorkloadContract.TRACE_REPLAY_VERSION);
        assertEquals("median_replay_ms",
                WorkloadContract.primaryMetricFor(WorkloadContract.TRACE_REPLAY_ID));
        assertTrue(WorkloadContract.isPerformance(WorkloadContract.TRACE_REPLAY_ID));
        assertTrue(WorkloadContract.lowerIsBetter(WorkloadContract.TRACE_REPLAY_ID));
    }

    @Test
    public void definitionsAreImmutableAndHaveCanonicalDigest() throws Exception {
        JSONObject mixed = TraceReplayContract.definition(TraceReplayContract.MIXED_TRACE_ID);
        assertTrue(mixed.getBoolean("immutable"));
        assertEquals("rgba8_plus_u32", mixed.getString("output_kind"));
        assertEquals(64, mixed.getInt("draw_count"));
        assertEquals(4, mixed.getInt("dispatch_count"));
        assertTrue(mixed.getString("definition_sha256").matches("[0-9a-f]{64}"));

        JSONObject compute = TraceReplayContract.definition(
                TraceReplayContract.COMPUTE_CHAIN_TRACE_ID);
        assertFalse(compute.has("graphics_width"));
        assertEquals(12, compute.getInt("dispatch_count"));
        assertEquals("u32", compute.getString("output_kind"));
    }
}
