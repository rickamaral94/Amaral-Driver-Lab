package com.amaral.driverlab.telemetry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Versioned, privacy-preserving contract shared by emulator integrations and Driver Lab. */
public final class TelemetryContract {
    public static final int TELEMETRY_SCHEMA_VERSION = 1;
    public static final int SDK_VERSION = 1;
    public static final int MAX_EVENTS = 200_000;
    public static final long MAX_SESSION_DURATION_MS = 12L * 60L * 60L * 1000L;

    public static final String CLOCK_MONOTONIC_NS = "monotonic_ns";
    public static final String FRAME_METRIC_DELTA_NS = "presented_frame_delta_ns";

    public static final String EVENT_FRAME = "frame";
    public static final String EVENT_MARKER = "marker";
    public static final String EVENT_VULKAN_ERROR = "vulkan_error";
    public static final String EVENT_RENDER_WARNING = "render_warning";
    public static final String EVENT_CRASH = "crash";
    public static final String EVENT_HANG = "hang";
    public static final String EVENT_SESSION_STATE = "session_state";

    public static final String DRIVER_SYSTEM = "system";
    public static final String DRIVER_CUSTOM = "custom";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final Pattern WINDOWS_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Set<String> EVENT_TYPES = Set.of(
            EVENT_FRAME, EVENT_MARKER, EVENT_VULKAN_ERROR, EVENT_RENDER_WARNING,
            EVENT_CRASH, EVENT_HANG, EVENT_SESSION_STATE);
    private static final Set<String> STATUSES = Set.of("complete", "crashed", "interrupted");
    private static final Set<String> STATES = Set.of("started", "paused", "resumed", "stopped");
    private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList(
            "user", "username", "user_name", "email", "phone", "address", "ip",
            "ip_address", "mac", "mac_address", "serial", "serial_number", "android_id",
            "advertising_id", "token", "access_token", "refresh_token", "password",
            "file_path", "rom_path", "save_path", "cache_path", "screenshot_path"));

    private TelemetryContract() {}

    /** Throws IllegalArgumentException when the bundle is not schema-v1 compliant. */
    public static void validateBundle(JSONObject bundle) {
        require(bundle != null, "bundle ausente");
        rejectSensitiveData(bundle, "$", 0);
        require(bundle.optInt("telemetry_schema_version", -1) == TELEMETRY_SCHEMA_VERSION,
                "telemetry_schema_version incompatível");
        requireOnlyKeys(bundle, Set.of("telemetry_schema_version", "session", "events"), "$", true);

        JSONObject session = requireObject(bundle, "session", "$session");
        requireOnlyKeys(session, Set.of(
                "session_id", "started_at_unix_ms", "ended_at_unix_ms", "status",
                "producer", "emulator", "content", "driver_binding", "collection",
                "event_count", "extensions"), "$.session", true);
        validateUuid(requireString(session, "session_id", 64));
        long started = requireLong(session, "started_at_unix_ms", 1L, Long.MAX_VALUE);
        long ended = requireLong(session, "ended_at_unix_ms", started, Long.MAX_VALUE);
        require(ended - started <= MAX_SESSION_DURATION_MS, "sessão excede 12 horas");
        require(STATUSES.contains(requireString(session, "status", 32)), "status inválido");

        JSONObject producer = requireObject(session, "producer", "$.session.producer");
        requireOnlyKeys(producer, Set.of("name", "version", "sdk_version", "extensions"),
                "$.session.producer", true);
        requireSafeText(requireString(producer, "name", 128), "producer.name");
        requireSafeText(requireString(producer, "version", 128), "producer.version");
        require(producer.optInt("sdk_version", -1) == SDK_VERSION, "sdk_version incompatível");

        JSONObject emulator = requireObject(session, "emulator", "$.session.emulator");
        requireOnlyKeys(emulator, Set.of("name", "package_name", "version", "extensions"),
                "$.session.emulator", true);
        requireSafeText(requireString(emulator, "name", 128), "emulator.name");
        String packageName = requireString(emulator, "package_name", 200);
        require(PACKAGE_NAME.matcher(packageName).matches(), "package_name inválido");
        requireSafeText(requireString(emulator, "version", 128), "emulator.version");

        JSONObject content = requireObject(session, "content", "$.session.content");
        requireOnlyKeys(content, Set.of("content_id_hash", "platform", "extensions"),
                "$.session.content", true);
        requireSha256(requireString(content, "content_id_hash", 64), "content_id_hash");
        requireSafeText(requireString(content, "platform", 64), "content.platform");

        JSONObject driver = requireObject(session, "driver_binding", "$.session.driver_binding");
        requireOnlyKeys(driver, Set.of(
                "mode", "candidate_sha256", "driver_name", "driver_version", "extensions"),
                "$.session.driver_binding", true);
        String mode = requireString(driver, "mode", 16);
        require(DRIVER_SYSTEM.equals(mode) || DRIVER_CUSTOM.equals(mode), "driver mode inválido");
        Object candidateHash = driver.opt("candidate_sha256");
        if (DRIVER_CUSTOM.equals(mode)) {
            require(candidateHash instanceof String, "candidate_sha256 obrigatório para custom");
            requireSha256((String) candidateHash, "candidate_sha256");
        } else {
            require(candidateHash == null || candidateHash == JSONObject.NULL
                    || String.valueOf(candidateHash).isEmpty(),
                    "candidate_sha256 deve ser nulo para system");
        }
        optionalSafeText(driver, "driver_name", 256);
        optionalSafeText(driver, "driver_version", 256);

        JSONObject collection = requireObject(session, "collection", "$.session.collection");
        requireOnlyKeys(collection, Set.of(
                "opt_in", "local_only", "clock", "frame_metric", "extensions"),
                "$.session.collection", true);
        require(collection.optBoolean("opt_in", false), "collection.opt_in deve ser true");
        require(collection.optBoolean("local_only", false), "collection.local_only deve ser true");
        require(CLOCK_MONOTONIC_NS.equals(requireString(collection, "clock", 32)),
                "clock incompatível");
        require(FRAME_METRIC_DELTA_NS.equals(requireString(collection, "frame_metric", 64)),
                "frame_metric incompatível");

        JSONArray events = bundle.optJSONArray("events");
        require(events != null, "events ausente");
        require(events.length() <= MAX_EVENTS, "events excede o limite");
        require(session.optInt("event_count", -1) == events.length(), "event_count divergente");
        long previousTimestamp = -1L;
        for (int index = 0; index < events.length(); ++index) {
            JSONObject event = events.optJSONObject(index);
            require(event != null, "evento inválido em " + index);
            previousTimestamp = validateEvent(event, index, previousTimestamp);
        }
    }

    public static String hashContentId(String salt, String namespace, String stableId) {
        require(salt != null && salt.length() >= 16, "salt deve ter ao menos 16 caracteres");
        require(namespace != null && !namespace.isBlank(), "namespace ausente");
        require(stableId != null && !stableId.isBlank(), "stableId ausente");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = (salt + "\u0000" + namespace + "\u0000" + stableId)
                    .getBytes(StandardCharsets.UTF_8);
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest.digest(bytes)) output.append(String.format("%02x", value & 0xff));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 indisponível", error);
        }
    }

    private static long validateEvent(JSONObject event, int index, long previousTimestamp) {
        requireOnlyKeys(event, Set.of(
                "seq", "timestamp_ns", "type", "frame_delta_ns", "presented_at_ns",
                "name", "category", "value", "vk_operation", "vk_result", "code",
                "details", "duration_ns", "state", "extensions"),
                "$.events[" + index + "]", true);
        require(event.optInt("seq", -1) == index, "seq deve ser contínuo a partir de zero");
        long timestamp = requireLong(event, "timestamp_ns", 0L, Long.MAX_VALUE);
        require(timestamp >= previousTimestamp, "timestamp_ns não monotônico");
        String type = requireString(event, "type", 32);
        require(EVENT_TYPES.contains(type), "tipo de evento inválido: " + type);

        switch (type) {
            case EVENT_FRAME:
                requireLong(event, "frame_delta_ns", 100_000L, 5_000_000_000L);
                if (event.has("presented_at_ns")) {
                    requireLong(event, "presented_at_ns", 0L, Long.MAX_VALUE);
                }
                break;
            case EVENT_MARKER:
                requireSafeText(requireString(event, "name", 128), "marker.name");
                optionalSafeText(event, "category", 64);
                optionalSafeText(event, "value", 512);
                break;
            case EVENT_VULKAN_ERROR:
                requireSafeText(requireString(event, "vk_operation", 128), "vk_operation");
                require(event.opt("vk_result") instanceof Number, "vk_result ausente");
                break;
            case EVENT_RENDER_WARNING:
            case EVENT_CRASH:
                requireSafeText(requireString(event, "code", 128), type + ".code");
                optionalSafeText(event, "details", 2048);
                break;
            case EVENT_HANG:
                requireLong(event, "duration_ns", 1_000_000_000L, 60_000_000_000L);
                optionalSafeText(event, "details", 2048);
                break;
            case EVENT_SESSION_STATE:
                require(STATES.contains(requireString(event, "state", 32)), "state inválido");
                break;
            default:
                throw new IllegalArgumentException("tipo de evento não suportado");
        }
        return timestamp;
    }

    private static void validateUuid(String value) {
        try {
            require(UUID.fromString(value).toString().equals(value.toLowerCase(Locale.US)),
                    "session_id deve ser UUID canônico");
        } catch (Exception error) {
            throw new IllegalArgumentException("session_id inválido", error);
        }
    }

    private static void rejectSensitiveData(Object value, String path, int depth) {
        require(depth <= 16, "JSON profundo demais");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String normalized = key.toLowerCase(Locale.US);
                require(!SENSITIVE_KEYS.contains(normalized)
                                && !normalized.endsWith("_path")
                                && !normalized.endsWith("_token"),
                        "campo sensível proibido: " + path + "." + key);
                rejectSensitiveData(object.opt(key), path + "." + key, depth + 1);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); ++index) {
                rejectSensitiveData(array.opt(index), path + "[" + index + "]", depth + 1);
            }
        } else if (value instanceof String) {
            String text = (String) value;
            String lower = text.toLowerCase(Locale.US);
            require(!text.startsWith("/") && !WINDOWS_PATH.matcher(text).matches()
                            && !lower.startsWith("file://") && !lower.startsWith("content://"),
                    "caminho/URI local proibido em " + path);
        }
    }

    private static JSONObject requireObject(JSONObject parent, String key, String label) {
        JSONObject value = parent.optJSONObject(key);
        require(value != null, label + " ausente");
        return value;
    }

    private static String requireString(JSONObject object, String key, int maxLength) {
        String value = object.optString(key, "");
        require(!value.isEmpty(), key + " ausente");
        require(value.length() <= maxLength, key + " excede " + maxLength + " caracteres");
        return value;
    }

    private static long requireLong(JSONObject object, String key, long minimum, long maximum) {
        Object raw = object.opt(key);
        require(raw instanceof Number, key + " ausente ou não numérico");
        long value = ((Number) raw).longValue();
        require(value >= minimum && value <= maximum, key + " fora do intervalo");
        return value;
    }

    private static void optionalSafeText(JSONObject object, String key, int maxLength) {
        if (!object.has(key) || object.isNull(key)) return;
        String value = object.optString(key, "");
        require(value.length() <= maxLength, key + " excede " + maxLength + " caracteres");
        requireSafeText(value, key);
    }

    private static void requireSafeText(String value, String label) {
        require(value.indexOf('\u0000') < 0, label + " contém NUL");
        require(!value.contains("\r") && !value.contains("\n"), label + " contém quebra de linha");
    }

    private static void requireSha256(String value, String label) {
        require(SHA256.matcher(value).matches(), label + " deve ser SHA-256 hexadecimal");
    }

    private static void requireOnlyKeys(JSONObject object, Set<String> allowed, String path,
                                        boolean allowExtensions) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (allowExtensions && "extensions".equals(key)) {
                require(object.optJSONObject(key) != null, path + ".extensions deve ser objeto");
                continue;
            }
            require(allowed.contains(key), "campo desconhecido em " + path + ": " + key);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
