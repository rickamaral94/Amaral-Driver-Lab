package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

final class FailureCatalog {
    private FailureCatalog() {}

    static JSONArray fromPhases(JSONArray phases) throws Exception {
        JSONArray failures = new JSONArray();
        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.getJSONObject(index);
            JSONObject nativeResult = phase.optJSONObject("native");
            if (!phase.optBoolean("success", false)) {
                String type = phase.optString("failure_type", "");
                if (type.isEmpty()) type = phase.optString("error", "phase_failure");
                if (nativeResult != null) {
                    type = nativeResult.optString("failure_type", type);
                }
                failures.put(entry(phase, index, type,
                        nativeResult == null ? phase : nativeResult));
            } else if (nativeResult != null && !nativeResult.optBoolean("success", false)) {
                failures.put(entry(phase, index,
                        nativeResult.optString("failure_type", "native_failure"), nativeResult));
            }

            JSONArray validationErrors = phase.optJSONArray("validation_errors");
            if (validationErrors != null) {
                for (int validationIndex = 0;
                     validationIndex < validationErrors.length(); ++validationIndex) {
                    JSONObject validation = new JSONObject();
                    validation.put("phase_index", index);
                    validation.put("phase", phase.optString("phase", "unknown"));
                    validation.put("driver_mode", phase.optString("driver_mode", "unknown"));
                    validation.put("round", phase.optInt("round", -1));
                    validation.put("failure_type", "validation_error");
                    validation.put("message", validationErrors.optString(validationIndex));
                    failures.put(validation);
                }
            }
        }
        return failures;
    }

    static void appendVisualMismatch(JSONArray failures, int round, int frame,
                                     JSONObject comparison) throws Exception {
        JSONObject failure = new JSONObject();
        failure.put("phase_index", JSONObject.NULL);
        failure.put("phase", "system_vs_candidate");
        failure.put("driver_mode", "comparison");
        failure.put("round", round);
        failure.put("checkpoint_frame", frame);
        failure.put("failure_type", "visual_scene_checkpoint_mismatch");
        failure.put("failure_stage", "visual_checkpoint_comparison");
        failure.put("pixel_match_percent", comparison.optDouble("pixel_match_percent"));
        failure.put("divergent_block_count", comparison.optInt("divergent_block_count"));
        failure.put("heatmap_relative_path", comparison.has("heatmap_relative_path")
                ? comparison.opt("heatmap_relative_path") : JSONObject.NULL);
        failure.put("message",
                "O checkpoint da cena visível excedeu a tolerância configurada.");
        failures.put(failure);
    }

    static void appendRenderMismatch(JSONArray failures, int round, JSONObject comparison)
            throws Exception {
        JSONObject failure = new JSONObject();
        failure.put("phase_index", JSONObject.NULL);
        failure.put("phase", "system_vs_candidate");
        failure.put("driver_mode", "comparison");
        failure.put("round", round);
        failure.put("failure_type", "render_mismatch");
        failure.put("failure_stage", "render_comparison");
        failure.put("pixel_match_percent", comparison.optDouble("pixel_match_percent"));
        failure.put("divergent_block_count", comparison.optInt("divergent_block_count"));
        failure.put("message", "A imagem candidata excedeu a tolerância configurada.");
        failures.put(failure);
    }

    private static JSONObject entry(JSONObject phase, int phaseIndex, String failureType,
                                    JSONObject details) throws Exception {
        JSONObject failure = new JSONObject();
        failure.put("phase_index", phaseIndex);
        failure.put("phase", phase.optString("phase", "unknown"));
        failure.put("driver_mode", phase.optString("driver_mode", "unknown"));
        failure.put("round", phase.optInt("round", -1));
        failure.put("failure_type", normalize(failureType));
        failure.put("failure_stage", details.optString("failure_stage",
                phase.optString("failure_stage", "runner")));
        if (details.has("vk_result")) failure.put("vk_result", details.opt("vk_result"));
        if (details.has("vulkan_operation")) {
            failure.put("vulkan_operation", details.opt("vulkan_operation"));
        }
        String message = details.optString("error", phase.optString("error", failureType));
        failure.put("message", message);
        failure.put("finished_at_ms", phase.has("finished_at_ms")
                ? phase.opt("finished_at_ms") : JSONObject.NULL);
        return failure;
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty()) return "phase_failure";
        if (value.contains("runner_timeout")) return "timeout";
        if (value.contains("runner_crash")) return "crash";
        if (value.contains("device_lost") || value.contains("DEVICE_LOST")) {
            return "vk_error_device_lost";
        }
        return value;
    }
}
