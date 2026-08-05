package com.amaral.driverlab;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;

final class CampaignCoordinator {
    interface Listener {
        void onCampaignStatus(String message);
        void onCampaignUpdated(File campaignFile, JSONObject campaign);
        void onCampaignComplete(File campaignFile, JSONObject campaign);
        void onCampaignFailure(String message, Throwable error);
    }

    private final Activity activity;
    private final File campaignFile;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject campaign;
    private RunCoordinator currentRun;
    private boolean active;

    CampaignCoordinator(Activity activity, File campaignFile, Listener listener) {
        this.activity = activity;
        this.campaignFile = campaignFile;
        this.listener = listener;
    }

    void startOrResume() {
        if (active) return;
        try {
            campaign = CampaignStore.load(campaignFile);
            int recovered = CampaignStore.recoverInterrupted(campaign);
            CampaignStore.markCampaignRunning(campaign);
            CampaignStore.save(campaignFile, campaign);
            active = true;
            listener.onCampaignUpdated(campaignFile, campaign);
            if (recovered > 0) {
                listener.onCampaignStatus(recovered
                        + " job(s) interrompido(s) serão repetidos antes de concluir.");
            }
            launchNext();
        } catch (Throwable error) {
            listener.onCampaignFailure("Não foi possível iniciar a campanha", error);
        }
    }

    void pauseAfterCurrent() {
        if (!active || campaign == null) return;
        try {
            CampaignStore.requestPause(campaign);
            if (currentRun == null) {
                CampaignStore.markPaused(campaign);
                active = false;
            }
            CampaignStore.save(campaignFile, campaign);
            listener.onCampaignUpdated(campaignFile, campaign);
            listener.onCampaignStatus(currentRun == null
                    ? "Campanha pausada."
                    : "Pausa solicitada; o job atual será concluído primeiro.");
        } catch (Throwable error) {
            listener.onCampaignFailure("Falha ao pausar a campanha", error);
        }
    }

    boolean isActive() {
        return active;
    }

    private void launchNext() {
        if (!active) return;
        String launchingJobId = null;
        try {
            if (CampaignStore.pauseRequested(campaign)) {
                CampaignStore.markPaused(campaign);
                CampaignStore.save(campaignFile, campaign);
                active = false;
                listener.onCampaignUpdated(campaignFile, campaign);
                listener.onCampaignStatus("Campanha pausada.");
                return;
            }
            JSONObject state = CampaignStore.nextPending(campaign);
            if (state == null) {
                finishCampaign();
                return;
            }
            String jobId = state.getString("job_id");
            launchingJobId = jobId;
            JSONObject job = CampaignPlan.immutableJob(campaign, jobId);
            if (job == null) throw new IllegalStateException("Plano sem " + jobId);
            DriverPackage driver = loadDriver(job.getString("candidate_sha256"));
            JSONObject protocol = campaign.getJSONObject("plan").getJSONObject("protocol");
            CampaignWorkload workload = CampaignWorkload.fromJson(job);
            CampaignStore.markRunning(campaign, jobId);
            CampaignStore.save(campaignFile, campaign);
            listener.onCampaignUpdated(campaignFile, campaign);

            int ordinal = job.optInt("ordinal", 0);
            int total = CampaignStore.totalJobs(campaign);
            listener.onCampaignStatus("Campanha " + ordinal + "/" + total + " · "
                    + driver.displayName() + " · " + workload.label());
            JSONObject context = new JSONObject()
                    .put("campaign_schema_version", Phase6Contract.CAMPAIGN_SCHEMA_VERSION)
                    .put("campaign_id", campaign.getString("campaign_id"))
                    .put("job_id", jobId)
                    .put("job_ordinal", ordinal)
                    .put("job_count", total)
                    .put("scheduler_version", Phase6Contract.SCHEDULER_VERSION)
                    .put("order_policy", Phase6Contract.ORDER_POLICY)
                    .put("thermal_position", job.optInt("thermal_position", 0))
                    .put("plan_sha256", campaign.getString("plan_sha256"));

            currentRun = new RunCoordinator(activity, driver, RunCoordinator.MODE_AB,
                    protocol.getInt("rounds"), protocol.getInt("warmup_seconds"),
                    protocol.getInt("measure_seconds"), workload.workloadId, workload.traceId,
                    protocol.getInt("pixel_tolerance"),
                    protocol.getInt("maximum_divergent_blocks"), context,
                    new RunCoordinator.Listener() {
                        @Override
                        public void onStatus(String message) {
                            listener.onCampaignStatus("[" + jobId + "] " + message);
                        }

                        @Override
                        public void onComplete(File reportFile, JSONObject report) {
                            currentRun = null;
                            completeJob(jobId, reportFile, report);
                        }

                        @Override
                        public void onFailure(String message, Throwable error) {
                            currentRun = null;
                            failJob(jobId, message + (error == null ? ""
                                    : ": " + error.getMessage()));
                        }
                    });
            currentRun.start();
        } catch (Throwable error) {
            if (launchingJobId == null) {
                JSONObject state = CampaignStore.nextPending(campaign);
                launchingJobId = state == null ? null : state.optString("job_id", "");
            }
            if (launchingJobId == null || launchingJobId.isEmpty()) {
                listener.onCampaignFailure("Falha ao preparar o próximo job", error);
                active = false;
                return;
            }
            failJob(launchingJobId, error.getMessage());
        }
    }

