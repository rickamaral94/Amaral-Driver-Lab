package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes a representative payload to `build/schema-samples/`.
 *
 * The JSON Schema the ingestion Action validates against is checked in this repo,
 * and it is validated in CI against this file. A schema that has never been run
 * against real output from the app is a schema that agrees with whatever the person
 * writing it imagined.
 */
class SampleReportWriterTest {

    @Test
    fun `writes a sample payload for schema validation`() {
        val builder = ReportBuilder(appVersion = "1.0.0-sample", clock = { 1_757_100_000_000 })

        val samples = mapOf(
            "sample-ranked.json" to builder.build(
                outcome = ReportFixtures.outcome(medianA = 15_000_000, medianB = 16_600_000, frameCount = 40),
                device = ReportFixtures.device,
                preflight = ReportFixtures.cleanPreflight(),
                session = ReportFixtures.session(),
                nullTestResult = ReportFixtures.passingNullTest(),
                config = AbConfig(bootstrapIterations = 500),
            ),
            "sample-unranked.json" to builder.build(
                outcome = ReportFixtures.outcome(frameCount = 40),
                device = ReportFixtures.device,
                preflight = ReportFixtures.cleanPreflight(),
                session = ReportFixtures.session(),
                nullTestResult = null,
                config = AbConfig(bootstrapIterations = 500),
            ),
        )

        val directory = File("build/schema-samples").apply { mkdirs() }
        for ((name, report) in samples) {
            val file = File(directory, name)
            file.writeText(ReportJson.encodePretty(report))
            assertTrue("$name should not be empty", file.length() > 0)
        }
    }
}
