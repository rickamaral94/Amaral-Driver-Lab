package com.amaral.driverlab.telemetry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Small producer-side builder for schema-v1 telemetry bundles. */
public final class TelemetrySession {
    private final JSONObject session;
    private final JSONArray events = new JSONArray();
    private long lastTimestampNs = -1L;
    private boolean finished;

    private TelemetrySession(Builder builder) {
        try {
            session = new JSONObject()
                    .put("session_id", builder.sessionId)
                    .put("started_at_unix_ms", builder.startedAtUnixMs)
                    .put("ended_at_unix_ms", builder.startedAtUnixMs)
                    .put("status", "interrupted")
                    .put("producer", new JSONObject()
                            .put("name", builder.producerName)
                            .put("version", builder.producerVersion)
                            .put("sdk_version", TelemetryContract.SDK_VERSION))
                    .put("emulator", new JSONObject()
                            .put("name", builder.emulatorName)
                            .put("package_name", builder.emulatorPackageName)
                            .put("version", builder.emulatorVersion))
                    .put("content", new JSONObject()
                            .put("content_id_hash", builder.contentIdHash)
                            .put("platform", builder.platform))
                    .put("driver_binding", builder.driverBinding)
                    .put("collection", new JSONObject()
                            .put("opt_in", true)
                            .put("local_only", true)
                            .put("clock", TelemetryContract.CLOCK_MONOTONIC_NS)
                            .put("frame_metric", TelemetryContract.FRAME_METRIC_DELTA_NS))
                    .put("event_count", 0);
        } catch (Exception error) {
            throw new IllegalArgumentException("configuração de telemetria inválida", error);
        }
    }

    public static Builder builder(String producerName, String producerVersion,
                                  String emulatorName, String emulatorPackageName,
                                  String emulatorVersion, String contentIdHash,
                                  String platform) {
        return new Builder(producerName, producerVersion, emulatorName, emulatorPackageName,
                emulatorVersion, contentIdHash, platform);
    }

    public synchronized TelemetrySession recordFrame(long timestampNs, long frameDeltaNs) {
        return add(timestampNs, TelemetryContract.EVENT_FRAME,
                event -> event.put("frame_delta_ns", frameDeltaNs));
    }

    public synchronized TelemetrySession recordFrame(long timestampNs, long frameDeltaNs,
                                                      long presentedAtNs) {
        return add(timestampNs, TelemetryContract.EVENT_FRAME, event -> event
                .put("frame_delta_ns", frameDeltaNs)
                .put("presented_at_ns", presentedAtNs));
    }

    public synchronized TelemetrySession recordMarker(long timestampNs, String name,
                                                       String category, String value) {
        return add(timestampNs, TelemetryContract.EVENT_MARKER, event -> {
            event.put("name", name);
            if (category != null && !category.isEmpty()) event.put("category", category);
            if (value != null && !value.isEmpty()) event.put("value", value);
        });
    }

    public synchronized TelemetrySession recordVulkanError(long timestampNs, String operation,
                                                            int vkResult) {
        return add(timestampNs, TelemetryContract.EVENT_VULKAN_ERROR, event -> event
                .put("vk_operation", operation)
                .put("vk_result", vkResult));
    }

    public synchronized TelemetrySession recordRenderWarning(long timestampNs, String code,
                                                              String details) {
        return add(timestampNs, TelemetryContract.EVENT_RENDER_WARNING, event -> {
            event.put("code", code);
            if (details != null && !details.isEmpty()) event.put("details", details);
        });
    }

    public synchronized TelemetrySession recordCrash(long timestampNs, String code,
                                                      String details) {
        return add(timestampNs, TelemetryContract.EVENT_CRASH, event -> {
            event.put("code", code);
            if (details != null && !details.isEmpty()) event.put("details", details);
        });
    }

    public synchronized TelemetrySession recordHang(long timestampNs, long durationNs,
                                                     String details) {
        return add(timestampNs, TelemetryContract.EVENT_HANG, event -> {
            event.put("duration_ns", durationNs);
            if (details != null && !details.isEmpty()) event.put("details", details);
        });
    }

    public synchronized TelemetrySession recordState(long timestampNs, String state) {
        return add(timestampNs, TelemetryContract.EVENT_SESSION_STATE,
                event -> event.put("state", state));
    }

