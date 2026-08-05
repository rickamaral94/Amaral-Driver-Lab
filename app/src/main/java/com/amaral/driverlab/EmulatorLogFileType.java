package com.amaral.driverlab;

import java.util.Locale;

final class EmulatorLogFileType {
    private EmulatorLogFileType() {}

    static boolean isSupported(String fileName) {
        if (fileName == null) return false;
        String normalized = fileName.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".txt") || normalized.endsWith(".log");
    }
}
