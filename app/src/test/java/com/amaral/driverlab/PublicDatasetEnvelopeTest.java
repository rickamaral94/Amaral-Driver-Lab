package com.amaral.driverlab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PublicDatasetEnvelopeTest {
    @Test
    public void exportsAnonymousSignedEnvelopeAndDetectsTampering() throws Exception {
        SuiteRecord record = SuiteRecord.parse(null, Phase4TestData.report("suite",
                Phase4TestData.sha('a'), "driver-a", 5.0,
                "candidate_better", 1L));
        JSONObject envelope = PublicDatasetEnvelope.create(record);
        assertTrue(PublicDatasetEnvelope.verify(envelope));
        String encoded = envelope.toString();
        assertFalse(encoded.contains("Odin 2 Portal"));
        assertFalse(encoded.contains("build_fingerprint"));
        envelope.getJSONObject("payload").getJSONObject("result")
                .put("ranking_score_percent", 99.0);
        assertFalse(PublicDatasetEnvelope.verify(envelope));
    }

    @Test
    public void rejectsBlockingValidityWarnings() throws Exception {
        JSONObject report = Phase4TestData.report("suite", Phase4TestData.sha('a'),
                "driver-a", 5.0, "candidate_better", 1L);
        report.getJSONArray("validity_warnings")
                .put("Temperatura inicial variou 4.0 °C entre fases; repita após resfriamento.");
        SuiteRecord record = SuiteRecord.parse(null, report);
        try {
            PublicDatasetEnvelope.create(record);
            fail("Expected blocking warning rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bloqueante"));
        }
    }
}
