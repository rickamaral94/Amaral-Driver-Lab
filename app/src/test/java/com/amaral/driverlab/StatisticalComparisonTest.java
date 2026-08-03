package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class StatisticalComparisonTest {
    @Test
    public void higherIsBetterCandidateIsClassifiedWithConfidence() throws Exception {
        JSONArray phases = new JSONArray();
        for (int round = 1; round <= 7; ++round) {
            phases.put(phase(round, false, "throughput_gops", 100.0 + round));
            phases.put(phase(round, true, "throughput_gops", 112.0 + round));
        }
        JSONObject analysis = StatisticalComparison.analyze(
                phases, WorkloadContract.COMPUTE_ARITHMETIC_ID);
        assertEquals("candidate_better", analysis.getString("classification"));
        assertTrue(analysis.getDouble("median_paired_improvement_percent") > 10.0);
        assertEquals(7, analysis.getInt("paired_sample_count"));
    }

    @Test
    public void lowerIsBetterUsesPositiveImprovementForLowerCandidateLatency() throws Exception {
        JSONArray phases = new JSONArray();
        for (int round = 1; round <= 6; ++round) {
            phases.put(phase(round, false, "cold_total_ms", 100.0 + round));
            phases.put(phase(round, true, "cold_total_ms", 90.0 + round));
        }
        JSONObject analysis = StatisticalComparison.analyze(
                phases, WorkloadContract.SHADER_COMPILE_ID);
        assertEquals("candidate_better", analysis.getString("classification"));
        assertTrue(analysis.getDouble("median_paired_improvement_percent") > 8.0);
    }

    @Test
    public void fewerThanFivePairsRemainInsufficient() throws Exception {
        JSONArray phases = new JSONArray();
        for (int round = 1; round <= 4; ++round) {
            phases.put(phase(round, false, "throughput_gops", 100.0));
            phases.put(phase(round, true, "throughput_gops", 120.0));
        }
        JSONObject analysis = StatisticalComparison.analyze(
                phases, WorkloadContract.COMPUTE_ARITHMETIC_ID);
        assertEquals("insufficient_samples", analysis.getString("classification"));
        assertEquals("insufficient_statistical_data",
                StatisticalComparison.verdictFor(analysis, 0));
    }

    @Test
    public void failedAndIncompleteRoundsAreNeverConvertedIntoNumbers() throws Exception {
        JSONArray phases = new JSONArray();
        phases.put(phase(1, false, "throughput_gops", 100.0));
        phases.put(failure(1, true));
        phases.put(phase(2, false, "throughput_gops", 100.0));
        phases.put(phase(2, true, "throughput_gops", 105.0));
        JSONObject analysis = StatisticalComparison.analyze(
                phases, WorkloadContract.COMPUTE_ARITHMETIC_ID);
        assertEquals(1, analysis.getInt("paired_sample_count"));
        assertEquals(1, analysis.getInt("failed_phase_count"));
        assertEquals(1, analysis.getInt("incomplete_pair_count"));
    }

    @Test
    public void bootstrapAndEffectSizeAreDeterministic() throws Exception {
        JSONArray phases = new JSONArray();
        for (int round = 1; round <= 5; ++round) {
            phases.put(phase(round, false, "throughput_gops", 100.0));
            phases.put(phase(round, true, "throughput_gops", 105.0 + round));
        }
        JSONObject first = StatisticalComparison.analyze(
                phases, WorkloadContract.COMPUTE_ARITHMETIC_ID);
        JSONObject second = StatisticalComparison.analyze(
                phases, WorkloadContract.COMPUTE_ARITHMETIC_ID);
        assertEquals(first.getJSONObject("confidence_interval_95_percent").toString(),
                second.getJSONObject("confidence_interval_95_percent").toString());
        assertEquals(1.0, first.getDouble("matched_rank_biserial_correlation"), 0.0);
    }

    @Test
    public void signTestAndRankBiserialHaveKnownSmallSampleValues() {
        assertEquals(0.25, StatisticalComparison.exactSignTestPValue(3, 0), 1e-12);
        assertEquals(2.0 / 3.0, StatisticalComparison.matchedRankBiserial(
                Arrays.asList(-1.0, 2.0, 3.0)), 1e-12);
    }

    private static JSONObject phase(int round, boolean custom, String metric, double value)
            throws Exception {
        JSONObject nativeResult = new JSONObject();
        nativeResult.put("success", true);
        nativeResult.put(metric, value);
        return new JSONObject()
                .put("success", true)
                .put("round", round)
                .put("driver_mode", custom ? "custom" : "system")
                .put("native", nativeResult);
    }

    private static JSONObject failure(int round, boolean custom) throws Exception {
        return new JSONObject()
                .put("success", false)
                .put("round", round)
                .put("driver_mode", custom ? "custom" : "system");
    }
}
