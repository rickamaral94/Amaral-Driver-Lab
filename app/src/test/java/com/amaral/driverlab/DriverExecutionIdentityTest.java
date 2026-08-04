package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;

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
}
