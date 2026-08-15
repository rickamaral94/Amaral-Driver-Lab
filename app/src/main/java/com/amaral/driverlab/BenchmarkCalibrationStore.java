package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Persistent, versioned calibration cache. Values are keyed by hardware and workload contract. */
final class BenchmarkCalibrationStore {
    private static final int STORE_VERSION = 1;
    private static final String FILE_NAME = "benchmark-calibrations-v1.json";

    private BenchmarkCalibrationStore() {}

    static JSONObject key(JSONObject device, String gpuName, String workloadId,
                          int workloadVersion, JSONObject workloadConfig,
                          String timestampSource, String thermalProfile) throws Exception {
        String soc = device == null ? "unknown" : device.optString("soc_model",
                device.optString("hardware", "unknown"));
        String model = device == null ? "unknown" : device.optString("model", "unknown");
        JSONObject key = new JSONObject()
                .put("hardware_key", normalize(model) + "/" + normalize(soc) + "/"
                        + normalize(gpuName))
                .put("workload_id", workloadId)
                .put("workload_version", workloadVersion)
                .put("workload_config_sha256", JsonCanonicalizer.sha256(workloadConfig))
                .put("timestamp_source", timestampSource == null ? "unknown" : timestampSource)
                .put("thermal_profile", thermalProfile == null || thermalProfile.isEmpty()
                        ? "unknown" : thermalProfile);
        key.put("calibration_key_sha256", JsonCanonicalizer.sha256WithoutKey(
                key, "calibration_key_sha256"));
        return key;
    }

    static JSONObject find(File filesDir, JSONObject key) {
        try {
            JSONObject root = load(filesDir);
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) return null;
            String wanted = key.optString("calibration_key_sha256", "");
            for (int index = 0; index < entries.length(); ++index) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry != null && wanted.equals(entry.optString("calibration_key_sha256"))) {
                    return new JSONObject(entry.toString());
                }
            }
        } catch (Exception ignored) {
            // A corrupt or legacy cache is a miss, never an inferred calibration.
        }
        return null;
    }

    static void put(File filesDir, JSONObject key, int repetitions,
                    double calibratedMedianUs, JSONObject linearity) throws Exception {
        JSONObject root = load(filesDir);
        JSONArray existing = root.optJSONArray("entries");
        List<JSONObject> entries = new ArrayList<>();
        String wanted = key.getString("calibration_key_sha256");
        if (existing != null) {
            for (int index = 0; index < existing.length(); ++index) {
                JSONObject entry = existing.optJSONObject(index);
                if (entry != null && !wanted.equals(
                        entry.optString("calibration_key_sha256"))) {
                    entries.add(entry);
                }
            }
        }
        JSONObject entry = new JSONObject(key.toString())
                .put("calibration_policy_version", BenchmarkCalibrationContract.POLICY_VERSION)
                .put("repetitions_per_sample", repetitions)
                .put("calibrated_median_us", calibratedMedianUs)
                .put("linearity", linearity)
                .put("created_at_ms", System.currentTimeMillis())
                .put("cold_calibration_not_valid_when_hot", true);
        entries.add(entry);
        entries.sort(Comparator.comparing(value -> value.optString(
                "calibration_key_sha256", "")));
        JSONArray encoded = new JSONArray();
        for (JSONObject value : entries) encoded.put(value);
        root.put("store_version", STORE_VERSION)
                .put("calibration_policy_version", BenchmarkCalibrationContract.POLICY_VERSION)
                .put("entries", encoded);
        ResultFiles.writeAtomic(file(filesDir), root.toString(2));
    }

    private static JSONObject load(File filesDir) throws Exception {
        File file = file(filesDir);
        if (!file.isFile()) {
            return new JSONObject()
                    .put("store_version", STORE_VERSION)
                    .put("calibration_policy_version", BenchmarkCalibrationContract.POLICY_VERSION)
                    .put("entries", new JSONArray());
        }
        JSONObject root = new JSONObject(ResultFiles.readUtf8(file));
        if (root.optInt("store_version", -1) != STORE_VERSION
                || root.optInt("calibration_policy_version", -1)
                != BenchmarkCalibrationContract.POLICY_VERSION) {
            return new JSONObject()
                    .put("store_version", STORE_VERSION)
                    .put("calibration_policy_version", BenchmarkCalibrationContract.POLICY_VERSION)
                    .put("entries", new JSONArray());
        }
        return root;
    }

    private static File file(File filesDir) {
        return new File(filesDir, FILE_NAME);
    }

    private static String normalize(String value) {
        String normalized = value == null ? "unknown" : value.trim().toLowerCase()
                .replaceAll("[^a-z0-9._+-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }
}
