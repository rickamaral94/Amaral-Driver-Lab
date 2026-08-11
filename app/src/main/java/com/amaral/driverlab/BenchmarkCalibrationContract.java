package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Methodological contract for dynamic-range calibration introduced by workload v2. */
final class BenchmarkCalibrationContract {
    static final int POLICY_VERSION = 1;
    static final long TARGET_MIN_US = 8_000L;
    static final long TARGET_MAX_US = 16_000L;
    static final long TARGET_CENTER_US = 12_000L;
    static final long VALIDITY_FLOOR_US = 2_000L;
    static final double LINEARITY_MIN_RATIO = 1.8;
    static final double LINEARITY_MAX_RATIO = 2.2;
    static final int MAX_REPETITIONS = 512;
    static final int PILOT_PAIRED_ROUNDS = 3;
    static final int MINIMUM_FINAL_PAIRED_ROUNDS = 5;
    static final int MAXIMUM_FINAL_PAIRED_ROUNDS = 20;
    static final double TARGET_MDE_PERCENT = 3.0;
    static final double THERMAL_DRIFT_LIMIT_PERCENT = 5.0;
    static final double OUTSIDE_TARGET_REVALIDATION_LIMIT_PERCENT = 20.0;
    static final long DEFAULT_OPERATIONAL_BUDGET_SECONDS = 45L * 60L;

    static final String STATUS_WITHIN_TARGET = "within_target";
    static final String STATUS_VALID_BELOW_TARGET = "valid_below_target";
    static final String STATUS_VALID_ABOVE_TARGET = "valid_above_target";
    static final String STATUS_INVALID_BELOW_FLOOR = "invalid_below_floor";
    static final String STATUS_NONLINEAR_SCALING = "nonlinear_scaling";
    static final String STATUS_UNSTABLE = "unstable";
    static final String STATUS_NOT_CALIBRATED = "not_calibrated";

    private BenchmarkCalibrationContract() {}

    static boolean requiresCalibration(String workloadId, int workloadVersion) {
        return workloadVersion >= 2 && WorkloadContract.isPerformance(workloadId)
                && !WorkloadContract.TRANSFER_ID.equals(workloadId);
    }

    static String repetitionUnit(String workloadId) {
        if (VisualSceneContract.isVisualScene(workloadId)
                || WorkloadContract.RENDERPASS_TILING_ID.equals(workloadId)) {
            return "renderpass";
        }
        if (WorkloadContract.STABLE_SCENE_ID.equals(workloadId)
                || WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
            return "frame";
        }
        if (WorkloadContract.COMPUTE_ARITHMETIC_ID.equals(workloadId)
                || WorkloadContract.THERMAL_SUSTAIN_ID.equals(workloadId)) {
            return "dispatch";
        }
        if (WorkloadContract.SHADER_COMPILE_ID.equals(workloadId)) return "draw_batch";
        if (WorkloadContract.TRANSFER_ID.equals(workloadId)) return "dispatch";
        throw new IllegalArgumentException("Workload sem unidade de repetição: " + workloadId);
    }

    static int selectMultiplier(double pilotMedianUs) {
        if (!Double.isFinite(pilotMedianUs) || pilotMedianUs <= 0.0) return 1;
        long proposed = Math.round(TARGET_CENTER_US / pilotMedianUs);
        return (int) Math.max(1L, Math.min(MAX_REPETITIONS / 2L, proposed));
    }

    static String classifyMedian(double medianBatchUs) {
        if (!Double.isFinite(medianBatchUs) || medianBatchUs <= 0.0) {
            return STATUS_NOT_CALIBRATED;
        }
        if (medianBatchUs < VALIDITY_FLOOR_US) return STATUS_INVALID_BELOW_FLOOR;
        if (medianBatchUs < TARGET_MIN_US) return STATUS_VALID_BELOW_TARGET;
        if (medianBatchUs <= TARGET_MAX_US) return STATUS_WITHIN_TARGET;
        return STATUS_VALID_ABOVE_TARGET;
    }

    static JSONObject linearity(double medianAtMUs, double medianAtDoubleMUs) throws Exception {
        double ratio = medianAtMUs > 0.0 ? medianAtDoubleMUs / medianAtMUs : Double.NaN;
        boolean valid = Double.isFinite(ratio)
                && ratio >= LINEARITY_MIN_RATIO && ratio <= LINEARITY_MAX_RATIO;
        return new JSONObject()
                .put("multiplier_ratio", 2)
                .put("median_at_m_us", finiteOrNull(medianAtMUs))
                .put("median_at_2m_us", finiteOrNull(medianAtDoubleMUs))
                .put("observed_time_ratio", finiteOrNull(ratio))
                .put("accepted_ratio_min", LINEARITY_MIN_RATIO)
                .put("accepted_ratio_max", LINEARITY_MAX_RATIO)
                .put("status", valid ? "linear" : STATUS_NONLINEAR_SCALING)
                .put("normalized_duration_allowed", valid);
    }

