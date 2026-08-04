package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TelemetryComparisonTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sameProtocolSystemAndCandidateProduceDescriptiveComparison() throws Exception {
        TelemetrySessionRecord system = TelemetrySessionRecord.parse(null,
                TelemetryTestData.session(temporary.newFile("system.json"),
                        "session-system01", "system", 'a', 18.0, false));
        TelemetrySessionRecord candidate = TelemetrySessionRecord.parse(null,
                TelemetryTestData.session(temporary.newFile("candidate.json"),
                        "session-candidate1", "custom", 'a', 15.5, false));
        JSONObject comparison = TelemetryComparison.compare(system, candidate);
        assertTrue(comparison.getBoolean("available"));
        assertTrue(comparison.getBoolean("historically_comparable"));
        assertEquals("candidate_better_descriptive", comparison.getString("classification"));
        assertFalse(comparison.getBoolean("statistical_inference_available"));
        assertFalse(comparison.getBoolean("included_in_full_qualification_score"));
    }

    @Test
    public void settingsMismatchBlocksHistoricalComparability() throws Exception {
        TelemetrySessionRecord system = TelemetrySessionRecord.parse(null,
                TelemetryTestData.session(temporary.newFile("system2.json"),
                        "session-system02", "system", 'a', 18.0, false));
        JSONObject changed = TelemetryTestData.session(temporary.newFile("candidate2.json"),
                "session-candidate2", "custom", 'a', 15.5, false);
        changed.getJSONObject("environment").put("settings_sha256", TelemetryTestData.sha('7'));
        TelemetryTestData.resign(changed);
        TelemetrySessionRecord candidate = TelemetrySessionRecord.parse(null, changed);
        JSONObject comparison = TelemetryComparison.compare(system, candidate);
        assertFalse(comparison.getBoolean("historically_comparable"));
        assertEquals("not_historically_comparable", comparison.getString("classification"));
    }

    @Test
    public void extraCandidateCrashPrecedesPerformance() throws Exception {
        TelemetrySessionRecord system = TelemetrySessionRecord.parse(null,
                TelemetryTestData.session(temporary.newFile("system3.json"),
                        "session-system03", "system", 'a', 18.0, false));
        TelemetrySessionRecord candidate = TelemetrySessionRecord.parse(null,
                TelemetryTestData.session(temporary.newFile("candidate3.json"),
                        "session-candidate3", "custom", 'a', 14.0, true));
        JSONObject comparison = TelemetryComparison.compare(system, candidate);
        assertTrue(comparison.getBoolean("historically_comparable"));
        assertEquals("candidate_regressed_stability", comparison.getString("classification"));
    }
}
