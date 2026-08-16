package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The v7 profile moves the performance weight onto an emulator-shaped workload.
 *
 * <p>v6 measured one large uniform scene with a coefficient of variation near
 * 0.03%, and every A/B between our drivers came back a technical tie at about a
 * tenth of a percent. The gates that actually caught a broken driver — offscreen
 * correctness and the visible scenes — stay, at weight zero.
 */
public final class EmulatorFramePatternTest {

    private static JSONObject step(JSONArray steps, String id) throws Exception {
        for (int index = 0; index < steps.length(); ++index) {
            if (id.equals(steps.getJSONObject(index).getString("step_id"))) {
                return steps.getJSONObject(index);
            }
        }
        throw new IllegalStateException("etapa ausente no perfil: " + id);
    }

    @Test
    public void performanceWeightSitsOnTheEmulatorWorkloadAndShaderCompile() throws Exception {
        JSONArray steps = QualificationProfile.definition().getJSONArray("steps");

        assertEquals(70, step(steps, "emulator_frame").getInt("score_weight"));
        assertEquals(WorkloadContract.EMULATOR_FRAME_ID,
                step(steps, "emulator_frame").getString("workload_id"));
        assertEquals(30, step(steps, "shader_compile").getInt("score_weight"));
    }

    @Test
    public void breakageGatesSurviveWithZeroWeight() throws Exception {
        JSONArray steps = QualificationProfile.definition().getJSONArray("steps");
        for (String id : new String[] {"correctness_pre", "correctness_post",
                "visual_geometry", "visual_materials", "visual_postprocess",
                "visual_gpu_stress", "trace_mixed"}) {
            JSONObject gate = step(steps, id);
            assertTrue(id + " deve continuar sendo gate de compatibilidade",
                    gate.getBoolean("compatibility_gate"));
            assertEquals(id + " não deve pesar na performance",
                    0, gate.getInt("score_weight"));
        }
    }

    @Test
    public void theWeakPerformanceStepsAreGone() throws Exception {
        JSONArray steps = QualificationProfile.definition().getJSONArray("steps");
        for (int index = 0; index < steps.length(); ++index) {
            assertFalse("stable_scene media ±0,05% em toda corrida; saiu do perfil",
                    WorkloadContract.STABLE_SCENE_ID.equals(
                            steps.getJSONObject(index).getString("workload_id")));
        }
    }

    @Test
    public void profileSixRemainsReadableForHistoricalRuns() throws Exception {
        assertEquals(9, QualificationProfile.stepsForVersion(
                Phase15DynamicRangeContract.LEGACY_PROFILE_VERSION).size());
        assertTrue(QualificationProfile.verify(QualificationProfile.definitionForVersion(
                Phase15DynamicRangeContract.LEGACY_PROFILE_VERSION)));
    }

    @Test
    public void lowerIsBetterForTheNewWorkload() {
        assertTrue(WorkloadContract.lowerIsBetter(WorkloadContract.EMULATOR_FRAME_ID));
        assertEquals("composite_frame_ms",
                WorkloadContract.primaryMetricFor(WorkloadContract.EMULATOR_FRAME_ID));
    }

    /**
     * v2 published the median of the samples of all five passes pooled together.
     * The passes cost wildly different amounts and each runs for a slice of wall
     * clock, so the number of samples per pass shifts between rounds and the
     * pooled median jumps to a different pass's population. Issue #68 showed the
     * damage: medians stepping between 4.18, 3.54 and 2.50 ms across rounds of the
     * same pair, linearity 1.46 against a 1.8 minimum, and the step dropped as
     * not_rankable — carrying weight 70.
     *
     * <p>The arithmetic below is the fix, restated: a fixed composition cannot
     * move when the sample counts do.
     */
    @Test
    public void theCompositeFrameDoesNotMoveWhenSampleCountsDo() {
        double[] cheapPass = { 0.40, 0.41, 0.42, 0.40, 0.41, 0.42, 0.41 };
        double[] costlyPass = { 4.10, 4.15, 4.20 };

        // The passes are unchanged. Only how many samples of each landed in the
        // measurement window differs — which is what varies round to round,
        // because each pass runs for a slice of wall clock, not a fixed count.
        double mostlyCheap = pooledMedian(cheapPass, 7, costlyPass, 3);
        double mostlyCostly = pooledMedian(cheapPass, 2, costlyPass, 3);
        assertEquals(0.42, mostlyCheap, 1e-9);
        assertEquals(4.10, mostlyCostly, 1e-9);
        assertTrue("this is the v2 defect: the metric jumped between passes",
                mostlyCostly - mostlyCheap > 3.0);

        // The composite is the sum of the per-pass medians, so it answers the
        // question the workload actually asks: what does one emulator-shaped
        // frame cost. It cannot move unless a pass gets faster or slower.
        assertEquals(0.41 + 4.15, composite(cheapPass, costlyPass), 1e-9);
    }

    private static double composite(double[]... passes) {
        double total = 0.0;
        for (double[] pass : passes) total += median(pass, pass.length);
        return total;
    }

    private static double pooledMedian(double[] first, int firstCount,
                                       double[] second, int secondCount) {
        double[] pooled = new double[firstCount + secondCount];
        System.arraycopy(first, 0, pooled, 0, firstCount);
        System.arraycopy(second, 0, pooled, firstCount, secondCount);
        return median(pooled, pooled.length);
    }

    private static double median(double[] values, int count) {
        double[] copy = new double[count];
        System.arraycopy(values, 0, copy, 0, count);
        java.util.Arrays.sort(copy);
        return copy[count / 2];
    }
}
