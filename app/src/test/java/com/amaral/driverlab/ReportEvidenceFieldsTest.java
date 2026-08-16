package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The evidence a driver evaluation needs and the report used to omit.
 *
 * <p>Each test here stands for a concrete cost already paid: two isolation builds
 * compiled because a failure had no stated reason, a 599 MB capture wasted because
 * two builds of the same Mesa commit were indistinguishable, and reference rounds
 * discarded because the candidate failed beside them.
 */
public final class ReportEvidenceFieldsTest {

    private static JSONObject phase(String role, int round, boolean success) throws Exception {
        JSONObject phase = new JSONObject()
                .put("round", round)
                .put("driver_role", role)
                .put("driver_mode", "custom")
                .put("driver_display_name", "Turnip " + role)
                .put("driver_sha256", role.equals("candidate") ? "aa11" : "bb22")
                .put("success", success);
        JSONObject nativeResult = new JSONObject()
                .put("success", success)
                .put("capabilities", new JSONObject()
                        .put("gpu_name", "Adreno (TM) 740")
                        .put("driver_id", VkResultNames.DRIVER_ID_MESA_TURNIP)
                        .put("driver_id_name", "VK_DRIVER_ID_MESA_TURNIP")
                        .put("driver_name", "turnip")
                        .put("driver_info", role.equals("candidate")
                                ? "Mesa 26.3.0-devel (git-724bffdeB8)"
                                : "Mesa 26.3.0-devel (git-724bffde43)")
                        .put("api_version", "1.4.358")
                        .put("vendor_id", 20803)
                        .put("device_id", 43050));
        if (!success) {
            nativeResult.put("failure_type", "visual_scene_failure")
                    .put("failure_stage", "create_swapchain")
                    .put("vk_result", -4)
                    .put("vulkan_operation", "vkQueuePresentKHR")
                    .put("device_lost", true)
                    .put("error", "vkQueuePresentKHR failed with VkResult=-4");
        }
        return phase.put("native", nativeResult);
    }

    private static JSONObject manifestWith(JSONArray phases) throws Exception {
        JSONObject suite = new JSONObject()
                .put("workload_id", "visual_scene_geometry")
                .put("verdict", "failed_visual_scene_execution")
                .put("phases", phases)
                .put("statistical_analysis", new JSONObject()
                        .put("primary_metric", "gpu_frame_time_ms")
                        .put("lower_is_better", true)
                        .put("system", new JSONObject().put("sample_count", 5)
                                .put("median", 10.0).put("mean", 10.2)
                                .put("p95", 11.0).put("p99", 12.0))
                        .put("candidate", new JSONObject().put("sample_count", 0)));
        JSONArray scored = new JSONArray().put(new JSONObject()
                .put("step_id", "visual_geometry")
                .put("status", "completed")
                .put("report", suite));
        JSONObject manifest = new JSONObject()
                .put("profile", new JSONObject().put("profile_version", 6))
                .put("preflight", new JSONObject().put("device", new JSONObject()
                        .put("battery_status", 2)
                        .put("battery_temperature_c", 30)
                        .put("battery_charge_counter_uah", 5769307L)));
        JSONObject optimization = QualificationOptimizationReport.build(
                manifest, scored, new JSONObject(), null);
        return manifest.put("report", new JSONObject().put("optimization_report", optimization));
    }

    @Test
    public void failedRoundCarriesStageResultAndFailingCall() throws Exception {
        JSONArray phases = new JSONArray()
                .put(phase("reference", 1, true))
                .put(phase("candidate", 1, false));
        JSONObject optimization = manifestWith(phases)
                .getJSONObject("report").getJSONObject("optimization_report");
        JSONArray audit = optimization.getJSONArray("loader_audit");

        JSONObject failedRow = null;
        for (int index = 0; index < audit.length(); index++) {
            if (!audit.getJSONObject(index).optBoolean("success", true)) {
                failedRow = audit.getJSONObject(index);
            }
        }
        assertTrue("a failing round must appear in the audit", failedRow != null);
        JSONObject failure = failedRow.getJSONObject("failure");
        assertEquals("create_swapchain", failure.getString("stage"));
        assertEquals("VK_ERROR_DEVICE_LOST", failure.getString("vk_result_name"));
        assertEquals("vkQueuePresentKHR", failure.getString("api_call"));
        assertTrue(failure.getBoolean("device_lost"));
    }

