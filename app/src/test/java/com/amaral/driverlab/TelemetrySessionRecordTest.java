package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class TelemetrySessionRecordTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void validSdkSessionParsesAndSummarizes() throws Exception {
        JSONObject session = TelemetryTestData.session(temporary.newFile("session.json"),
                "session-valid-001", "custom", 'a', 16.0, false);
        TelemetrySessionRecord record = TelemetrySessionRecord.parse(null, session);
        assertEquals("session-valid-001", record.sessionId);
        assertEquals("custom", record.driverMode);
        assertEquals(600, record.summary.getJSONObject("frame").getInt("sample_count"));
        assertEquals(2, record.summary.getJSONObject("thermal").getInt("sample_count"));
        assertTrue(record.summary.getJSONObject("frame").getDouble("p99_frame_ms") > 16.0);
    }

    @Test
    public void tamperedPayloadIsRejected() throws Exception {
        JSONObject session = TelemetryTestData.session(temporary.newFile("tampered.json"),
                "session-tampered", "system", 'b', 16.0, false);
        session.getJSONArray("samples").getJSONObject(0).put("frame_time_ms", 99.0);
        try {
            TelemetrySessionRecord.parse(null, session);
            fail("payload alterado deveria ser recusado");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("payload_sha256"));
        }
    }

    @Test
    public void privateGameTitleFlagIsRejectedEvenWithValidSignature() throws Exception {
        JSONObject session = TelemetryTestData.session(temporary.newFile("private.json"),
                "session-private1", "system", 'b', 16.0, false);
        session.getJSONObject("privacy").put("contains_title", true);
        TelemetryTestData.resign(session);
        try {
            TelemetrySessionRecord.parse(null, session);
            fail("identidade privada deveria ser recusada");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("privada"));
        }
    }
}
