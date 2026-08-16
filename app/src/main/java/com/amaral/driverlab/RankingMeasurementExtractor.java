package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts a completed qualification into one immutable raw ranking measurement. */
final class RankingMeasurementExtractor {
    private RankingMeasurementExtractor() {}

    static MeasurementRecord fromQualification(File filesDir, JSONObject manifest,
                                               JSONObject fingerprint) throws Exception {
        JSONArray workloads = new JSONArray();
        JSONArray failures = new JSONArray();
        JSONObject candidateIdentity = null;
        JSONObject referenceIdentity = null;
        boolean allSuitesCompleted = true;
        boolean equivalentClock = true;
        int minimumPairedSamples = Integer.MAX_VALUE;
        JSONArray states = manifest.getJSONObject("execution").getJSONArray("steps");
        for (int index = 0; index < states.length(); index++) {
            JSONObject state = states.getJSONObject(index);
            if (!QualificationProfile.KIND_SUITE.equals(
                    state.optString("step_kind", QualificationProfile.KIND_SUITE))) continue;
            String status = state.optString("status");
            if (!"completed".equals(status)) allSuitesCompleted = false;
            File suiteFile = QualificationStore.suiteFile(filesDir, state);
            if (suiteFile == null || !suiteFile.isFile()) continue;
            JSONObject suite = new JSONObject(ResultFiles.readUtf8(suiteFile));
            JSONObject candidateObserved = armIdentity(suite, true,
                    manifest.getJSONObject("driver"));
            JSONObject referenceObserved = armIdentity(suite, false,
                    manifest.optJSONObject("reference_driver"));
            if (candidateIdentity == null && candidateObserved != null) {
                candidateIdentity = candidateObserved;
            }
            if (referenceIdentity == null && referenceObserved != null) {
                referenceIdentity = referenceObserved;
            }
            JSONObject workload = workload(suite);
            workloads.put(workload);
            minimumPairedSamples = Math.min(minimumPairedSamples,
                    workload.optInt("paired_sample_count", 0));
            if (isPerformanceWorkload(suite.optString("workload_id"))) {
                equivalentClock &= usesCpuClockOnBothArms(suite.optJSONArray("phases"));
            }
            appendFailures(failures, suite);
        }
        if (candidateIdentity == null) {
            candidateIdentity = fallbackIdentity(manifest.getJSONObject("driver"), "unknown");
        }
        if (referenceIdentity == null) {
            JSONObject referencePackage = manifest.optJSONObject("reference_driver");
            referenceIdentity = referencePackage == null
                    ? systemIdentity() : fallbackIdentity(referencePackage, "unknown");
        }
        JSONObject preflight = manifest.optJSONObject("preflight");
        JSONObject evaluation = preflight == null ? null : preflight.optJSONObject("evaluation");
        boolean thermalPassed = evaluation == null
                || !evaluation.optBoolean("ranking_blocked", false);
        String id = manifest.getString("qualification_id");
        return new MeasurementRecord(new JSONObject()
                .put("measurement_schema_version", MeasurementRecord.SCHEMA_VERSION)
                .put("measurement_id", id)
                .put("source_qualification_id", id)
                .put("timestamp_ms", manifest.optLong("created_at_ms", System.currentTimeMillis()))
                .put("device_fingerprint_sha256",
                        fingerprint.getString("device_fingerprint_sha256"))
                .put("epoch_sha256", fingerprint.getString("epoch_sha256"))
                .put("environment_fingerprint", fingerprint)
                .put("workload_set_sha256", fingerprint.getString("workload_set_sha256"))
                .put("measurement_protocol_version",
                        fingerprint.getInt("measurement_protocol_version"))
                .put("formula_version", MeasurementRecord.CURRENT_FORMULA_VERSION)
                .put("paired_session_count", minimumPairedSamples == Integer.MAX_VALUE
                        ? 0 : minimumPairedSamples)
                .put("measurement_clock", equivalentClock
                        ? "cpu_monotonic_both_arms" : "mixed_or_gpu_timestamp")
                .put("measurement_clock_equivalent", equivalentClock)
                .put("noise_floor_at_capture", JSONObject.NULL)
                .put("anchor_confidence", MeasurementRecord.CONFIDENCE_CO_MEASURED)
                .put("comparison_mode", manifest.optString("comparison_mode"))
                .put("null_test", "null_test".equals(manifest.optString("comparison_mode")))
                .put("complete_abba", allSuitesCompleted)
                .put("thermal_gate_passed", thermalPassed)
                .put("imported", false)
                .put("foreign_device", false)
                .put("candidate_identity", candidateIdentity)
                .put("reference_identity", referenceIdentity)
                .put("workloads", workloads)
                .put("failures", failures));
    }

