package com.amaral.driverlab;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public final class VisualNativeInitializationContractTest {
    @Test
    public void physicalDeviceAndSwapchainSupportAreResolvedBeforeAndroidSurface() throws Exception {
        File source = new File("src/main/cpp/visual_scenes.cpp");
        if (!source.isFile()) source = new File("app/src/main/cpp/visual_scenes.cpp");
        String cpp = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        int enumerateDevice = cpp.indexOf("setStage(\"enumerate_physical_devices_count\")");
        int checkSwapchain = cpp.indexOf(
                "hasExtension(deviceExtensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME)");
        int acquireWindow = cpp.indexOf("setStage(\"acquire_android_window\")");
        int createSurface = cpp.indexOf("setStage(\"create_android_surface_call\")");
        int surfaceReturned = cpp.indexOf("setStage(\"create_android_surface_returned\")");

        assertTrue(enumerateDevice >= 0);
        assertTrue(enumerateDevice < checkSwapchain);
        assertTrue(checkSwapchain < acquireWindow);
        assertTrue(acquireWindow < createSurface);
        assertTrue(createSurface < surfaceReturned);
        assertTrue(cpp.contains("persistNativeStage();"));
    }
}
