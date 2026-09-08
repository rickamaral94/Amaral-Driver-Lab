package com.amaral.driverlab.telemetry

import kotlinx.serialization.Serializable

/**
 * What a thermal zone is probably measuring.
 *
 * A guess from the zone's `type` string, and labelled as one. Zone naming is
 * device-specific and undocumented, so this is good enough to decide which sensors are
 * worth *mentioning* to the user and nowhere near good enough to block a run on.
 */
@Serializable
public enum class ThermalRole {
    CPU,
    GPU,
    /** Case or ambient sensors — the ones that track what the device feels like. */
    SKIN,
    /** Battery, charger and power-management dies. These idle warm and mean little. */
    POWER,
    /** Modem, Wi-Fi, camera, display: real sensors, unrelated to graphics work. */
    PERIPHERAL,
    UNKNOWN;

    /** Whether this zone says anything about the heat a benchmark would have to fight. */
    public val relevantToBenchmarking: Boolean
        get() = this == CPU || this == GPU || this == SKIN

    public companion object {
        public fun classify(type: String): ThermalRole {
            val name = type.lowercase()
            fun has(vararg needles: String) = needles.any { it in name }
            return when {
                has("gpu", "mali", "adreno") -> GPU
                has("skin", "quiet-therm", "shell", "case-therm") -> SKIN
                // Checked after the power patterns below would be wrong: "cpu" is a
                // substring of nothing else here, but PMIC names are checked first
                // because some of them embed core names.
                has("pm8", "pmic", "batt", "bms", "charg", "vbat", "usb", "conn-therm") -> POWER
                has("cpu", "apc", "kryo", "silver", "gold", "prime", "cluster", "big-", "little") -> CPU
                has("mdm", "modem", "wlan", "wifi", "camera", "cam-", "display", "disp", "lcd", "nspss", "video") -> PERIPHERAL
                else -> UNKNOWN
            }
        }
    }
}

/** One thermal zone as the kernel exposes it, in degrees Celsius. */
@Serializable
public data class ThermalZoneReading(
    val zone: String,
    val type: String,
    val celsius: Double,
    /**
     * Derived from [type] by default, so a reading cannot be built carrying the wrong role
     * — or, worse, carrying UNKNOWN because whoever constructed it did not think about it.
     */
    val role: ThermalRole = ThermalRole.classify(type),
)

/**
 * Android's own view of thermal headroom, which is the only signal that is
 * consistent across devices. The `/sys` zones are richer and completely
 * device-specific, so both are recorded and neither is trusted alone.
 */
@Serializable
public enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN;

    public val throttling: Boolean get() = this != NONE && this != UNKNOWN

    public companion object {
        /** Maps `PowerManager.THERMAL_STATUS_*`. */
        public fun fromAndroid(value: Int): ThermalStatus = when (value) {
            0 -> NONE
            1 -> LIGHT
            2 -> MODERATE
            3 -> SEVERE
            4 -> CRITICAL
            5 -> EMERGENCY
            6 -> SHUTDOWN
            else -> UNKNOWN
        }
    }
}

@Serializable
public data class BatteryState(
    /** 0..100, or -1 when unknown. */
    val levelPercent: Int,
    /**
     * `BATTERY_PROPERTY_CURRENT_NOW` in microamps. The sign convention is not
     * consistent across devices, so efficiency figures derived from it are always
     * labelled an estimate.
     */
    val currentNowMicroamps: Long,
    val charging: Boolean,
    val plugged: Boolean,
    /** Null when the device does not report it. Never NaN: JSON has no such value. */
    val temperatureCelsius: Double? = null,
)

/** Everything that describes the machine, captured once per session. */
@Serializable
public data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    val soc: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildFingerprint: String,
    /** Null when the display could not be queried. Never NaN: JSON has no such value. */
    val displayRefreshRateHz: Double? = null,
    /** -1 when brightness could not be read. */
    val screenBrightness: Int = -1,
)

/**
 * A reading taken at one moment. Section 5 asks for these at the start, the middle
 * and the end of a run, so a result carries the thermal story rather than a single
 * number that could have been taken at the best moment.
 */
@Serializable
public data class TelemetrySample(
    val elapsedRealtimeMs: Long,
    val thermalStatus: ThermalStatus,
    val battery: BatteryState,
    val zones: List<ThermalZoneReading>,
) {
    /**
     * Hottest zone of any kind. Recorded in the report, because a reader who knows the
     * device can interpret it. Not a basis for a decision: it is routinely the PMIC or
     * the charger, which idle warm on a cold device.
     */
    public val peakZoneCelsius: Double?
        get() = zones.maxOfOrNull { it.celsius }

    /**
     * Hottest zone that plausibly reflects the heat a benchmark would have to fight, or
     * null when no zone could be classified.
     *
     * Null is the honest answer on an unfamiliar device, and callers must treat it as
     * "unknown" rather than "cool". Earlier this was [peakZoneCelsius], which blocked
     * runs on freshly booted devices because some unrelated sensor was sitting at 62 °C.
     */
    public val representativeZone: ThermalZoneReading?
        get() = zones.filter { it.role.relevantToBenchmarking }.maxByOrNull { it.celsius }

    public val representativeCelsius: Double?
        get() = representativeZone?.celsius
}
