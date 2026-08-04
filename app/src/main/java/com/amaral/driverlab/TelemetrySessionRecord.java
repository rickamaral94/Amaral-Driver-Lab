package com.amaral.driverlab;

import com.amaral.driverlab.telemetry.TelemetryContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

final class TelemetrySessionRecord {
    final File file;
    final JSONObject session;
    final JSONObject summary;
    final String sessionSha256;
    final String sessionId;
    final long createdAtMs;
    final long finishedAtMs;
    final String emulatorId;
    final String emulatorLabel;
    final String packageName;
    final String versionName;
    final String buildId;
    final String gameKeySha256;
    final String settingsSha256;
    final String hardwarePublicKey;
    final String socModel;
    final String gpuModel;
    final String driverMode;
    final String driverSha256;
    final String driverLabel;
    final String collectionMethod;
    final String frameTimeClock;
    final String samplePolicy;

    private TelemetrySessionRecord(File file, JSONObject session, JSONObject summary,
                                   String sessionSha256) {
        this.file = file;
        this.session = session;
        this.summary = summary;
        this.sessionSha256 = sessionSha256;
        sessionId = session.optString("session_id");
        createdAtMs = session.optLong("created_at_ms");
        finishedAtMs = session.optLong("finished_at_ms");
        JSONObject source = session.optJSONObject("source");
        emulatorId = source.optString("emulator_id");
        emulatorLabel = source.optString("display_name", emulatorId);
        packageName = source.optString("package_name");
        versionName = source.optString("version_name");
        buildId = source.optString("build_id");
        JSONObject privacy = session.optJSONObject("privacy");
        gameKeySha256 = privacy.optString("game_key_sha256").toLowerCase(Locale.US);
        JSONObject environment = session.optJSONObject("environment");
        settingsSha256 = environment.optString("settings_sha256").toLowerCase(Locale.US);
        hardwarePublicKey = environment.optString("hardware_public_key");
        socModel = environment.optString("soc_model", "unknown");
        gpuModel = environment.optString("gpu_model", "unknown");
        JSONObject driver = session.optJSONObject("driver");
        driverMode = driver.optString("mode");
        driverSha256 = "custom".equals(driverMode)
                ? driver.optString("package_sha256").toLowerCase(Locale.US) : "system";
        String name = driver.optString("name", "custom".equals(driverMode)
                ? "driver custom" : "driver do sistema");
        String version = driver.optString("version", "");
        driverLabel = version.isEmpty() ? name : name + " · " + version;
        JSONObject collection = session.optJSONObject("collection");
        collectionMethod = collection.optString("method");
        frameTimeClock = collection.optString("frame_time_clock");
        samplePolicy = collection.optString("sample_policy");
    }

    static TelemetrySessionRecord parse(File file, JSONObject session) throws Exception {
        validate(session);
        JSONObject summary = TelemetrySummary.summarize(session);
        return new TelemetrySessionRecord(file, session, summary,
                JsonCanonicalizer.sha256(session));
    }

    String comparisonKey() {
        return TelemetryContract.TELEMETRY_SCHEMA_VERSION + "|"
                + Phase9Contract.TELEMETRY_SUMMARY_VERSION + "|"
                + emulatorId + "|" + packageName + "|" + versionName + "|" + buildId + "|"
                + gameKeySha256 + "|" + settingsSha256 + "|" + hardwarePublicKey + "|"
                + collectionMethod + "|" + frameTimeClock + "|" + samplePolicy;
    }

    String displayLabel() {
        JSONObject frame = summary.optJSONObject("frame");
        double p99 = frame == null ? Double.NaN
                : frame.optDouble("p99_frame_ms", Double.NaN);
        String metric = Double.isFinite(p99)
                ? String.format(Locale.US, "P99 %.2f ms", p99) : "sem frame metrics";
        return emulatorLabel + " · " + driverLabel + " · " + metric + " · " + sessionId;
    }

