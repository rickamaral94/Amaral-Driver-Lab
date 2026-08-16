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
    public void phaseTwoSeriesAdvanceToVersionTwoWhileV1RemainsSupported() {
        assertEquals(15, WorkloadContract.RESULT_SCHEMA_VERSION);
        assertEquals(6, WorkloadContract.PHASE2_IDS.size());
        for (String workloadId : WorkloadContract.PHASE2_IDS) {
            // emulator_frame_pattern is at 3: its v2 primary metric was the median
            // of the five passes pooled, which moves with the sample mix. See
            // EmulatorFramePatternTest.
            final int expected = WorkloadContract.EMULATOR_FRAME_ID.equals(workloadId) ? 4 : 2;
            assertEquals(expected, WorkloadContract.versionFor(workloadId));
            assertTrue(WorkloadContract.isSupportedVersion(workloadId, 1));
            assertTrue(WorkloadContract.isSupportedVersion(workloadId, 2));
            assertTrue(WorkloadContract.isSupported(workloadId));
            assertTrue(WorkloadContract.isPhase2(workloadId));
            assertFalse(WorkloadContract.limitationFor(workloadId).isEmpty());
        }
    }

    /**
     * The invariant that was missing. Every guard asserted isSupportedVersion for
     * the literals 1 and 2, so bumping a workload to 3 passed the whole suite while
     * the runner refused to launch it on the device. A workload must accept the
     * version its own contract declares current.
     */
    @Test
    public void everyWorkloadSupportsTheVersionItDeclaresCurrent() {
        java.util.List<String> everyId = new java.util.ArrayList<>(WorkloadContract.PHASE2_IDS);
        everyId.addAll(VisualSceneContract.IDS);
        everyId.add(WorkloadContract.RENDER_CORRECTNESS_ID);
        everyId.add(WorkloadContract.TRANSFER_ID);
        for (String workloadId : everyId) {
            int current = WorkloadContract.versionFor(workloadId);
            assertTrue(workloadId + " precisa aceitar a própria versão corrente " + current,
                    WorkloadContract.isSupportedVersion(workloadId, current));
            assertFalse(workloadId + " não pode aceitar versão acima da corrente",
                    WorkloadContract.isSupportedVersion(workloadId, current + 1));
            assertFalse(workloadId + " não pode aceitar versão zero",
                    WorkloadContract.isSupportedVersion(workloadId, 0));
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
        assertEquals(3, Phase4Contract.RANKING_VERSION);
        assertEquals(1, Phase4Contract.BISECT_VERSION);
        assertEquals(3, Phase4Contract.PUBLIC_DATASET_SCHEMA_VERSION);
        assertEquals(2, WorkloadContract.COMPUTE_ARITHMETIC_VERSION);
        assertEquals(2, WorkloadContract.STABLE_SCENE_VERSION);
    }
    @Test
    public void phaseSixCampaignVersionsDoNotRedefineWorkloads() {
        assertEquals(1, Phase6Contract.CAMPAIGN_SCHEMA_VERSION);
        assertEquals(1, Phase6Contract.SCHEDULER_VERSION);
        assertEquals(1, Phase6Contract.SUMMARY_VERSION);
        assertEquals("rotating_serpentine_v1", Phase6Contract.ORDER_POLICY);
        assertTrue(WorkloadContract.isSupportedVersion(WorkloadContract.TRACE_REPLAY_ID, 1));
        assertTrue(WorkloadContract.isSupportedVersion(
                WorkloadContract.COMPUTE_ARITHMETIC_ID, 1));
    }

    @Test
    public void phaseSevenQualificationVersionsDoNotRedefineWorkloads() {
        assertEquals(1, Phase7Contract.QUALIFICATION_SCHEMA_VERSION);
        assertEquals(1, Phase7Contract.PROFILE_VERSION);
        assertEquals(1, Phase7Contract.REPORT_VERSION);
        assertEquals(1, Phase7Contract.SCORE_VERSION);
        assertEquals(1, Phase7Contract.BUNDLE_VERSION);
        assertEquals(1, WorkloadContract.RENDER_CORRECTNESS_VERSION);
        assertTrue(WorkloadContract.isSupportedVersion(WorkloadContract.TRACE_REPLAY_ID, 1));
        assertEquals(1, WorkloadContract.STATISTICAL_ANALYSIS_VERSION);
    }

    @Test
    public void visibleScenesRemainIndependentWithoutRedefiningEarlierSeries() {
        assertEquals(1, Phase8Contract.VISUAL_SCENE_CONTRACT_VERSION);
        assertEquals(1, Phase8Contract.CHECKPOINT_ANALYSIS_VERSION);
        assertEquals(2, Phase8Contract.CURRENT_FULL_PROFILE_VERSION);
        assertEquals(4, VisualSceneContract.IDS.size());
        for (String workloadId : VisualSceneContract.IDS) {
            // v3: the GPU timestamp bracket encloses only the repetition loop.
            assertEquals(3, WorkloadContract.versionFor(workloadId));
            assertTrue(WorkloadContract.isSupportedVersion(workloadId, 1));
            assertEquals("p99_gpu_frame_ms", WorkloadContract.primaryMetricFor(workloadId));
            assertTrue(WorkloadContract.lowerIsBetter(workloadId));
        }
        assertEquals("visual_scene_gpu_stress_v1",
                WorkloadContract.nativeNameFor(VisualSceneContract.GPU_STRESS_ID, 1));
        assertEquals(1, WorkloadContract.RENDER_CORRECTNESS_VERSION);
        assertEquals(2, WorkloadContract.TRACE_REPLAY_VERSION);
    }

    @Test
    public void phaseElevenAddsFullV3WithoutRedefiningEarlierSeries() {
        assertEquals(3, Phase11Contract.PROFILE_VERSION);
        assertEquals(4, Phase11Contract.QUALIFICATION_SCHEMA_VERSION);
        assertEquals(4, Phase11Contract.REPORT_VERSION);
        assertEquals(4, Phase11Contract.SCORE_VERSION);
        assertEquals(5, Phase11Contract.FULL_SOAK_CYCLES);
        assertEquals(128, Phase11Contract.RECOMMENDED_MEMORY_MIB);
        assertEquals(2, Phase8Contract.CURRENT_FULL_PROFILE_VERSION);
        assertEquals(1, Phase10Contract.PROFILE_VERSION);
        assertEquals(1, WorkloadContract.RENDER_CORRECTNESS_VERSION);
        assertTrue(WorkloadContract.isSupportedVersion(WorkloadContract.TRACE_REPLAY_ID, 1));
    }

}
