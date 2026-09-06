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

    @Test
    fun `a hot device blocks`() {
        val report = Preflight.evaluate(sample(peakCelsius = 52.0), millisSinceLastRun = null)
        val issue = report.blockingIssues.single { it.code == PreflightIssue.Code.DEVICE_TOO_HOT }
        assertTrue(issue.message.contains("52"))
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
        val issue = report.issues.single()
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
            setOf(
                PreflightIssue.Code.BATTERY_TOO_LOW,
                PreflightIssue.Code.PLUGGED_IN,
                PreflightIssue.Code.ALREADY_THROTTLING,
                PreflightIssue.Code.DEVICE_TOO_HOT,
                PreflightIssue.Code.NOT_COOLED_DOWN,
            ),
            report.blockingIssues.map { it.code }.toSet(),
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
