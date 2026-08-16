package com.amaral.driverlab;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Device identity and epoch identity are separate so old epochs remain visible but isolated. */
final class EnvironmentFingerprint {
    static final int WORKLOAD_SET_VERSION = 1;
    static final int MEASUREMENT_PROTOCOL_VERSION = 1;

    private EnvironmentFingerprint() {}

    static JSONObject capture(Context context) throws Exception {
        JSONObject device = new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("soc", Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "unknown");
        JSONObject epoch = new JSONObject()
                .put("android_build_fingerprint", Build.FINGERPRINT)
                .put("android_incremental", Build.VERSION.INCREMENTAL)
                .put("kernel", System.getProperty("os.version", "unknown"))
                .put("kgsl", readFirst("/sys/class/kgsl/kgsl-3d0/gpu_model"))
                .put("gmu", readFirst("/sys/class/kgsl/kgsl-3d0/gmu_version"))
                .put("workload_set_version", WORKLOAD_SET_VERSION)
                .put("measurement_protocol_version", MEASUREMENT_PROTOCOL_VERSION);
        return new JSONObject()
                .put("fingerprint_schema_version", 1)
                .put("device", device)
                .put("epoch", epoch)
                .put("device_fingerprint_sha256", sha256(canonical(device)))
                .put("epoch_sha256", sha256(canonical(epoch)))
                .put("workload_set_sha256", sha256("adl-ranking-workloads-v"
                        + WORKLOAD_SET_VERSION))
                .put("measurement_protocol_version", MEASUREMENT_PROTOCOL_VERSION);
    }

    static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte item : digest) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }

    private static String canonical(JSONObject value) {
        // Keys above are inserted in a fixed order; org.json preserves that order in this app.
        return value.toString();
    }

    private static String readFirst(String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) return "unknown";
            String value = ResultFiles.readUtf8(file).trim();
            return value.isEmpty() ? "unknown" : value.split("\\R", 2)[0];
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}
