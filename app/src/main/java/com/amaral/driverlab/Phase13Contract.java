package com.amaral.driverlab;

/** Phase 13 UX contract plus the short recommended Turnip validation profile. */
final class Phase13Contract {
    static final int UX_SCHEMA_VERSION = 2;
    static final int RESULT_SCHEMA_VERSION = WorkloadContract.RESULT_SCHEMA_VERSION;
    static final String BASIC_MODE = "basic";
    static final String ADVANCED_MODE = "advanced";
    static final String SYSTEM_VS_TURNIP = "system_vs_turnip";
    static final String TURNIP_VS_TURNIP = "turnip_vs_turnip";
    static final boolean HOME_DIRECT_DRIVER_SELECTION = true;
    static final boolean HOME_DIRECT_DRIVER_IMPORT = true;
    static final boolean LOG_OPENS_AFTER_HOME_TEST = true;
    static final int GUIDED_STEP_COUNT = 5;
    static final boolean TECHNICAL_IDENTIFIERS_STABLE = true;
    static final boolean RECOMMENDED_PROFILE_CHANGED = true;
    static final boolean LEGACY_FULL_V3_PRESERVED = true;

    private Phase13Contract() {}
}
