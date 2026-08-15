package com.amaral.driverlab;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Persistent, user-accessible diagnostics stored in Android/data/<package>/files/logs. */
final class AppDiagnostics {
    static final int SCHEMA_VERSION = 1;
    static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    static final int MAX_ARCHIVES = 3;
    static final long MAX_TRACE_BYTES = 1024L * 1024L;
    static final int MAX_TRACE_FILES = 8;

    private static final String TAG = "AmaralDriverLab";
    private static final Object WRITE_LOCK = new Object();
    private static final AtomicBoolean EXIT_SCAN_RUNNING = new AtomicBoolean(false);
    private static volatile Context applicationContext;
    private static volatile String processName = "unknown";
    private static volatile long lastExitScanAtMs;

    private AppDiagnostics() {}

    static void initialize(Application application) {
        applicationContext = application.getApplicationContext();
        processName = currentProcessName(application);
        ensureMetadata(application);
        event("process_start", details(
                "pid", Process.myPid(),
                "process_name", processName,
                "app_version", BuildConfig.VERSION_NAME,
                "android_sdk", Build.VERSION.SDK_INT,
                "manufacturer", Build.MANUFACTURER,
                "model", Build.MODEL));
        installUncaughtExceptionLogger();
        registerActivityLifecycle(application);
        if (isMainProcess(application)) captureHistoricalExitReasonsAsync(application);
    }

    static File logsDirectory(Context context) {
        File external = context.getExternalFilesDir(null);
        File root = external == null ? context.getFilesDir() : external;
        File logs = new File(root, "logs");
        if (!logs.isDirectory() && !logs.mkdirs()) {
            Log.e(TAG, "Unable to create diagnostics directory: " + logs);
        }
        return logs;
    }

    static void event(String name, JSONObject details) {
        Context context = applicationContext;
        if (context == null) return;
        try {
            JSONObject line = json()
                    .put("diagnostic_schema_version", SCHEMA_VERSION)
                    .put("timestamp", Instant.now().toString())
                    .put("timestamp_ms", System.currentTimeMillis())
                    .put("event", name)
                    .put("pid", Process.myPid())
                    .put("thread", Thread.currentThread().getName())
                    .put("process_name", processName)
                    .put("details", details == null ? new JSONObject() : details);
            append(context, processLogName(), line.toString() + "\n");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to persist diagnostic event " + name, error);
        }
    }

    static void captureHistoricalExitReasonsAsync(Context context) {
        if (Build.VERSION.SDK_INT < 30 || !isMainProcess(context)) return;
        long now = System.currentTimeMillis();
        if (now - lastExitScanAtMs < 2_000L || !EXIT_SCAN_RUNNING.compareAndSet(false, true)) {
            return;
        }
        lastExitScanAtMs = now;
        new Thread(() -> {
            try {
                ExitHistoryApi30.capture(context.getApplicationContext());
            } finally {
                EXIT_SCAN_RUNNING.set(false);
            }
        }, "driverlab-exit-history").start();
    }

