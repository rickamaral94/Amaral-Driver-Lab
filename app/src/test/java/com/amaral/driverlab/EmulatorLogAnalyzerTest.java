package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
        assertEquals(2, report.getInt("schema_version"));
        assertEquals("turnip", report.getString("driver"));
        assertEquals("runtime_confirmed", report.getString("driver_identity_confidence"));
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

    @Test
    public void issue43SanitizedRuntimeResolvesQualcommBlobAcrossInitializationBlocks()
            throws Exception {
        String log = fixture("issue-43-sanitized.log");
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden_log.txt", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("qualcomm_blob", report.getString("driver"));
        assertEquals("runtime_confirmed", report.getString("driver_identity_confidence"));
        assertTrue(report.getString("driver_display_name").contains("Qualcomm"));
        assertFalse(report.getString("issue_title").toLowerCase().contains("turnip"));
        assertTrue(report.getJSONArray("driver_identity_blocks").length() >= 4);
        assertTrue(report.getString("issue_body").contains("Driver identity evidence"));
    }

    @Test
    public void issue39SanitizedRuntimeConfirmsTurnip() throws Exception {
        String log = fixture("issue-39-sanitized.log");
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden_gpu.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("turnip", report.getString("driver"));
        assertEquals("runtime_confirmed", report.getString("driver_identity_confidence"));
        assertTrue(report.getString("issue_title").toLowerCase().contains("turnip"));
    }

    @Test
    public void emptyDriverFieldAndBlobStyleVersionDoNotInventQualcommIdentity()
            throws Exception {
        for (String version : new String[] {"512.615.0", "512.744.7"}) {
            String log = "Eden 0.2.1\nVulkan Driver: - " + version + "\n";
            JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                    false, device(), "0.13.0-alpha12");

            assertEquals("unknown", report.getString("driver"));
            assertEquals("disputed", report.getString("driver_identity_confidence"));
            assertTrue(report.getString("issue_title").contains("[driver-unconfirmed]"));
        }
    }

    @Test
    public void noDriverInformationIsUnknownInferredAndNeverAggregated() throws Exception {
        String log = "Eden version 0.2.1\nGPU: Adreno 740\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("unknown", report.getString("driver"));
        assertEquals("inferred", report.getString("driver_identity_confidence"));
        assertTrue(report.getString("issue_title").contains("[driver-unconfirmed]"));
        assertFalse(EmulatorLogAnalyzer.isEligibleForIdentityAggregation(report));
    }

    @Test
    public void configurationOnlyIsInferredAndHiddenFromIssueTitle() throws Exception {
        String log = "Eden version 0.2.1\nSelected GPU driver: Turnip Mesa 26.3.0\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("turnip", report.getString("driver"));
        assertEquals("inferred", report.getString("driver_identity_confidence"));
        assertTrue(report.getString("issue_title").contains("[driver-unconfirmed]"));
    }

    @Test
    public void explicitQualcommNameInConfigurationRemainsInferred() throws Exception {
        String log = "Selected GPU driver: Qualcomm Technologies Inc. Adreno Vulkan Driver\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("qualcomm_blob", report.getString("driver"));
        assertEquals("inferred", report.getString("driver_identity_confidence"));
        assertTrue(report.getString("issue_title").contains("[driver-unconfirmed]"));
    }

    @Test
    public void mesaNamedDriverIsPositiveTurnipRuntimeEvidence() throws Exception {
        String log = "Driver Name: Mesa driver\nDriver Info: Mesa 26.3.0-devel\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("turnip", report.getString("driver"));
        assertEquals("runtime_confirmed", report.getString("driver_identity_confidence"));
    }

    @Test
    public void blobStyleVersionDisputesButDoesNotOverrideNamedTurnip() throws Exception {
        String log = "Driver Name: turnip Mesa driver\nDriver Version: 512.615.0\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("turnip", report.getString("driver"));
        assertEquals("disputed", report.getString("driver_identity_confidence"));
        assertTrue(report.getString("issue_title").contains("[driver-unconfirmed]"));
    }

    @Test
    public void explicitQualcommVendorVetoPreservesConflictEvidence() throws Exception {
        String log = "Selected GPU driver: Turnip Mesa 26.3.0\n"
                + "EmuWindow_Android: initializing\n"
                + "[GPU Logging] Initialized with level: Standard, driver: Qualcomm Technologies\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("qualcomm_blob", report.getString("driver"));
        assertEquals("disputed", report.getString("driver_identity_confidence"));
        assertTrue(report.getString("issue_title").contains("[driver-unconfirmed]"));
        assertTrue(report.getString("issue_body").contains("Driver identity is disputed"));
        JSONArray evidence = report.getJSONArray("driver_identity_evidence");
        assertTrue(evidence.length() >= 2);
        boolean sawTurnip = false;
        boolean sawQualcomm = false;
        for (int index = 0; index < evidence.length(); index++) {
            JSONObject item = evidence.optJSONObject(index);
            if (item == null) continue;
            assertTrue(item.optInt("line") > 0);
            assertFalse(item.optString("content").isEmpty());
            sawTurnip |= "turnip".equals(item.optString("identity"));
            sawQualcomm |= "qualcomm_blob".equals(item.optString("identity"));
        }
        assertTrue(sawTurnip);
        assertTrue(sawQualcomm);
    }

    @Test
    public void disagreementBetweenInitializationBlocksIsDisputed() throws Exception {
        String log = "EmuWindow_Android: initializing\n"
                + "Driver Name: turnip Mesa driver\n"
                + "EmuWindow_Android: initializing\n"
                + "[GPU Logging] Initialized with level: Standard, driver: Qualcomm Proprietary\n";
        JSONObject report = EmulatorLogAnalyzer.analyze(log, "eden.log", log.length(),
                false, device(), "0.13.0-alpha12");

        assertEquals("qualcomm_blob", report.getString("driver"));
        assertEquals("disputed", report.getString("driver_identity_confidence"));
    }

    @Test
    public void legacyV1ReportsReadAsUnaudited() throws Exception {
        JSONObject legacy = new JSONObject()
                .put("schema_version", 1)
                .put("driver", "Turnip Mesa 26.2.99");

        JSONObject normalized = EmulatorLogAnalyzer.normalizeForRead(legacy);

        assertEquals("unaudited", normalized.getString("driver_identity_confidence"));
        assertFalse(EmulatorLogAnalyzer.isEligibleForIdentityAggregation(normalized));
    }

    @Test
    public void onlyRuntimeConfirmedKnownIdentityCanBeAggregated() throws Exception {
        JSONObject confirmed = new JSONObject()
                .put("schema_version", 2)
                .put("driver", "turnip")
                .put("driver_identity_confidence", "runtime_confirmed");
        JSONObject disputed = new JSONObject(confirmed.toString())
                .put("driver_identity_confidence", "disputed");

        assertTrue(EmulatorLogAnalyzer.isEligibleForIdentityAggregation(confirmed));
        assertFalse(EmulatorLogAnalyzer.isEligibleForIdentityAggregation(disputed));
    }

    private static String fixture(String name) throws Exception {
        String path = "/emulator-logs/" + name;
        try (InputStream input = EmulatorLogAnalyzerTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Fixture não encontrada: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject device() throws Exception {
        return new JSONObject()
                .put("manufacturer", "AYN")
                .put("model", "Odin 2 Portal")
                .put("android_release", "13")
                .put("android_sdk", 33);
    }
}