    static JSONObject sampleDurationGate(double medianBatchUs, JSONObject linearity)
            throws Exception {
        String classification = classifyMedian(medianBatchUs);
        boolean linear = linearity != null
                && "linear".equals(linearity.optString("status"));
        boolean validRange = !STATUS_INVALID_BELOW_FLOOR.equals(classification)
                && !STATUS_NOT_CALIBRATED.equals(classification);
        return new JSONObject()
                .put("median_batch_us", finiteOrNull(medianBatchUs))
                .put("classification", linear ? classification : STATUS_NONLINEAR_SCALING)
                .put("ranking_eligible", validRange && linear)
                .put("normalized_duration_allowed", validRange && linear);
    }

    static JSONObject fixedSampleSizePlan(JSONArray pilotPairedRatios,
                                          long operationalBudgetSeconds) throws Exception {
        List<Double> values = finitePositive(pilotPairedRatios);
        double cvPercent = coefficientOfVariationPercent(values);
        int statisticalRequirement;
        if (!Double.isFinite(cvPercent)) {
            statisticalRequirement = MAXIMUM_FINAL_PAIRED_ROUNDS;
        } else {
            statisticalRequirement = (int) Math.ceil(Math.pow(
                    1.96 * cvPercent / TARGET_MDE_PERCENT, 2.0));
        }
        int required = Math.max(MINIMUM_FINAL_PAIRED_ROUNDS, statisticalRequirement);
        int capped = Math.min(MAXIMUM_FINAL_PAIRED_ROUNDS, required);
        double estimatedMde = Double.isFinite(cvPercent)
                ? 1.96 * cvPercent / Math.sqrt(capped) : Double.NaN;
        return new JSONObject()
                .put("pilot_pair_count", values.size())
                .put("pilot_samples_reused", false)
                .put("paired_ratio_cv_percent", finiteOrNull(cvPercent))
                .put("target_mde_percent", TARGET_MDE_PERCENT)
                .put("estimated_mde_percent", finiteOrNull(estimatedMde))
                .put("required_paired_rounds", required)
                .put("planned_paired_rounds", capped)
                .put("maximum_paired_rounds", MAXIMUM_FINAL_PAIRED_ROUNDS)
                .put("operational_budget_seconds", Math.max(1L, operationalBudgetSeconds))
                .put("sample_size_status", required > capped
                        ? "insufficient_sensitivity" : "fixed_after_pilot")
                .put("optional_stopping_prohibited", true)
                .put("reestimate_from_observed_delta", false);
    }

    static JSONObject thermalDrift(JSONArray orderedReferenceBatchUs) throws Exception {
        List<Double> values = finitePositive(orderedReferenceBatchUs);
        if (values.size() < 3) {
            return new JSONObject()
                    .put("thermal_drift_detected", JSONObject.NULL)
                    .put("status", "not_determinable")
                    .put("first_third_median_us", JSONObject.NULL)
                    .put("last_third_median_us", JSONObject.NULL)
                    .put("drift_percent", JSONObject.NULL);
        }
        int third = Math.max(1, values.size() / 3);
        double first = median(values.subList(0, third));
        double last = median(values.subList(values.size() - third, values.size()));
        double drift = (last / first - 1.0) * 100.0;
        return new JSONObject()
                .put("thermal_drift_detected", Math.abs(drift) > THERMAL_DRIFT_LIMIT_PERCENT)
                .put("status", Math.abs(drift) > THERMAL_DRIFT_LIMIT_PERCENT
                        ? "drift_detected" : "stable")
                .put("first_third_median_us", first)
                .put("last_third_median_us", last)
                .put("drift_percent", drift)
                .put("threshold_percent", THERMAL_DRIFT_LIMIT_PERCENT);
    }

