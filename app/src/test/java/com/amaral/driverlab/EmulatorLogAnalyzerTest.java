package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EmulatorLogAnalyzerTest {
    @Test
    public void detectsEmulatorDriverGpuAndFatalFailure() throws Exception {
        String log = "Eden version 0.2.1\n"
                + "Game: The Legend of Zelda: Breath of the Wild\n"
                + "Title ID: 01007EF00011E000\n"
                + "GPU: Adreno 740\n"
                + "Vulkan API version 1.3.280\n"
                + "Driver: Mesa Turnip 25.3.0 R11\n"
                + "FATAL: VK_ERROR_DEVICE_LOST while submitting queue\n";

        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha8");

        assertEquals("Eden", report.getString("emulator"));
        assertEquals("Adreno 740", report.getString("gpu"));
        assertTrue(report.getString("driver").contains("Turnip"));
        assertEquals("fatal", report.getString("severity"));
        assertEquals(1, report.getInt("fatal_count"));
        assertTrue(report.getString("issue_title").contains("[Emulator Log]"));
        assertTrue(report.getString("issue_body").contains("VK_ERROR_DEVICE_LOST"));
        assertTrue(report.getString("issue_body").contains("01007EF00011E000"));
    }

    @Test
    public void redactsPathsEmailSecretsAndIpAddresses() throws Exception {
        String log = "Yuzu 278\n"
                + "email=user@example.com\n"
                + "rom=/storage/emulated/0/Games/Zelda/game.nsp\n"
                + "token: abcdef123456\n"
                + "server=192.168.0.12\n"
                + "ERROR: failed to open /data/user/0/org.yuzu/files/cache.bin\n";

        JSONObject report = EmulatorLogAnalyzer.analyze(log, "private.log", log.length(),
                false, device(), "0.13.0-alpha8");
        String body = report.getString("issue_body");

        assertTrue(report.getInt("privacy_redactions") >= 5);
        assertFalse(body.contains("user@example.com"));
        assertFalse(body.contains("/storage/emulated/0"));
        assertFalse(body.contains("abcdef123456"));
        assertFalse(body.contains("192.168.0.12"));
        assertFalse(body.contains("/data/user/0"));
        assertTrue(body.contains("<redacted"));
    }

    @Test
    public void readEnforcesMaximumBytesAndMarksTruncation() throws Exception {
        byte[] content = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        EmulatorLogAnalyzer.ReadResult result = EmulatorLogAnalyzer.read(
                new ByteArrayInputStream(content), 8);

        assertEquals("01234567", result.text);
        assertEquals(8, result.bytesRead);
        assertTrue(result.truncated);
    }

    @Test
    public void reportStaysUsefulWithoutExplicitErrors() throws Exception {
        String log = "Cemu 2.6\nGPU: Adreno 740\nDriver: Turnip Mesa 26.0.0\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "cemu.txt", log.length(),
                false, device(), "0.13.0-alpha8");

        assertEquals("informational", report.getString("severity"));
        assertTrue(report.getJSONArray("key_findings").length() >= 1);
        assertTrue(report.getString("issue_body").contains("User context"));
    }

    private static JSONObject device() throws Exception {
        return new JSONObject()
                .put("manufacturer", "AYN")
                .put("model", "Odin 2 Portal")
                .put("android_release", "13")
                .put("android_sdk", 33);
    }
}
