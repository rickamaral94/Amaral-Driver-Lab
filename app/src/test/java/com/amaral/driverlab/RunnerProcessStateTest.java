package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class RunnerProcessStateTest {
    @Test
    public void crashEvidencePreservesLastNativeBreadcrumbAndDriverContext() throws Exception {
        File directory = Files.createTempDirectory("runner-state").toFile();
        File result = new File(directory, "calibration.json");
        RunnerProcessState.start(result, 321, 1000L);
        RunnerProcessState.checkpoint(result, "runner_inputs_validated",
                new JSONObject()
                        .put("workload_id", "visual_scene_geometry")
                        .put("driver_sha256", "abc123"));
        RunnerProcessState.checkpoint(result, "native_vulkan_call", null);

        JSONObject failure = new JSONObject()
                .put("success", false)
                .put("failure_type", "crash")
                .put("error", "runner_crash");
        RunnerProcessState.attachToSyntheticFailure(failure, result, "runner_process");

        assertEquals("native_vulkan_call", failure.getString("failure_stage"));
        assertEquals("native_vulkan_call", failure.getString("runner_last_stage"));
        JSONObject state = failure.getJSONObject("runner_state");
        assertEquals(321, state.getInt("pid"));
        assertEquals("abc123", state.getJSONObject("context").getString("driver_sha256"));
        assertTrue(state.getJSONArray("breadcrumbs").length() >= 3);
        JSONObject process = failure.getJSONObject("process_exit_observation");
        assertTrue(process.isNull("native_signal"));
        assertTrue(process.getString("native_signal_limitation").contains("tombstone"));
    }

    @Test
    public void nativeCrashPromotesLastDurableNativeStage() throws Exception {
        File directory = Files.createTempDirectory("runner-native-stage").toFile();
        File result = new File(directory, "visual.json");
        RunnerProcessState.start(result, 777, 3000L);
        RunnerProcessState.checkpoint(result, "native_vulkan_call", null);
        Files.write(RunnerProcessState.nativeStageFileFor(result).toPath(),
                "create_android_surface_call\n".getBytes(StandardCharsets.UTF_8));

        JSONObject failure = new JSONObject()
                .put("success", false)
                .put("failure_type", "crash");
        RunnerProcessState.attachToSyntheticFailure(failure, result, "runner_process");

        assertEquals("create_android_surface_call", failure.getString("failure_stage"));
        assertEquals("native_vulkan_call", failure.getString("runner_last_stage"));
        assertEquals("create_android_surface_call",
                failure.getString("runner_last_native_stage"));
    }

    @Test
    public void normalCompletionKeepsBreadcrumbHistory() throws Exception {
        File directory = Files.createTempDirectory("runner-complete").toFile();
        File result = new File(directory, "phase.json");
        RunnerProcessState.start(result, 654, 2000L);
        RunnerProcessState.checkpoint(result, "native_vulkan_returned", null);
        RunnerProcessState.complete(result, 654, true);

        JSONObject state = RunnerProcessState.read(result);
        assertNotNull(state);
        assertEquals("completed", state.getString("state"));
        assertEquals("runner_completed", state.getString("last_stage"));
        assertTrue(state.getBoolean("success"));
        assertTrue(state.getJSONArray("breadcrumbs").length() > 0);
    }
}
