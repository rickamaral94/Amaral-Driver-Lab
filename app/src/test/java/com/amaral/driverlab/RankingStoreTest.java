package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RankingStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void migrationPreservesLegacyFileAndEveryRecord() throws Exception {
        File root = temporary.newFolder("files");
        File ranking = new File(root, "ranking");
        assertTrue(ranking.mkdirs());
        File legacy = new File(ranking, "measurements.json");
        ResultFiles.writeAtomic(legacy, new JSONArray().put(record("legacy-1")).toString());
        assertEquals(1, RankingStore.load(root).size());
        assertTrue(legacy.isFile());
        assertEquals(1, RankingStore.load(root).size());
    }

    @Test public void appendOnlyRejectsMutationOfExistingId() throws Exception {
        File root = temporary.newFolder("append");
        MeasurementRecord first = new MeasurementRecord(record("same"));
        RankingStore.append(root, first);
        JSONObject changed = record("same");
        changed.put("timestamp_ms", 999L);
        boolean rejected = false;
        try { RankingStore.append(root, new MeasurementRecord(changed)); }
        catch (IllegalStateException expected) { rejected = true; }
        assertTrue(rejected);
    }

    @Test public void signedForeignImportIsVerifiedAndMarkedForeign() throws Exception {
        File source = temporary.newFolder("source");
        File destination = temporary.newFolder("destination");
        MeasurementRecord measurement = new MeasurementRecord(record("exported"));
        JSONObject envelope = RankingStore.exportEnvelope(source,
                Collections.singletonList(measurement), new JSONObject()
                        .put("device_fingerprint_sha256", "d".repeat(64)));
        assertEquals(1, RankingStore.importEnvelope(destination, envelope, "x".repeat(64)));
        MeasurementRecord imported = RankingStore.load(destination).get(0);
        assertTrue(imported.foreignDevice());
        assertEquals("verified_foreign",
                imported.raw.getString("import_signature_status"));
    }

    @Test public void tamperedSignedExportIsRejected() throws Exception {
        File source = temporary.newFolder("signed-source");
        File destination = temporary.newFolder("signed-destination");
        JSONObject envelope = RankingStore.exportEnvelope(source,
                Collections.singletonList(new MeasurementRecord(record("signed"))),
                new JSONObject().put("device_fingerprint_sha256", "d".repeat(64)));
        envelope.getJSONArray("records").getJSONObject(0).put("timestamp_ms", 999L);
        boolean rejected = false;
        try { RankingStore.importEnvelope(destination, envelope, "d".repeat(64)); }
        catch (SecurityException expected) { rejected = true; }
        assertTrue(rejected);
    }

    private static JSONObject record(String id) throws Exception {
        String sha = "1".repeat(64);
        return new JSONObject()
                .put("measurement_schema_version", 1)
                .put("measurement_id", id)
                .put("timestamp_ms", 1L)
                .put("device_fingerprint_sha256", "d".repeat(64))
                .put("epoch_sha256", "e".repeat(64))
                .put("anchor_confidence", "co-medida")
                .put("candidate_identity", new JSONObject().put("binary_sha256", sha))
                .put("workloads", new JSONArray())
                .put("failures", new JSONArray());
    }
}
