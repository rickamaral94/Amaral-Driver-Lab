package com.amaral.driverlab;

import org.json.JSONObject;

import java.io.File;

/** Keeps the analytical arm separate from the Vulkan loader actually used. */
final class DriverExecutionIdentity {
    static final String ROLE_SYSTEM = "system";
    static final String ROLE_REFERENCE = "reference";
    static final String ROLE_CANDIDATE = "candidate";

    private DriverExecutionIdentity() {}

    static String role(boolean candidateArm, boolean hasCustomDriver) {
        if (candidateArm) return ROLE_CANDIDATE;
        return hasCustomDriver ? ROLE_REFERENCE : ROLE_SYSTEM;
    }

    static String mode(boolean hasCustomDriver) {
        return hasCustomDriver ? "custom" : "system";
    }

    static String injectionMode(boolean hasCustomDriver) {
        return hasCustomDriver ? "custom_dso_injection" : "platform_loader_no_injection";
    }

    /** Hashes the requested package library; this is not proof of the effective loaded DSO. */
    static Object requestedLibrarySha256(String directory, String libraryName) {
        if (directory == null || directory.isEmpty()
                || libraryName == null || libraryName.isEmpty()) return JSONObject.NULL;
        try {
            File root = new File(directory);
            File library = new File(root, libraryName);
            if (!root.isDirectory() || !library.isFile() || !ResultFiles.isInside(root, library)) {
                return JSONObject.NULL;
            }
            return ResultFiles.sha256(library);
        } catch (Exception ignored) {
            return JSONObject.NULL;
        }
    }

    static void normalizeRuntimeCapabilities(JSONObject nativeResult) throws Exception {
        if (nativeResult == null) return;
        JSONObject capabilities = nativeResult.optJSONObject("capabilities");
        if (capabilities == null) return;
        if (!capabilities.has("conformance_version")) {
            capabilities.put("conformance_version", JSONObject.NULL);
        }
        if (capabilities.has("driver_version_raw")
                && !capabilities.has("driver_version_decoded")) {
            long raw = capabilities.optLong("driver_version_raw", -1L);
            if (raw >= 0L) {
                capabilities.put("driver_version_decoded", decodeVulkanVersion(raw));
            } else capabilities.put("driver_version_decoded", JSONObject.NULL);
        }
        capabilities.put("driver_version_decode_policy",
                "VK_VERSION_fields_only; vendor_semantics_unknown");
    }

    private static String decodeVulkanVersion(long raw) {
        long major = (raw >>> 22) & 0x7fL;
        long minor = (raw >>> 12) & 0x3ffL;
        long patch = raw & 0xfffL;
        return major + "." + minor + "." + patch;
    }

    /** Analytical A/B arm; independent from whether the Vulkan loader is custom. */
    static boolean isCandidateArm(JSONObject phase) {
        if (phase == null) return false;
        String role = phase.optString("driver_role", "");
        if (ROLE_CANDIDATE.equals(role)) return true;
        if (ROLE_REFERENCE.equals(role) || ROLE_SYSTEM.equals(role)) return false;

        String historicalPhase = phase.optString("phase", "");
        if (ROLE_CANDIDATE.equals(historicalPhase)) return true;
        if (ROLE_SYSTEM.equals(historicalPhase)) return false;

        // Compatibility with reports/tests created before driver_role and phase
        // were recorded separately. In those files, custom meant candidate.
        return "custom".equals(phase.optString("driver_mode", "system"));
    }

    static boolean isReferenceArm(JSONObject phase) {
        return phase != null && !isCandidateArm(phase);
    }
}
