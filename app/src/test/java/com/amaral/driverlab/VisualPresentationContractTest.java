package com.amaral.driverlab;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VisualPresentationContractTest {
    @Test
    public void visibleSceneWaitsForStableSurfaceDimensions() throws Exception {
        String source = read(sourceFile("main/java/com/amaral/driverlab/VisualRunnerActivity.java"));
        int created = source.indexOf("void surfaceCreated");
        int changed = source.indexOf("void surfaceChanged");
        int destroyed = source.indexOf("void surfaceDestroyed");
        String createdBody = source.substring(created, changed);
        String changedBody = source.substring(changed, destroyed);

        assertFalse(createdBody.contains("execute(surface)"));
        assertTrue(changedBody.contains("width <= 0 || height <= 0"));
        assertTrue(changedBody.contains("surface.isValid()"));
        assertTrue(changedBody.contains("execute(surface)"));
    }

    @Test
    public void visualRenderPassesPublishWritesBeforeSamplingAndTransfer() throws Exception {
        String source = read(sourceFile("main/cpp/visual_scenes.cpp"));

        assertTrue(source.contains("std::array<VkSubpassDependency, 2> sceneDependencies"));
        assertTrue(source.contains("sceneDependencies[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT"));
        assertTrue(source.contains("std::array<VkSubpassDependency, 2> finalDependencies"));
        assertTrue(source.contains("finalDependencies[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT"));
    }

    @Test
    public void presentationUsesRequiredAndroidFifoMode() throws Exception {
        String source = read(sourceFile("main/cpp/visual_scenes.cpp"));

        assertTrue(source.contains("presentMode = VK_PRESENT_MODE_FIFO_KHR;"));
        assertFalse(source.contains("presentMode = VK_PRESENT_MODE_MAILBOX_KHR;"));
        assertFalse(source.contains("presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR;"));
    }

    private static File sourceFile(String relative) {
        File direct = new File("src/" + relative);
        return direct.isFile() ? direct : new File("app/src/" + relative);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
