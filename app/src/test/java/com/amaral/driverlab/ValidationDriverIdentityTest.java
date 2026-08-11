package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ValidationDriverIdentityTest {
    @Test
    public void fullCoverageAndUnanimityAreRuntimeConfirmed() throws Exception {
        JSONObject audit = ValidationDriverIdentity.auditPhases(new JSONArray()
                        .put(phase("reference", 1, qualcomm()))
                        .put(phase("candidate", 1, turnip())),
                turnipPackage('a'), null);

        assertEquals("runtime_confirmed",
                audit.getString("driver_identity_confidence"));
        assertTrue(audit.getBoolean("eligible_for_aggregation"));
        assertEquals("full", audit.getJSONObject("identity_observation_coverage")
                .getString("coverage_class"));
        assertTrue(audit.getBoolean("loader_isolation_verified"));
    }

    @Test
    public void partialCoverageAndUnanimityAreInferred() throws Exception {
        JSONObject audit = ValidationDriverIdentity.auditPhases(new JSONArray()
                        .put(phase("reference", 1, qualcomm()))
                        .put(phase("candidate", 1, turnip()))
                        .put(phase("reference", 2, qualcomm()))
                        .put(phase("candidate", 2, null)),
                turnipPackage('a'), null);

        assertEquals("inferred", audit.getString("driver_identity_confidence"));
        assertEquals("inferred", audit.getJSONObject("candidate")
                .getString("driver_identity_confidence"));
        assertFalse(audit.getBoolean("eligible_for_aggregation"));
        assertEquals("partial", audit.getJSONObject("identity_observation_coverage")
                .getString("coverage_class"));
    }

    @Test
    public void AnyRuntimeDivergenceIsDisputed() throws Exception {
        JSONObject audit = ValidationDriverIdentity.auditPhases(new JSONArray()
                        .put(phase("reference", 1, qualcomm()))
                        .put(phase("candidate", 1, turnip()))
                        .put(phase("candidate", 2, qualcomm())),
                turnipPackage('a'), null);

        assertEquals("disputed", audit.getString("driver_identity_confidence"));
        assertEquals("qualcomm_blob", audit.getJSONObject("candidate").getString("driver"));
        assertFalse(audit.getBoolean("eligible_for_aggregation"));
    }

    @Test
    public void QualcommVendorVetoWinsIdentityButPreservesPackageConflict() throws Exception {
        JSONObject audit = ValidationDriverIdentity.auditPhases(new JSONArray()
                        .put(phase("reference", 1, qualcomm()))
                        .put(phase("candidate", 1, qualcomm())),
                turnipPackage('a'), null);

        JSONObject candidate = audit.getJSONObject("candidate");
        assertEquals("qualcomm_blob", candidate.getString("driver"));
        assertEquals("disputed", candidate.getString("driver_identity_confidence"));
    }

    @Test
    public void NoRuntimeEvidenceIsInferredNotTurnip() throws Exception {
        JSONObject audit = ValidationDriverIdentity.auditPhases(new JSONArray()
                        .put(phase("reference", 1, null))
                        .put(phase("candidate", 1, null)),
                turnipPackage('a'), null);

        assertEquals("unknown", audit.getJSONObject("candidate").getString("driver"));
        assertEquals("inferred", audit.getString("driver_identity_confidence"));
        assertEquals("none", audit.getJSONObject("identity_observation_coverage")
                .getString("coverage_class"));
    }

    @Test
    public void IdenticalTurnipPropertiesCannotSeparateTwoBuilds() throws Exception {
        JSONObject audit = ValidationDriverIdentity.auditPhases(new JSONArray()
                        .put(phase("reference", 1, turnip()))
                        .put(phase("candidate", 1, turnip())),
                turnipPackage('b'), turnipPackage('a'));

        assertEquals("runtime_confirmed",
                audit.getString("driver_identity_confidence"));
        assertEquals("not_determinable", audit.get("loader_isolation_verified"));
        assertEquals("not_observed", audit.getString("build_identity_status"));
    }

    @Test
    public void SchemaThirteenIsExplicitlyUnaudited() throws Exception {
        JSONObject normalized = ValidationDriverIdentity.normalizeForRead(
                new JSONObject().put("schema_version", 13));
        assertEquals("unaudited",
                normalized.getString("driver_identity_confidence"));
        assertFalse(normalized.getBoolean("eligible_for_aggregation"));
    }

    private static JSONObject phase(String role, int round, JSONObject capabilities)
            throws Exception {
        JSONObject phase = new JSONObject()
                .put("success", capabilities != null)
                .put("driver_role", role)
                .put("driver_mode", "reference".equals(role) ? "system" : "custom")
                .put("round", round)
                .put("driver_sha256", "candidate".equals(role)
                        ? Phase4TestData.sha('a') : JSONObject.NULL);
        if (capabilities != null) {
            phase.put("native", new JSONObject()
                    .put("success", true)
                    .put("capabilities", capabilities));
        }
        return phase;
    }

    private static JSONObject turnip() throws Exception {
        return capabilities(19, "MESA_TURNIP", "turnip Mesa driver",
                "Mesa 26.3.0-devel", 1234L, "0.0.1.0");
    }

    private static JSONObject qualcomm() throws Exception {
        return capabilities(8, "QUALCOMM_PROPRIETARY", "Qualcomm Technologies, Inc.",
                "512.676.53", 5678L, null);
    }

    private static JSONObject capabilities(int id, String idName, String name, String info,
                                           long rawVersion, String conformance) throws Exception {
        JSONObject value = new JSONObject()
                .put("driver_id", id)
                .put("driver_id_name", idName)
                .put("driver_name", name)
                .put("driver_info", info)
                .put("api_version_raw", 4206872)
                .put("api_version", "1.3.280")
                .put("driver_version_raw", rawVersion)
                .put("driver_version_decoded", "1.2.3");
        value.put("conformance_version", conformance == null ? JSONObject.NULL : conformance);
        return value;
    }

    private static JSONObject turnipPackage(char hash) throws Exception {
        return new JSONObject()
                .put("sha256", Phase4TestData.sha(hash))
                .put("name", "Turnip Amaral")
                .put("packageVersion", hash == 'a' ? "v1" : "v3")
                .put("vendor", "Mesa")
                .put("metadata", new JSONObject().put("name", "Turnip"));
    }
}
