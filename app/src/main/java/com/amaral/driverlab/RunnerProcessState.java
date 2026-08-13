package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

/** Durable breadcrumbs shared by isolated runner processes and their coordinators. */
final class RunnerProcessState {
    static final int SCHEMA_VERSION = 1;
    private static final int MAX_BREADCRUMBS = 24;

    private RunnerProcessState() {}

    static File fileFor(File resultFile) {
        return new File(resultFile.getAbsolutePath() + ".state");
    }

    static void start(File resultFile, int pid, long startedAtMs) throws Exception {
        JSONObject state = new JSONObject()
                .put("runner_state_schema_version", SCHEMA_VERSION)
                .put("state", "started")
                .put("pid", pid)
                .put("started_at_ms", startedAtMs)
                .put("last_stage", "runner_started")
                .put("last_stage_at_ms", startedAtMs)
                .put("context", new JSONObject())
                .put("breadcrumbs", new JSONArray()
                        .put(breadcrumb("runner_started", startedAtMs)));
        ResultFiles.writeAtomic(fileFor(resultFile), state.toString(2));
        AppDiagnostics.event("runner_state_started", new JSONObject()
                .put("result_file", resultFile.getName())
                .put("pid", pid));
    }

    static void checkpoint(File resultFile, String stage, JSONObject details) throws Exception {
        JSONObject state = read(resultFile);
        if (state == null) {
            throw new IllegalStateException("Estado do runner ausente");
        }
        long now = System.currentTimeMillis();
        state.put("state", "started")
                .put("last_stage", stage)
                .put("last_stage_at_ms", now);
        JSONObject context = state.optJSONObject("context");
        if (context == null) context = new JSONObject();
        if (details != null) {
            Iterator<String> keys = details.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                context.put(key, details.opt(key));
            }
        }
        state.put("context", context);
        JSONArray previous = state.optJSONArray("breadcrumbs");
        JSONArray breadcrumbs = new JSONArray();
        int start = previous == null ? 0 : Math.max(0, previous.length() - MAX_BREADCRUMBS + 1);
        if (previous != null) {
            for (int index = start; index < previous.length(); ++index) {
                breadcrumbs.put(previous.opt(index));
            }
        }
        breadcrumbs.put(breadcrumb(stage, now));
        state.put("breadcrumbs", breadcrumbs);
        ResultFiles.writeAtomic(fileFor(resultFile), state.toString(2));
        AppDiagnostics.event("runner_checkpoint", new JSONObject()
                .put("result_file", resultFile.getName())
                .put("stage", stage)
                .put("context", context));
    }

    static void complete(File resultFile, int pid, boolean success) throws Exception {
        JSONObject state = read(resultFile);
        if (state == null) state = new JSONObject()
                .put("runner_state_schema_version", SCHEMA_VERSION)
                .put("context", new JSONObject())
                .put("breadcrumbs", new JSONArray());
        long now = System.currentTimeMillis();
        JSONArray breadcrumbs = state.optJSONArray("breadcrumbs");
        if (breadcrumbs == null) breadcrumbs = new JSONArray();
        breadcrumbs.put(breadcrumb("runner_completed", now));
        state.put("breadcrumbs", breadcrumbs)
                .put("state", "completed")
                .put("pid", pid)
                .put("success", success)
                .put("last_stage", "runner_completed")
                .put("last_stage_at_ms", now)
                .put("finished_at_ms", now);
        ResultFiles.writeAtomic(fileFor(resultFile), state.toString(2));
        AppDiagnostics.event("runner_state_completed", new JSONObject()
                .put("result_file", resultFile.getName())
                .put("pid", pid)
                .put("success", success));
    }

    static JSONObject read(File resultFile) {
        try {
            File stateFile = fileFor(resultFile);
            return stateFile.isFile()
                    ? new JSONObject(ResultFiles.readUtf8(stateFile)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static void attachToSyntheticFailure(JSONObject failure, File resultFile,
                                         String fallbackStage) throws Exception {
        JSONObject state = read(resultFile);
        String lastStage = state == null
                ? fallbackStage : state.optString("last_stage", fallbackStage);
        failure.put("failure_stage", lastStage)
                .put("runner_last_stage", lastStage)
                .put("runner_state", state == null ? JSONObject.NULL : state)
                .put("process_exit_observation", new JSONObject()
                        .put("classification", "isolated_runner_exit")
                        .put("native_signal", JSONObject.NULL)
                        .put("native_signal_limitation",
                                "SIGSEGV/SIGABRT require an Android tombstone or external ADB logcat for exact attribution"));
        AppDiagnostics.event("runner_synthetic_failure", new JSONObject()
                .put("result_file", resultFile.getName())
                .put("last_stage", lastStage)
                .put("failure_type", failure.optString("failure_type"))
                .put("error", failure.optString("error")));
    }

    private static JSONObject breadcrumb(String stage, long atMs) throws Exception {
        return new JSONObject().put("stage", stage).put("at_ms", atMs);
    }
}
