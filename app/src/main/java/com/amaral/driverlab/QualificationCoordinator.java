package com.amaral.driverlab;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;

final class QualificationCoordinator {
    interface Listener {
        void onStatus(String message);
        void onUpdated(File qualificationFile, JSONObject manifest);
        void onComplete(File qualificationFile, JSONObject manifest, File bundleFile);
        void onFailure(String message, Throwable error);
    }

    private final Activity activity;
    private final File qualificationFile;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject manifest;
    private DriverPackage driver;
    private RunCoordinator currentRun;
    private DeepDiagnosticsCoordinator currentDeepDiagnostics;
    private boolean active;

    QualificationCoordinator(Activity activity, File qualificationFile, Listener listener) {
        this.activity = activity;
        this.qualificationFile = qualificationFile;
        this.listener = listener;
    }

    void startOrResume() {
        if (active) return;
        try {
            manifest = QualificationStore.load(qualificationFile);
            driver = loadDriver(manifest.getJSONObject("driver").getString("sha256"));
            int recovered = QualificationStore.recoverInterrupted(manifest);
            QualificationStore.markRunning(manifest);
            QualificationStore.save(qualificationFile, manifest);
            active = true;
            listener.onUpdated(qualificationFile, manifest);
            if (recovered > 0) {
                listener.onStatus(recovered + " etapa(s) interrompida(s) voltarão para pending.");
            }
            launchNext();
        } catch (Throwable error) {
            active = false;
            listener.onFailure("Não foi possível iniciar o Full Qualification", error);
        }
    }

    void pauseAfterCurrent() {
        if (!active || manifest == null) return;
        try {
            QualificationStore.requestPause(manifest);
            if (!hasCurrentWork()) {
                QualificationStore.markPaused(manifest);
                active = false;
            }
            QualificationStore.save(qualificationFile, manifest);
            listener.onUpdated(qualificationFile, manifest);
            listener.onStatus(!hasCurrentWork() ? "Full Qualification pausado."
                    : "Pausa solicitada; o bloco atual será concluído primeiro.");
        } catch (Throwable error) {
            listener.onFailure("Falha ao solicitar pausa", error);
        }
    }

    boolean isActive() { return active; }

    private boolean hasCurrentWork() {
        return currentRun != null || currentDeepDiagnostics != null;
    }

    private void launchNext() {
        if (!active) return;
        String launchingStep = null;
        try {
            if (QualificationStore.pauseRequested(manifest)) {
                QualificationStore.markPaused(manifest);
                QualificationStore.save(qualificationFile, manifest);
                active = false;
                listener.onUpdated(qualificationFile, manifest);
                listener.onStatus("Full Qualification pausado.");
                return;
            }
            JSONObject state = QualificationStore.nextPending(manifest);
            if (state == null) {
                finishQualification();
                return;
            }
            String stepId = state.getString("step_id");
            launchingStep = stepId;
            int profileVersion = manifest.getJSONObject("profile").getInt("profile_version");
            java.util.List<QualificationProfile.Step> profileSteps =
                    QualificationProfile.stepsForVersion(profileVersion);
            QualificationProfile.Step step = QualificationProfile.step(profileVersion, stepId);
            if (step == null) throw new IllegalStateException("Etapa desconhecida: " + stepId);
            QualificationStore.markStepRunning(manifest, stepId);
            QualificationStore.save(qualificationFile, manifest);
            listener.onUpdated(qualificationFile, manifest);
            int ordinal = stepOrdinal(stepId);
            listener.onStatus("Full Qualification " + ordinal + "/"
                    + profileSteps.size() + " · " + step.label);
            if (QualificationProfile.KIND_SUITE.equals(step.kind)) launchSuite(step, ordinal,
                    profileVersion, profileSteps.size());
            else launchDeepDiagnostics(step);
        } catch (Throwable error) {
            active = false;
            listener.onFailure("Falha ao preparar "
                    + (launchingStep == null ? "a próxima etapa" : launchingStep), error);
        }
    }

