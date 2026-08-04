package com.amaral.driverlab.telemetry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Versioned public contract for Amaral Driver Lab emulator telemetry. */
public final class TelemetryContract {
    public static final int TELEMETRY_SCHEMA_VERSION = 1;
    public static final int SDK_VERSION = 1;
    public static final int METRIC_SUMMARY_VERSION = 1;
    public static final int COMPARISON_VERSION = 1;
    public static final int LINK_SCHEMA_VERSION = 1;
    public static final int MAX_FRAME_SAMPLES = 120_000;
    public static final int MAX_EVENTS = 10_000;
    public static final long MAX_SESSION_DURATION_MS = 24L * 60L * 60L * 1_000L;

    public static final Set<String> COLLECTION_METHODS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("embedded_sdk", "external_adapter", "manual_import")));
    public static final Set<String> FRAME_CLOCKS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("emulator_internal", "monotonic_present",
                    "choreographer", "unknown")));
    public static final Set<String> SAMPLE_POLICIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("every_frame", "fixed_interval", "event_only")));
    public static final Set<String> DRIVER_MODES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("system", "custom")));
    public static final Set<String> EVENT_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("shader_compile", "graphics_warning", "device_lost",
                    "crash", "stutter_marker", "thermal_sample", "present_timeout",
                    "session_note")));
    public static final Set<String> SEVERITIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("info", "warning", "error", "fatal")));

    public static final String LIMITATION =
            "Telemetria de emuladores descreve uma sessão real e opt-in, mas não controla cenas, "
                    + "carga de CPU, cache, temperatura, processos em segundo plano ou entrada do "
                    + "usuário. Comparações são descritivas, não inferência estatística, e nunca "
                    + "entram automaticamente no índice Full Qualification.";

    private TelemetryContract() {}

    public static JSONObject contractJson() throws Exception {
        return new JSONObject()
                .put("emulator_telemetry_schema_version", TELEMETRY_SCHEMA_VERSION)
                .put("telemetry_sdk_version", SDK_VERSION)
                .put("metric_summary_version", METRIC_SUMMARY_VERSION)
                .put("telemetry_comparison_version", COMPARISON_VERSION)
                .put("telemetry_link_schema_version", LINK_SCHEMA_VERSION)
                .put("maximum_frame_samples", MAX_FRAME_SAMPLES)
                .put("maximum_events", MAX_EVENTS)
                .put("maximum_session_duration_ms", MAX_SESSION_DURATION_MS)
                .put("collection_methods", sorted(COLLECTION_METHODS))
                .put("frame_time_clocks", sorted(FRAME_CLOCKS))
                .put("sample_policies", sorted(SAMPLE_POLICIES))
                .put("event_types", sorted(EVENT_TYPES))
                .put("game_identity_mode", "sha256_only")
                .put("automatic_upload", false)
                .put("included_in_full_qualification_score", false)
                .put("limitations", LIMITATION);
    }

    private static JSONArray sorted(Set<String> values) {
        String[] items = values.toArray(new String[0]);
        Arrays.sort(items);
        return new JSONArray(Arrays.asList(items));
    }
}
