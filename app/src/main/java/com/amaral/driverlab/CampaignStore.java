package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

final class CampaignStore {
    private CampaignStore() {}

    static File create(File filesDir, JSONObject campaign) throws Exception {
        if (!CampaignPlan.verify(campaign)) {
            throw new IllegalArgumentException("Manifesto de campanha inválido");
        }
        File root = new File(filesDir, "campaigns");
        File directory = new File(root, campaign.getString("campaign_id"));
        if (directory.exists() || !directory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta da campanha");
        }
        File file = new File(directory, "campaign.json");
        save(file, campaign);
        return file;
    }

    static JSONObject load(File campaignFile) throws Exception {
        JSONObject campaign = new JSONObject(ResultFiles.readUtf8(campaignFile));
        if (!CampaignPlan.verify(campaign)) {
            throw new IllegalArgumentException("campaign.json inválido ou adulterado");
        }
        return campaign;
    }

    static void save(File campaignFile, JSONObject campaign) throws Exception {
        if (!CampaignPlan.verify(campaign)) {
            throw new IllegalArgumentException("Manifesto de campanha inválido");
        }
        ResultFiles.writeAtomic(campaignFile, campaign.toString(2));
    }

    static File findLatestIncomplete(File filesDir) {
        File root = new File(filesDir, "campaigns");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) return null;
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            File file = new File(directory, "campaign.json");
            if (!file.isFile()) continue;
            try {
                JSONObject campaign = load(file);
                String state = campaign.getJSONObject("execution").optString("state", "pending");
                if (!state.startsWith("completed")) return file;
            } catch (Exception ignored) {
                // Corrupt campaigns are intentionally not resumed automatically.
            }
        }
        return null;
    }

    static int recoverInterrupted(JSONObject campaign) throws Exception {
        JSONArray jobs = campaign.getJSONObject("execution").getJSONArray("jobs");
        int recovered = 0;
        for (int index = 0; index < jobs.length(); ++index) {
            JSONObject state = jobs.getJSONObject(index);
            if (!"running".equals(state.optString("status"))) continue;
            state.put("status", "pending");
            state.put("started_at_ms", JSONObject.NULL);
            state.put("finished_at_ms", JSONObject.NULL);
            state.put("failure", JSONObject.NULL);
            recovered++;
        }
        if (recovered > 0) {
            JSONObject execution = campaign.getJSONObject("execution");
            execution.put("state", "paused");
            execution.put("pause_requested", false);
            execution.put("recovery_count", execution.optInt("recovery_count", 0) + recovered);
            execution.getJSONArray("warnings").put(
                    recovered + " job(s) interrompido(s) retornaram para pending; serão repetidos.");
        }
        return recovered;
    }

    static JSONObject nextPending(JSONObject campaign) {
        JSONArray states = campaign.optJSONObject("execution") == null ? null
                : campaign.optJSONObject("execution").optJSONArray("jobs");
        if (states == null) return null;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.optJSONObject(index);
            if (state != null && "pending".equals(state.optString("status"))) return state;
        }
        return null;
    }

    static JSONObject stateFor(JSONObject campaign, String jobId) {
        JSONArray states = campaign.optJSONObject("execution") == null ? null
                : campaign.optJSONObject("execution").optJSONArray("jobs");
        if (states == null) return null;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.optJSONObject(index);
            if (state != null && jobId.equals(state.optString("job_id"))) return state;
        }
        return null;
    }

    static void markCampaignRunning(JSONObject campaign) throws Exception {
        JSONObject execution = campaign.getJSONObject("execution");
        if (execution.isNull("started_at_ms")) {
            execution.put("started_at_ms", System.currentTimeMillis());
        }
        execution.put("finished_at_ms", JSONObject.NULL);
        execution.put("state", "running");
        execution.put("pause_requested", false);
    }

    static void markRunning(JSONObject campaign, String jobId) throws Exception {
        JSONObject state = requireState(campaign, jobId);
        if (!"pending".equals(state.optString("status"))) {
            throw new IllegalStateException("Job não está pendente: " + jobId);
        }
        state.put("status", "running");
        state.put("attempt_count", state.optInt("attempt_count", 0) + 1);
        state.put("started_at_ms", System.currentTimeMillis());
        state.put("finished_at_ms", JSONObject.NULL);
        state.put("failure", JSONObject.NULL);
    }

    static void markCompleted(File filesDir, JSONObject campaign, String jobId,
                              File suiteFile, JSONObject suite) throws Exception {
        JSONObject state = requireState(campaign, jobId);
        if (!"running".equals(state.optString("status"))) {
            throw new IllegalStateException("Job não está em execução: " + jobId);
        }
        SuiteRecord record = SuiteRecord.parse(suiteFile, suite);
        state.put("status", "completed");
        state.put("finished_at_ms", System.currentTimeMillis());
        state.put("suite_relative_path", relativePath(filesDir, suiteFile));
        state.put("suite_id", record.suiteId);
        state.put("verdict", record.verdict);
        state.put("blocking_validity", record.blockingValidity);
        state.put("failure", JSONObject.NULL);
    }

    static void markFailed(JSONObject campaign, String jobId, String message) throws Exception {
        JSONObject state = requireState(campaign, jobId);
        state.put("status", "failed");
        state.put("finished_at_ms", System.currentTimeMillis());
        state.put("failure", new JSONObject()
                .put("message", message == null ? "falha desconhecida" : message)
                .put("recorded_at_ms", System.currentTimeMillis()));
    }

    static void requestPause(JSONObject campaign) throws Exception {
        JSONObject execution = campaign.getJSONObject("execution");
        execution.put("pause_requested", true);
    }

    static void markPaused(JSONObject campaign) throws Exception {
        JSONObject execution = campaign.getJSONObject("execution");
        execution.put("state", "paused");
        execution.put("pause_requested", false);
    }

    static boolean pauseRequested(JSONObject campaign) {
        JSONObject execution = campaign.optJSONObject("execution");
        return execution != null && execution.optBoolean("pause_requested", false);
    }

    static void finish(JSONObject campaign, JSONObject summary) throws Exception {
        JSONObject execution = campaign.getJSONObject("execution");
        int failed = countStatus(campaign, "failed");
        int pending = countStatus(campaign, "pending") + countStatus(campaign, "running");
        if (pending > 0) throw new IllegalStateException("Ainda existem jobs pendentes");
        execution.put("state", failed > 0 ? "completed_with_failures" : "completed");
        execution.put("finished_at_ms", System.currentTimeMillis());
        execution.put("pause_requested", false);
        campaign.put("summary", summary);
    }

    static int countStatus(JSONObject campaign, String status) {
        JSONArray states = campaign.optJSONObject("execution") == null ? null
                : campaign.optJSONObject("execution").optJSONArray("jobs");
        if (states == null) return 0;
        int count = 0;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.optJSONObject(index);
            if (state != null && status.equals(state.optString("status"))) count++;
        }
        return count;
    }

    static int totalJobs(JSONObject campaign) {
        JSONArray jobs = campaign.optJSONObject("plan") == null ? null
                : campaign.optJSONObject("plan").optJSONArray("jobs");
        return jobs == null ? 0 : jobs.length();
    }

    static File suiteFile(File filesDir, JSONObject state) throws Exception {
        String relative = state.optString("suite_relative_path", "");
        if (relative.isEmpty()) return null;
        File file = new File(filesDir, relative);
        if (!ResultFiles.isInside(filesDir, file)) {
            throw new IllegalArgumentException("Caminho de suíte fora do armazenamento interno");
        }
        return file;
    }

    private static JSONObject requireState(JSONObject campaign, String jobId) {
        JSONObject state = stateFor(campaign, jobId);
        if (state == null) throw new IllegalArgumentException("Job ausente: " + jobId);
        return state;
    }

    private static String relativePath(File filesDir, File file) throws Exception {
        if (!ResultFiles.isInside(filesDir, file)) {
            throw new IllegalArgumentException("suite.json fora do armazenamento interno");
        }
        String root = filesDir.getCanonicalPath();
        String child = file.getCanonicalPath();
        return child.substring(root.length() + 1).replace(File.separatorChar, '/');
    }
}
