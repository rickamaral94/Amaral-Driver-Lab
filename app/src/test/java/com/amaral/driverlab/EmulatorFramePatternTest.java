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
        assertEquals("median_frame_ms",
                WorkloadContract.primaryMetricFor(WorkloadContract.EMULATOR_FRAME_ID));
    }
}
