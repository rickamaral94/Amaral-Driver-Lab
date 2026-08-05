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
        final boolean candidateArm;
        final String label;
        final DriverPackage driver;
        Phase(boolean candidateArm, DriverPackage driver) {
            this.candidateArm = candidateArm;
            this.label = candidateArm ? "candidate" : "system";
            this.driver = driver;
        }
        boolean usesCustomDriver() { return driver != null; }
        String executionRole() {
            return DriverExecutionIdentity.role(candidateArm, usesCustomDriver());
        }
    }

    private final Activity activity;
    private final DriverPackage candidate;
    private final DriverPackage reference;
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
        this(activity, candidate, null, mode, cycles, memoryMiB, listener);
    }

    DeepDiagnosticsCoordinator(Activity activity, DriverPackage candidate,
                               DriverPackage reference, String mode,
                               int cycles, int memoryMiB, Listener listener) {
        this.activity = activity;
        this.candidate = candidate;
        this.reference = reference;
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
            if (reference != null && !reference.isUsable()) {
                throw new IllegalStateException("Driver de referência inválido");
            }
            if (reference != null && candidate.sha256.equalsIgnoreCase(reference.sha256)) {
                throw new IllegalStateException("Candidato e referência devem ser diferentes");
            }
            startedAt = System.currentTimeMillis();
            directory = new File(new File(activity.getFilesDir(), "deep-diagnostics"),
                    "report-" + startedAt);
            if (!directory.mkdirs()) {
                throw new IllegalStateException("Não foi possível criar a pasta da Fase 10");
            }
            phases.add(new Phase(false, reference));
            phases.add(new Phase(true, candidate));
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
                + (phase.driver == null ? "driver do sistema" : phase.driver.displayName()));
        Intent intent = new Intent(activity, DeepDiagnosticsRunnerActivity.class);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_RESULT_PATH,
                currentResult.getAbsolutePath());
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_PHASE_LABEL, phase.label);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_MODE, mode);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_CYCLES, cycles);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_MEMORY_MIB, memoryMiB);
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_MODE_OVERRIDE,
                DriverExecutionIdentity.mode(phase.usesCustomDriver()));
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_ROLE, phase.executionRole());
        intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_DISPLAY_NAME,
                phase.driver == null ? "" : phase.driver.displayName());
        if (phase.driver != null) {
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_DIR,
                    phase.driver.directory.getAbsolutePath());
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_NAME,
                    phase.driver.libraryName);
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_META,
                    phase.driver.metadata.toString());
            intent.putExtra(DeepDiagnosticsRunnerActivity.EXTRA_DRIVER_SHA, phase.driver.sha256);
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
                .put("driver_mode", DriverExecutionIdentity.mode(phase.usesCustomDriver()))
                .put("driver_role", phase.executionRole())
                .put("driver_display_name", phase.driver == null
                        ? JSONObject.NULL : phase.driver.displayName())
                .put("driver_sha256", phase.driver == null
                        ? JSONObject.NULL : phase.driver.sha256)
                .put("success", false)
                .put("failure_type", type)
                .put("failure_stage", stage)
                .put("finished_at_ms", System.currentTimeMillis());
        ResultFiles.writeAtomic(currentResult, failure.toString(2));
        results.put(failure);
    }

    private void finish() {
        try {
            JSONObject system = find(false);
            JSONObject candidateResult = find(true);
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
                    .put("reference_driver", reference == null
                            ? JSONObject.NULL : reference.toJson())
                    .put("comparison_mode", reference == null
                            ? "system_vs_turnip" : "turnip_vs_turnip")
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

    private JSONObject find(boolean candidateArm) {
        for (int index = 0; index < results.length(); ++index) {
            JSONObject phase = results.optJSONObject(index);
            if (phase != null
                    && candidateArm == DriverExecutionIdentity.isCandidateArm(phase)) return phase;
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
