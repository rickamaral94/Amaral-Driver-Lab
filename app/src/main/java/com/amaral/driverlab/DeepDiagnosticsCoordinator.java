package com.amaral.driverlab;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DeepDiagnosticsCoordinator {
    interface Listener {
        void onStatus(String status);
        void onComplete(File reportFile, File bundleFile, JSONObject report);
        void onFailure(String message, Throwable error);
    }

    private static final class Phase {
        final boolean custom;
        final String label;
        Phase(boolean custom) {
            this.custom = custom;
            this.label = custom ? "candidate" : "system";
        }
    }

    private final Activity activity;
    private final DriverPackage candidate;
    private final String mode;
    private final int cycles;
    private final int memoryMiB;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Phase> phases = new ArrayList<>();
    private final JSONArray results = new JSONArray();

    private File directory;
    private File currentResult;
    private long deadlineElapsed;
    private int phaseIndex;
    private long startedAt;
    private boolean active;

    DeepDiagnosticsCoordinator(Activity activity, DriverPackage candidate, String mode,
                               int cycles, int memoryMiB, Listener listener) {
        this.activity = activity;
        this.candidate = candidate;
        this.mode = "soak".equals(mode) ? "soak" : "full";
        this.cycles = Math.max(Phase10Contract.MIN_SOAK_CYCLES,
                Math.min(cycles, Phase10Contract.MAX_SOAK_CYCLES));
        this.memoryMiB = Math.max(Phase10Contract.MIN_MEMORY_MIB,
                Math.min(memoryMiB, Phase10Contract.MAX_MEMORY_MIB));
        this.listener = listener;
    }

    void start() {
        try {
            if (candidate == null || !candidate.isUsable()) {
                throw new IllegalStateException("Selecione um driver Turnip válido");
            }
            startedAt = System.currentTimeMillis();
            directory = new File(new File(activity.getFilesDir(), "deep-diagnostics"),
                    "report-" + startedAt);
            if (!directory.mkdirs()) {
                throw new IllegalStateException("Não foi possível criar a pasta da Fase 10");
            }
            phases.add(new Phase(false));
            phases.add(new Phase(true));
            active = true;
            launchNext();
        } catch (Throwable error) {
            listener.onFailure("Não foi possível iniciar a Fase 10", error);
        }
    }

    boolean isActive() { return active; }

    private void launchNext() {
        if (!active) return;
        if (phaseIndex >= phases.size()) {
            finish();
            return;
        }
        Phase phase = phases.get(phaseIndex);
        currentResult = new File(directory, String.format(Locale.US,
                "phase-%02d-%s.json", phaseIndex + 1, phase.label));
        listener.onStatus(("soak".equals(mode) ? "Soak Test" : "Diagnóstico profundo")
                + " · " + (phaseIndex + 1) + "/" + phases.size() + " · "
                + (phase.custom ? candidate.displayName() : "driver do sistema"));
        Intent intent = new Intent(activity, DeepDiagnosticsRunnerActivity.class);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_RESULT_PATH,
                currentResult.getAbsolutePath());
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_PHASE_LABEL, phase.label);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_MODE, mode);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_CYCLES, cycles);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_MEMORY_MIB, memoryMiB);
        if (phase.custom) {
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_DIR,
                    candidate.directory.getAbsolutePath());
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_NAME,
                    candidate.libraryName);
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_META,
                    candidate.metadata.toString());
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_SHA, candidate.sha256);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.startActivity(intent);
        long timeoutSeconds = "soak".equals(mode)
                ? Math.max(180L, cycles * 15L + 90L) : 420L;
        deadlineElapsed = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L;
        handler.postDelayed(this::poll, 500L);
    }

    private void poll() {
        if (!active) return;
        try {
            if (currentResult.isFile()) {
                results.put(new JSONObject(ResultFiles.readUtf8(currentResult)));
                phaseIndex++;
                handler.postDelayed(this::launchNext, 1200L);
                return;
            }
            if (SystemClock.elapsedRealtime() >= deadlineElapsed) {
                killRunner();
                recordSyntheticFailure("timeout", "phase10_runner_timeout");
                phaseIndex++;
                handler.postDelayed(this::launchNext, 1200L);
                return;
            }
            handler.postDelayed(this::poll, 500L);
        } catch (Throwable error) {
            active = false;
            listener.onFailure("Falha ao ler o diagnóstico", error);
        }
    }

    private void recordSyntheticFailure(String type, String stage) throws Exception {
        Phase phase = phases.get(phaseIndex);
        JSONObject failure = new JSONObject()
                .put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION)
                .put("phase10_contract", Phase10Contract.contractJson())
                    .put("phase11_contract", Phase11Contract.contractJson())
                .put("phase", phase.label)
                .put("driver_mode", phase.custom ? "custom" : "system")
                .put("driver_sha256", phase.custom ? candidate.sha256 : JSONObject.NULL)
                .put("success", false)
                .put("failure_type", type)
                .put("failure_stage", stage)
                .put("finished_at_ms", System.currentTimeMillis());
        ResultFiles.writeAtomic(currentResult, failure.toString(2));
        results.put(failure);
    }

    private void finish() {
        try {
            JSONObject system = find("system");
            JSONObject candidateResult = find("custom");
            JSONObject comparison = DeepDiagnosticsComparison.compare(system, candidateResult);
            String reportId = "phase10-" + startedAt;
            JSONObject report = new JSONObject()
                    .put("schema_version", WorkloadContract.RESULT_SCHEMA_VERSION)
                    .put("deep_diagnostic_report_version", Phase10Contract.REPORT_VERSION)
                    .put("report_id", reportId)
                    .put("profile_id", Phase10Contract.PROFILE_ID)
                    .put("profile_version", Phase10Contract.PROFILE_VERSION)
                    .put("profile_sha256", Phase10Contract.profileSha256())
                    .put("phase10_contract", Phase10Contract.contractJson())
                    .put("phase11_contract", Phase11Contract.contractJson())
                    .put("mode", mode)
                    .put("cycles", cycles)
                    .put("memory_mib", memoryMiB)
                    .put("started_at_ms", startedAt)
                    .put("finished_at_ms", System.currentTimeMillis())
                    .put("candidate_driver", candidate.toJson())
                    .put("phases", results)
                    .put("comparison", comparison)
                    .put("historical_comparability", new JSONObject()
                            .put("series", Phase10Contract.PROFILE_ID + "/v1/" + mode)
                            .put("requires_same_profile_sha256", true)
                            .put("mixed_with_full_qualification", false)
                            .put("mixed_with_existing_workloads", false))
                    .put("limitations", Phase10Contract.LIMITATION);
            File reportFile = new File(directory, "report.json");
            ResultFiles.writeAtomic(reportFile, report.toString(2));
            JSONObject bundle = DeepDiagnosticsBundle.create(directory, report);
            report.put("diagnostic_bundle", bundle);
            ResultFiles.writeAtomic(reportFile, report.toString(2));
            active = false;
            File bundleFile = new File(directory, bundle.getString("relative_path"));
            listener.onComplete(reportFile, bundleFile, report);
        } catch (Throwable error) {
            active = false;
            listener.onFailure("Falha ao consolidar a Fase 10", error);
        }
    }

    private JSONObject find(String driverMode) {
        for (int index = 0; index < results.length(); ++index) {
            JSONObject phase = results.optJSONObject(index);
            if (phase != null && driverMode.equals(phase.optString("driver_mode"))) return phase;
        }
        return null;
    }

    private void killRunner() {
        try {
            ActivityManager manager = (ActivityManager) activity.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return;
            String expected = activity.getPackageName() + ":runner";
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (expected.equals(process.processName)) Process.killProcess(process.pid);
            }
        } catch (Exception ignored) {
            // Timeout still becomes an explicit synthetic failure.
        }
    }
}
