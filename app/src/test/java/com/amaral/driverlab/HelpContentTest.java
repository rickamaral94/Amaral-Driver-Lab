package com.amaral.driverlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public final class HelpContentTest {
    @Test
    public void priorityMetricsHaveThreeLevelHelp() {
        assertEquals(4, HelpContent.size());
        assertNotNull(HelpContent.forId("p99_gpu_frame_ms"));
        assertNotNull(HelpContent.forId("compatibility"));
        assertNotNull(HelpContent.forId("thermal"));
        assertNotNull(HelpContent.forId("unknown_metric"));
    }
}
