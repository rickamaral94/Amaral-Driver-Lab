package com.amaral.driverlab;

import org.json.JSONObject;

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
