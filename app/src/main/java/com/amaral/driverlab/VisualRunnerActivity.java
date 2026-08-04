package com.amaral.driverlab;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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
import java.util.concurrent.atomic.AtomicBoolean;

public final class VisualRunnerActivity extends LocalizedActivity implements SurfaceHolder.Callback {
    private static final int MAX_RAW_BYTES = 32 * 1024 * 1024;
    private static final int[] CHECKPOINT_FRAMES = {30, 90, 150};

    private static native String runNativeVisualScene(
            Surface surface,
            String sceneId,
            String driverDirectory,
            String driverName,
            String nativeLibraryDirectory,
            String temporaryDirectory,
            int warmupSeconds,
            int measureSeconds,
            String rawPrefix);

    private final AtomicBoolean started = new AtomicBoolean(false);
    private File resultFile;
    private TextView overlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        hideSystemBars();
        try {
            resultFile = validateResultPath(getIntent().getStringExtra(RunnerActivity.EXTRA_RESULT_PATH));
        } catch (Exception error) {
            finishAndRemoveTask();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(15);
        overlay.setPadding(dp(18), dp(12), dp(18), dp(12));
        overlay.setBackgroundColor(0x99000000);
        overlay.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        String workloadId = getIntent().getStringExtra(RunnerActivity.EXTRA_WORKLOAD_ID);
        String phase = getIntent().getStringExtra(RunnerActivity.EXTRA_PHASE_LABEL);
        String role = getIntent().getStringExtra(RunnerActivity.EXTRA_DRIVER_ROLE);
        String displayName = getIntent().getStringExtra(
                RunnerActivity.EXTRA_DRIVER_DISPLAY_NAME);
        if (role == null || role.isEmpty()) {
            String driverDir = getIntent().getStringExtra(RunnerActivity.EXTRA_DRIVER_DIR);
            role = DriverExecutionIdentity.role("candidate".equals(phase),
                    driverDir != null && !driverDir.isEmpty());
        }
        String armLabel;
        if (DriverExecutionIdentity.ROLE_CANDIDATE.equals(role)) {
            armLabel = getString(R.string.phase13_candidate_driver_label);
        } else if (DriverExecutionIdentity.ROLE_REFERENCE.equals(role)) {
            armLabel = getString(R.string.phase13_reference_driver_label);
        } else {
            armLabel = getString(R.string.phase13_system_driver);
        }
        if (displayName != null && !displayName.isEmpty()
                && !DriverExecutionIdentity.ROLE_SYSTEM.equals(role)) {
            armLabel += " · " + displayName;
        }
        overlay.setText("Amaral Driver Lab · cena Vulkan visível\n"
                + VisualSceneContract.labelFor(workloadId) + " · " + armLabel
                + "\nCheckpoints: frames 30, 90 e 150");
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(overlay, overlayParams);
        setContentView(root);
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!started.compareAndSet(false, true)) return;
        Surface surface = holder.getSurface();
        Thread worker = new Thread(() -> execute(surface), "visible-vulkan-scene");
        worker.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {}

    private File validateResultPath(String rawPath) throws Exception {
        if (rawPath == null) throw new IllegalArgumentException("Caminho de resultado ausente");
        File root = new File(getFilesDir(), "runs");
        File result = new File(rawPath);
        if (!ResultFiles.isInside(root, result)) {
            throw new SecurityException("Resultado visual fora da pasta do app");
        }
        return result;
    }