    @Test
    public void runtimeIdentityDistinguishesTwoBuildsOfTheSameCommit() throws Exception {
        JSONArray phases = new JSONArray()
                .put(phase("reference", 1, true))
                .put(phase("candidate", 1, true));
        JSONObject manifest = manifestWith(phases);
        String markdown = QualificationOptimizationReport.runtimeIdentityMarkdown(manifest);

        assertTrue(markdown.contains("git-724bffdeB8"));
        assertTrue(markdown.contains("git-724bffde43"));
        assertTrue(markdown.contains("VK_DRIVER_ID_MESA_TURNIP"));
    }

    @Test
    public void identicalDriverInfoBetweenArmsIsFlaggedAsInvalidating() throws Exception {
        JSONObject reference = phase("reference", 1, true);
        reference.getJSONObject("native").getJSONObject("capabilities")
                .put("driver_info", "Mesa 26.3.0-devel (git-724bffdeB8)");
        JSONArray phases = new JSONArray().put(reference).put(phase("candidate", 1, true));
        JSONObject optimization = manifestWith(phases)
                .getJSONObject("report").getJSONObject("optimization_report");

        JSONArray caveats = optimization.getJSONObject("runtime_driver_identity")
                .getJSONArray("caveats");
        boolean flagged = false;
        for (int index = 0; index < caveats.length(); index++) {
            if (caveats.getString(index).startsWith("identical_runtime_driver_info_between_arms")) {
                flagged = true;
            }
        }
        assertTrue("two arms resolving to the same driver must invalidate the run", flagged);
    }

    @Test
    public void candidateFailureKeepsTheReferenceStatistics() throws Exception {
        JSONArray phases = new JSONArray()
                .put(phase("reference", 1, true))
                .put(phase("candidate", 1, false));
        JSONObject optimization = manifestWith(phases)
                .getJSONObject("report").getJSONObject("optimization_report");
        JSONObject metric = optimization.getJSONArray("metrics").getJSONObject(0);

        assertEquals("candidate_failed", metric.getString("classification"));
        assertEquals("reference", metric.getString("surviving_arm"));
        assertEquals(5, metric.getJSONObject("reference").getInt("sample_count"));
        assertEquals(1, metric.getJSONObject("failure_breakdown").getInt("failed_round_count"));
        assertEquals(1, metric.getJSONObject("failure_breakdown")
                .getJSONObject("by_stage").getInt("create_swapchain"));
    }

    @Test
    public void consumptionIsDeclaredUnreadableWhileCharging() throws Exception {
        JSONObject manifest = new JSONObject()
                .put("preflight", new JSONObject().put("device", new JSONObject()
                        .put("battery_status", 2)))
                .put("execution", new JSONObject().put("steps", new JSONArray()
                        .put(new JSONObject()
                                .put("step_id", "stable_scene")
                                .put("environment_start", new JSONObject()
                                        .put("device", new JSONObject()
                                                .put("battery_temperature_c", 30)
                                                .put("thermal_status", 0)
                                                .put("battery_charge_counter_uah", 5769307L)))
                                .put("environment_end", new JSONObject()
                                        .put("device", new JSONObject()
                                                .put("battery_temperature_c", 34)
                                                .put("thermal_status", 2)
                                                .put("battery_charge_counter_uah", 6649001L))))));

        String markdown = QualificationOptimizationReport.thermalMarkdown(manifest);
        assertTrue(markdown.contains("n/d (carregando)"));
        assertTrue("the temperature rise must still be reported", markdown.contains("+4.0"));
        assertFalse("a charge delta must never be printed as if valid",
                markdown.contains("-879694"));
    }

    @Test
    public void vkResultNamesCoverTheErrorsThatEndARound() {
        assertEquals("VK_ERROR_DEVICE_LOST", VkResultNames.of(-4));
        assertEquals("VK_ERROR_OUT_OF_DATE_KHR", VkResultNames.of(-1000001004));
        assertEquals("VK_ERROR_SURFACE_LOST_KHR", VkResultNames.of(-1000000000));
        assertEquals("VK_ERROR_INITIALIZATION_FAILED", VkResultNames.of(-3));
        assertEquals("VK_DRIVER_ID_MESA_TURNIP", VkResultNames.driverId(18));
        assertEquals("VK_DRIVER_ID_QUALCOMM_PROPRIETARY", VkResultNames.driverId(8));
    }
}
