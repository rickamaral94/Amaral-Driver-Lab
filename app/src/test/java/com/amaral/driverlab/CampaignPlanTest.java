package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CampaignPlanTest {
    @Test
    public void rotatingScheduleBalancesThermalPositionAcrossOneLatinCycle() throws Exception {
        JSONArray drivers = new JSONArray()
                .put(driver('a', "A"))
                .put(driver('b', "B"))
                .put(driver('c', "C"));
        JSONArray workloads = new JSONArray()
                .put(new CampaignWorkload(WorkloadContract.COMPUTE_ARITHMETIC_ID, "").toJson())
                .put(new CampaignWorkload(WorkloadContract.STABLE_SCENE_ID, "").toJson())
                .put(new CampaignWorkload(WorkloadContract.TRACE_REPLAY_ID,
                        TraceReplayContract.MIXED_TRACE_ID).toJson());
        JSONObject campaign = CampaignPlan.create("campaign-1700000000000", 1700000000000L,
                drivers, workloads, protocol());

        assertTrue(CampaignPlan.verify(campaign));
        JSONArray jobs = campaign.getJSONObject("plan").getJSONArray("jobs");
        assertEquals(9, jobs.length());
        Map<String, int[]> positions = new HashMap<>();
        for (int index = 0; index < jobs.length(); ++index) {
            JSONObject job = jobs.getJSONObject(index);
            int[] counts = positions.computeIfAbsent(job.getString("candidate_sha256"),
                    ignored -> new int[3]);
            counts[job.getInt("thermal_position") - 1]++;
        }
        for (int[] counts : positions.values()) {
            assertEquals(1, counts[0]);
            assertEquals(1, counts[1]);
            assertEquals(1, counts[2]);
        }
    }

    @Test
    public void changingImmutablePlanInvalidatesDigest() throws Exception {
        JSONObject campaign = CampaignPlan.create("campaign-1700000000001", 1700000000001L,
                new JSONArray().put(driver('a', "A")),
                new JSONArray().put(new CampaignWorkload(
                        WorkloadContract.COMPUTE_ARITHMETIC_ID, "").toJson()), protocol());
        assertTrue(CampaignPlan.verify(campaign));
        campaign.getJSONObject("plan").getJSONObject("protocol").put("rounds", 9);
        assertFalse(CampaignPlan.verify(campaign));
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateWorkloadSpecsAreRejected() throws Exception {
        JSONObject workload = new CampaignWorkload(
                WorkloadContract.COMPUTE_ARITHMETIC_ID, "").toJson();
        CampaignPlan.create("campaign-1700000000002", 1700000000002L,
                new JSONArray().put(driver('a', "A")),
                new JSONArray().put(workload).put(new JSONObject(workload.toString())), protocol());
    }

    private static JSONObject driver(char sha, String label) throws Exception {
        return new JSONObject()
                .put("candidate_sha256", Phase4TestData.sha(sha))
                .put("candidate_label", label)
                .put("library_name", "libvulkan_freedreno.so");
    }

    private static JSONObject protocol() throws Exception {
        return new JSONObject()
                .put("mode", "ab_system_vs_candidate")
                .put("rounds", 5)
                .put("warmup_seconds", 3)
                .put("measure_seconds", 10)
                .put("cooldown_seconds", 15)
                .put("pixel_tolerance", 2)
                .put("maximum_divergent_blocks", 0);
    }
}
