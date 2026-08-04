package com.amaral.driverlab;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DeepDiagnosticsRunnerActivity extends Activity {
    static final String EXTRA_RESULT_PATH = "result_path";
    static final String EXTRA_PHASE_LABEL = "phase_label";
    static final String EXTRA_DRIVER_DIR = "driver_dir";
    static final String EXTRA_DRIVER_NAME = "driver_name";
    static final String EXTRA_DRIVER_META = "driver_meta";
    static final String EXTRA_DRIVER_SHA = "driver_sha";
    static final String EXTRA_MODE = "diagnostic_mode";
    static final String EXTRA_CYCLES = "cycles";
    static final String EXTRA_MEMORY_MIB = "memory_mib";

    private static native String runNativeDeepDiagnostics(
            String mode,
            String driverDirectory,
            String driverName,
            String nativeLibraryDirectory,
            String temporaryDirectory,
            int cycles,
            int memoryMiB);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File result;
        try {
            result = validateResultPath(getIntent().getStringExtra(EXTRA_RESULT_PATH));
        } catch (Exception error) {
            finishAndRemoveTask();
            return;
        }
        new Thread(() -> execute(result), "phase10-deep-diagnostics").start();
    }

    private File validateResultPath(String path) throws Exception {
        if (path == null) throw new IllegalArgumentException("Caminho ausente");
        File root = new File(getFilesDir(), "deep-diagnostics");
        File result = new File(path);
        if (!ResultFiles.isInside(root, result)) {
            throw new SecurityException("Resultado fora da pasta da Fase 10");
        }
        return result;
    }

    private void execute(File resultFile) {
        JSONObject result = new JSONObject();
        List<JSONObject> samples = Collections.synchronizedList(new ArrayList<>());
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        long started = System.currentTimeMillis();
        try {
            JSONObject state = new JSONObject()
                    .put("state", "started")
                    .put("pid", Process.myPid())
                    .put("started_at_ms", started);
            ResultFiles.writeAtomic(new File(resultFile.getAbsolutePath() + ".state"),
                    state.toString(2));

            String mode = getIntent().getStringExtra(EXTRA_MODE);
            if (!"soak".equals(mode)) mode = "full";
            int cycles = Math.max(Phase10Contract.MIN_SOAK_CYCLES,
                    Math.min(getIntent().getIntExtra(EXTRA_CYCLES,
                                    Phase10Contract.DEFAULT_SOAK_CYCLES),
                            Phase10Contract.MAX_SOAK_CYCLES));
            int memoryMiB = Math.max(Phase10Contract.MIN_MEMORY_MIB,
                    Math.min(getIntent().getIntExtra(EXTRA_MEMORY_MIB,
                                    Phase10Contract.DEFAULT_MEMORY_MIB),
                            Phase10Contract.MAX_MEMORY_MIB));
            String driverDir = getIntent().getStringExtra(EXTRA_DRIVER_DIR);
            String driverName = getIntent().getStringExtra(EXTRA_DRIVER_NAME);
            String driverMeta = getIntent().getStringExtra(EXTRA_DRIVER_META);
            String driverSha = getIntent().getStringExtra(EXTRA_DRIVER_SHA);
            String phase = getIntent().getStringExtra(EXTRA_PHASE_LABEL);

            result.put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION)
                    .put("phase10_contract", Phase10Contract.contractJson())
                    .put("phase", phase == null ? "unknown" : phase)
                    .put("driver_mode", driverDir == null || driverDir.isEmpty()
                            ? "system" : "custom")
                    .put("driver_sha256", driverSha == null || driverSha.isEmpty()
                            ? JSONObject.NULL : driverSha)
                    .put("driver_metadata", driverMeta == null || driverMeta.isEmpty()
                            ? JSONObject.NULL : new JSONObject(driverMeta))
                    .put("diagnostic_mode", mode)
                    .put("cycles", cycles)
                    .put("memory_mib", memoryMiB)
                    .put("started_at_ms", started)
                    .put("runner_pid", Process.myPid())
                    .put("device_before", DeviceSnapshot.capture(this));

            sampler.scheduleAtFixedRate(
                    () -> samples.add(DeviceSnapshot.captureTelemetry(this)),
                    0, 1, TimeUnit.SECONDS);
            System.loadLibrary("driverlab");
            File temporary = new File(getCacheDir(), "phase10-native-temp");
            if (!temporary.isDirectory() && !temporary.mkdirs()) {
                throw new IllegalStateException("Falha ao criar pasta temporária nativa");
            }
            String nativeJson = runNativeDeepDiagnostics(
                    mode,
                    driverDir == null ? "" : driverDir,
                    driverName == null ? "" : driverName,
                    getApplicationInfo().nativeLibraryDir,
                    temporary.getAbsolutePath(),
                    cycles,
                    memoryMiB);
            JSONObject nativeResult = new JSONObject(nativeJson);
            result.put("native", nativeResult)
                    .put("success", nativeResult.optBoolean("success", false));
            if (!nativeResult.optBoolean("success", false)) {
                result.put("failure_type",
                                nativeResult.optString("failure_type", "native_failure"))
                        .put("failure_stage",
                                nativeResult.optString("failure_stage", "phase10_native"))
                        .put("error", nativeResult.optString("error", "Falha nativa"));
            }
        } catch (Throwable error) {
            try {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                result.put("success", false)
                        .put("failure_type", "runner_exception")
                        .put("failure_stage", "phase10_java")
                        .put("error", error.toString())
                        .put("java_stacktrace", trace.toString());
            } catch (Exception ignored) {
                // Preserve original failure.
            }
        } finally {
            sampler.shutdownNow();
            try {
                JSONArray telemetry = new JSONArray();
                synchronized (samples) {
                    for (JSONObject sample : samples) telemetry.put(sample);
                }
                result.put("telemetry_samples", telemetry)
                        .put("device_after", DeviceSnapshot.capture(this))
                        .put("finished_at_ms", System.currentTimeMillis());
                String logcat = captureOwnLogcat();
                if (!logcat.isEmpty()) result.put("runner_logcat_tail", logcat);
                ResultFiles.writeAtomic(resultFile, result.toString(2));
                ResultFiles.writeAtomic(new File(resultFile.getAbsolutePath() + ".state"),
                        new JSONObject()
                                .put("state", "completed")
                                .put("pid", Process.myPid())
                                .put("success", result.optBoolean("success", false))
                                .toString(2));
            } catch (Exception ignored) {
                // Coordinator classifies a missing result as a crash.
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                finishAndRemoveTask();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Process.killProcess(Process.myPid()), 350L);
            });
        }
    }

    private static String captureOwnLogcat() {
        java.lang.Process logcat = null;
        try {
            logcat = new ProcessBuilder("logcat", "-d", "--pid=" + Process.myPid(),
                    "-v", "threadtime", "-t", "500")
                    .redirectErrorStream(true)
                    .start();
            try (InputStream input = logcat.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0 && output.size() < 96 * 1024) {
                    output.write(buffer, 0, Math.min(count, 96 * 1024 - output.size()));
                }
                logcat.waitFor(2, TimeUnit.SECONDS);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (logcat != null) logcat.destroy();
        }
    }
}
