package com.amaral.driverlab;

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
}
