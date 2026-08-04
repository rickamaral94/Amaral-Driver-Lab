package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Phase13ContractTest {
    @Test
    public void uxRedesignDoesNotChangeTechnicalSchema() {
        assertEquals(13, Phase13Contract.RESULT_SCHEMA_VERSION);
        assertEquals(1, Phase13Contract.UX_SCHEMA_VERSION);
        assertEquals(5, Phase13Contract.GUIDED_STEP_COUNT);
        assertTrue(Phase13Contract.TECHNICAL_IDENTIFIERS_STABLE);
        assertFalse(Phase13Contract.METHODOLOGY_CHANGED);
    }

    @Test
    public void modesRemainStableIdentifiers() {
        assertEquals("basic", Phase13Contract.BASIC_MODE);
        assertEquals("advanced", Phase13Contract.ADVANCED_MODE);
    }
}