    private void completeJob(String jobId, File reportFile, JSONObject report) {
        try {
            CampaignStore.markCompleted(activity.getFilesDir(), campaign, jobId,
                    reportFile, report);
            CampaignStore.save(campaignFile, campaign);
            listener.onCampaignUpdated(campaignFile, campaign);
            continueAfterCooldown();
        } catch (Throwable error) {
            failJob(jobId, "Falha ao registrar suite.json: " + error.getMessage());
        }
    }

    private void failJob(String jobId, String message) {
        try {
            JSONObject state = CampaignStore.stateFor(campaign, jobId);
            if (state != null && !"failed".equals(state.optString("status"))) {
                CampaignStore.markFailed(campaign, jobId, message);
            }
            CampaignStore.save(campaignFile, campaign);
            listener.onCampaignUpdated(campaignFile, campaign);
            listener.onCampaignStatus("Job " + jobId + " falhou: " + message);
            continueAfterCooldown();
        } catch (Throwable error) {
            active = false;
            listener.onCampaignFailure("Falha ao registrar erro da campanha", error);
        }
    }

    private void continueAfterCooldown() throws Exception {
        if (CampaignStore.pauseRequested(campaign)) {
            CampaignStore.markPaused(campaign);
            CampaignStore.save(campaignFile, campaign);
            active = false;
            listener.onCampaignUpdated(campaignFile, campaign);
            listener.onCampaignStatus("Campanha pausada após concluir o job atual.");
            return;
        }
        int cooldown = campaign.getJSONObject("plan").getJSONObject("protocol")
                .optInt("cooldown_seconds", 0);
        if (cooldown <= 0) {
            handler.post(this::launchNext);
        } else {
            listener.onCampaignStatus("Cooldown de " + cooldown
                    + " s antes do próximo job.");
            handler.postDelayed(this::launchNext, cooldown * 1000L);
        }
    }

    private void finishCampaign() {
        try {
            JSONObject summary = CampaignSummary.build(activity.getFilesDir(), campaign);
            CampaignStore.finish(campaign, summary);
            CampaignStore.save(campaignFile, campaign);
            active = false;
            listener.onCampaignComplete(campaignFile, campaign);
        } catch (Throwable error) {
            active = false;
            listener.onCampaignFailure("Falha ao consolidar a campanha", error);
        }
    }

    private DriverPackage loadDriver(String sha256) throws Exception {
        File directory = new File(new File(activity.getFilesDir(), "drivers"), sha256);
        File descriptor = new File(directory, "descriptor.json");
        DriverPackage driver = DriverPackage.fromJson(ResultFiles.readUtf8(descriptor));
        if (!sha256.equalsIgnoreCase(driver.sha256) || !driver.isUsable()) {
            throw new IllegalStateException("Driver ausente ou inválido: " + sha256);
        }
        return driver;
    }
}
