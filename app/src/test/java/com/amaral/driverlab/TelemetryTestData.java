package com.amaral.driverlab;

import com.amaral.driverlab.telemetry.TelemetrySessionWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

final class TelemetryTestData {
    private TelemetryTestData() {}

    static JSONObject session(File file, String id, String mode, char shaChar,
                              double frameBaseMs, boolean crash) throws Exception {
        long created = 1_780_000_000_000L;
        JSONObject metadata = new JSONObject()
                .put("session_id", id)
                .put("created_at_ms", created)
                .put("source", new JSONObject()
                        .put("emulator_id", "eden.android")
                        .put("display_name", "Eden")
                        .put("package_name", "org.eden.emulator")
                        .put("version_name", "0.2.1")
                        .put("build_id", "nightly-20260804"))
                .put("privacy", new JSONObject()
                        .put("game_identity_mode", "sha256")
                        .put("game_key_sha256", sha('9'))
                        .put("contains_title", false)
                        .put("contains_paths", false)
                        .put("contains_account_identifiers", false))
                .put("environment", new JSONObject()
                        .put("hardware_public_key", "sm8550/adreno-740")
                        .put("soc_model", "SM8550")
                        .put("gpu_model", "Adreno 740")
                        .put("android_sdk", 36)
                        .put("settings_sha256", sha('8')))
                .put("driver", new JSONObject()
                        .put("mode", mode)
                        .put("name", "custom".equals(mode) ? "Turnip" : "Sistema")
                        .put("version", "test")
                        .put("package_sha256", "custom".equals(mode)
                                ? sha(shaChar) : JSONObject.NULL))
                .put("collection", new JSONObject()
                        .put("method", "embedded_sdk")
                        .put("frame_time_clock", "emulator_internal")
                        .put("sample_policy", "every_frame")
                        .put("includes_gpu_time", true));
        TelemetrySessionWriter writer = TelemetrySessionWriter.create(file, metadata);
        for (int index = 0; index < 600; ++index) {
            double frame = frameBaseMs + (index % 20 == 0 ? 8.0 : index % 3 * 0.1);
            writer.appendFrame(index * 17L, frame, frame * 0.72, 0.4);
        }
        writer.appendEvent(100L, "thermal_sample", "info", "thermal.start",
                new JSONObject().put("temperature_c", 38.0).put("battery_percent", 90.0));
        writer.appendEvent(9_000L, "thermal_sample", "info", "thermal.end",
                new JSONObject().put("temperature_c", 43.0).put("battery_percent", 87.0));
        if (crash) writer.appendEvent(9_100L, "crash", "fatal", "native.crash", null);
        writer.finish(created + 10_200L);
        return new JSONObject(ResultFiles.readUtf8(file));
    }

    static void resign(JSONObject session) throws Exception {
        session.remove("integrity");
        String hash = JsonCanonicalizer.sha256(session);
        session.put("integrity", new JSONObject()
                .put("algorithm", "sha256")
                .put("canonicalization", "json_canonical_v1")
                .put("payload_sha256", hash));
    }

    static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    static JSONObject suiteReport(String candidateSha) throws Exception {
        return new JSONObject()
                .put("schema_version", 10)
                .put("suite_id", "suite-telemetry-link")
                .put("started_at_ms", 1_780_000_000_000L)
                .put("finished_at_ms", 1_780_000_010_000L)
                .put("workload_id", WorkloadContract.STABLE_SCENE_ID)
                .put("workload_version", 1)
                .put("workload_config", new JSONObject()
                        .put("warmup_seconds", 3)
                        .put("measure_seconds", 10)
                        .put("primary_metric", WorkloadContract.STABLE_SCENE_METRIC))
                .put("candidate", new JSONObject()
                        .put("sha256", candidateSha)
                        .put("name", "Turnip"))
                .put("hardware_identity", new JSONObject()
                        .put("manufacturer", "AYN")
                        .put("model", "Odin 2 Portal")
                        .put("soc_model", "SM8550")
                        .put("gpu_model", "Adreno 740")
                        .put("device_key", "ayn/odin-2-portal/sm8550/adreno-740")
                        .put("public_hardware_key", "sm8550/adreno-740"))
                .put("analysis_contract", new JSONObject().put("analysis_version", 1))
                .put("statistical_analysis", new JSONObject()
                        .put("available", true)
                        .put("median_paired_improvement_percent", 5.0)
                        .put("classification", "candidate_better"))
                .put("failure_catalog", new JSONArray())
                .put("validity_warnings", new JSONArray())
                .put("verdict", "candidate_better_with_confidence");
    }
}