    private static JSONObject workload(JSONObject suite) throws Exception {
        String workloadId = suite.optString("workload_id", "unknown");
        JSONArray phases = suite.optJSONArray("phases");
        JSONObject output = new JSONObject()
                .put("workload_id", workloadId)
                .put("workload_version", suite.optInt("workload_version", 1))
                .put("included_in_sustained", isPerformanceWorkload(workloadId))
                .put("included_in_low", hasFrameSeries(phases));
        JSONObject analysis = suite.optJSONObject("statistical_analysis");
        output.put("paired_sample_count", analysis == null ? 0
                : analysis.optInt("paired_sample_count", 0));
        output.put("confidence_interval_95_percent", analysis == null
                ? JSONObject.NULL : analysis.opt("confidence_interval_95_percent"));
        output.put("arm_order", analysis == null ? new JSONArray()
                : analysis.optJSONArray("paired_rounds") == null ? new JSONArray()
                : analysis.optJSONArray("paired_rounds"));
        output.put("candidate_time_ms", armMedianBatchMs(phases, true));
        output.put("anchor_time_ms", armMedianBatchMs(phases, false));
        output.put("candidate_frame_times_ms", armFrameTimes(phases, true));
        output.put("anchor_frame_times_ms", armFrameTimes(phases, false));
        JSONObject visual = suite.optJSONObject("visual_scene");
        if (visual != null) {
            output.put("candidate_image_hash", hashObject(
                    visual.opt("candidate_checkpoint_hashes")))
                    .put("anchor_image_hash", hashObject(
                            visual.opt("system_checkpoint_hashes")));
        } else {
            JSONObject render = suite.optJSONObject("render_correctness");
            if (render != null) {
                output.put("candidate_image_hash", hashObject(render.opt("candidate_hashes")))
                        .put("anchor_image_hash", hashObject(render.opt("system_hashes")));
            } else {
                output.put("candidate_image_hash", JSONObject.NULL)
                        .put("anchor_image_hash", JSONObject.NULL);
            }
        }
        return output;
    }

    private static boolean isPerformanceWorkload(String id) {
        return !WorkloadContract.RENDER_CORRECTNESS_ID.equals(id);
    }

    private static boolean hasFrameSeries(JSONArray phases) {
        if (phases == null) return false;
        for (int index = 0; index < phases.length(); index++) {
            JSONObject nativeResult = phases.optJSONObject(index) == null ? null
                    : phases.optJSONObject(index).optJSONObject("native");
            if (nativeResult != null && nativeResult.optJSONArray("frame_times_ms") != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesCpuClockOnBothArms(JSONArray phases) {
        boolean candidate = false;
        boolean reference = false;
        for (int index = 0; phases != null && index < phases.length(); index++) {
            JSONObject phase = phases.optJSONObject(index);
            JSONObject nativeResult = phase == null ? null : phase.optJSONObject("native");
            if (nativeResult == null || !nativeResult.optBoolean("success", false)) continue;
            if (nativeResult.optBoolean("gpu_timestamps_used", true)) return false;
            if (DriverExecutionIdentity.isCandidateArm(phase)) candidate = true;
            else reference = true;
        }
        return candidate && reference;
    }

    private static Object armMedianBatchMs(JSONArray phases, boolean candidate) {
        List<Double> values = new ArrayList<>();
        for (int index = 0; phases != null && index < phases.length(); index++) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || candidate != DriverExecutionIdentity.isCandidateArm(phase)
                    || !phase.optBoolean("success", false)) continue;
            JSONObject nativeResult = phase.optJSONObject("native");
            double us = nativeResult == null ? Double.NaN
                    : nativeResult.optDouble("median_batch_us", Double.NaN);
            if (Double.isFinite(us) && us > 0.0) values.add(us / 1000.0);
        }
        if (values.isEmpty()) return JSONObject.NULL;
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    private static JSONArray armFrameTimes(JSONArray phases, boolean candidate) {
        JSONArray output = new JSONArray();
        for (int index = 0; phases != null && index < phases.length(); index++) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || candidate != DriverExecutionIdentity.isCandidateArm(phase)
                    || !phase.optBoolean("success", false)) continue;
            JSONObject nativeResult = phase.optJSONObject("native");
            JSONArray values = nativeResult == null ? null
                    : nativeResult.optJSONArray("frame_times_ms");
            for (int sample = 0; values != null && sample < values.length(); sample++) {
                double value = values.optDouble(sample, Double.NaN);
                if (Double.isFinite(value) && value > 0.0) output.put(value);
            }
        }
        return output;
    }

