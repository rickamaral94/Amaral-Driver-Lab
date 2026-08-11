package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Phase15DynamicRangeContractTest {
    @Test
    public void versionBumpsMatchChangedMeaning() throws Exception {
        JSONObject contract = Phase15DynamicRangeContract.contractJson();
        assertEquals(15, contract.getInt("result_schema_version"));
        assertEquals(5, contract.getInt("qualification_schema_version"));
        assertEquals(6, contract.getInt("profile_version"));
        assertEquals(5, contract.getInt("qualification_report_version"));
        assertEquals(5, contract.getInt("qualification_score_version"));
        assertEquals(3, contract.getInt("ranking_version"));
        assertEquals(3, contract.getInt("public_dataset_schema_version"));
    }

    @Test
    public void calibrationContractFreezesArmsAndCrossHardwareMeaning() throws Exception {
        JSONObject calibration = BenchmarkCalibrationContract.contractJson();
        assertFalse(calibration.getBoolean(
                "cross_hardware_absolute_time_comparison_allowed"));
        assertTrue(calibration.getBoolean(
                "cross_hardware_effect_size_comparison_allowed"));
        assertFalse(calibration.getBoolean("pilot_samples_reused"));
        assertTrue(calibration.getBoolean("multiplier_shared_between_arms"));
        assertFalse(calibration.getBoolean("per_driver_calibration_allowed"));
        assertEquals(1, calibration.getInt("statistical_analysis_version"));
    }
}