    public synchronized JSONObject finish(String status, long endedAtUnixMs) {
        ensureOpen();
        try {
            session.put("status", status)
                    .put("ended_at_unix_ms", endedAtUnixMs)
                    .put("event_count", events.length());
            JSONObject bundle = new JSONObject()
                    .put("telemetry_schema_version", TelemetryContract.TELEMETRY_SCHEMA_VERSION)
                    .put("session", new JSONObject(session.toString()))
                    .put("events", new JSONArray(events.toString()));
            TelemetryContract.validateBundle(bundle);
            finished = true;
            return bundle;
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error;
            throw new IllegalArgumentException("não foi possível finalizar a telemetria", error);
        }
    }

    public static void write(JSONObject bundle, OutputStream output) throws IOException {
        TelemetryContract.validateBundle(bundle);
        output.write(bundle.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private TelemetrySession add(long timestampNs, String type, EventWriter writer) {
        ensureOpen();
        if (events.length() >= TelemetryContract.MAX_EVENTS) {
            throw new IllegalStateException("limite de eventos atingido");
        }
        if (timestampNs < lastTimestampNs) {
            throw new IllegalArgumentException("timestamp_ns não monotônico");
        }
        try {
            JSONObject event = new JSONObject()
                    .put("seq", events.length())
                    .put("timestamp_ns", timestampNs)
                    .put("type", type);
            writer.write(event);
            events.put(event);
            lastTimestampNs = timestampNs;
            return this;
        } catch (Exception error) {
            throw new IllegalArgumentException("evento inválido", error);
        }
    }

    private void ensureOpen() {
        if (finished) throw new IllegalStateException("sessão já finalizada");
    }

    private interface EventWriter {
        void write(JSONObject event) throws Exception;
    }

    public static final class Builder {
        private final String producerName;
        private final String producerVersion;
        private final String emulatorName;
        private final String emulatorPackageName;
        private final String emulatorVersion;
        private final String contentIdHash;
        private final String platform;
        private String sessionId = UUID.randomUUID().toString();
        private long startedAtUnixMs = System.currentTimeMillis();
        private JSONObject driverBinding;

        private Builder(String producerName, String producerVersion, String emulatorName,
                        String emulatorPackageName, String emulatorVersion,
                        String contentIdHash, String platform) {
            this.producerName = producerName;
            this.producerVersion = producerVersion;
            this.emulatorName = emulatorName;
            this.emulatorPackageName = emulatorPackageName;
            this.emulatorVersion = emulatorVersion;
            this.contentIdHash = contentIdHash;
            this.platform = platform;
            this.driverBinding = defaultSystemBinding();
        }

        private static JSONObject defaultSystemBinding() {
            try {
                return new JSONObject()
                        .put("mode", TelemetryContract.DRIVER_SYSTEM)
                        .put("candidate_sha256", JSONObject.NULL);
            } catch (Exception error) {
                throw new IllegalStateException("não foi possível criar driver_binding", error);
            }
        }

        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        public Builder startedAtUnixMs(long value) {
            this.startedAtUnixMs = value;
            return this;
        }

        public Builder systemDriver(String name, String version) {
            try {
                driverBinding = new JSONObject()
                        .put("mode", TelemetryContract.DRIVER_SYSTEM)
                        .put("candidate_sha256", JSONObject.NULL);
                if (name != null && !name.isEmpty()) driverBinding.put("driver_name", name);
                if (version != null && !version.isEmpty()) driverBinding.put("driver_version", version);
                return this;
            } catch (Exception error) {
                throw new IllegalArgumentException("driver do sistema inválido", error);
            }
        }

        public Builder customDriver(String candidateSha256, String name, String version) {
            try {
                driverBinding = new JSONObject()
                        .put("mode", TelemetryContract.DRIVER_CUSTOM)
                        .put("candidate_sha256", candidateSha256);
                if (name != null && !name.isEmpty()) driverBinding.put("driver_name", name);
                if (version != null && !version.isEmpty()) driverBinding.put("driver_version", version);
                return this;
            } catch (Exception error) {
                throw new IllegalArgumentException("driver custom inválido", error);
            }
        }

        public TelemetrySession build() {
            TelemetrySession session = new TelemetrySession(this);
            session.finish("interrupted", startedAtUnixMs);
            // Validation above verifies all immutable metadata. Return a fresh open session.
            return new TelemetrySession(this);
        }
    }
}