    static JSONObject calibrationDrift(double calibratedReferenceMedianUs,
                                       double finalReferenceMedianUs) throws Exception {
        boolean startedInTarget = calibratedReferenceMedianUs >= TARGET_MIN_US
                && calibratedReferenceMedianUs <= TARGET_MAX_US;
        double relativeDrift = calibratedReferenceMedianUs > 0.0
                ? (finalReferenceMedianUs / calibratedReferenceMedianUs - 1.0) * 100.0
                : Double.NaN;
        boolean finalInvalid = !Double.isFinite(finalReferenceMedianUs)
                || finalReferenceMedianUs < VALIDITY_FLOOR_US;
        boolean leftTarget = startedInTarget && (finalReferenceMedianUs < TARGET_MIN_US
                || finalReferenceMedianUs > TARGET_MAX_US);
        boolean outsideTargetMoved = !startedInTarget
                && (!Double.isFinite(relativeDrift)
                || Math.abs(relativeDrift) > OUTSIDE_TARGET_REVALIDATION_LIMIT_PERCENT);
        boolean drift = finalInvalid || leftTarget || outsideTargetMoved;
        return new JSONObject()
                .put("calibration_drift", drift)
                .put("initial_reference_median_us",
                        finiteOrNull(calibratedReferenceMedianUs))
                .put("final_reference_median_us", finiteOrNull(finalReferenceMedianUs))
                .put("relative_drift_percent", finiteOrNull(relativeDrift))
                .put("initially_within_target", startedInTarget)
                .put("outside_target_revalidation_limit_percent",
                        OUTSIDE_TARGET_REVALIDATION_LIMIT_PERCENT)
                .put("target_min_us", TARGET_MIN_US)
                .put("target_max_us", TARGET_MAX_US)
                .put("ranking_eligible", !drift);
    }

    static JSONObject effectInjectionContract() throws Exception {
        return new JSONObject()
                .put("domain", "original_native_measurement_domain")
                .put("mechanism", "additional_gpu_repetition_units_or_pipeline_creations_inside_original_timestamp_domain")
                .put("nominal_effect_percent", new JSONArray().put(0).put(1).put(3).put(10))
                .put("cpu_sleep_allowed", false)
                .put("cpu_busy_wait_allowed", false)
                .put("public_ranking_eligible", false);
    }

    static JSONObject effectInjectionObservation(int nominalPercent,
                                                 double realizedWorkloadPercent,
                                                 double baselineMedianUs,
                                                 double injectedMedianUs,
                                                 String domain) throws Exception {
        double measured = baselineMedianUs > 0.0
                ? (injectedMedianUs / baselineMedianUs - 1.0) * 100.0 : Double.NaN;
        return new JSONObject()
                .put("nominal_effect_percent", nominalPercent)
                .put("realized_workload_effect_percent",
                        finiteOrNull(realizedWorkloadPercent))
                .put("measured_duration_effect_percent", finiteOrNull(measured))
                .put("baseline_median_us", finiteOrNull(baselineMedianUs))
                .put("injected_median_us", finiteOrNull(injectedMedianUs))
                .put("domain", domain == null || domain.isEmpty()
                        ? "unknown" : domain)
                .put("numeric_correction_applied", false);
    }

    static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("calibration_policy_version", POLICY_VERSION)
                .put("target_min_us", TARGET_MIN_US)
                .put("target_max_us", TARGET_MAX_US)
                .put("target_center_us", TARGET_CENTER_US)
                .put("validity_floor_us", VALIDITY_FLOOR_US)
                .put("linearity_ratio_min", LINEARITY_MIN_RATIO)
                .put("linearity_ratio_max", LINEARITY_MAX_RATIO)
                .put("pilot_samples_reused", false)
                .put("required_paired_rounds_fixed_after_pilot", true)
                .put("multiplier_shared_between_arms", true)
                .put("per_driver_calibration_allowed", false)
                .put("multiplier_persisted_by_calibration_key", true)
                .put("cold_calibration_not_valid_when_hot", true)
                .put("cross_hardware_absolute_time_comparison_allowed", false)
                .put("cross_hardware_effect_size_comparison_allowed", true)
                .put("effect_injection", effectInjectionContract())
                .put("statistical_analysis_version", WorkloadContract.STATISTICAL_ANALYSIS_VERSION)
                .put("statistical_analysis_version_note",
                        "The estimator is unchanged. Consumers must read completed_paired_rounds because n is no longer constant.");
    }

    private static List<Double> finitePositive(JSONArray input) {
        List<Double> output = new ArrayList<>();
        if (input == null) return output;
        for (int index = 0; index < input.length(); ++index) {
            double value = input.optDouble(index, Double.NaN);
            if (Double.isFinite(value) && value > 0.0) output.add(value);
        }
        return output;
    }

    private static double coefficientOfVariationPercent(List<Double> values) {
        if (values.size() < 2) return Double.NaN;
        double mean = 0.0;
        for (double value : values) mean += value;
        mean /= values.size();
        if (mean == 0.0) return Double.NaN;
        double sum = 0.0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / (values.size() - 1)) / Math.abs(mean) * 100.0;
    }

    private static double median(List<Double> input) {
        List<Double> values = new ArrayList<>(input);
        Collections.sort(values);
        int middle = values.size() / 2;
        return values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2.0
                : values.get(middle);
    }

    private static Object finiteOrNull(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }
}
