package com.amaral.driverlab;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Android 11+ exit-history bridge, isolated to keep API 28/29 class verification safe. */
final class ExitHistoryApi30 {
    private static final String PREFERENCES = "app_diagnostics";
    private static final String LAST_EXIT_TIMESTAMP = "last_exit_timestamp";

    private ExitHistoryApi30() {}

    static void capture(Context context) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return;
            SharedPreferences preferences = context.getSharedPreferences(
                    PREFERENCES, Context.MODE_PRIVATE);
            long previousTimestamp = preferences.getLong(LAST_EXIT_TIMESTAMP, 0L);
            long newestTimestamp = previousTimestamp;
            List<ApplicationExitInfo> exits = new ArrayList<>(
                    manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 20));
            exits.sort(Comparator.comparingLong(ApplicationExitInfo::getTimestamp));
            for (ApplicationExitInfo exit : exits) {
                if (exit.getTimestamp() <= previousTimestamp) continue;
                JSONObject details = new JSONObject()
                        .put("timestamp_ms", exit.getTimestamp())
                        .put("process_name", exit.getProcessName())
                        .put("pid", exit.getPid())
                        .put("reason", exit.getReason())
                        .put("reason_name", reasonName(exit.getReason()))
                        .put("status", exit.getStatus())
                        .put("description", exit.getDescription())
                        .put("importance", exit.getImportance())
                        .put("pss_kb", exit.getPss())
                        .put("rss_kb", exit.getRss());
                String traceName = copyExitTrace(context, exit);
                details.put("trace_file", traceName == null ? JSONObject.NULL : traceName);
                AppDiagnostics.event("historical_process_exit", details);
                newestTimestamp = Math.max(newestTimestamp, exit.getTimestamp());
            }
            if (newestTimestamp > previousTimestamp) {
                preferences.edit().putLong(LAST_EXIT_TIMESTAMP, newestTimestamp).apply();
            }
        } catch (Throwable error) {
            AppDiagnostics.event("historical_exit_scan_failed",
                    AppDiagnostics.details("error", error.toString()));
        }
    }

    private static String copyExitTrace(Context context, ApplicationExitInfo exit) {
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) return null;
            String safeProcess = exit.getProcessName() == null ? "unknown"
                    : exit.getProcessName().replaceAll("[^a-zA-Z0-9._-]", "_");
            String name = "exit-trace-" + exit.getTimestamp() + "-" + safeProcess + ".trace";
            File target = new File(AppDiagnostics.logsDirectory(context), name);
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0 && total < AppDiagnostics.MAX_TRACE_BYTES) {
                    int accepted = (int) Math.min(count, AppDiagnostics.MAX_TRACE_BYTES - total);
                    output.write(buffer, 0, accepted);
                    total += accepted;
                    if (accepted < count) break;
                }
                output.getFD().sync();
            }
            pruneOldTraces(context);
            return name;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void pruneOldTraces(Context context) {
        File[] traces = AppDiagnostics.logsDirectory(context).listFiles(
                file -> file.isFile() && file.getName().startsWith("exit-trace-")
                        && file.getName().endsWith(".trace"));
        if (traces == null || traces.length <= AppDiagnostics.MAX_TRACE_FILES) return;
        java.util.Arrays.sort(traces, Comparator.comparingLong(File::lastModified).reversed());
        for (int index = AppDiagnostics.MAX_TRACE_FILES; index < traces.length; ++index) {
            traces[index].delete();
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low_memory";
            case ApplicationExitInfo.REASON_CRASH: return "java_crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native_crash";
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "initialization_failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission_change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive_resource_usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency_died";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            case ApplicationExitInfo.REASON_UNKNOWN:
            default: return "unknown";
        }
    }
}