    private void execute(Surface surface) {
        JSONObject result = new JSONObject();
        JSONObject beforeSnapshot = null;
        long startedAt = System.currentTimeMillis();
        List<JSONObject> telemetry = Collections.synchronizedList(new ArrayList<>());
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        String rawPrefix = null;
        try {
            JSONObject state = new JSONObject()
                    .put("state", "started")
                    .put("pid", Process.myPid())
                    .put("started_at_ms", startedAt);
            ResultFiles.writeAtomic(new File(resultFile.getAbsolutePath() + ".state"),
                    state.toString(2));

            String workloadId = getIntent().getStringExtra(RunnerActivity.EXTRA_WORKLOAD_ID);
            if (!VisualSceneContract.isVisualScene(workloadId)) {
                throw new IllegalArgumentException("Cena visual não suportada: " + workloadId);
            }
            int workloadVersion = getIntent().getIntExtra(
                    RunnerActivity.EXTRA_WORKLOAD_VERSION,
                    WorkloadContract.versionFor(workloadId));
            if (workloadVersion != WorkloadContract.versionFor(workloadId)) {
                throw new IllegalArgumentException("Versão da cena visual incompatível");
            }
            String phase = getIntent().getStringExtra(RunnerActivity.EXTRA_PHASE_LABEL);
            String driverDir = getIntent().getStringExtra(RunnerActivity.EXTRA_DRIVER_DIR);
            String driverModeOverride = getIntent().getStringExtra(
                    RunnerActivity.EXTRA_DRIVER_MODE_OVERRIDE);
            String driverRole = getIntent().getStringExtra(RunnerActivity.EXTRA_DRIVER_ROLE);
            String driverDisplayName = getIntent().getStringExtra(
                    RunnerActivity.EXTRA_DRIVER_DISPLAY_NAME);
            String driverName = getIntent().getStringExtra(RunnerActivity.EXTRA_DRIVER_NAME);
            String driverMetadata = getIntent().getStringExtra(RunnerActivity.EXTRA_DRIVER_META);
            String driverSha = getIntent().getStringExtra(RunnerActivity.EXTRA_DRIVER_SHA);
            int round = getIntent().getIntExtra(RunnerActivity.EXTRA_ROUND, 1);
            int warmup = getIntent().getIntExtra(RunnerActivity.EXTRA_WARMUP_SECONDS, 2);
            int measure = getIntent().getIntExtra(RunnerActivity.EXTRA_MEASURE_SECONDS, 10);
            int pixelTolerance = getIntent().getIntExtra(
                    RunnerActivity.EXTRA_PIXEL_TOLERANCE,
                    VisualSceneContract.DEFAULT_PIXEL_TOLERANCE);
            int maximumDivergentBlocks = getIntent().getIntExtra(
                    RunnerActivity.EXTRA_MAX_DIVERGENT_BLOCKS,
                    VisualSceneContract.DEFAULT_MAX_DIVERGENT_BLOCKS);

            result.put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION)
                    .put("phase", phase == null ? "unknown" : phase)
                    .put("round", round)
                    .put("runner_pid", Process.myPid())
                    .put("started_at_ms", startedAt)
                    .put("workload_id", workloadId)
                    .put("workload_version", workloadVersion)
                    .put("metric_limitations", WorkloadContract.limitationFor(workloadId))
                    .put("workload_config", VisualSceneContract.workloadConfig(
                            workloadId, warmup, measure, pixelTolerance,
                            maximumDivergentBlocks))
                    .put("driver_mode", "system".equals(driverModeOverride)
                            || "custom".equals(driverModeOverride)
                            ? driverModeOverride
                            : driverDir == null || driverDir.isEmpty() ? "system" : "custom")
                    .put("driver_role", driverRole == null || driverRole.isEmpty()
                            ? DriverExecutionIdentity.role("candidate".equals(phase),
                            driverDir != null && !driverDir.isEmpty())
                            : driverRole)
                    .put("driver_display_name",
                            driverDisplayName == null || driverDisplayName.isEmpty()
                                    ? JSONObject.NULL : driverDisplayName)
                    .put("driver_sha256", driverSha == null || driverSha.isEmpty()
                            ? JSONObject.NULL : driverSha)
                    .put("driver_metadata", driverMetadata == null || driverMetadata.isEmpty()
                            ? JSONObject.NULL : new JSONObject(driverMetadata));
            beforeSnapshot = DeviceSnapshot.capture(this);
            result.put("device_before", beforeSnapshot);
            sampler.scheduleAtFixedRate(
                    () -> telemetry.add(DeviceSnapshot.captureTelemetry(this)),
                    0, 1, TimeUnit.SECONDS);

            System.loadLibrary("driverlab");
            File temporary = new File(getCacheDir(), "visual-runner-temp");
            if (!temporary.isDirectory() && !temporary.mkdirs()) {
                throw new IllegalStateException("Falha ao criar pasta temporária visual");
            }
            rawPrefix = rawPrefix(resultFile);
            String nativeJson = runNativeVisualScene(
                    surface,
                    workloadId,
                    driverDir == null ? "" : driverDir,
                    driverName == null ? "" : driverName,
                    getApplicationInfo().nativeLibraryDir,
                    temporary.getAbsolutePath(),
                    warmup,
                    measure,
                    rawPrefix);
            JSONObject nativeResult = new JSONObject(nativeJson);
            result.put("native", nativeResult);
            boolean success = nativeResult.optBoolean("success", false);
            if (success) {
                result.put("evidence", finalizeCheckpoints(resultFile, rawPrefix, nativeResult));
            }
            result.put("success", success);
            if (!success) {
                result.put("failure_type",
                        nativeResult.optString("failure_type", "visual_scene_failure"));
                result.put("failure_stage",
                        nativeResult.optString("failure_stage", "visual_scene_native"));
                result.put("error",
                        nativeResult.optString("error", "Falha na cena Vulkan visível"));
            }
        } catch (Throwable error) {
            try {
                result.put("success", false)
                        .put("failure_type", "visual_runner_exception")
                        .put("failure_stage", "visual_runner_java")
                        .put("error", error.toString());
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                result.put("java_stacktrace", trace.toString());
            } catch (Exception ignored) {
                // Preserve the original failure.
            }
        } finally {
            sampler.shutdownNow();
            deleteRawCheckpoints(rawPrefix);
            try {
                JSONArray samples = new JSONArray();
                synchronized (telemetry) {
                    for (JSONObject sample : telemetry) samples.put(sample);
                }
                result.put("telemetry_samples", samples);
                JSONObject after = DeviceSnapshot.capture(this);
                result.put("device_after", after);
                long finishedAt = System.currentTimeMillis();
                result.put("finished_at_ms", finishedAt);
                addEnergyEstimate(result, beforeSnapshot, after,
                        Math.max(1L, finishedAt - startedAt));
                String logcat = captureOwnLogcat();
                if (!logcat.isEmpty()) {
                    result.put("runner_logcat_tail", logcat);
                    JSONArray validationErrors = extractValidationErrors(logcat);
                    if (validationErrors.length() > 0) {
                        result.put("validation_errors", validationErrors);
                    }
                }
                ResultFiles.writeAtomic(resultFile, result.toString(2));
                ResultFiles.writeAtomic(new File(resultFile.getAbsolutePath() + ".state"),
                        new JSONObject()
                                .put("state", "completed")
                                .put("pid", Process.myPid())
                                .put("success", result.optBoolean("success", false))
                                .toString(2));
            } catch (Exception ignored) {
                // Controller classifies a missing result as a crashed phase.
            }
            runOnUiThread(() -> overlay.setText("Cena concluída · consolidando resultados…"));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finishAndRemoveTask();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Process.killProcess(Process.myPid()), 350);
            }, 250);
        }
    }

    private JSONObject finalizeCheckpoints(File resultFile, String prefix,
                                           JSONObject nativeResult) throws Exception {
        int width = nativeResult.optInt("image_width", -1);
        int height = nativeResult.optInt("image_height", -1);
        if (width != VisualSceneContract.WIDTH || height != VisualSceneContract.HEIGHT) {
            throw new IllegalStateException("Dimensões inesperadas da cena visual");
        }
        int expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (expectedBytes <= 0 || expectedBytes > MAX_RAW_BYTES) {
            throw new IllegalStateException("Tamanho de checkpoint visual inválido");
        }
        JSONArray nativeFrames = nativeResult.optJSONArray("checkpoint_frames");
        if (nativeFrames == null || nativeFrames.length() != CHECKPOINT_FRAMES.length) {
            throw new IllegalStateException("Lista de checkpoints visuais incompleta");
        }
        JSONArray checkpoints = new JSONArray();
        for (int index = 0; index < CHECKPOINT_FRAMES.length; ++index) {
            int frame = CHECKPOINT_FRAMES[index];
            if (nativeFrames.optInt(index, -1) != frame) {
                throw new IllegalStateException("Frame visual inesperado: " + nativeFrames.optInt(index));
            }
            File raw = new File(String.format(Locale.US, "%s-f%04d.rgba", prefix, frame));
            if (!ResultFiles.isInside(new File(getFilesDir(), "runs"), raw)
                    || !raw.isFile() || raw.length() != expectedBytes) {
                throw new IllegalStateException("Checkpoint RGBA ausente no frame " + frame);
            }
            byte[] rgba = readExactly(raw, expectedBytes);
            String rgbaHash = sha256(rgba);
            int[] argb = rgbaToArgb(rgba);
            String baseName = withoutExtension(resultFile.getName());
            File png = new File(resultFile.getParentFile(),
                    String.format(Locale.US, "%s-visual-f%04d.png", baseName, frame));
            Bitmap bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(png, false)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("Falha ao codificar checkpoint visual");
                }
                output.getFD().sync();
            } finally {
                bitmap.recycle();
            }
            String pngHash = ResultFiles.sha256(png);
            checkpoints.put(new JSONObject()
                    .put("frame", frame)
                    .put("relative_path", png.getName())
                    .put("sha256_rgba", rgbaHash)
                    .put("sha256_png", pngHash)
                    .put("png_size_bytes", png.length()));
            if (!raw.delete()) throw new IllegalStateException("Falha ao remover RGBA temporário");
        }
        JSONObject evidence = new JSONObject()
                .put("kind", "visible_vulkan_scene_checkpoints")
                .put("scene_id", nativeResult.optString("visual_scene_id"))
                .put("scene_version", nativeResult.optInt("visual_scene_version", 1))
                .put("width", width)
                .put("height", height)
                .put("format", "RGBA8_UNORM")
                .put("checkpoint_count", checkpoints.length())
                .put("checkpoints", checkpoints);
        nativeResult.put("checkpoint_evidence_count", checkpoints.length());
        return evidence;
    }

    private static String rawPrefix(File resultFile) {
        return new File(resultFile.getParentFile(),
                withoutExtension(resultFile.getName()) + ".visual").getAbsolutePath();
    }

    private void deleteRawCheckpoints(String prefix) {
        if (prefix == null || prefix.isEmpty()) return;
        for (int frame : CHECKPOINT_FRAMES) {
            File raw = new File(String.format(Locale.US, "%s-f%04d.rgba", prefix, frame));
            if (raw.isFile()) raw.delete();
        }
    }

    private static String withoutExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(0, dot);
    }

    private static byte[] readExactly(File file, int expected) throws Exception {
        byte[] output = new byte[expected];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (offset < output.length) {
                int count = input.read(output, offset, output.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != output.length || input.read() >= 0) {
                throw new IllegalStateException("Tamanho do checkpoint visual divergente");
            }
        }
        return output;
    }

    private static int[] rgbaToArgb(byte[] rgba) {
        int[] argb = new int[rgba.length / 4];
        for (int pixel = 0, offset = 0; pixel < argb.length; ++pixel, offset += 4) {
            int red = rgba[offset] & 0xff;
            int green = rgba[offset + 1] & 0xff;
            int blue = rgba[offset + 2] & 0xff;
            int alpha = rgba[offset + 3] & 0xff;
            argb[pixel] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        return argb;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte item : digest) output.append(String.format(Locale.US, "%02x", item & 0xff));
        return output.toString();
    }

    private static JSONArray extractValidationErrors(String logcat) {
        JSONArray errors = new JSONArray();
        for (String line : logcat.split("\\r?\\n")) {
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
                || after.isNull("battery_energy_counter_nwh")) return;
        try {
            long beforeEnergy = before.getLong("battery_energy_counter_nwh");
            long afterEnergy = after.getLong("battery_energy_counter_nwh");
            long consumed = beforeEnergy - afterEnergy;
            double hours = elapsedMs / 3_600_000.0;
            result.put("device_energy_consumed_nwh", consumed)
                    .put("average_device_power_w", hours > 0.0
                            ? consumed / 1_000_000_000.0 / hours : JSONObject.NULL)
                    .put("power_note",
                            "Estimativa do aparelho inteiro; sinal pode inverter durante carga.");
        } catch (Exception ignored) {
            // Optional sensor.
        }
    }

    private static String captureOwnLogcat() {
        java.lang.Process logcat = null;
        try {
            logcat = new ProcessBuilder(
                    "logcat", "-d", "--pid=" + Process.myPid(), "-v", "threadtime", "-t", "500")
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