    private void launchSuite(QualificationProfile.Step step, int ordinal,
                             int profileVersion, int stepCount) throws Exception {
        int schemaVersion = profileVersion >= 3
                ? Phase11Contract.QUALIFICATION_SCHEMA_VERSION
                : Phase7Contract.QUALIFICATION_SCHEMA_VERSION;
        JSONObject context = new JSONObject()
                .put("qualification_schema_version", schemaVersion)
                .put("qualification_id", manifest.getString("qualification_id"))
                .put("profile_id", Phase7Contract.PROFILE_ID)
                .put("profile_version", profileVersion)
                .put("profile_sha256", manifest.getString("profile_sha256"))
                .put("step_id", step.stepId)
                .put("step_kind", step.kind)
                .put("step_ordinal", ordinal)
                .put("step_count", stepCount)
                .put("score_weight", step.weight)
                .put("compatibility_gate", step.compatibilityGate);
        currentRun = new RunCoordinator(activity, driver, RunCoordinator.MODE_AB,
                step.rounds, step.warmupSeconds, step.measureSeconds,
                step.workloadId, step.traceId,
                VisualSceneContract.isVisualScene(step.workloadId)
                        ? VisualSceneContract.DEFAULT_PIXEL_TOLERANCE
                        : WorkloadContract.DEFAULT_PIXEL_TOLERANCE,
                VisualSceneContract.isVisualScene(step.workloadId)
                        ? VisualSceneContract.DEFAULT_MAX_DIVERGENT_BLOCKS
                        : WorkloadContract.DEFAULT_MAX_DIVERGENT_BLOCKS,
                null, context, new RunCoordinator.Listener() {
                    @Override public void onStatus(String message) {
                        listener.onStatus("[" + step.stepId + "] " + message);
                    }
                    @Override public void onComplete(File reportFile, JSONObject report) {
                        currentRun = null;
                        completeSuiteStep(step, reportFile, report);
                    }
                    @Override public void onFailure(String message, Throwable error) {
                        currentRun = null;
                        failStep(step, message
                                + (error == null ? "" : ": " + error.getMessage()));
                    }
                });
        currentRun.start();
    }

    private void launchDeepDiagnostics(QualificationProfile.Step step) {
        String mode = QualificationProfile.KIND_SHORT_SOAK.equals(step.kind) ? "soak" : "full";
        currentDeepDiagnostics = new DeepDiagnosticsCoordinator(activity, driver, mode,
                Math.max(1, step.diagnosticCycles), step.memoryMiB,
                new DeepDiagnosticsCoordinator.Listener() {
                    @Override public void onStatus(String status) {
                        listener.onStatus("[" + step.stepId + "] " + status);
                    }
                    @Override public void onComplete(File reportFile, File bundleFile,
                                                     JSONObject report) {
                        currentDeepDiagnostics = null;
                        completeArtifactStep(step, reportFile, report);
                    }
                    @Override public void onFailure(String message, Throwable error) {
                        currentDeepDiagnostics = null;
                        failStep(step, message
                                + (error == null ? "" : ": " + error.getMessage()));
                    }
                });
        currentDeepDiagnostics.start();
    }

    private void completeSuiteStep(QualificationProfile.Step step, File reportFile,
                                   JSONObject report) {
        try {
            QualificationStore.markStepCompleted(activity.getFilesDir(), manifest,
                    step.stepId, reportFile, report);
            persistAndContinue(step);
        } catch (Throwable error) {
            failStep(step, "Falha ao registrar suite.json: " + error.getMessage());
        }
    }

    private void completeArtifactStep(QualificationProfile.Step step, File reportFile,
                                      JSONObject report) {
        try {
            QualificationStore.markStepCompletedArtifact(activity.getFilesDir(), manifest,
                    step.stepId, reportFile, report, step.kind);
            persistAndContinue(step);
        } catch (Throwable error) {
            failStep(step, "Falha ao registrar diagnóstico: " + error.getMessage());
        }
    }