    static void writeZip(Context context, OutputStream output) throws Exception {
        event("diagnostic_export_started", details());
        File directory = logsDirectory(context);
        File[] files = directory.listFiles(File::isFile);
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        byte[] buffer = new byte[16 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (File file : files) {
                ZipEntry entry = new ZipEntry("logs/" + file.getName());
                entry.setTime(file.lastModified());
                zip.putNextEntry(entry);
                try (FileInputStream input = new FileInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
            ZipEntry manifestEntry = new ZipEntry("export-info.json");
            zip.putNextEntry(manifestEntry);
            byte[] manifest = json()
                    .put("diagnostic_schema_version", SCHEMA_VERSION)
                    .put("exported_at", Instant.now().toString())
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("package_name", context.getPackageName())
                    .put("source_directory", directory.getAbsolutePath())
                    .put("file_count", files.length)
                    .toString(2).getBytes(StandardCharsets.UTF_8);
            zip.write(manifest);
            zip.closeEntry();
        }
    }

    private static void append(Context context, String fileName, String value) throws Exception {
        synchronized (WRITE_LOCK) {
            File file = new File(logsDirectory(context), fileName);
            rotateIfNeeded(file, value.getBytes(StandardCharsets.UTF_8).length);
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        }
    }

    static void rotateIfNeeded(File file, long incomingBytes) {
        if (!file.isFile() || file.length() + incomingBytes <= MAX_LOG_BYTES) return;
        File oldest = new File(file.getAbsolutePath() + "." + MAX_ARCHIVES);
        if (oldest.isFile() && !oldest.delete()) {
            Log.w(TAG, "Unable to delete old diagnostic archive: " + oldest);
        }
        for (int index = MAX_ARCHIVES - 1; index >= 1; --index) {
            File source = new File(file.getAbsolutePath() + "." + index);
            File destination = new File(file.getAbsolutePath() + "." + (index + 1));
            if (source.isFile() && !source.renameTo(destination)) {
                Log.w(TAG, "Unable to rotate diagnostic archive: " + source);
            }
        }
        File first = new File(file.getAbsolutePath() + ".1");
        if (!file.renameTo(first)) Log.w(TAG, "Unable to rotate diagnostic log: " + file);
    }

    private static void ensureMetadata(Context context) {
        try {
            File directory = logsDirectory(context);
            File readme = new File(directory, "README.txt");
            if (!readme.isFile()) {
                String text = "Amaral Driver Lab diagnostic logs\n\n"
                        + "Directory: " + directory.getAbsolutePath() + "\n"
                        + "app-main.log: main UI and Android exit history\n"
                        + "app-runner.log: isolated Vulkan runner and durable breadcrumbs\n"
                        + "exit-trace-*.trace: Android-provided ANR/native exit trace when available\n\n"
                        + "Logs rotate at 2 MiB and keep three archives per process.\n"
                        + "They are deleted if the application is uninstalled.\n";
                try (FileOutputStream output = new FileOutputStream(readme, false)) {
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            }
            File info = new File(directory, "diagnostic-info.json");
            JSONObject metadata = json()
                    .put("diagnostic_schema_version", SCHEMA_VERSION)
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("package_name", context.getPackageName())
                    .put("directory", directory.getAbsolutePath())
                    .put("android_sdk", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("updated_at", Instant.now().toString());
            try (FileOutputStream output = new FileOutputStream(info, false)) {
                output.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to initialize persistent diagnostics", error);
        }
    }

    private static void installUncaughtExceptionLogger() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            event("uncaught_java_exception", details(
                    "exception", error.toString(),
                    "stacktrace", stackTrace(error)));
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void registerActivityLifecycle(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                activityEvent("activity_created", activity);
            }
            @Override public void onActivityStarted(Activity activity) {
                activityEvent("activity_started", activity);
            }
            @Override public void onActivityResumed(Activity activity) {
                activityEvent("activity_resumed", activity);
                captureHistoricalExitReasonsAsync(activity);
            }
            @Override public void onActivityPaused(Activity activity) {
                activityEvent("activity_paused", activity);
            }
            @Override public void onActivityStopped(Activity activity) {
                activityEvent("activity_stopped", activity);
            }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                activityEvent("activity_state_saved", activity);
            }
            @Override public void onActivityDestroyed(Activity activity) {
                activityEvent("activity_destroyed", activity);
            }
        });
    }

    private static void activityEvent(String event, Activity activity) {
        event(event, details(
                "activity", activity.getClass().getSimpleName(),
                "finishing", activity.isFinishing(),
                "changing_configurations", activity.isChangingConfigurations(),
                "task_id", activity.getTaskId()));
    }

    private static String processLogName() {
        return processName.endsWith(":runner") ? "app-runner.log" : "app-main.log";
    }

    private static boolean isMainProcess(Context context) {
        return context.getPackageName().equals(currentProcessName(context));
    }

    private static String currentProcessName(Context context) {
        String name = Application.getProcessName();
        return name == null || name.isEmpty() ? context.getPackageName() : name;
    }

    private static JSONObject json() {
        return new JSONObject();
    }

    static JSONObject details(Object... keyValues) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index + 1 < keyValues.length; index += 2) {
                result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
            }
        } catch (Throwable ignored) {
            // Diagnostic construction must never interfere with the tested workload.
        }
        return result;
    }

    private static String stackTrace(Throwable error) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        java.io.PrintWriter writer = new java.io.PrintWriter(output);
        error.printStackTrace(writer);
        writer.flush();
        return output.toString(StandardCharsets.UTF_8);
    }
}
