package com.amaral.driverlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmulatorLogFileTypeTest {
    @Test
    public void acceptsTxtAndLogFiles() {
        assertTrue(EmulatorLogFileType.isSupported("eden.txt"));
        assertTrue(EmulatorLogFileType.isSupported("game-crash.log"));
        assertTrue(EmulatorLogFileType.isSupported("EDEN.TXT"));
        assertTrue(EmulatorLogFileType.isSupported("TURNIP.LOG"));
    }

    @Test
    public void rejectsOtherOrMissingExtensions() {
        assertFalse(EmulatorLogFileType.isSupported("report.json"));
        assertFalse(EmulatorLogFileType.isSupported("report.csv"));
        assertFalse(EmulatorLogFileType.isSupported("report"));
        assertFalse(EmulatorLogFileType.isSupported(null));
    }
}
