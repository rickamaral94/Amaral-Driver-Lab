package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class TelemetryStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importAndLinkKeepSourceEvidenceImmutable() throws Exception {
        File source = temporary.newFile("source.json");
        JSONObject sessionJson = TelemetryTestData.session(source,
                "session-store001", "custom", 'a', 16.0, false);
        File filesDir = temporary.newFolder("files");
        TelemetrySessionRecord imported = TelemetryStore.importSession(filesDir,
                new ByteArrayInputStream(sessionJson.toString()
                        .getBytes(StandardCharsets.UTF_8)));
        String before = ResultFiles.readUtf8(imported.file);

        File suiteDir = new File(new File(filesDir, "suites"), "suite-telemetry-link");
        assertTrue(suiteDir.mkdirs());
        File suiteFile = new File(suiteDir, "suite.json");
        JSONObject suiteJson = TelemetryTestData.suiteReport(TelemetryTestData.sha('a'));
        ResultFiles.writeAtomic(suiteFile, suiteJson.toString(2));
        SuiteRecord suite = SuiteRecord.parse(suiteFile, suiteJson);

        JSONObject link = TelemetryStore.linkToSuite(filesDir, imported, suite);
        assertEquals(imported.sessionId, link.getString("session_id"));
        assertFalse(link.getBoolean("source_session_mutated"));
        assertFalse(link.getBoolean("suite_mutated"));
        assertEquals(before, ResultFiles.readUtf8(imported.file));
        assertNotNull(TelemetryStore.readLink(imported));
        assertEquals(1, TelemetryStore.scan(filesDir).size());
    }
}
