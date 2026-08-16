package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable raw input for ranking derivation. Derived scores are deliberately not stored here. */
final class MeasurementRecord {
    static final int SCHEMA_VERSION = 1;
    static final int CURRENT_FORMULA_VERSION = 1;
    static final String CONFIDENCE_CO_MEASURED = "co-medida";
    static final String CONFIDENCE_RECENT = "recente";

    final JSONObject raw;

    MeasurementRecord(JSONObject source) throws Exception {
        raw = new JSONObject(source.toString());
        validate(raw);
    }

    String id() { return raw.optString("measurement_id"); }
    long timestampMs() { return raw.optLong("timestamp_ms", 0L); }
    String deviceFingerprint() { return raw.optString("device_fingerprint_sha256"); }
    String epoch() { return raw.optString("epoch_sha256"); }
    String driverBinarySha() {
        JSONObject identity = raw.optJSONObject("candidate_identity");
        return identity == null ? "" : identity.optString("binary_sha256");
    }
    JSONObject candidateIdentity() { return raw.optJSONObject("candidate_identity"); }
    JSONObject referenceIdentity() { return raw.optJSONObject("reference_identity"); }
    JSONArray workloads() { return raw.optJSONArray("workloads"); }
    JSONArray failures() { return raw.optJSONArray("failures"); }
    boolean nullTest() { return raw.optBoolean("null_test", false); }
    boolean imported() { return raw.optBoolean("imported", false); }
    boolean foreignDevice() { return raw.optBoolean("foreign_device", false); }
    boolean thermalGatePassed() { return raw.optBoolean("thermal_gate_passed", false); }
    boolean completeAbBa() { return raw.optBoolean("complete_abba", false); }
    String anchorConfidence() {
        return raw.optString("anchor_confidence", CONFIDENCE_CO_MEASURED);
    }

    JSONObject toJson() throws Exception { return new JSONObject(raw.toString()); }

    static void validate(JSONObject value) {
        if (value.optInt("measurement_schema_version", -1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("measurement_schema_version incompatível");
        }
        if (value.optString("measurement_id").isEmpty()
                || value.optLong("timestamp_ms", 0L) <= 0L) {
            throw new IllegalArgumentException("Identidade temporal da medição ausente");
        }
        if (value.optString("device_fingerprint_sha256").length() != 64
                || value.optString("epoch_sha256").length() != 64) {
            throw new IllegalArgumentException("Fingerprint da medição inválido");
        }
        if (value.optJSONObject("candidate_identity") == null
                || value.optJSONArray("workloads") == null
                || value.optJSONArray("failures") == null) {
            throw new IllegalArgumentException("Medição bruta incompleta");
        }
        String confidence = value.optString("anchor_confidence", CONFIDENCE_CO_MEASURED);
        if (!CONFIDENCE_CO_MEASURED.equals(confidence)
                && !CONFIDENCE_RECENT.equals(confidence)) {
            throw new IllegalArgumentException("Confiança da âncora inválida");
        }
    }
}
