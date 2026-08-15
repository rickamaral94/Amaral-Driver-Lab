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
        int properties2Call = cpp.indexOf("setStage(\"query_driver_properties2_call\")");
        int properties2Returned = cpp.indexOf(
                "setStage(\"query_driver_properties2_returned\")");
        int acquireWindow = cpp.indexOf("setStage(\"acquire_android_window\")");
        int createSurface = cpp.indexOf("setStage(\"create_android_surface_call\")");
        int surfaceReturned = cpp.indexOf("setStage(\"create_android_surface_returned\")");

        assertTrue(enumerateDevice >= 0);
        assertTrue(enumerateDevice < checkSwapchain);
        assertTrue(checkSwapchain < properties2Call);
        assertTrue(properties2Call < properties2Returned);
        assertTrue(properties2Returned < acquireWindow);
        assertTrue(acquireWindow < createSurface);
        assertTrue(createSurface < surfaceReturned);
        assertTrue(cpp.contains("persistNativeStage();"));
    }

    @Test
    public void properties2IsNegotiatedBeforeItCanBeCalled() throws Exception {
        File source = new File("src/main/cpp/visual_scenes.cpp");
        if (!source.isFile()) source = new File("app/src/main/cpp/visual_scenes.cpp");
        String cpp = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertTrue(cpp.contains("vkEnumerateInstanceVersion"));
        assertTrue(cpp.contains("VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME"));
        assertTrue(cpp.contains("properties2Core = instanceApiVersion >= VK_API_VERSION_1_1"));
        assertTrue(cpp.contains("if (properties2Core)"));
        assertTrue(cpp.contains("else if (properties2Enabled)"));
        assertTrue(cpp.contains("\"vkGetPhysicalDeviceProperties2KHR\""));
        assertTrue(cpp.contains("if (properties2Enabled"));
        assertTrue(cpp.indexOf("appInfo.apiVersion = instanceApiVersion")
                < cpp.indexOf("vkGetPhysicalDeviceProperties2(physicalDevice, &properties2)"));
    }
}
