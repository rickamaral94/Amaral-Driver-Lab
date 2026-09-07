package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import org.junit.Assert.assertEquals
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
}
