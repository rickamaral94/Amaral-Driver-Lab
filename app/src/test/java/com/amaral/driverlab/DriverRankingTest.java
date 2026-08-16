package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DriverRankingTest {
    private static final String DEVICE = repeat('d');
    private static final String EPOCH = repeat('e');
    private static final String SET = repeat('f');
    private static final String DRIVER = repeat('1');
    private static final String OTHER = repeat('2');

    @Test public void fastDriverWithDivergentHashIsDisqualified() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 0.5);
        records.get(1).workloads().getJSONObject(0).put("candidate_image_hash", "bad");
        RankingResult result = compute(records, true);
        assertEquals(1, result.disqualified.size());
        assertTrue(result.ranked.isEmpty());
    }

    @Test public void unknownIdentityIsIneligible() throws Exception {
        RankingResult result = compute(baseCampaign(DRIVER, "unknown", 1.0), true);
        assertEquals(1, result.ineligible.size());
    }

    @Test public void qualcommBlobNeverRanks() throws Exception {
        RankingResult result = compute(baseCampaign(DRIVER, "qualcomm_proprietary", 1.0), true);
        assertTrue(result.ranked.isEmpty());
        assertEquals(1, result.ineligible.size());
    }

    @Test public void missingStockAnchorLeavesVsStockAbsent() throws Exception {
        RankingResult result = compute(baseCampaign(DRIVER, "mesa", 0.8), false);
        assertEquals(1, result.ranked.size());
        assertNull(result.ranked.get(0).vsStock);
    }

    @Test public void sessionWithoutPrimaryAnchorDoesNotScore() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 1.0);
        for (MeasurementRecord record : records) {
            if (!record.nullTest()) record.referenceIdentity().put("binary_sha256", OTHER);
        }
        RankingResult result = compute(records, true);
        assertTrue(result.ranked.isEmpty());
        assertEquals(1, result.ineligible.size());
    }

    @Test public void badNullTestBlocksRanking() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 1.0);
        records.get(0).raw.put("score_ci95", new JSONObject().put("low", 101).put("high", 103));
        RankingResult result = compute(records, true);
        assertTrue(result.blocked);
        assertEquals("null_test_ci95_does_not_contain_100", result.blockedReason);
    }

    @Test public void recentAnchorIsPreservedInUiEntry() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 1.0);
        records.get(records.size() - 1).raw.put("anchor_confidence", "recente");
        RankingResult result = compute(records, true);
        assertEquals("recente", result.ranked.get(0).anchorConfidence);
    }

    @Test public void previousEpochDoesNotEnterCurrentRanking() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 1.0);
        for (MeasurementRecord record : records) if (!record.nullTest()) {
            record.raw.put("epoch_sha256", repeat('a'));
        }
        RankingResult result = compute(records, true);
        assertTrue(result.ranked.isEmpty());
    }

    @Test public void remeasurementPreservesHistoryAndUsesLatest() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 1.0);
        records.add(record("newest", DRIVER, "mesa", false, 0.5,
                DriverRanking.PRIMARY_ANCHOR_SHA256, EPOCH, DEVICE, 9000));
        RankingResult result = compute(records, true);
        DriverEntry entry = result.ranked.get(0);
        assertEquals(Integer.valueOf(200), entry.score);
        assertEquals(4, entry.history.length());
    }

    @Test public void foreignImportAffectsNeitherRankingNorNoiseFloor() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 1.0);
        MeasurementRecord foreign = record("foreign", DRIVER, "mesa", false, 0.1,
                DriverRanking.PRIMARY_ANCHOR_SHA256, EPOCH, repeat('9'), 9999);
        foreign.raw.put("imported", true).put("foreign_device", true);
        records.add(foreign);
        RankingResult result = compute(records, true);
        assertEquals(Integer.valueOf(100), result.ranked.get(0).score);
        assertEquals(DriverRanking.FALLBACK_NOISE_FLOOR, result.noiseFloor, 0.00001);
    }

    @Test public void formulaRecalculationDoesNotMutateRaw() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 0.8);
        String before = records.get(1).toJson().toString();
        records.get(1).raw.put("formula_version", 2);
        compute(records, true);
        assertEquals(2, records.get(1).raw.getInt("formula_version"));
        records.get(1).raw.put("formula_version", 1);
        assertEquals(before, records.get(1).toJson().toString());
    }

    @Test public void noiseFloorTieUsesSharedRank() throws Exception {
        List<MeasurementRecord> records = baseCampaign(DRIVER, "mesa", 1.0);
        records.addAll(candidateSessions(OTHER, "mesa", 0.99, 10000));
        RankingResult result = compute(records, true);
        assertEquals(2, result.ranked.size());
        assertEquals(result.ranked.get(0).rank, result.ranked.get(1).rank);
    }

    @Test public void auditedV3AnchorIsExactly100() throws Exception {
        List<MeasurementRecord> records = baseCampaign(
                DriverRanking.PRIMARY_ANCHOR_SHA256, "mesa", 0.7);
        RankingResult result = compute(records, true);
        assertEquals(Integer.valueOf(100), result.ranked.get(0).score);
    }

    @Test public void absentNullDataIsNeverRenderedAsZero() throws Exception {
        List<MeasurementRecord> records = candidateSessions(DRIVER, "mesa", 1.0, 1000);
        RankingResult result = compute(records, true);
        assertTrue(result.blocked);
        assertFalse(result.ineligible.isEmpty());
        assertNull(result.ineligible.get(0).score);
    }

    private static RankingResult compute(List<MeasurementRecord> records, boolean stock)
            throws Exception {
        JSONObject primary = new JSONObject()
                .put("binary_sha256", DriverRanking.PRIMARY_ANCHOR_SHA256)
                .put("mesa_commit", DriverRanking.PRIMARY_ANCHOR_MESA_COMMIT)
                .put("device_fingerprint_sha256", DEVICE)
                .put("workload_set_sha256", SET);
        JSONObject stockAnchor = stock ? new JSONObject()
                .put("binary_sha256", "system")
                .put("runtime_vendor", "qualcomm_proprietary") : null;
        return DriverRanking.computeRanking(records, primary, stockAnchor, EPOCH);
    }

    private static List<MeasurementRecord> baseCampaign(String sha, String vendor, double time)
            throws Exception {
        List<MeasurementRecord> records = new ArrayList<>();
        records.add(record("null", repeat('8'), "mesa", true, 1.0,
                repeat('8'), EPOCH, DEVICE, 100));
        records.addAll(candidateSessions(sha, vendor, time, 1000));
        return records;
    }

    private static List<MeasurementRecord> candidateSessions(String sha, String vendor,
                                                              double time, long start)
            throws Exception {
        List<MeasurementRecord> records = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            records.add(record("session-" + sha.charAt(0) + "-" + index, sha, vendor,
                    false, time, DriverRanking.PRIMARY_ANCHOR_SHA256,
                    EPOCH, DEVICE, start + index));
        }
        return records;
    }

    private static MeasurementRecord record(String id, String sha, String vendor,
                                            boolean nullTest, double candidateTime,
                                            String referenceSha, String epoch,
                                            String device, long timestamp) throws Exception {
        JSONObject workload = new JSONObject()
                .put("workload_id", "visual_scene_geometry")
                .put("workload_version", 2)
                .put("included_in_sustained", true)
                .put("included_in_low", true)
                .put("anchor_time_ms", 1.0)
                .put("candidate_time_ms", candidateTime)
                .put("anchor_frame_times_ms", new JSONArray().put(1.0).put(1.0).put(1.0))
                .put("candidate_frame_times_ms", new JSONArray()
                        .put(candidateTime).put(candidateTime).put(candidateTime))
                .put("anchor_image_hash", "same")
                .put("candidate_image_hash", "same");
        return new MeasurementRecord(new JSONObject()
                .put("measurement_schema_version", 1)
                .put("measurement_id", id)
                .put("timestamp_ms", timestamp)
                .put("device_fingerprint_sha256", device)
                .put("epoch_sha256", epoch)
                .put("workload_set_sha256", SET)
                .put("measurement_protocol_version", 1)
                .put("formula_version", 1)
                .put("anchor_confidence", "co-medida")
                .put("null_test", nullTest)
                .put("complete_abba", true)
                .put("thermal_gate_passed", true)
                .put("imported", false)
                .put("foreign_device", false)
                .put("candidate_identity", identity(sha, vendor))
                .put("reference_identity", identity(referenceSha,
                        "system".equals(referenceSha) ? "qualcomm_proprietary" : "mesa"))
                .put("workloads", new JSONArray().put(workload))
                .put("failures", new JSONArray())
                .put("score_ci95", nullTest
                        ? new JSONObject().put("low", 99).put("high", 101) : JSONObject.NULL));
    }

    private static JSONObject identity(String sha, String vendor) throws Exception {
        return new JSONObject().put("binary_sha256", sha)
                .put("runtime_vendor", vendor)
                .put("driver_name", "Driver " + sha.charAt(0))
                .put("api_version", "1.3.274")
                .put("mesa_commit", DriverRanking.PRIMARY_ANCHOR_MESA_COMMIT);
    }

    private static String repeat(char value) {
        return String.valueOf(value).repeat(64);
    }
}