    private void persistAndContinue(QualificationProfile.Step step) throws Exception {
        QualificationStore.save(qualificationFile, manifest);
        listener.onUpdated(qualificationFile, manifest);
        continueAfterCooldown(step.cooldownSeconds);
    }

    private void failStep(QualificationProfile.Step step, String message) {
        try {
            QualificationStore.markStepFailed(manifest, step.stepId, message);
            QualificationStore.save(qualificationFile, manifest);
            listener.onUpdated(qualificationFile, manifest);
            listener.onStatus("Etapa " + step.label + " falhou; o diagnóstico continuará.");
            continueAfterCooldown(step.cooldownSeconds);
        } catch (Throwable error) {
            active = false;
            listener.onFailure("Falha ao registrar erro da etapa", error);
        }
    }

    private void continueAfterCooldown(int seconds) throws Exception {
        if (QualificationStore.pauseRequested(manifest)) {
            QualificationStore.markPaused(manifest);
            QualificationStore.save(qualificationFile, manifest);
            active = false;
            listener.onUpdated(qualificationFile, manifest);
            listener.onStatus("Full Qualification pausado após a etapa atual.");
            return;
        }
        if (seconds <= 0) handler.post(this::launchNext);
        else {
            listener.onStatus("Cooldown de " + seconds + " s antes da próxima etapa.");
            handler.postDelayed(this::launchNext, seconds * 1000L);
        }
    }

    private void finishQualification() {
        try {
            listener.onStatus("Consolidando performance, compatibilidade, telemetria e bundle…");
            JSONObject finalEnvironment = QualificationPreflight.capture(activity);
            JSONObject comparison = QualificationPreflight.compare(
                    manifest.getJSONObject("preflight"), finalEnvironment);
            File directory = qualificationFile.getParentFile();
            ResultFiles.writeAtomic(new File(directory, "final-environment.json"),
                    finalEnvironment.toString(2));
            ResultFiles.writeAtomic(new File(directory, "environment-comparison.json"),
                    comparison.toString(2));
            JSONObject report = QualificationReport.build(
                    activity.getFilesDir(), manifest, finalEnvironment, comparison);
            File reportFile = new File(directory, "report.json");
            ResultFiles.writeAtomic(reportFile, report.toString(2));
            report.put("local_leaderboard",
                    QualificationHistory.leaderboard(activity.getFilesDir(), report));
            ResultFiles.writeAtomic(reportFile, report.toString(2));
            ResultFiles.writeAtomic(new File(directory, "summary.html"),
                    HtmlReportRenderer.render(report));
            JSONObject bundle = DiagnosticBundle.create(
                    activity.getFilesDir(), qualificationFile, manifest, report);
            QualificationStore.finish(manifest, finalEnvironment, comparison, report, bundle);
            QualificationStore.save(qualificationFile, manifest);
            active = false;
            listener.onComplete(qualificationFile, manifest,
                    new File(directory, bundle.getString("relative_path")));
        } catch (Throwable error) {
            active = false;
            listener.onFailure("Falha ao consolidar o Full Qualification", error);
        }
    }

    private DriverPackage loadDriver(String sha256) throws Exception {
        File directory = new File(new File(activity.getFilesDir(), "drivers"), sha256);
        DriverPackage loaded = DriverPackage.fromJson(
                ResultFiles.readUtf8(new File(directory, "descriptor.json")));
        if (!sha256.equalsIgnoreCase(loaded.sha256) || !loaded.isUsable()) {
            throw new IllegalStateException("Driver ausente ou inválido: " + sha256);
        }
        return loaded;
    }

    private int stepOrdinal(String stepId) {
        int profileVersion = manifest == null ? QualificationProfile.currentVersion()
                : manifest.optJSONObject("profile") == null
                ? QualificationProfile.currentVersion()
                : manifest.optJSONObject("profile").optInt(
                        "profile_version", QualificationProfile.currentVersion());
        int index = 1;
        for (QualificationProfile.Step step : QualificationProfile.stepsForVersion(profileVersion)) {
            if (step.stepId.equals(stepId)) return index;
            index++;
        }
        return 0;
    }
}
