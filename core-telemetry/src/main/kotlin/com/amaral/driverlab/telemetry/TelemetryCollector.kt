package com.amaral.driverlab.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display

/**
 * Reads the device's state through Android's APIs.
 *
 * Every field is optional in practice: manufacturers disable sensors, deny reads,
 * and report units inconsistently. Nothing here substitutes a zero for a missing
 * reading — a report that cannot say how hot the device was should say that,
 * because a fabricated 0 °C is indistinguishable from a real one in a chart.
 */
public class TelemetryCollector(
    private val context: Context,
    private val zoneReader: ThermalZoneReader = ThermalZoneReader(),
) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    public fun sample(): TelemetrySample = TelemetrySample(
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        thermalStatus = thermalStatus(),
        battery = battery(),
        zones = zoneReader.read(),
    )

    public fun thermalStatus(): ThermalStatus =
        powerManager?.let { ThermalStatus.fromAndroid(it.currentThermalStatus) } ?: ThermalStatus.UNKNOWN

    public fun battery(): BatteryState {
        val status: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val pluggedFlag = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeStatus = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temperatureTenths = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE

        return BatteryState(
            levelPercent = batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
                ?: -1,
            currentNowMicroamps = batteryManager
                ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?: 0L,
            charging = chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = pluggedFlag != 0,
            temperatureCelsius = if (temperatureTenths == Int.MIN_VALUE) {
                Double.NaN
            } else {
                temperatureTenths / 10.0
            },
        )
    }

    public fun snapshot(): DeviceSnapshot = DeviceSnapshot(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOfNotNull(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
        } else {
            Build.HARDWARE.orEmpty()
        },
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        buildFingerprint = Build.FINGERPRINT.orEmpty(),
        displayRefreshRateHz = refreshRateHz(),
        screenBrightness = brightness(),
    )

    private fun refreshRateHz(): Double = try {
        val display: Display? = context.display
        display?.refreshRate?.toDouble() ?: Double.NaN
    } catch (_: Exception) {
        Double.NaN
    }

    private fun brightness(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Exception) {
        -1
    }

    /**
     * Registers a listener that records whether the device throttled at any point
     * during a run. A run that throttled is not invalid, but it is not comparable
     * with one that did not, and the report has to say so.
     */
    public fun watchThrottling(onStatusChanged: (ThermalStatus) -> Unit): AutoCloseable {
        val manager = powerManager ?: return AutoCloseable {}
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            onStatusChanged(ThermalStatus.fromAndroid(status))
        }
        manager.addThermalStatusListener(listener)
        return AutoCloseable { manager.removeThermalStatusListener(listener) }
    }
}
