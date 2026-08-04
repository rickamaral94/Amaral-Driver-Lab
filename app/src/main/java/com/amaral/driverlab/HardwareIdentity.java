package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class HardwareIdentity {
    private HardwareIdentity() {}

    static JSONObject fromReport(JSONObject report) throws Exception {
        JSONObject host = report.optJSONObject("host_device");
        String manufacturer = value(host, "manufacturer", "unknown");
        String model = value(host, "model", "unknown");
        String socManufacturer = value(host, "soc_manufacturer", "unknown");
        String socModel = value(host, "soc_model", "");
        if (socModel.isEmpty()) socModel = value(host, "hardware", "");
        if (socModel.isEmpty()) socModel = value(host, "board", "unknown");
        String gpuModel = findGpuName(report);
        String deviceKey = normalize(manufacturer) + "/" + normalize(model) + "/"
                + normalize(socModel) + "/" + normalize(gpuModel);
        String publicHardwareKey = normalize(socModel) + "/" + normalize(gpuModel);
        return new JSONObject()
                .put("identity_version", 1)
                .put("manufacturer", manufacturer)
                .put("model", model)
                .put("soc_manufacturer", socManufacturer)
                .put("soc_model", socModel)
                .put("gpu_model", gpuModel)
                .put("device_key", deviceKey)
                .put("public_hardware_key", publicHardwareKey);
    }

    static String deviceKey(JSONObject report) {
        try {
            JSONObject existing = report.optJSONObject("hardware_identity");
            if (existing != null && !existing.optString("device_key", "").isEmpty()) {
                return existing.optString("device_key");
            }
            return fromReport(report).optString("device_key", "unknown");
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String findGpuName(JSONObject report) {
        JSONArray phases = report.optJSONArray("phases");
        if (phases == null) return "unknown";
        String fallback = "";
        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || !phase.optBoolean("success", false)) continue;
            JSONObject nativeResult = phase.optJSONObject("native");
            if (nativeResult == null) continue;
            JSONObject capabilities = nativeResult.optJSONObject("capabilities");
            String name = capabilities == null
                    ? nativeResult.optString("gpu_name", "")
                    : capabilities.optString("gpu_name", nativeResult.optString("gpu_name", ""));
            if (name.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = name;
            if (DriverExecutionIdentity.isReferenceArm(phase)) return name;
        }
        return fallback.isEmpty() ? "unknown" : fallback;
    }

    private static String value(JSONObject object, String key, String fallback) {
        return object == null ? fallback : object.optString(key, fallback);
    }

    private static String normalize(String value) {
        String normalized = value == null ? "unknown" : value.trim().toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9._+-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }
}
