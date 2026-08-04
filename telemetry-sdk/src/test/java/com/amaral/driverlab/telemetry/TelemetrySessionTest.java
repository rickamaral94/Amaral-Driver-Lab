package com.amaral.driverlab.telemetry;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TelemetrySessionTest {
    private static final String HASH = "a".repeat(64);

    @Test
    public void builderProducesValidVersionedBundle() {
        TelemetrySession session = TelemetrySession.builder(
                        "Eden adapter", "1.0", "Eden", "org.eden.emulator", "0.3", HASH, "switch")
                .sessionId("123e4567-e89b-12d3-a456-426614174000")
                .startedAtUnixMs(1_000L)
                .customDriver("b".repeat(64), "Turnip", "26.0")
                .build();
        JSONObject bundle = session
                .recordState(0L, "started")
                .recordFrame(10_000_000L, 16_666_667L)
                .recordMarker(20_000_000L, "scene", "gameplay", "field")
                .finish("complete", 2_000L);

        TelemetryContract.validateBundle(bundle);
        assertEquals(3, bundle.getJSONObject("session").getInt("event_count"));
        assertEquals("custom", bundle.getJSONObject("session")
                .getJSONObject("driver_binding").getString("mode"));
    }

    @Test
    public void contentHashIsSaltedAndDeterministic() {
        String first = TelemetryContract.hashContentId(
                "0123456789abcdef", "switch-title-id", "01007EF00011E000");
        String same = TelemetryContract.hashContentId(
                "0123456789abcdef", "switch-title-id", "01007EF00011E000");
        String other = TelemetryContract.hashContentId(
                "fedcba9876543210", "switch-title-id", "01007EF00011E000");
        assertEquals(first, same);
        assertNotEquals(first, other);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    public void rejectsSensitivePathsInsideExtensions() throws Exception {
        JSONObject bundle = validBundle();
        bundle.getJSONObject("session").put("extensions",
                new JSONObject().put("rom_path", "/storage/emulated/0/game.nsp"));
        assertRejected(bundle, "campo sensível");
    }

    @Test
    public void rejectsNonMonotonicEvents() throws Exception {
        JSONObject bundle = validBundle();
        bundle.getJSONArray("events").getJSONObject(1).put("timestamp_ns", 1L);
        assertRejected(bundle, "não monotônico");
    }

    @Test
    public void rejectsCustomDriverWithoutHash() throws Exception {
        JSONObject bundle = validBundle();
        JSONObject binding = bundle.getJSONObject("session").getJSONObject("driver_binding");
        binding.put("mode", "custom").put("candidate_sha256", JSONObject.NULL);
        assertRejected(bundle, "candidate_sha256");
    }

    private static JSONObject validBundle() {
        return TelemetrySession.builder(
                        "adapter", "1", "Emulator", "org.example.emulator", "1", HASH, "pc")
                .sessionId("123e4567-e89b-12d3-a456-426614174000")
                .startedAtUnixMs(1_000L)
                .build()
                .recordFrame(10L, 16_000_000L)
                .recordFrame(20L, 17_000_000L)
                .finish("complete", 2_000L);
    }

    private static void assertRejected(JSONObject bundle, String fragment) {
        try {
            TelemetryContract.validateBundle(bundle);
            fail("bundle deveria ser recusado");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(fragment));
        }
    }
}
