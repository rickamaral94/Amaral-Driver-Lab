package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TraceReplayAnalysisTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void identicalPairedOutputsPassCorrectnessGate() throws Exception {
        JSONArray phases = new JSONArray();
        for (int round = 1; round <= 5; ++round) {
            phases.put(phase(round, false, HASH_A));
            phases.put(phase(round, true, HASH_A));
        }
        JSONObject analysis = TraceReplayAnalysis.analyze(
                phases, 5, RunCoordinator.MODE_AB);
        assertTrue(analysis.getBoolean("comparison_available"));
        assertTrue(analysis.getBoolean("passed_correctness_gate"));
        assertEquals(5, analysis.getInt("complete_pair_count"));
        assertEquals(0, analysis.getInt("output_mismatch_count"));
    }

    @Test
    public void mismatchBlocksPerformanceVerdict() throws Exception {
        JSONArray phases = new JSONArray();
        for (int round = 1; round <= 5; ++round) {
            phases.put(phase(round, false, HASH_A));
            phases.put(phase(round, true, HASH_B));
        }
        JSONObject trace = TraceReplayAnalysis.analyze(phases, 5, RunCoordinator.MODE_AB);
        assertFalse(trace.getBoolean("passed_correctness_gate"));
        assertFalse(trace.getBoolean("system_nondeterministic"));
        assertFalse(trace.getBoolean("candidate_nondeterministic"));
        assertEquals(5, trace.getInt("output_mismatch_count"));
        assertEquals("failed_trace_output_mismatch",
                TraceReplayAnalysis.verdictFor(trace, new JSONObject(), RunCoordinator.MODE_AB));
    }

    @Test
    public void changingHashWithinOneArmIsNondeterministic() throws Exception {
        JSONArray phases = new JSONArray();
        phases.put(phase(1, false, HASH_A));
        phases.put(phase(1, true, HASH_A));
        phases.put(phase(2, false, HASH_B));
        phases.put(phase(2, true, HASH_A));
        JSONObject trace = TraceReplayAnalysis.analyze(phases, 2, RunCoordinator.MODE_AB);
        assertTrue(trace.getBoolean("system_nondeterministic"));
        assertEquals("failed_trace_nondeterminism",
                TraceReplayAnalysis.verdictFor(trace, new JSONObject(), RunCoordinator.MODE_AB));
    }

    private static JSONObject phase(int round, boolean custom, String hash) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("round", round)
                .put("driver_mode", custom ? "custom" : "system")
                .put("native", new JSONObject()
                        .put("success", true)
                        .put("median_replay_ms", custom ? 1.0 : 1.1))
                .put("evidence", new JSONObject().put("sha256_output", hash));
    }
}
