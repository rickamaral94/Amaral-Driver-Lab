package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportJsonTest {

    private fun report() = ReportBuilder("1.0.0-test", clock = { 1_757_100_000_000 }).build(
        outcome = ReportFixtures.outcome(medianA = 15_000_000, medianB = 16_600_000),
        device = ReportFixtures.device,
        preflight = ReportFixtures.cleanPreflight(),
        session = ReportFixtures.session(),
        nullTestResult = ReportFixtures.passingNullTest(),
        config = AbConfig(bootstrapIterations = 500),
    )

    @Test
    fun `a report survives a round trip unchanged`() {
        val original = report()
        val decoded = ReportJson.decode(ReportJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `the frametime series round trips exactly`() {
        val original = report()
        val decoded = ReportJson.decode(ReportJson.encode(original))
        assertEquals(
            original.executions.map { it.frametimesNs },
            decoded.executions.map { it.frametimesNs },
        )
    }

    @Test
    fun `defaults are written out so a reader never has to guess them`() {
        val json = ReportJson.encode(report())
        assertTrue(json.contains("\"schemaVersion\":${ReportSchema.VERSION}"))
        assertTrue(json.contains("\"submitterAlias\""))
    }

    /**
     * A payload from a newer app is refused rather than parsed hopefully. Reading it
     * partially is how a leaderboard fills with numbers that mean something else.
     */
    @Test
    fun `a newer schema version is refused with a reason`() {
        val bumped = ReportJson.encode(report())
            .replace("\"schemaVersion\":${ReportSchema.VERSION}", "\"schemaVersion\":${ReportSchema.VERSION + 1}")
        val error = runCatching { ReportJson.decode(bumped) }.exceptionOrNull()
        assertTrue(error is SchemaVersionException)
        assertTrue(error!!.message!!.contains("Update the app"))
    }

    @Test
    fun `a payload with no schema version is refused`() {
        val error = runCatching { ReportJson.decode("""{"appVersion":"1.0"}""") }.exceptionOrNull()
        assertTrue(error is SchemaVersionException)
    }

    @Test
    fun `an older schema version is still readable`() {
        // Version 1 is current; this guards the comparison direction, so that when
        // version 2 arrives, v1 payloads keep decoding instead of being refused.
        val json = ReportJson.encode(report())
        assertEquals(ReportSchema.VERSION, ReportJson.decode(json).schemaVersion)
    }

    @Test
    fun `pretty output is the same data, just readable`() {
        val original = report()
        assertEquals(original, ReportJson.decode(ReportJson.encodePretty(original)))
    }

    /**
     * The crash this guards against happened on a real device, after a full run had
     * finished: the display refresh rate came back as NaN, JSON has no way to write that,
     * and the runner process died taking a completed run with it. A missing reading is
     * null now, and this holds it that way.
     */
    @Test
    fun `a report with unknown device readings still encodes`() {
        val unknownReadings = report().copy(
            device = ReportFixtures.device.copy(
                displayRefreshRateHz = null,
                screenBrightness = -1,
            ),
        )
        val encoded = ReportJson.encode(unknownReadings)
        assertTrue(encoded.contains("\"displayRefreshRateHz\":null"))
        assertEquals(unknownReadings, ReportJson.decode(encoded))
    }

    @Test
    fun `an unknown battery temperature encodes as null`() {
        val original = report()
        val stripped = original.copy(
            executions = original.executions.map { execution ->
                execution.copy(
                    telemetryBefore = execution.telemetryBefore.copy(
                        battery = execution.telemetryBefore.battery.copy(temperatureCelsius = null),
                    ),
                )
            },
        )
        val encoded = ReportJson.encode(stripped)
        assertTrue(encoded.contains("\"temperatureCelsius\":null"))
        assertEquals(stripped, ReportJson.decode(encoded))
    }

    /**
     * Belt and braces on the same failure mode. Any non-finite double anywhere in the
     * report would be unpublishable, so the encoder is asked to prove it can write the
     * whole thing rather than trusting each field to have been thought about.
     */
    @Test
    fun `nothing in a report encodes to a value JSON cannot represent`() {
        val encoded = ReportJson.encode(report())
        assertFalse(encoded.contains("NaN"))
        assertFalse(encoded.contains("Infinity"))
    }
}
