package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * A calibration probe that dies must say why.
 *
 * <p>Written from a real session: driver v8b failed every visible scene at
 * vkAcquireNextImageKHR, each attempt lasting about a second, and the only
 * feedback was the runner activity flashing. The operator reasonably concluded
 * the app was broken. Offscreen correctness passed in the same run, because it
 * never touches a swapchain.
 */
public final class CalibrationFailureMessageTest {

    private static JSONObject probeFailure(String stage, String call, int vkResult)
            throws Exception {
        return new JSONObject().put("success", false).put("native", new JSONObject()
                .put("success", false)
                .put("failure_type", "visual_scene_failure")
                .put("failure_stage", stage)
                .put("vulkan_operation", call)
                .put("vk_result", vkResult)
                .put("error", call + " failed with VkResult=" + vkResult));
    }

    @Test
    public void presentationFailureIsNamedAsSuch() throws Exception {
        String message = RunCoordinator.describeProbeFailure(
                probeFailure("render_visible_frame", "vkAcquireNextImageKHR", -1000072003));

        assertTrue(message, message.contains("não conseguiu apresentar"));
        assertTrue(message, message.contains("vkAcquireNextImageKHR"));
        assertTrue(message, message.contains("VK_ERROR_INVALID_EXTERNAL_HANDLE"));
        assertTrue(message, message.contains("render_visible_frame"));
    }

    @Test
    public void nonPresentationFailureKeepsTheGenericLead() throws Exception {
        String message = RunCoordinator.describeProbeFailure(
                probeFailure("create_device", "vkCreateDevice", -3));

        assertTrue(message, message.contains("falhou na calibração"));
        assertTrue(message, message.contains("VK_ERROR_INITIALIZATION_FAILED"));
    }

    @Test
    public void aProbeWithNoDiagnosisFallsBackInsteadOfInventingOne() throws Exception {
        JSONObject bare = new JSONObject().put("success", false);
        assertEquals("Probe de calibração sem mediana válida",
                RunCoordinator.describeProbeFailure(bare));
    }
}
