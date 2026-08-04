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
            if (currentRun == null) {
                QualificationStore.markPaused(manifest);
                active = false;
            }
            QualificationStore.save(qualificationFile, manifest);
            listener.onUpdated(qualificationFile, manifest);
            listener.onStatus(currentRun == null ? "Full Qualification pausado."
                    : "Pausa solicitada; a etapa atual será concluída primeiro.");
        } catch (Throwable error) {
            listener.onFailure("Falha ao solicitar pausa", error);
        }
    }

    boolean isActive() {
        return active;
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
            QualificationProfile.Step step = QualificationProfile.step(stepId);
            if (step == null) throw new IllegalStateException("Etapa desconhecida: " + stepId);
            QualificationStore.markStepRunning(manifest, stepId);
            QualificationStore.save(qualificationFile, manifest);
            listener.onUpdated(qualificationFile, manifest);
            int ordinal = stepOrdinal(stepId);
            listener.onStatus("Full Qualification " + ordinal + "/"
                    + QualificationProfile.steps().size() + " · " + step.label);
            JSONObject context = new JSONObject()
                    .put("qualification_schema_version", Phase7Contract.QUALIFICATION_SCHEMA_VERSION)
                    .put("qualification_id", manifest.getString("qualification_id"))
                    .put("profile_id", Phase7Contract.PROFILE_ID)
                    .put("profile_version", Phase7Contract.PROFILE_VERSION)
                    .put("profile_sha256", manifest.getString("profile_sha256"))
                    .put("step_id", stepId)
                    .put("step_ordinal", ordinal)
                    .put("step_count", QualificationProfile.steps().size())
                    .put("score_weight", step.weight)
                    .put("compatibility_gate", step.compatibilityGate);
            currentRun = new RunCoordinator(activity, driver, RunCoordinator.MODE_AB,
                    step.rounds, step.warmupSeconds, step.measureSeconds,
                    step.workloadId, step.traceId,
                    WorkloadContract.DEFAULT_PIXEL_TOLERANCE,
                    WorkloadContract.DEFAULT_MAX_DIVERGENT_BLOCKS,
                    null, context, new RunCoordinator.Listener() {
                        @Override
                        public void onStatus(String message) {
                            listener.onStatus("[" + stepId + "] " + message);
                        }

                        @Override
                        public void onComplete(File reportFile, JSONObject report) {
                            currentRun = null;
                            completeStep(step, reportFile, report);
                        }

                        @Override
                        public void onFailure(String message, Throwable error) {
                            currentRun = null;
                            failStep(step, message + (error == null ? "" : ": " + error.getMessage()));
                        }
                    });
            currentRun.start();
        } catch (Throwable error) {
            active = false;
            listener.onFailure("Falha ao preparar " + (launchingStep == null ? "a próxima etapa" : launchingStep), error);
        }
    }

    private void completeStep(QualificationProfile.Step step, File reportFile, JSONObject report) {
        try {
            QualificationStore.markStepCompleted(activity.getFilesDir(), manifest,
                    step.stepId, reportFile, report);
            QualificationStore.save(qualificationFile, manifest);
            listener.onUpdated(qualificationFile, manifest);
            continueAfterCooldown(step.cooldownSeconds);
        } catch (Throwable error) {
            failStep(step, "Falha ao registrar suite.json: " + error.getMessage());
        }
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
            listener.onStatus("Consolidando score, ranking e pacote completo de diagnóstico…");
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
        int index = 1;
        for (QualificationProfile.Step step : QualificationProfile.steps()) {
            if (step.stepId.equals(stepId)) return index;
            index++;
        }
        return 0;
    }
}
