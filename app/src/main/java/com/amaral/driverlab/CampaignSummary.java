package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CampaignSummary {
    private CampaignSummary() {}

    static JSONObject build(File filesDir, JSONObject campaign) throws Exception {
        if (!CampaignPlan.verify(campaign)) {
            throw new IllegalArgumentException("Campanha inválida");
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        JSONArray states = campaign.getJSONObject("execution").getJSONArray("jobs");
        int completed = 0;
        int blocked = 0;
        for (int index = 0; index < states.length(); ++index) {
            JSONObject state = states.getJSONObject(index);
            if (!"completed".equals(state.optString("status"))) continue;
            File suiteFile = CampaignStore.suiteFile(filesDir, state);
            if (suiteFile == null || !suiteFile.isFile()) {
                throw new IllegalStateException("Job concluído sem suite.json: "
                        + state.optString("job_id"));
            }
            SuiteRecord record = SuiteRecord.parse(suiteFile,
                    new JSONObject(ResultFiles.readUtf8(suiteFile)));
            JSONObject immutable = CampaignPlan.immutableJob(campaign,
                    state.getString("job_id"));
            if (immutable == null) {
                throw new IllegalStateException("Job concluído ausente do plano: "
                        + state.optString("job_id"));
            }
            String traceId = immutable.isNull("trace_id") ? ""
                    : immutable.optString("trace_id", "");
            String groupKey = immutable.optString("workload_key") + "|"
                    + record.comparisonKey();
            Group group = groups.computeIfAbsent(groupKey, ignored -> new Group(
                    immutable.optString("workload_id"),
                    immutable.optInt("workload_version", 1), traceId,
                    record.comparisonKey()));
            group.records.add(record);
            completed++;
            if (record.blockingValidity) blocked++;
        }

        JSONArray encodedGroups = new JSONArray();
        for (Group group : groups.values()) encodedGroups.put(group.toJson());
        int failed = CampaignStore.countStatus(campaign, "failed");
        int pending = CampaignStore.countStatus(campaign, "pending")
                + CampaignStore.countStatus(campaign, "running");
        return new JSONObject()
                .put("campaign_summary_version", Phase6Contract.SUMMARY_VERSION)
                .put("campaign_id", campaign.optString("campaign_id"))
                .put("plan_sha256", campaign.optString("plan_sha256"))
                .put("total_jobs", CampaignStore.totalJobs(campaign))
                .put("completed_jobs", completed)
                .put("failed_jobs", failed)
                .put("pending_jobs", pending)
                .put("blocked_suite_count", blocked)
                .put("has_blocking_results", blocked > 0 || failed > 0)
                .put("cross_workload_score_available", false)
                .put("cross_workload_winner", JSONObject.NULL)
                .put("groups", encodedGroups)
                .put("limitations", Phase6Contract.LIMITATION);
    }

    private static final class Group {
        final String workloadId;
        final int workloadVersion;
        final String traceId;
        final String comparisonKey;
        final List<SuiteRecord> records = new ArrayList<>();

        Group(String workloadId, int workloadVersion, String traceId, String comparisonKey) {
            this.workloadId = workloadId;
            this.workloadVersion = workloadVersion;
            this.traceId = traceId;
            this.comparisonKey = comparisonKey;
        }

        JSONObject toJson() throws Exception {
            int blocked = 0;
            JSONArray suites = new JSONArray();
            for (SuiteRecord record : records) {
                if (record.blockingValidity) blocked++;
                suites.put(record.compactJson());
            }
            JSONObject ranking = records.isEmpty()
                    ? new JSONObject().put("available", false)
                    : SuiteHistory.ranking(records, records.get(0));
            JSONObject output = new JSONObject()
                    .put("workload_id", workloadId)
                    .put("workload_version", workloadVersion)
                    .put("trace_id", traceId.isEmpty() ? JSONObject.NULL : traceId)
                    .put("comparison_key", comparisonKey)
                    .put("suite_count", records.size())
                    .put("blocked_suite_count", blocked)
                    .put("group_status", blocked > 0 ? "has_blocking_results" : "complete")
                    .put("ranking", ranking)
                    .put("suites", suites);
            if (!traceId.isEmpty()) {
                output.put("trace_label", TraceReplayContract.labelFor(traceId));
            }
            return output;
        }
    }
}
