package com.amaral.driverlab;

import java.util.HashMap;
import java.util.Map;

/** Contextual explanations presented without renaming technical metric identifiers. */
final class HelpContent {
    static final class Entry {
        final int title;
        final int what;
        final int why;
        final int how;

        Entry(int title, int what, int why, int how) {
            this.title = title;
            this.what = what;
            this.why = why;
            this.how = how;
        }
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    static {
        ENTRIES.put("p99_gpu_frame_ms", new Entry(
                R.string.help_p99_title, R.string.help_p99_what,
                R.string.help_p99_why, R.string.help_p99_how));
        ENTRIES.put("performance", new Entry(
                R.string.help_performance_title, R.string.help_performance_what,
                R.string.help_performance_why, R.string.help_performance_how));
        ENTRIES.put("compatibility", new Entry(
                R.string.help_compatibility_title, R.string.help_compatibility_what,
                R.string.help_compatibility_why, R.string.help_compatibility_how));
        ENTRIES.put("thermal", new Entry(
                R.string.help_thermal_title, R.string.help_thermal_what,
                R.string.help_thermal_why, R.string.help_thermal_how));
    }

    private HelpContent() {}

    static Entry forId(String identifier) {
        Entry entry = ENTRIES.get(identifier);
        return entry == null ? ENTRIES.get("performance") : entry;
    }

    static int size() {
        return ENTRIES.size();
    }
}
