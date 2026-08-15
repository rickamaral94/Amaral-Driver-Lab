package com.amaral.driverlab;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared normalization rules. Runtime evidence and package labels remain distinct sources. */
final class DriverIdentityPolicy {
    static final int POLICY_VERSION = 2;

    static final String QUALCOMM_BLOB = "qualcomm_blob";
    static final String TURNIP = "turnip";
    static final String OTHER = "other";
    static final String UNKNOWN = "unknown";

    static final String RUNTIME_CONFIRMED = "runtime_confirmed";
    static final String INFERRED = "inferred";
    static final String DISPUTED = "disputed";
    static final String UNAUDITED = "unaudited";

    private static final Pattern QUALCOMM_VENDOR = Pattern.compile(
            "(?i)\\bQualcomm\\s+(?:Proprietary|Technologies(?:,?\\s*Inc\\.?)?)\\b");
    private static final Pattern QUALCOMM_DRIVER_ID = Pattern.compile(
            "(?i)\\b(?:VK_DRIVER_ID_)?QUALCOMM_PROPRIETARY\\b");
    private static final Pattern TURNIP_PATTERN = Pattern.compile(
            "(?i)\\b(?:turnip|freedreno|(?:VK_DRIVER_ID_)?MESA_TURNIP)\\b");
    private static final Pattern MESA_DRIVER = Pattern.compile(
            "(?i)\\bMesa\\b(?=[^\\n]{0,80}\\b(?:driver|turnip|freedreno)\\b)");

    private DriverIdentityPolicy() {}

    static String classifyRuntimeText(String value) {
        String text = compact(value);
        if (text.isEmpty()) return UNKNOWN;
        if (QUALCOMM_VENDOR.matcher(text).find()
                || QUALCOMM_DRIVER_ID.matcher(text).find()) return QUALCOMM_BLOB;
        if (TURNIP_PATTERN.matcher(text).find()
                || MESA_DRIVER.matcher(text).find()) return TURNIP;
        return OTHER;
    }

    static String classifyPackageMetadata(JSONObject driver) {
        if (driver == null) return UNKNOWN;
        StringBuilder text = new StringBuilder();
        append(text, driver.optString("name"));
        append(text, driver.optString("vendor"));
        append(text, driver.optString("packageVersion"));
        append(text, driver.optString("driverVersion"));
        JSONObject metadata = driver.optJSONObject("metadata");
        if (metadata != null) append(text, metadata.toString());
        String classified = classifyRuntimeText(text.toString());
        return OTHER.equals(classified) ? UNKNOWN : classified;
    }

    static boolean isConfirmed(String confidence, String identity) {
        return RUNTIME_CONFIRMED.equals(confidence)
                && !UNKNOWN.equals(identity)
                && identity != null && !identity.trim().isEmpty();
    }

    static String displayName(String identity) {
        if (QUALCOMM_BLOB.equals(identity)) return "Qualcomm proprietary driver";
        if (TURNIP.equals(identity)) return "Turnip (Mesa Freedreno)";
        if (OTHER.equals(identity)) return "Other Vulkan driver";
        return "Unknown";
    }

    private static void append(StringBuilder output, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (output.length() > 0) output.append(' ');
        output.append(value);
    }

    private static String compact(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
