package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CampaignStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void interruptedRunningJobReturnsToPendingAndIsNeverApproved() throws Exception {
        File filesDir = temporary.newFolder("files");
        JSONObject campaign = campaign("campaign-1700000000100", Phase4TestData.sha('a'));
        File file = CampaignStore.create(filesDir, campaign);
        CampaignStore.markCampaignRunning(campaign);
        CampaignStore.markRunning(campaign, "job-001");
        CampaignStore.save(file, campaign);

        JSONObject reloaded = CampaignStore.load(file);
        assertEquals(1, CampaignStore.recoverInterrupted(reloaded));
        JSONObject state = CampaignStore.stateFor(reloaded, "job-001");
        assertEquals("pending", state.getString("status"));
        assertEquals(1, state.getInt("attempt_count"));
        assertTrue(state.isNull("verdict"));
        assertEquals(1, reloaded.getJSONObject("execution").getInt("recovery_count"));
    }

    @Test
    public void completedJobIsNotReopenedByRecovery() throws Exception {
        File filesDir = temporary.newFolder("files-complete");
        String sha = Phase4TestData.sha('a');
        JSONObject campaign = campaign("campaign-1700000000101", sha);
        File campaignFile = CampaignStore.create(filesDir, campaign);
        CampaignStore.markCampaignRunning(campaign);
        CampaignStore.markRunning(campaign, "job-001");

        File suiteFile = new File(filesDir, "runs/suite-a/suite.json");
        JSONObject report = Phase4TestData.report("suite-a", sha, "A", 4.0,
                "candidate_better", 1700000000200L);
        ResultFiles.writeAtomic(suiteFile, report.toString(2));
        CampaignStore.markCompleted(filesDir, campaign, "job-001", suiteFile, report);
        CampaignStore.save(campaignFile, campaign);

        assertEquals(0, CampaignStore.recoverInterrupted(campaign));
        assertEquals("completed", CampaignStore.stateFor(campaign, "job-001")
                .getString("status"));
    }

    private static JSONObject campaign(String id, String sha) throws Exception {
        JSONArray drivers = new JSONArray().put(new JSONObject()
                .put("candidate_sha256", sha)
                .put("candidate_label", "Driver A")
                .put("library_name", "libvulkan_freedreno.so"));
        JSONArray workloads = new JSONArray().put(new CampaignWorkload(
                WorkloadContract.COMPUTE_ARITHMETIC_ID, "").toJson());
        JSONObject protocol = new JSONObject()
                .put("mode", "ab_system_vs_candidate")
                .put("rounds", 5)
                .put("warmup_seconds", 3)
                .put("measure_seconds", 10)
                .put("cooldown_seconds", 0)
                .put("pixel_tolerance", 2)
                .put("maximum_divergent_blocks", 0);
        return CampaignPlan.create(id, 1700000000000L, drivers, workloads, protocol);
    }
}
