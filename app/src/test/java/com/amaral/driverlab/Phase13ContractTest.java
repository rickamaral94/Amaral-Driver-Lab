package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Phase13ContractTest {
    @Test
    public void uxRedesignDoesNotChangeTechnicalSchema() {
        assertEquals(13, Phase13Contract.RESULT_SCHEMA_VERSION);
        assertEquals(2, Phase13Contract.UX_SCHEMA_VERSION);
        assertEquals(5, Phase13Contract.GUIDED_STEP_COUNT);
        assertTrue(Phase13Contract.TECHNICAL_IDENTIFIERS_STABLE);
        assertTrue(Phase13Contract.RECOMMENDED_PROFILE_CHANGED);
        assertTrue(Phase13Contract.LEGACY_FULL_V3_PRESERVED);
    }

    @Test
    public void modesRemainStableIdentifiers() {
        assertEquals("basic", Phase13Contract.BASIC_MODE);
        assertEquals("advanced", Phase13Contract.ADVANCED_MODE);
        assertEquals("system_vs_turnip", Phase13Contract.SYSTEM_VS_TURNIP);
        assertEquals("turnip_vs_turnip", Phase13Contract.TURNIP_VS_TURNIP);
        assertTrue(Phase13Contract.HOME_DIRECT_DRIVER_SELECTION);
        assertTrue(Phase13Contract.HOME_DIRECT_DRIVER_IMPORT);
        assertTrue(Phase13Contract.LOG_OPENS_AFTER_HOME_TEST);
    }
}