    JSONObject compactJson() throws Exception {
        return new JSONObject()
                .put("session_id", sessionId)
                .put("session_sha256", sessionSha256)
                .put("created_at_ms", createdAtMs)
                .put("finished_at_ms", finishedAtMs)
                .put("emulator_id", emulatorId)
                .put("package_name", packageName)
                .put("version_name", versionName)
                .put("build_id", buildId)
                .put("game_key_sha256", gameKeySha256)
                .put("settings_sha256", settingsSha256)
                .put("hardware_public_key", hardwarePublicKey)
                .put("soc_model", socModel)
                .put("gpu_model", gpuModel)
                .put("driver_mode", driverMode)
                .put("driver_sha256", driverSha256)
                .put("driver_label", driverLabel)
                .put("collection_method", collectionMethod)
                .put("frame_time_clock", frameTimeClock)
                .put("sample_policy", samplePolicy)
                .put("summary", summary);
    }

    private static void validate(JSONObject session) throws Exception {
        if (session.optInt("telemetry_schema_version", -1)
                != TelemetryContract.TELEMETRY_SCHEMA_VERSION) {
            throw new IllegalArgumentException("telemetry_schema_version não suportado");
        }
        if (session.optInt("telemetry_sdk_version", -1) != TelemetryContract.SDK_VERSION) {
            throw new IllegalArgumentException("telemetry_sdk_version não suportado");
        }
        if (!session.optString("session_id", "").matches("[A-Za-z0-9._-]{8,96}")) {
            throw new IllegalArgumentException("session_id inválido");
        }
        long created = session.optLong("created_at_ms", -1L);
        long finished = session.optLong("finished_at_ms", -1L);
        if (created <= 0L || finished < created
                || finished - created > TelemetryContract.MAX_SESSION_DURATION_MS) {
            throw new IllegalArgumentException("janela temporal inválida");
        }
        validateSource(session.optJSONObject("source"));
        validatePrivacy(session.optJSONObject("privacy"));
        validateEnvironment(session.optJSONObject("environment"));
        validateDriver(session.optJSONObject("driver"));
        validateCollection(session.optJSONObject("collection"));
        validateSamples(session.optJSONArray("samples"), finished - created);
        validateEvents(session.optJSONArray("events"), finished - created);
        JSONObject integrity = session.optJSONObject("integrity");
        if (integrity == null || !"sha256".equals(integrity.optString("algorithm"))
                || !"json_canonical_v1".equals(
                        integrity.optString("canonicalization"))) {
            throw new IllegalArgumentException("integrity ausente ou incompatível");
        }
        String expected = integrity.optString("payload_sha256", "");
        if (!expected.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("payload_sha256 inválido");
        }
        String actual = JsonCanonicalizer.sha256WithoutKey(session, "integrity");
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("payload_sha256 divergente");
        }
    }

    private static void validateSource(JSONObject source) {
        if (source == null
                || !source.optString("emulator_id", "")
                        .matches("[a-z0-9][a-z0-9._-]{2,63}")
                || !source.optString("package_name", "")
                        .matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
                || !bounded(source.optString("version_name"), 1, 96)
                || !bounded(source.optString("build_id"), 1, 128)) {
            throw new IllegalArgumentException("source inválido");
        }
        String displayName = source.optString("display_name", "");
        if (!displayName.isEmpty() && !bounded(displayName, 1, 96)) {
            throw new IllegalArgumentException("source.display_name inválido");
        }
    }

    private static void validatePrivacy(JSONObject privacy) {
        if (privacy == null || !"sha256".equals(privacy.optString("game_identity_mode"))
                || !privacy.optString("game_key_sha256", "")
                        .matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("privacy inválido");
        }
        if (privacy.optBoolean("contains_title", true)
                || privacy.optBoolean("contains_paths", true)
                || privacy.optBoolean("contains_account_identifiers", true)) {
            throw new IllegalArgumentException("sessão contém identidade privada proibida");
        }
    }

    private static void validateEnvironment(JSONObject environment) {
        if (environment == null
                || !bounded(environment.optString("hardware_public_key"), 3, 256)
                || !environment.optString("settings_sha256", "")
                        .matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("environment inválido");
        }
        int androidSdk = environment.optInt("android_sdk", -1);
        if (androidSdk < 21 || androidSdk > 100) {
            throw new IllegalArgumentException("android_sdk inválido");
        }
    }

    private static void validateDriver(JSONObject driver) {
        if (driver == null || !TelemetryContract.DRIVER_MODES.contains(
                driver.optString("mode"))) {
            throw new IllegalArgumentException("driver inválido");
        }
        if ("custom".equals(driver.optString("mode"))
                && !driver.optString("package_sha256", "")
                        .matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("driver custom sem package_sha256");
        }
    }

    private static void validateCollection(JSONObject collection) {
        if (collection == null
                || !TelemetryContract.COLLECTION_METHODS.contains(
                        collection.optString("method"))
                || !TelemetryContract.FRAME_CLOCKS.contains(
                        collection.optString("frame_time_clock"))
                || !TelemetryContract.SAMPLE_POLICIES.contains(
                        collection.optString("sample_policy"))) {
            throw new IllegalArgumentException("collection inválido");
        }
    }

    private static void validateSamples(JSONArray samples, long durationMs) {
        if (samples == null || samples.length() > TelemetryContract.MAX_FRAME_SAMPLES) {
            throw new IllegalArgumentException("samples ausente ou excede o limite");
        }
        long previous = -1L;
        for (int index = 0; index < samples.length(); ++index) {
            JSONObject sample = samples.optJSONObject(index);
            if (sample == null) throw new IllegalArgumentException("sample inválido");
            long relative = sample.optLong("relative_ms", -1L);
            double frame = sample.optDouble("frame_time_ms", Double.NaN);
            if (relative < previous || relative < 0L || relative > durationMs
                    || !finiteRange(frame, 0.0, 10_000.0, false)) {
                throw new IllegalArgumentException("frame sample inválido no índice " + index);
            }
            double gpu = sample.optDouble("gpu_time_ms", Double.NaN);
            if (sample.has("gpu_time_ms") && !finiteRange(gpu, 0.0, 10_000.0, false)) {
                throw new IllegalArgumentException("gpu_time_ms inválido no índice " + index);
            }
            double present = sample.optDouble("present_wait_ms", Double.NaN);
            if (sample.has("present_wait_ms")
                    && !finiteRange(present, 0.0, 10_000.0, true)) {
                throw new IllegalArgumentException("present_wait_ms inválido no índice " + index);
            }
            previous = relative;
        }
    }

    private static void validateEvents(JSONArray events, long durationMs) {
        if (events == null || events.length() > TelemetryContract.MAX_EVENTS) {
            throw new IllegalArgumentException("events ausente ou excede o limite");
        }
        long previous = -1L;
        for (int index = 0; index < events.length(); ++index) {
            JSONObject event = events.optJSONObject(index);
            if (event == null) throw new IllegalArgumentException("evento inválido");
            long relative = event.optLong("relative_ms", -1L);
            if (relative < previous || relative < 0L || relative > durationMs
                    || !TelemetryContract.EVENT_TYPES.contains(event.optString("type"))
                    || !TelemetryContract.SEVERITIES.contains(event.optString("severity"))
                    || !event.optString("code", "")
                            .matches("[A-Za-z0-9._-]{1,96}")) {
                throw new IllegalArgumentException("evento inválido no índice " + index);
            }
            previous = relative;
        }
    }

    private static boolean finiteRange(double value, double minimum, double maximum,
                                       boolean inclusiveMinimum) {
        return Double.isFinite(value)
                && (inclusiveMinimum ? value >= minimum : value > minimum)
                && value <= maximum;
    }

    private static boolean bounded(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum;
    }
}
