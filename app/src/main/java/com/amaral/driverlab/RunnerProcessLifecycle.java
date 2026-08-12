package com.amaral.driverlab;

/** Timing contract for activities that share the isolated {@code :runner} process. */
final class RunnerProcessLifecycle {
    static final long VISUAL_COMPLETION_DELAY_MS = 250L;
    static final long SELF_TERMINATION_DELAY_MS = 350L;
    static final long RELAUNCH_SAFETY_MARGIN_MS = 500L;
    static final long RELAUNCH_DELAY_MS = 1_200L;

    private RunnerProcessLifecycle() {}

    static boolean hasSafeRelaunchGap() {
        return RELAUNCH_DELAY_MS >= VISUAL_COMPLETION_DELAY_MS
                + SELF_TERMINATION_DELAY_MS + RELAUNCH_SAFETY_MARGIN_MS;
    }
}
