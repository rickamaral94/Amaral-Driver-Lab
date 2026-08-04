package com.amaral.driverlab.telemetry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal opt-in writer that emulator projects can embed without network permissions.
 * The completed JSON is published atomically and includes a canonical SHA-256 payload hash.
 */
public final class TelemetrySessionWriter implements Closeable {
    private final File target;
    private final JSONObject root;
    private final JSONArray samples = new JSONArray();
    private final JSONArray events = new JSONArray();
    private boolean finished;

    private TelemetrySessionWriter(File target, JSONObject metadata) throws Exception {
        this.target = target;
        this.root = new JSONObject(metadata.toString());
        validateMetadata(root);
        root.put("telemetry_schema_version", TelemetryContract.TELEMETRY_SCHEMA_VERSION);
        root.put("telemetry_sdk_version", TelemetryContract.SDK_VERSION);
        root.remove("samples");
        root.remove("events");
        root.remove("integrity");
    }

    public static TelemetrySessionWriter create(File target, JSONObject metadata) throws Exception {
        if (target == null) throw new IllegalArgumentException("target ausente");
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalArgumentException("diretório de saída indisponível");
        }
        return new TelemetrySessionWriter(target, metadata);
    }

    public synchronized TelemetrySessionWriter appendFrame(long relativeMs,
                                                            double frameTimeMs,
                                                            Double gpuTimeMs,
                                                            Double presentWaitMs) throws Exception {
        ensureOpen();
        if (samples.length() >= TelemetryContract.MAX_FRAME_SAMPLES) {
            throw new IllegalStateException("limite de frame samples excedido");
        }
        if (relativeMs < 0L || !finitePositive(frameTimeMs) || frameTimeMs > 10_000.0) {
            throw new IllegalArgumentException("frame sample inválido");
        }
        JSONObject sample = new JSONObject()
                .put("relative_ms", relativeMs)
                .put("frame_time_ms", frameTimeMs);
        if (gpuTimeMs != null) {
            if (!finitePositive(gpuTimeMs) || gpuTimeMs > 10_000.0) {
                throw new IllegalArgumentException("gpu_time_ms inválido");
            }
            sample.put("gpu_time_ms", gpuTimeMs);
        }
        if (presentWaitMs != null) {
            if (!finiteNonNegative(presentWaitMs) || presentWaitMs > 10_000.0) {
                throw new IllegalArgumentException("present_wait_ms inválido");
            }
            sample.put("present_wait_ms", presentWaitMs);
        }
        samples.put(sample);
        return this;
    }

    public synchronized TelemetrySessionWriter appendEvent(long relativeMs,
                                                            String type,
                                                            String severity,
                                                            String code,
                                                            JSONObject details) throws Exception {
        ensureOpen();
        if (events.length() >= TelemetryContract.MAX_EVENTS) {
            throw new IllegalStateException("limite de eventos excedido");
        }
        if (relativeMs < 0L || !TelemetryContract.EVENT_TYPES.contains(type)
                || !TelemetryContract.SEVERITIES.contains(severity)
                || code == null || !code.matches("[A-Za-z0-9._-]{1,96}")) {
            throw new IllegalArgumentException("evento inválido");
        }
        JSONObject event = new JSONObject()
                .put("relative_ms", relativeMs)
                .put("type", type)
                .put("severity", severity)
                .put("code", code);
        if (details != null) event.put("details", new JSONObject(details.toString()));
        events.put(event);
        return this;
    }

    public synchronized File finish(long finishedAtMs) throws Exception {
        ensureOpen();
        long createdAtMs = root.getLong("created_at_ms");
        if (finishedAtMs < createdAtMs
                || finishedAtMs - createdAtMs > TelemetryContract.MAX_SESSION_DURATION_MS) {
            throw new IllegalArgumentException("duração da sessão inválida");
        }
        root.put("finished_at_ms", finishedAtMs)
                .put("samples", samples)
                .put("events", events);
        String payloadHash = sha256(root);
        root.put("integrity", new JSONObject()
                .put("algorithm", "sha256")
                .put("canonicalization", "json_canonical_v1")
                .put("payload_sha256", payloadHash));
        writeAtomic(target, root.toString(2));
        finished = true;
        return target;
    }

    public synchronized JSONObject snapshot() throws Exception {
        JSONObject copy = new JSONObject(root.toString())
                .put("samples", new JSONArray(samples.toString()))
                .put("events", new JSONArray(events.toString()));
        return copy;
    }

    @Override
    public synchronized void close() {
        finished = true;
    }

    private void ensureOpen() {
        if (finished) throw new IllegalStateException("sessão já finalizada");
    }

    private static void validateMetadata(JSONObject value) {
        if (!value.optString("session_id", "").matches("[A-Za-z0-9._-]{8,96}")) {
            throw new IllegalArgumentException("session_id inválido");
        }
        if (value.optLong("created_at_ms", -1L) <= 0L) {
            throw new IllegalArgumentException("created_at_ms inválido");
        }
        JSONObject privacy = value.optJSONObject("privacy");
        if (privacy == null || !"sha256".equals(privacy.optString("game_identity_mode"))
                || !privacy.optString("game_key_sha256", "").matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("privacy/game_key_sha256 inválido");
        }
        if (privacy.optBoolean("contains_title", true)
                || privacy.optBoolean("contains_paths", true)
                || privacy.optBoolean("contains_account_identifiers", true)) {
            throw new IllegalArgumentException("metadados privados não são aceitos");
        }
        JSONObject collection = value.optJSONObject("collection");
        if (collection == null
                || !TelemetryContract.COLLECTION_METHODS.contains(
                        collection.optString("method"))
                || !TelemetryContract.FRAME_CLOCKS.contains(
                        collection.optString("frame_time_clock"))
                || !TelemetryContract.SAMPLE_POLICIES.contains(
                        collection.optString("sample_policy"))) {
            throw new IllegalArgumentException("collection inválido");
        }
        JSONObject driver = value.optJSONObject("driver");
        if (driver == null || !TelemetryContract.DRIVER_MODES.contains(
                driver.optString("mode"))) {
            throw new IllegalArgumentException("driver inválido");
        }
        if ("custom".equals(driver.optString("mode"))
                && !driver.optString("package_sha256", "").matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("package_sha256 obrigatório para driver custom");
        }
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static void writeAtomic(File target, String value) throws Exception {
        File parent = target.getAbsoluteFile().getParentFile();
        File temporary = new File(parent, target.getName() + ".partial");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("não foi possível substituir a sessão anterior");
        }
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("falha ao publicar a sessão");
        }
    }

    private static String sha256(JSONObject value) throws Exception {
        JSONObject copy = new JSONObject(value.toString());
        copy.remove("integrity");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalize(copy).getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(64);
        for (byte item : digest) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }

    private static String canonicalize(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder output = new StringBuilder("{");
            for (int index = 0; index < keys.size(); ++index) {
                if (index > 0) output.append(',');
                String key = keys.get(index);
                output.append(JSONObject.quote(key)).append(':')
                        .append(canonicalize(object.opt(key)));
            }
            return output.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder output = new StringBuilder("[");
            for (int index = 0; index < array.length(); ++index) {
                if (index > 0) output.append(',');
                output.append(canonicalize(array.opt(index)));
            }
            return output.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return JSONObject.quote(String.valueOf(value));
    }
}
