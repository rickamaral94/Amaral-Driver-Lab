package com.amaral.driverlab.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {

    private fun sample(
        batteryPercent: Int = 85,
        plugged: Boolean = false,
        thermalStatus: ThermalStatus = ThermalStatus.NONE,
        peakCelsius: Double? = 32.0,
    ) = TelemetrySample(
        elapsedRealtimeMs = 1_000,
        thermalStatus = thermalStatus,
        battery = BatteryState(
            levelPercent = batteryPercent,
            currentNowMicroamps = -450_000,
            charging = plugged,
            plugged = plugged,
            temperatureCelsius = 30.0,
        ),
        zones = peakCelsius?.let {
            listOf(
                ThermalZoneReading("thermal_zone0", "cpu-0-0-usr", it - 4.0),
                ThermalZoneReading("thermal_zone7", "gpuss-0-usr", it),
            )
        } ?: emptyList(),
    )

    @Test
    fun `a device in good shape has nothing to report`() {
        val report = Preflight.evaluate(sample(), millisSinceLastRun = null)
        assertTrue(report.issues.isEmpty())
        assertTrue(report.clearedToRun)
        assertFalse(report.lowConfidence)
        assertTrue(report.summary().contains("ready"))
    }

    @Test
    fun `low battery blocks`() {
        val report = Preflight.evaluate(sample(batteryPercent = 22), millisSinceLastRun = null)
        assertEquals(listOf(PreflightIssue.Code.BATTERY_TOO_LOW), report.blockingIssues.map { it.code })
        assertFalse(report.clearedToRun)
        assertTrue(report.blockingIssues.single().message.contains("22%"))
    }

    @Test
    fun `a connected charger blocks`() {
        val report = Preflight.evaluate(sample(plugged = true), millisSinceLastRun = null)
        assertTrue(report.blockingIssues.any { it.code == PreflightIssue.Code.PLUGGED_IN })
    }

    @Test
    fun `a device already throttling blocks`() {
        val report = Preflight.evaluate(
            sample(thermalStatus = ThermalStatus.MODERATE),
            millisSinceLastRun = null,
        )
        assertTrue(report.blockingIssues.any { it.code == PreflightIssue.Code.ALREADY_THROTTLING })
    }

    /**
     * A hot sensor warns and never blocks. Which sysfs zone means what is device-specific
     * and undocumented, so this reading can be wrong in ways nothing here can detect —
     * and a check the user learns to override is a check that has stopped working.
     * Android's own thermal status is the gate for a genuinely hot device.
     */
    @Test
    fun `a hot sensor warns, names itself, and does not block`() {
        val report = Preflight.evaluate(sample(peakCelsius = 52.0), millisSinceLastRun = null)
        val issue = report.issues.single { it.code == PreflightIssue.Code.DEVICE_TOO_HOT }
        assertFalse(issue.blocking)
        assertTrue(report.clearedToRun)
        assertTrue(issue.message.contains("52"))
        assertTrue("the user must be able to tell which sensor", issue.message.contains("gpuss-0-usr"))
    }

    /**
     * The bug this rule was rewritten for. A freshly booted, cold device reported 62 °C on
     * a power-management sensor, and the app refused to run — it took the maximum across
     * every zone, including ones that idle warm and say nothing about graphics work.
     */
    @Test
    fun `an unrelated hot sensor on a cool device says nothing`() {
        val coolDeviceWithHotPmic = TelemetrySample(
            elapsedRealtimeMs = 1_000,
            thermalStatus = ThermalStatus.NONE,
            battery = BatteryState(77, -400_000, false, false, 28.0),
            zones = listOf(
                ThermalZoneReading("thermal_zone0", "cpu-0-0-usr", 31.0, ThermalRole.CPU),
                ThermalZoneReading("thermal_zone7", "gpuss-0-usr", 30.0, ThermalRole.GPU),
                ThermalZoneReading("thermal_zone20", "pm8550b_tz", 62.0, ThermalRole.POWER),
            ),
        )
        val report = Preflight.evaluate(coolDeviceWithHotPmic, millisSinceLastRun = null)
        assertTrue("a cold device must not be warned about", report.issues.isEmpty())
        assertTrue(report.clearedToRun)
        assertEquals(62.0, coolDeviceWithHotPmic.peakZoneCelsius!!, 1e-9)
        assertEquals(
            "the representative reading must ignore the power sensor",
            31.0, coolDeviceWithHotPmic.representativeCelsius!!, 1e-9,
        )
    }

    @Test
    fun `too little cooldown since the last run blocks`() {
        val report = Preflight.evaluate(sample(), millisSinceLastRun = 60_000)
        val issue = report.blockingIssues.single { it.code == PreflightIssue.Code.NOT_COOLED_DOWN }
        assertTrue(issue.message.contains("more minutes"))
    }

    @Test
    fun `enough cooldown does not block`() {
        val report = Preflight.evaluate(sample(), millisSinceLastRun = 10 * 60_000)
        assertTrue(report.blockingIssues.isEmpty())
    }

    /**
     * A device with no readable sensors can still be benchmarked; what it cannot do
     * is claim it did not heat up. So this is a note, not a block.
     */
    @Test
    fun `missing thermal telemetry warns without blocking`() {
        val report = Preflight.evaluate(sample(peakCelsius = null), millisSinceLastRun = null)
        val issue = report.issues.single { it.code == PreflightIssue.Code.NO_THERMAL_TELEMETRY }
        assertEquals(PreflightIssue.Code.NO_THERMAL_TELEMETRY, issue.code)
        assertFalse(issue.blocking)
        assertTrue(report.clearedToRun)
    }

    @Test
    fun `overriding lets the run start and marks it low confidence`() {
        val base = Preflight.evaluate(sample(batteryPercent = 10, plugged = true), null)
        assertFalse(base.clearedToRun)

        val overridden = base.copy(overridden = true)
        assertTrue(overridden.clearedToRun)
        assertTrue(
            "an overridden run must be flagged, or it will be ranked against clean ones",
            overridden.lowConfidence,
        )
        assertTrue(overridden.summary().contains("overriding"))
    }

    @Test
    fun `a clean run is never low confidence`() {
        assertFalse(Preflight.evaluate(sample(), null).copy(overridden = true).lowConfidence)
    }

    @Test
    fun `every blocking condition is reported at once, not one at a time`() {
        val report = Preflight.evaluate(
            sample(batteryPercent = 12, plugged = true, thermalStatus = ThermalStatus.SEVERE, peakCelsius = 60.0),
            millisSinceLastRun = 1_000,
        )
        assertEquals(
            "only the conditions the app can be certain about block",
            setOf(
                PreflightIssue.Code.BATTERY_TOO_LOW,
                PreflightIssue.Code.PLUGGED_IN,
                PreflightIssue.Code.ALREADY_THROTTLING,
                PreflightIssue.Code.NOT_COOLED_DOWN,
            ),
            report.blockingIssues.map { it.code }.toSet(),
        )
        assertTrue(
            "the hot sensor is still reported, just not as a block",
            report.issues.any { it.code == PreflightIssue.Code.DEVICE_TOO_HOT && !it.blocking },
        )
    }

    @Test
    fun `thermal status maps from Android's constants`() {
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromAndroid(0))
        assertEquals(ThermalStatus.SEVERE, ThermalStatus.fromAndroid(3))
        assertEquals(ThermalStatus.UNKNOWN, ThermalStatus.fromAndroid(-1))
        assertFalse(ThermalStatus.NONE.throttling)
        assertFalse("unknown is not evidence of throttling", ThermalStatus.UNKNOWN.throttling)
        assertTrue(ThermalStatus.LIGHT.throttling)
    }
}
