package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class DriverExecutionIdentityTest {
    @Test
    public void systemBaselineUsesSystemLoaderAndRole() {
        assertEquals("system", DriverExecutionIdentity.mode(false));
        assertEquals("system", DriverExecutionIdentity.role(false, false));
    }

    @Test
    public void turnipReferenceUsesCustomLoaderAndReferenceRole() {
        assertEquals("custom", DriverExecutionIdentity.mode(true));
        assertEquals("reference", DriverExecutionIdentity.role(false, true));
    }

    @Test
    public void candidateUsesCustomLoaderAndCandidateRole() {
        assertEquals("custom", DriverExecutionIdentity.mode(true));
        assertEquals("candidate", DriverExecutionIdentity.role(true, true));
    }
    @Test
    public void turnipArmsAreSeparatedByRoleNotLoaderMode() throws Exception {
        JSONObject reference = new JSONObject()
                .put("driver_mode", "custom")
                .put("driver_role", "reference")
                .put("phase", "system");
        JSONObject candidate = new JSONObject()
                .put("driver_mode", "custom")
                .put("driver_role", "candidate")
                .put("phase", "candidate");
        assertFalse(DriverExecutionIdentity.isCandidateArm(reference));
        assertTrue(DriverExecutionIdentity.isReferenceArm(reference));
        assertTrue(DriverExecutionIdentity.isCandidateArm(candidate));
    }

    @Test
    public void legacyResultsFallBackToHistoricalPhaseLabel() throws Exception {
        assertTrue(DriverExecutionIdentity.isCandidateArm(
                new JSONObject().put("phase", "candidate").put("driver_mode", "custom")));
        assertFalse(DriverExecutionIdentity.isCandidateArm(
                new JSONObject().put("phase", "system").put("driver_mode", "system")));
    }
}