    private static JSONObject armIdentity(JSONObject suite, boolean candidate,
                                          JSONObject packageIdentity) throws Exception {
        JSONArray phases = suite.optJSONArray("phases");
        for (int index = 0; phases != null && index < phases.length(); index++) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || candidate != DriverExecutionIdentity.isCandidateArm(phase)) continue;
            JSONObject nativeResult = phase.optJSONObject("native");
            JSONObject capabilities = nativeResult == null ? null
                    : nativeResult.optJSONObject("capabilities");
            if (capabilities == null) continue;
            JSONObject runtime = ValidationDriverIdentity.runtimeIdentity(nativeResult);
            String family = runtime.optString("driver", DriverIdentityPolicy.UNKNOWN);
            String vendor = DriverIdentityPolicy.TURNIP.equals(family) ? "mesa"
                    : DriverIdentityPolicy.QUALCOMM_BLOB.equals(family)
                    ? "qualcomm_proprietary" : "unknown";
            String binarySha = phase.optString("requested_library_sha256", "");
            if (binarySha.isEmpty() && packageIdentity != null) {
                binarySha = packageIdentity.optString("sha256", "");
            }
            Object commit = mesaCommit(packageIdentity);
            if (DriverRanking.PRIMARY_ANCHOR_SHA256.equalsIgnoreCase(binarySha)) {
                commit = DriverRanking.PRIMARY_ANCHOR_MESA_COMMIT;
            }
            return new JSONObject()
                    .put("runtime_vendor", vendor)
                    .put("vendor_string", capabilities.optString("driver_id_name",
                            capabilities.optString("driver_name", "unknown")))
                    .put("driver_name", packageIdentity == null
                            ? capabilities.optString("driver_name", "Driver do sistema")
                            : packageIdentity.optString("name",
                            capabilities.optString("driver_name", "Turnip")))
                    .put("driver_version", capabilities.opt("driver_info"))
                    .put("api_version", capabilities.opt("api_version"))
                    .put("binary_sha256", binarySha)
                    .put("mesa_commit", commit)
                    .put("identity_confidence", runtime.optString(
                            "driver_identity_confidence", DriverIdentityPolicy.INFERRED));
        }
        return null;
    }

    private static JSONObject fallbackIdentity(JSONObject driver, String vendor) throws Exception {
        return new JSONObject()
                .put("runtime_vendor", vendor)
                .put("vendor_string", JSONObject.NULL)
                .put("driver_name", driver == null ? "unknown" : driver.optString("name", "unknown"))
                .put("driver_version", driver == null ? JSONObject.NULL : driver.opt("driverVersion"))
                .put("api_version", JSONObject.NULL)
                .put("binary_sha256", driver == null ? "" : driver.optString("sha256", ""))
                .put("mesa_commit", mesaCommit(driver))
                .put("identity_confidence", DriverIdentityPolicy.INFERRED);
    }

    private static JSONObject systemIdentity() throws Exception {
        return new JSONObject()
                .put("runtime_vendor", "unknown")
                .put("driver_name", "Driver do sistema")
                .put("binary_sha256", "system")
                .put("api_version", JSONObject.NULL)
                .put("mesa_commit", JSONObject.NULL)
                .put("identity_confidence", DriverIdentityPolicy.INFERRED);
    }

    private static Object mesaCommit(JSONObject driver) {
        if (driver == null) return JSONObject.NULL;
        JSONObject metadata = driver.optJSONObject("metadata");
        String commit = metadata == null ? "" : metadata.optString("mesaCommit",
                metadata.optString("mesa_commit", ""));
        return commit.isEmpty() ? JSONObject.NULL : commit;
    }

    private static void appendFailures(JSONArray output, JSONObject suite) throws Exception {
        JSONArray catalog = suite.optJSONArray("failure_catalog");
        for (int index = 0; catalog != null && index < catalog.length(); index++) {
            JSONObject failure = catalog.optJSONObject(index);
            if (failure == null) continue;
            String role = failure.optString("driver_role", "");
            String type = failure.optString("failure_type", "");
            boolean comparisonFailure = type.contains("mismatch");
            if (!DriverExecutionIdentity.ROLE_CANDIDATE.equals(role) && !comparisonFailure) {
                continue;
            }
            output.put(new JSONObject()
                    .put("code", failureCode(type, failure))
                    .put("workload_id", suite.optString("workload_id"))
                    .put("failure_stage", failure.opt("failure_stage"))
                    .put("vk_result", failure.opt("vk_result"))
                    .put("message", failure.opt("message")));
        }
    }

    private static String failureCode(String type, JSONObject failure) {
        if ("vk_error_device_lost".equals(type)
                || failure.optInt("vk_result", 0) == -4) return "device_lost";
        if ("crash".equals(type)) return "runner_crash";
        if ("timeout".equals(type)) return "workload_timeout";
        if (type.contains("mismatch")) return "image_hash_mismatch";
        if (type.contains("validation")) return "fatal_validation_error";
        return "workload_incomplete";
    }

    private static Object hashObject(Object value) {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        try { return EnvironmentFingerprint.sha256(String.valueOf(value)); }
        catch (Exception ignored) { return JSONObject.NULL; }
    }
}
