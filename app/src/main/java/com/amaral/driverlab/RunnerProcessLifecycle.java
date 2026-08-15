package com.amaral.driverlab;

import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;

/** Coordinates the hand-off between activities that share the isolated runner process. */
final class RunnerProcessLifecycle {
    static final long VISUAL_COMPLETION_DELAY_MS = 250L;
    static final long ACTIVITY_DESTROY_TIMEOUT_MS = 5_000L;
    static final long PROCESS_EXIT_TIMEOUT_MS = 2_000L;
    static final long PROCESS_STATE_POLL_MS = 50L;

    interface ActiveCheck {
        boolean isActive();
    }

    interface Callback {
        void onRetired();
        void onFailure(Throwable error);
    }

    private RunnerProcessLifecycle() {}

    static boolean activityDestroyed(JSONObject state, int expectedPid) {
        return state != null
                && state.optInt("pid", -1) == expectedPid
                && state.optLong("activity_destroyed_at_ms", 0L) > 0L;
    }

    /**
     * Waits for the completed Activity to be destroyed, terminates that exact PID, and waits for
     * /proc to confirm its exit before allowing another Activity to reuse the :runner process.
     */
    static void retireCompletedRunner(Handler handler, File resultFile,
                                      ActiveCheck activeCheck, Callback callback) {
        JSONObject state = resultFile == null ? null : RunnerProcessState.read(resultFile);
        int pid = state == null ? -1 : state.optInt("pid", -1);
        if (pid <= 0) {
            AppDiagnostics.event("runner_process_retirement_skipped",
                    AppDiagnostics.details("reason", "pid_unavailable",
                            "result_file", resultFile == null
                                    ? JSONObject.NULL : resultFile.getName()));
            handler.post(callback::onRetired);
            return;
        }
        if (pid == Process.myPid()) {
            callback.onFailure(new IllegalStateException(
                    "PID do runner coincide com o processo coordenador"));
            return;
        }
        AppDiagnostics.event("runner_process_retirement_wait_started",
                AppDiagnostics.details("pid", pid,
                        "result_file", resultFile.getName()));
        long deadline = SystemClock.elapsedRealtime() + ACTIVITY_DESTROY_TIMEOUT_MS;
        awaitActivityDestroyed(handler, resultFile, pid, deadline, activeCheck, callback);
    }

    private static void awaitActivityDestroyed(Handler handler, File resultFile, int pid,
                                               long deadline, ActiveCheck activeCheck,
                                               Callback callback) {
        if (!activeCheck.isActive()) return;
        if (!processExists(pid)) {
            retirementComplete(handler, resultFile, pid, "already_exited", callback);
            return;
        }
        boolean destroyed = activityDestroyed(RunnerProcessState.read(resultFile), pid);
        boolean timedOut = SystemClock.elapsedRealtime() >= deadline;
        if (!destroyed && !timedOut) {
            handler.postDelayed(() -> awaitActivityDestroyed(handler, resultFile, pid, deadline,
                    activeCheck, callback), PROCESS_STATE_POLL_MS);
            return;
        }
        AppDiagnostics.event("runner_process_kill_requested",
                AppDiagnostics.details("pid", pid,
                        "result_file", resultFile.getName(),
                        "activity_destroyed", destroyed,
                        "forced_after_timeout", timedOut && !destroyed));
        Process.killProcess(pid);
        long exitDeadline = SystemClock.elapsedRealtime() + PROCESS_EXIT_TIMEOUT_MS;
        awaitProcessExit(handler, resultFile, pid, exitDeadline, activeCheck, callback);
    }

    private static void awaitProcessExit(Handler handler, File resultFile, int pid,
                                         long deadline, ActiveCheck activeCheck,
                                         Callback callback) {
        if (!activeCheck.isActive()) return;
        if (!processExists(pid)) {
            retirementComplete(handler, resultFile, pid, "coordinator_kill", callback);
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            AppDiagnostics.event("runner_process_retirement_failed",
                    AppDiagnostics.details("pid", pid,
                            "result_file", resultFile.getName(),
                            "reason", "pid_still_alive"));
            callback.onFailure(new IllegalStateException(
                    "O processo isolado não encerrou antes do próximo teste"));
            return;
        }
        handler.postDelayed(() -> awaitProcessExit(handler, resultFile, pid, deadline,
                activeCheck, callback), PROCESS_STATE_POLL_MS);
    }

    private static void retirementComplete(Handler handler, File resultFile, int pid,
                                           String reason, Callback callback) {
        AppDiagnostics.event("runner_process_retirement_completed",
                AppDiagnostics.details("pid", pid,
                        "result_file", resultFile.getName(),
                        "reason", reason));
        handler.post(callback::onRetired);
    }

    private static boolean processExists(int pid) {
        return pid > 0 && new File("/proc/" + pid).exists();
    }
}
