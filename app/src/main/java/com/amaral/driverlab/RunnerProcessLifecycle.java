package com.amaral.driverlab;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/** Timing contract for activities that share the isolated {@code :runner} process. */
final class RunnerProcessLifecycle {
    static final long VISUAL_COMPLETION_DELAY_MS = 250L;
    static final long POST_DESTROY_TERMINATION_DELAY_MS = 350L;
    static final long RELAUNCH_SAFETY_MARGIN_MS = 500L;
    static final long RELAUNCH_DELAY_MS = 1_200L;

    private RunnerProcessLifecycle() {}

    static boolean hasSafeRelaunchGap() {
        return RELAUNCH_DELAY_MS >= VISUAL_COMPLETION_DELAY_MS
                + POST_DESTROY_TERMINATION_DELAY_MS + RELAUNCH_SAFETY_MARGIN_MS;
    }

    /**
     * Retires the isolated process only after Android has destroyed the completed Activity.
     * Killing it immediately after finish() can race the task-stack transaction and leave the
     * launcher in front instead of returning to the qualification screen.
     */
    static void retireAfterActivityDestroyed() {
        int pid = Process.myPid();
        AppDiagnostics.event("runner_process_retirement_scheduled",
                AppDiagnostics.details(
                        "pid", pid,
                        "delay_ms", POST_DESTROY_TERMINATION_DELAY_MS));
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> Process.killProcess(pid), POST_DESTROY_TERMINATION_DELAY_MS);
    }
}
