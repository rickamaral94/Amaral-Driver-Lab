package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeepDiagnosticsComparisonTest {
    @Test
    public void formatRegressionBlocksCandidate() throws Exception {
        JSONObject system = phase("system", fullNative(true, 100, 50, 128, 2.0, 5.0));
        JSONObject candidate = phase("custom", fullNative(false, 80, 40, 128, 1.5, 4.0));
        JSONObject result = DeepDiagnosticsComparison.compare(system, candidate);
        assertFalse(result.getBoolean("comparable"));
        assertEquals("candidate_blocked", result.getString("verdict"));
        assertTrue(result.getJSONObject("format_matrix").getInt("regression_count") > 0);
    }

    @Test
    public void broadDescriptiveImprovementsAreReportedWithoutStatisticalClaim() throws Exception {
        JSONObject system = phase("system", fullNative(true, 100, 50, 96, 3.0, 8.0));
        JSONObject candidate = phase("custom", fullNative(true, 75, 35, 128, 2.0, 5.0));
        JSONObject result = DeepDiagnosticsComparison.compare(system, candidate);
        assertTrue(result.getBoolean("comparable"));
        assertEquals("candidate_improved_descriptive", result.getString("verdict"));
        assertFalse(result.getBoolean("statistical_significance_claimed"));
        assertFalse(result.getBoolean("eligible_for_full_qualification_score"));
    }

    @Test
    public void candidateSoakFailureHasPrecedence() throws Exception {
        JSONObject systemNative = new JSONObject().put("success", true).put("mode", "soak")
                .put("soak", soak(true, 10, 4.0));
        JSONObject candidateNative = new JSONObject().put("success", true).put("mode", "soak")
                .put("soak", soak(false, 6, 3.0));
        JSONObject result = DeepDiagnosticsComparison.compare(
                phase("system", systemNative), phase("custom", candidateNative));
        assertFalse(result.getBoolean("comparable"));
        assertEquals("candidate_blocked", result.getString("verdict"));
    }

    private static JSONObject phase(String mode, JSONObject nativeResult) throws Exception {
        return new JSONObject().put("driver_mode", mode).put("native", nativeResult)
                .put("success", true);
    }

    private static JSONObject fullNative(boolean formatSupported, double cold, double warm,
                                         double memoryPeakMiB, double fenceP99,
                                         double reliabilityP99) throws Exception {
        JSONObject format = new JSONObject()
                .put("format", "R8G8B8A8_UNORM")
                .put("optimal_sampled", formatSupported)
                .put("optimal_color_attachment", formatSupported)
                .put("optimal_depth_stencil_attachment", false)
                .put("optimal_storage_image", formatSupported)
                .put("sampled_image_supported", formatSupported)
                .put("attachment_image_supported", formatSupported)
                .put("storage_image_supported", formatSupported);
        return new JSONObject()
                .put("success", true)
                .put("mode", "full")
                .put("format_matrix", new JSONObject().put("formats",
                        new JSONArray().put(format)))
                .put("shader_pipeline_corpus", new JSONObject()
                        .put("successful_cases", 6)
                        .put("cold_pipeline_total_ms", cold)
                        .put("warm_pipeline_total_ms", warm)
                        .put("pipeline_cache_serialized_bytes", 4096))
                .put("memory_pressure", new JSONObject()
                        .put("peak_allocated_bytes", memoryPeakMiB * 1024.0 * 1024.0)
                        .put("duration_ms", cold)
                        .put("completed_safe_target", true))
                .put("synchronization", new JSONObject()
                        .put("passed", true)
                        .put("fence_submit_wait_p99_ms", fenceP99)
                        .put("timeline_semaphore_executed", false))
                .put("reliability_probe", soak(true, 5, reliabilityP99));
    }

    private static JSONObject soak(boolean passed, int cycles, double p99) throws Exception {
        return new JSONObject()
                .put("passed", passed)
                .put("completed_cycles", cycles)
                .put("cycle_p99_ms", p99);
    }
}
