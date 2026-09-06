package com.amaral.driverlab.telemetry

import kotlinx.serialization.Serializable

/** One thermal zone as the kernel exposes it, in degrees Celsius. */
@Serializable
public data class ThermalZoneReading(
    val zone: String,
    val type: String,
    val celsius: Double,
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
    val temperatureCelsius: Double,
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
    val displayRefreshRateHz: Double,
    val screenBrightness: Int,
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
    /** Hottest zone, which is the one that will throttle first. */
    public val peakZoneCelsius: Double?
        get() = zones.maxOfOrNull { it.celsius }
}
