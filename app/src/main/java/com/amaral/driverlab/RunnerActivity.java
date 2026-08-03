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

public final class RunnerActivity extends Activity {
    static final String EXTRA_RESULT_PATH = "result_path";
    static final String EXTRA_DRIVER_DIR = "driver_dir";
    static final String EXTRA_DRIVER_NAME = "driver_name";
    static final String EXTRA_DRIVER_META = "driver_meta";
    static final String EXTRA_DRIVER_SHA = "driver_sha";
    static final String EXTRA_PHASE_LABEL = "phase_label";
    static final String EXTRA_ROUND = "round";
    static final String EXTRA_WARMUP_SECONDS = "warmup_seconds";
    static final String EXTRA_MEASURE_SECONDS = "measure_seconds";

    private static native String runNativeBenchmark(
            String driverDirectory,
            String driverName,
            String nativeLibraryDirectory,
            String temporaryDirectory,
            int warmupSeconds,
            int measureSeconds);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File resultFile;
        try {
            resultFile = validateResultPath(getIntent().getStringExtra(EXTRA_RESULT_PATH));
        } catch (Exception error) {
            finishAndRemoveTask();
            return;
        }

        Thread worker = new Thread(() -> execute(resultFile), "vulkan-benchmark");
        worker.start();
    }

    private File validateResultPath(String rawPath) throws Exception {
        if (rawPath == null) {
            throw new IllegalArgumentException("Caminho de resultado ausente");
        }
        File runsRoot = new File(getFilesDir(), "runs");
        File result = new File(rawPath);
        if (!ResultFiles.isInside(runsRoot, result)) {
            throw new SecurityException("Resultado fora da pasta do app");
        }
        return result;
    }

    private void execute(File resultFile) {
        JSONObject result = new JSONObject();
        JSONObject beforeSnapshot = null;
        long executionStartedAt = System.currentTimeMillis();
        List<JSONObject> samples = Collections.synchronizedList(new ArrayList<>());
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        try {
            File stateFile = new File(resultFile.getAbsolutePath() + ".state");
            JSONObject state = new JSONObject();
            state.put("state", "started");
            state.put("pid", Process.myPid());
            state.put("started_at_ms", System.currentTimeMillis());
            ResultFiles.writeAtomic(stateFile, state.toString(2));

            String phase = getIntent().getStringExtra(EXTRA_PHASE_LABEL);
            String driverDir = getIntent().getStringExtra(EXTRA_DRIVER_DIR);
            String driverName = getIntent().getStringExtra(EXTRA_DRIVER_NAME);
            String driverMeta = getIntent().getStringExtra(EXTRA_DRIVER_META);
            String driverSha = getIntent().getStringExtra(EXTRA_DRIVER_SHA);
            int round = getIntent().getIntExtra(EXTRA_ROUND, 1);
            int warmup = getIntent().getIntExtra(EXTRA_WARMUP_SECONDS, 3);
            int measure = getIntent().getIntExtra(EXTRA_MEASURE_SECONDS, 10);

            result.put("schema_version", 1);
            result.put("phase", phase == null ? "unknown" : phase);
            result.put("round", round);
            result.put("runner_pid", Process.myPid());
            executionStartedAt = System.currentTimeMillis();
            result.put("started_at_ms", executionStartedAt);
            result.put("warmup_seconds", warmup);
            result.put("measure_seconds", measure);
            result.put("driver_mode", driverDir == null || driverDir.isEmpty() ? "system" : "custom");
            result.put("driver_sha256", driverSha == null || driverSha.isEmpty()
                    ? JSONObject.NULL : driverSha);
            result.put("driver_metadata", driverMeta == null || driverMeta.isEmpty()
                    ? JSONObject.NULL : new JSONObject(driverMeta));
            beforeSnapshot = DeviceSnapshot.capture(this);
            result.put("device_before", beforeSnapshot);

            sampler.scheduleAtFixedRate(
                    () -> samples.add(DeviceSnapshot.captureTelemetry(this)), 0, 1, TimeUnit.SECONDS);

            System.loadLibrary("driverlab");
            File temporary = new File(getCacheDir(), "driver-runner-temp");
            if (!temporary.isDirectory() && !temporary.mkdirs()) {
                throw new IllegalStateException("Falha ao criar pasta temporária nativa");
            }
            String nativeJson = runNativeBenchmark(
                    driverDir == null ? "" : driverDir,
                    driverName == null ? "" : driverName,
                    getApplicationInfo().nativeLibraryDir,
                    temporary.getAbsolutePath(),
                    warmup,
                    measure);
            result.put("native", new JSONObject(nativeJson));
            result.put("success", result.getJSONObject("native").optBoolean("success", false));
        } catch (Throwable error) {
            try {
                result.put("success", false);
                result.put("error", error.toString());
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                result.put("java_stacktrace", trace.toString());
            } catch (Exception ignored) {
                // Preserve the original failure.
            }
        } finally {
            sampler.shutdownNow();
            try {
                JSONArray sampleArray = new JSONArray();
                synchronized (samples) {
                    for (JSONObject sample : samples) {
                        sampleArray.put(sample);
                    }
                }
                result.put("telemetry_samples", sampleArray);
                JSONObject afterSnapshot = DeviceSnapshot.capture(this);
                result.put("device_after", afterSnapshot);
                long finishedAt = System.currentTimeMillis();
                result.put("finished_at_ms", finishedAt);
                addEnergyEstimate(result, beforeSnapshot, afterSnapshot,
                        Math.max(1L, finishedAt - executionStartedAt));
                String logcat = captureOwnLogcat();
                if (!logcat.isEmpty()) result.put("runner_logcat_tail", logcat);
                ResultFiles.writeAtomic(resultFile, result.toString(2));
                JSONObject completed = new JSONObject();
                completed.put("state", "completed");
                completed.put("pid", Process.myPid());
                ResultFiles.writeAtomic(new File(resultFile.getAbsolutePath() + ".state"),
                        completed.toString(2));
            } catch (Exception ignored) {
                // The controller will classify a missing file as a crashed phase.
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                finishAndRemoveTask();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Process.killProcess(Process.myPid()), 350);
            });
        }
    }

    private static void addEnergyEstimate(JSONObject result, JSONObject before, JSONObject after,
                                          long elapsedMs) {
        if (before == null || after == null
                || before.isNull("battery_energy_counter_nwh")
                || after.isNull("battery_energy_counter_nwh")) {
            return;
        }
        try {
            long beforeEnergy = before.getLong("battery_energy_counter_nwh");
            long afterEnergy = after.getLong("battery_energy_counter_nwh");
            long consumedNwh = beforeEnergy - afterEnergy;
            double elapsedHours = elapsedMs / 3_600_000.0;
            result.put("device_energy_consumed_nwh", consumedNwh);
            result.put("average_device_power_w",
                    elapsedHours > 0.0 ? consumedNwh / 1_000_000_000.0 / elapsedHours
                            : JSONObject.NULL);
            result.put("power_note", "Estimativa do aparelho inteiro; sinal pode inverter durante carga.");
        } catch (Exception ignored) {
            // Some devices report unsupported or unstable energy counters.
        }
    }

    private static String captureOwnLogcat() {
        java.lang.Process logcat = null;
        try {
            logcat = new ProcessBuilder(
                    "logcat", "-d", "--pid=" + Process.myPid(), "-v", "threadtime", "-t", "400")
                    .redirectErrorStream(true)
                    .start();
            try (InputStream input = logcat.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0 && output.size() < 64 * 1024) {
                    output.write(buffer, 0, Math.min(count, 64 * 1024 - output.size()));
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
