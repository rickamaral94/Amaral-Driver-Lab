package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CampaignSummaryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void summaryRanksOnlyInsideComparableWorkloadGroup() throws Exception {
        File filesDir = temporary.newFolder("files");
        String shaA = Phase4TestData.sha('a');
        String shaB = Phase4TestData.sha('b');
        JSONObject campaign = CampaignPlan.create("campaign-1700000000300",
                1700000000300L,
                new JSONArray()
                        .put(driver(shaA, "A"))
                        .put(driver(shaB, "B")),
                new JSONArray().put(new CampaignWorkload(
                        WorkloadContract.COMPUTE_ARITHMETIC_ID, "").toJson()),
                protocol());
        CampaignStore.markCampaignRunning(campaign);

        complete(filesDir, campaign, "job-001", shaA, "A", 2.0, "suite-a");
        complete(filesDir, campaign, "job-002", shaB, "B", 7.0, "suite-b");
        JSONObject summary = CampaignSummary.build(filesDir, campaign);

        assertEquals(2, summary.getInt("completed_jobs"));
        assertFalse(summary.getBoolean("cross_workload_score_available"));
        assertTrue(summary.isNull("cross_workload_winner"));
        JSONArray groups = summary.getJSONArray("groups");
        assertEquals(1, groups.length());
        JSONArray entries = groups.getJSONObject(0).getJSONObject("ranking")
                .getJSONArray("entries");
        assertEquals(2, entries.length());
        assertEquals(shaB, entries.getJSONObject(0).getString("candidate_sha256"));
    }

    private static void complete(File filesDir, JSONObject campaign, String jobId,
                                 String sha, String label, double score, String suiteId)
            throws Exception {
        CampaignStore.markRunning(campaign, jobId);
        File suiteFile = new File(filesDir, "runs/" + suiteId + "/suite.json");
        JSONObject report = Phase4TestData.report(suiteId, sha, label, score,
                "candidate_better", 1700000000400L + (long) score);
        ResultFiles.writeAtomic(suiteFile, report.toString(2));
        CampaignStore.markCompleted(filesDir, campaign, jobId, suiteFile, report);
    }

    private static JSONObject driver(String sha, String label) throws Exception {
        return new JSONObject()
                .put("candidate_sha256", sha)
                .put("candidate_label", label)
                .put("library_name", "libvulkan_freedreno.so");
    }

    private static JSONObject protocol() throws Exception {
        return new JSONObject()
                .put("mode", "ab_system_vs_candidate")
                .put("rounds", 5)
                .put("warmup_seconds", 3)
                .put("measure_seconds", 10)
                .put("cooldown_seconds", 0)
                .put("pixel_tolerance", 2)
                .put("maximum_divergent_blocks", 0);
    }
}
