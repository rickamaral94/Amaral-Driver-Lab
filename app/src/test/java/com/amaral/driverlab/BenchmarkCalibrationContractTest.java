package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BenchmarkCalibrationContractTest {
    @Test
    public void repetitionUnitsPreserveWholeGpuWork() {
        assertEquals("renderpass", BenchmarkCalibrationContract.repetitionUnit(
                WorkloadContract.RENDERPASS_TILING_ID));
        assertEquals("renderpass", BenchmarkCalibrationContract.repetitionUnit(
                VisualSceneContract.GEOMETRY_ID));
        assertEquals("frame", BenchmarkCalibrationContract.repetitionUnit(
                WorkloadContract.TRACE_REPLAY_ID));
        assertEquals("dispatch", BenchmarkCalibrationContract.repetitionUnit(
                WorkloadContract.COMPUTE_ARITHMETIC_ID));
        assertEquals("draw_batch", BenchmarkCalibrationContract.repetitionUnit(
                WorkloadContract.SHADER_COMPILE_ID));
    }

    @Test
    public void linearityControlsNormalizedDuration() throws Exception {
        JSONObject linear = BenchmarkCalibrationContract.linearity(10_000.0, 20_500.0);
        assertEquals("linear", linear.getString("status"));
        assertTrue(linear.getBoolean("normalized_duration_allowed"));
        JSONObject nonlinear = BenchmarkCalibrationContract.linearity(10_000.0, 15_000.0);
        assertEquals("nonlinear_scaling", nonlinear.getString("status"));
        assertFalse(nonlinear.getBoolean("normalized_duration_allowed"));
        assertFalse(BenchmarkCalibrationContract.sampleDurationGate(10_000.0, nonlinear)
                .getBoolean("ranking_eligible"));
    }

    @Test
    public void twoMillisecondFloorIsBlocking() throws Exception {
        JSONObject linear = BenchmarkCalibrationContract.linearity(1_900.0, 3_800.0);
        JSONObject gate = BenchmarkCalibrationContract.sampleDurationGate(1_900.0, linear);
        assertEquals("invalid_below_floor", gate.getString("classification"));
        assertFalse(gate.getBoolean("normalized_duration_allowed"));
    }

    @Test
    public void pilotIsIndependentAndSampleSizeIsFixedOnce() throws Exception {
        JSONObject plan = BenchmarkCalibrationContract.fixedSampleSizePlan(
                new JSONArray().put(1.00).put(1.03).put(0.98), 2_700L);
        assertEquals(3, plan.getInt("pilot_pair_count"));
        assertFalse(plan.getBoolean("pilot_samples_reused"));
        assertTrue(plan.getBoolean("optional_stopping_prohibited"));
        assertFalse(plan.getBoolean("reestimate_from_observed_delta"));
        assertTrue(plan.getInt("planned_paired_rounds") >= 5);
        assertTrue(plan.getInt("planned_paired_rounds") <= 20);
    }

    @Test
    public void thermalAndCalibrationDriftAreExplicit() throws Exception {
        JSONObject thermal = BenchmarkCalibrationContract.thermalDrift(
                new JSONArray().put(10_000).put(10_200).put(11_000));
        assertTrue(thermal.getBoolean("thermal_drift_detected"));
        assertTrue(BenchmarkCalibrationContract.calibrationDrift(12_000, 17_000)
                .getBoolean("calibration_drift"));
        assertFalse(BenchmarkCalibrationContract.calibrationDrift(12_000, 12_000)
                .getBoolean("calibration_drift"));
        assertFalse(BenchmarkCalibrationContract.calibrationDrift(60_000, 66_000)
                .getBoolean("calibration_drift"));
    }

    @Test
    public void effectInjectionRejectsCpuDelayDomain() throws Exception {
        JSONObject injection = BenchmarkCalibrationContract.effectInjectionContract();
        assertEquals("original_native_measurement_domain", injection.getString("domain"));
        assertFalse(injection.getBoolean("cpu_sleep_allowed"));
        assertFalse(injection.getBoolean("cpu_busy_wait_allowed"));
        assertEquals(4, injection.getJSONArray("nominal_effect_percent").length());
        JSONObject observation = BenchmarkCalibrationContract.effectInjectionObservation(
                3, 3.125, 10_000.0, 10_280.0, "gpu_workload");
        assertEquals(3, observation.getInt("nominal_effect_percent"));
        assertEquals(2.8, observation.getDouble(
                "measured_duration_effect_percent"), 0.0001);
        assertFalse(observation.getBoolean("numeric_correction_applied"));
    }
}
