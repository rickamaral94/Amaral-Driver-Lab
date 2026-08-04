package com.amaral.driverlab;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    static final String EXTRA_WORKLOAD_ID = "workload_id";
    static final String EXTRA_WORKLOAD_VERSION = "workload_version";
    static final String EXTRA_PIXEL_TOLERANCE = "pixel_tolerance";
    static final String EXTRA_MAX_DIVERGENT_BLOCKS = "max_divergent_blocks";
    static final String EXTRA_TRACE_ID = "trace_id";

    private static native String runNativeBenchmark(
            String driverDirectory,
            String driverName,
            String nativeLibraryDirectory,
            String temporaryDirectory,
            int warmupSeconds,
            int measureSeconds);

    private static native String runNativeRenderCorrectness(
            String driverDirectory,
            String driverName,
            String nativeLibraryDirectory,
            String temporaryDirectory,
            String rawOutputPath);

    private static native String runNativePhase2Workload(
            String workloadId,
            String driverDirectory,
            String driverName,
            String nativeLibraryDirectory,
            String temporaryDirectory,
            int warmupSeconds,
            int measureSeconds);

    private static native String runNativeTraceReplay(
            String traceId,
            String driverDirectory,
            String driverName,
            String nativeLibraryDirectory,
            String temporaryDirectory,
            int warmupSeconds,
            int measureSeconds,
            String rawOutputPath);

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

        Thread worker = new Thread(() -> execute(resultFile), "vulkan-workload");
        worker.start();
    }

    private File validateResultPath(String rawPath) throws Exception {
        if (rawPath == null) throw new IllegalArgumentException("Caminho de resultado ausente");
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
        File rawEvidence = null;
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
            String workloadId = getIntent().getStringExtra(EXTRA_WORKLOAD_ID);
            if (workloadId == null || workloadId.isEmpty()) workloadId = WorkloadContract.TRANSFER_ID;
            if (!WorkloadContract.isSupported(workloadId)) {
                throw new IllegalArgumentException("Workload não suportado: " + workloadId);
            }
            int workloadVersion = getIntent().getIntExtra(
                    EXTRA_WORKLOAD_VERSION, WorkloadContract.versionFor(workloadId));
            String traceId = getIntent().getStringExtra(EXTRA_TRACE_ID);
            if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
                if (!TraceReplayContract.isSupported(traceId)) {
                    throw new IllegalArgumentException("Trace não suportado: " + traceId);
                }
            } else {
                traceId = TraceReplayContract.MIXED_TRACE_ID;
            }
            if (workloadVersion != WorkloadContract.versionFor(workloadId)) {
                throw new IllegalArgumentException("Versão de workload incompatível");
            }
            int round = getIntent().getIntExtra(EXTRA_ROUND, 1);
            int warmup = getIntent().getIntExtra(EXTRA_WARMUP_SECONDS, 3);
            int measure = getIntent().getIntExtra(EXTRA_MEASURE_SECONDS, 10);
            int pixelTolerance = getIntent().getIntExtra(
                    EXTRA_PIXEL_TOLERANCE, WorkloadContract.DEFAULT_PIXEL_TOLERANCE);
            int maxDivergentBlocks = getIntent().getIntExtra(
                    EXTRA_MAX_DIVERGENT_BLOCKS,
                    WorkloadContract.DEFAULT_MAX_DIVERGENT_BLOCKS);

            result.put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION);
            result.put("phase", phase == null ? "unknown" : phase);
            result.put("round", round);
            result.put("runner_pid", Process.myPid());
            executionStartedAt = System.currentTimeMillis();
            result.put("started_at_ms", executionStartedAt);
            result.put("workload_id", workloadId);
            result.put("workload_version", workloadVersion);
            result.put("metric_limitations", WorkloadContract.limitationFor(workloadId));
            JSONObject workloadConfig = new JSONObject();
            if (WorkloadContract.TRANSFER_ID.equals(workloadId)) {
                workloadConfig.put("warmup_seconds", warmup);
                workloadConfig.put("measure_seconds", measure);
                result.put("warmup_seconds", warmup);
                result.put("measure_seconds", measure);
            } else if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
                workloadConfig.put("image_width", WorkloadContract.RENDER_WIDTH);
                workloadConfig.put("image_height", WorkloadContract.RENDER_HEIGHT);
                workloadConfig.put("pixel_tolerance", pixelTolerance);
                workloadConfig.put("block_size_px", WorkloadContract.BLOCK_SIZE);
                workloadConfig.put("minimum_block_match_percent",
                        WorkloadContract.MINIMUM_BLOCK_MATCH_PERCENT);
                workloadConfig.put("maximum_divergent_blocks", maxDivergentBlocks);
            } else if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
                workloadConfig.put("warmup_seconds", warmup);
                workloadConfig.put("measure_seconds", measure);
                workloadConfig.put("trace", TraceReplayContract.definition(traceId));
                result.put("trace_id", traceId);
                result.put("trace_version", TraceReplayContract.TRACE_VERSION);
            } else {
                workloadConfig.put("warmup_seconds", warmup);
                workloadConfig.put("measure_seconds", measure);
            }
            result.put("workload_config", workloadConfig);
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

            String nativeJson;
            if (WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
                rawEvidence = siblingEvidence(resultFile, ".rgba");
                nativeJson = runNativeRenderCorrectness(
                        driverDir == null ? "" : driverDir,
                        driverName == null ? "" : driverName,
                        getApplicationInfo().nativeLibraryDir,
                        temporary.getAbsolutePath(),
                        rawEvidence.getAbsolutePath());
            } else if (WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
                rawEvidence = siblingEvidence(resultFile, ".trace.raw");
                nativeJson = runNativeTraceReplay(
                        traceId,
                        driverDir == null ? "" : driverDir,
                        driverName == null ? "" : driverName,
                        getApplicationInfo().nativeLibraryDir,
                        temporary.getAbsolutePath(),
                        warmup,
                        measure,
                        rawEvidence.getAbsolutePath());
            } else if (WorkloadContract.isPhase2(workloadId)) {
                nativeJson = runNativePhase2Workload(
                        workloadId,
                        driverDir == null ? "" : driverDir,
                        driverName == null ? "" : driverName,
                        getApplicationInfo().nativeLibraryDir,
                        temporary.getAbsolutePath(),
                        warmup,
                        measure);
            } else {
                nativeJson = runNativeBenchmark(
                        driverDir == null ? "" : driverDir,
                        driverName == null ? "" : driverName,
                        getApplicationInfo().nativeLibraryDir,
                        temporary.getAbsolutePath(),
                        warmup,
                        measure);
            }

            JSONObject nativeResult = new JSONObject(nativeJson);
            result.put("native", nativeResult);
            boolean nativeSuccess = nativeResult.optBoolean("success", false);
            if (nativeSuccess && WorkloadContract.RENDER_CORRECTNESS_ID.equals(workloadId)) {
                result.put("evidence", finalizeRenderEvidence(resultFile, rawEvidence, nativeResult));
            } else if (nativeSuccess && WorkloadContract.TRACE_REPLAY_ID.equals(workloadId)) {
                result.put("evidence", finalizeTraceEvidence(resultFile, rawEvidence, nativeResult));
            }
            result.put("success", nativeSuccess);
            if (!nativeSuccess) {
                result.put("failure_type",
                        nativeResult.optString("failure_type", "native_failure"));
                result.put("failure_stage",
                        nativeResult.optString("failure_stage", "native_workload"));
                result.put("error", nativeResult.optString("error", "Falha no workload nativo"));
            }
        } catch (Throwable error) {
            try {
                result.put("success", false);
                result.put("failure_type", "runner_exception");
                result.put("failure_stage", "runner_java");
                result.put("error", error.toString());
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                result.put("java_stacktrace", trace.toString());
            } catch (Exception ignored) {
                // Preserve the original failure.
            }
        } finally {
            sampler.shutdownNow();
            if (rawEvidence != null && rawEvidence.isFile()) rawEvidence.delete();
            try {
                JSONArray sampleArray = new JSONArray();
                synchronized (samples) {
                    for (JSONObject sample : samples) sampleArray.put(sample);
                }
                result.put("telemetry_samples", sampleArray);
                JSONObject afterSnapshot = DeviceSnapshot.capture(this);
                result.put("device_after", afterSnapshot);
                long finishedAt = System.currentTimeMillis();
                result.put("finished_at_ms", finishedAt);
                addEnergyEstimate(result, beforeSnapshot, afterSnapshot,
                        Math.max(1L, finishedAt - executionStartedAt));
                String logcat = captureOwnLogcat();
                if (!logcat.isEmpty()) {
                    result.put("runner_logcat_tail", logcat);
                    JSONArray validationErrors = extractValidationErrors(logcat);
                    if (validationErrors.length() > 0) result.put("validation_errors", validationErrors);
                }
                ResultFiles.writeAtomic(resultFile, result.toString(2));
                JSONObject completed = new JSONObject();
                completed.put("state", "completed");
                completed.put("pid", Process.myPid());
                completed.put("success", result.optBoolean("success", false));
                ResultFiles.writeAtomic(new File(resultFile.getAbsolutePath() + ".state"),
                        completed.toString(2));
            } catch (Exception ignored) {
                // The controller classifies a missing file as a crashed phase.
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                finishAndRemoveTask();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Process.killProcess(Process.myPid()), 350);
            });
        }
    }

    private File siblingEvidence(File resultFile, String extension) throws Exception {
        String name = resultFile.getName();
        int dot = name.lastIndexOf('.');
        File evidence = new File(resultFile.getParentFile(),
                (dot < 0 ? name : name.substring(0, dot)) + extension);
        if (!ResultFiles.isInside(new File(getFilesDir(), "runs"), evidence)) {
            throw new SecurityException("Evidência fora da pasta de execuções");
        }
        return evidence;
    }

    private JSONObject finalizeRenderEvidence(File resultFile, File rawFile,
                                               JSONObject nativeResult) throws Exception {
        int width = nativeResult.optInt("image_width", -1);
        int height = nativeResult.optInt("image_height", -1);
        if (width != WorkloadContract.RENDER_WIDTH || height != WorkloadContract.RENDER_HEIGHT) {
            throw new IllegalStateException("Dimensões inesperadas da evidência nativa");
        }
        int expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (rawFile == null || !rawFile.isFile() || rawFile.length() != expectedBytes) {
            throw new IllegalStateException("Evidência RGBA ausente ou incompleta");
        }
        byte[] rgba = readExactly(rawFile, expectedBytes);
        String sha256 = sha256(rgba);
        int[] argb = new int[width * height];
        for (int pixel = 0, offset = 0; pixel < argb.length; ++pixel, offset += 4) {
            int red = rgba[offset] & 0xff;
            int green = rgba[offset + 1] & 0xff;
            int blue = rgba[offset + 2] & 0xff;
            int alpha = rgba[offset + 3] & 0xff;
            argb[pixel] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }

        File png = siblingEvidence(resultFile, ".png");
        Bitmap bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(png, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Falha ao codificar preview PNG");
            }
            output.getFD().sync();
        } finally {
            bitmap.recycle();
        }
        if (!png.isFile() || png.length() == 0) {
            throw new IllegalStateException("Preview PNG não foi persistido");
        }

        nativeResult.put("render_sha256", sha256);
        nativeResult.put("preview_png", png.getName());
        nativeResult.put("raw_output_bytes", expectedBytes);
        JSONObject evidence = new JSONObject();
        evidence.put("kind", "render_preview_png");
        evidence.put("relative_path", png.getName());
        evidence.put("sha256_rgba", sha256);
        evidence.put("width", width);
        evidence.put("height", height);
        evidence.put("format", "RGBA8_UNORM");
        evidence.put("png_size_bytes", png.length());
        return evidence;
    }

    private JSONObject finalizeTraceEvidence(File resultFile, File rawFile,
                                             JSONObject nativeResult) throws Exception {
        int expectedBytes = nativeResult.optInt("output_bytes", -1);
        int graphicsBytes = nativeResult.optInt("graphics_output_bytes", 0);
        int width = nativeResult.optInt("graphics_width", 0);
        int height = nativeResult.optInt("graphics_height", 0);
        if (expectedBytes <= 0 || expectedBytes > 8 * 1024 * 1024) {
            throw new IllegalStateException("Tamanho de saída do trace inválido");
        }
        if (rawFile == null || !rawFile.isFile() || rawFile.length() != expectedBytes) {
            throw new IllegalStateException("Saída binária do trace ausente ou incompleta");
        }
        byte[] outputBytes = readExactly(rawFile, expectedBytes);
        String sha256 = sha256(outputBytes);
        File binary = siblingEvidence(resultFile, ".trace.bin");
        if (binary.isFile() && !binary.delete()) {
            throw new IllegalStateException("Não foi possível substituir evidência anterior");
        }
        if (!rawFile.renameTo(binary)) {
            try (FileOutputStream stream = new FileOutputStream(binary, false)) {
                stream.write(outputBytes);
                stream.getFD().sync();
            }
            if (!rawFile.delete()) throw new IllegalStateException("Falha ao remover temporário do trace");
        }

        JSONObject evidence = new JSONObject();
        evidence.put("kind", "versioned_vulkan_trace_output");
        evidence.put("relative_path", binary.getName());
        evidence.put("sha256_output", sha256);
        evidence.put("output_size_bytes", expectedBytes);
        evidence.put("output_format", nativeResult.optString("output_format", "binary"));
        evidence.put("trace_id", nativeResult.optString("trace_id"));
        evidence.put("trace_version", nativeResult.optInt("trace_version", 1));

        if (graphicsBytes > 0) {
            int expectedGraphics = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            if (graphicsBytes != expectedGraphics || graphicsBytes > outputBytes.length) {
                throw new IllegalStateException("Segmento gráfico do trace inválido");
            }
            int[] argb = new int[width * height];
            for (int pixel = 0, offset = 0; pixel < argb.length; ++pixel, offset += 4) {
                int red = outputBytes[offset] & 0xff;
                int green = outputBytes[offset + 1] & 0xff;
                int blue = outputBytes[offset + 2] & 0xff;
                int alpha = outputBytes[offset + 3] & 0xff;
                argb[pixel] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            File png = siblingEvidence(resultFile, ".trace.png");
            Bitmap bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            try (FileOutputStream stream = new FileOutputStream(png, false)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IllegalStateException("Falha ao codificar preview do trace");
                }
                stream.getFD().sync();
            } finally {
                bitmap.recycle();
            }
            evidence.put("preview_png", png.getName());
            evidence.put("graphics_width", width);
            evidence.put("graphics_height", height);
            evidence.put("graphics_format", "RGBA8_UNORM");
        }
        nativeResult.put("output_sha256", sha256);
        nativeResult.put("evidence_binary", binary.getName());
        return evidence;
    }

    private static byte[] readExactly(File file, int expectedBytes) throws Exception {
        byte[] output = new byte[expectedBytes];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (offset < output.length) {
                int count = input.read(output, offset, output.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != output.length || input.read() >= 0) {
                throw new IllegalStateException("Tamanho RGBA divergente");
            }
        }
        return output;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) hex.append(String.format(Locale.US, "%02x", item & 0xff));
        return hex.toString();
    }

    private static JSONArray extractValidationErrors(String logcat) {
        JSONArray errors = new JSONArray();
        String[] lines = logcat.split("\\r?\\n");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (line.contains("VUID-") || lower.contains("validation error")
                    || lower.contains("validation layer")) {
                errors.put(line.length() > 2000 ? line.substring(0, 2000) : line);
                if (errors.length() >= 50) break;
            }
        }
        return errors;
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
