package com.amaral.driverlab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WorkloadContractTest {
    @Test
    public void legacyTransferMetricAndWorkloadRemainStable() {
        assertEquals("vulkan_transfer_stress", WorkloadContract.TRANSFER_ID);
        assertEquals(1, WorkloadContract.TRANSFER_VERSION);
        assertEquals("vulkan_transfer_stress_v1", WorkloadContract.TRANSFER_NATIVE_NAME);
        assertEquals("transfer_payload_gib_s", WorkloadContract.TRANSFER_METRIC);
    }

    @Test
    public void correctionSceneRemainsAnIndependentVersionOneSeries() {
        assertEquals("render_correctness_offscreen", WorkloadContract.RENDER_CORRECTNESS_ID);
        assertEquals(1, WorkloadContract.RENDER_CORRECTNESS_VERSION);
    }

    @Test
    public void phaseTwoSeriesRemainVersionOneWhileSchemaAdvancesAdditively() {
        assertEquals(6, WorkloadContract.RESULT_SCHEMA_VERSION);
        assertEquals(5, WorkloadContract.PHASE2_IDS.size());
        for (String workloadId : WorkloadContract.PHASE2_IDS) {
            assertEquals(1, WorkloadContract.versionFor(workloadId));
            assertTrue(WorkloadContract.isSupported(workloadId));
            assertTrue(WorkloadContract.isPhase2(workloadId));
            assertFalse(WorkloadContract.limitationFor(workloadId).isEmpty());
        }
    }

    @Test
    public void phaseThreeAnalysisContractIsVersionedIndependently() {
        assertEquals(1, WorkloadContract.STATISTICAL_ANALYSIS_VERSION);
        assertEquals(5_000, WorkloadContract.BOOTSTRAP_ITERATIONS);
        assertEquals(5, WorkloadContract.MINIMUM_PAIRED_SAMPLES);
        assertEquals(3.0, WorkloadContract.PRACTICAL_EQUIVALENCE_MARGIN_PERCENT, 0.0);
    }

    @Test
    public void directionAndPrimaryMetricAreExplicit() {
        assertEquals("cold_total_ms", WorkloadContract.primaryMetricFor(WorkloadContract.SHADER_COMPILE_ID));
        assertEquals("median_frame_ms", WorkloadContract.primaryMetricFor(WorkloadContract.RENDERPASS_TILING_ID));
        assertEquals("throughput_gops", WorkloadContract.primaryMetricFor(WorkloadContract.COMPUTE_ARITHMETIC_ID));
        assertEquals("p99_frame_ms", WorkloadContract.primaryMetricFor(WorkloadContract.STABLE_SCENE_ID));
        assertEquals("sustained_throughput_gops", WorkloadContract.primaryMetricFor(WorkloadContract.THERMAL_SUSTAIN_ID));
        assertTrue(WorkloadContract.lowerIsBetter(WorkloadContract.SHADER_COMPILE_ID));
        assertFalse(WorkloadContract.lowerIsBetter(WorkloadContract.COMPUTE_ARITHMETIC_ID));
        assertEquals("median_replay_ms", WorkloadContract.primaryMetricFor(WorkloadContract.TRACE_REPLAY_ID));
        assertTrue(WorkloadContract.lowerIsBetter(WorkloadContract.TRACE_REPLAY_ID));
    }

    @Test
    public void phaseFourVersionsCatalogWithoutChangingWorkloads() {
        assertEquals(1, Phase4Contract.CATALOG_VERSION);
        assertEquals(1, Phase4Contract.SUITE_DIFF_VERSION);
        assertEquals(1, Phase4Contract.RANKING_VERSION);
        assertEquals(1, Phase4Contract.BISECT_VERSION);
        assertEquals(1, Phase4Contract.PUBLIC_DATASET_SCHEMA_VERSION);
        assertEquals(1, WorkloadContract.COMPUTE_ARITHMETIC_VERSION);
        assertEquals(1, WorkloadContract.STABLE_SCENE_VERSION);
    }
}
