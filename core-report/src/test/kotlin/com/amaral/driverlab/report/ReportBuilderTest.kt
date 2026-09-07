package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBuilderTest {

    private val builder = ReportBuilder(appVersion = "1.0.0-test", clock = { 1_757_100_000_000 })
    private val config = AbConfig(bootstrapIterations = 800)

    private fun build(
        medianA: Long = 16_600_000,
        medianB: Long = 16_600_000,
        nullTest: com.amaral.driverlab.stats.NullTestResult? = ReportFixtures.passingNullTest(),
    ) = builder.build(
        outcome = ReportFixtures.outcome(medianA = medianA, medianB = medianB),
        device = ReportFixtures.device,
        preflight = ReportFixtures.cleanPreflight(),
        session = ReportFixtures.session(),
        nullTestResult = nullTest,
        config = config,
    )

    @Test
    fun `the raw series survives into the report`() {
        val report = build()
        val execution = report.executions.first()
        assertEquals(60, execution.frametimesNs.size)
        assertEquals(60, execution.cpuFrametimesNs.size)
        assertEquals(
            "the summary must describe the series that is published, not a different one",
            execution.frametimesNs.size, execution.summary.frameCount,
        )
    }

    @Test
    fun `a real difference produces a winner and a readable sentence`() {
        val report = build(medianA = 15_000_000, medianB = 16_600_000)
        val comparison = report.comparisons.single()
        assertEquals(Verdict.A_FASTER.name, comparison.verdict)
        assertTrue(comparison.percentDifference > 5.0)
        assertTrue(report.plainVerdict.contains("Turnip v3 is"))
        assertTrue(report.plainVerdict.contains("faster"))
    }

    @Test
    fun `no difference is stated as a tie against the measured floor`() {
        val report = build()
        assertEquals(Verdict.TECHNICAL_TIE.name, report.comparisons.single().verdict)
        assertTrue(report.plainVerdict.contains("same speed"))
        assertTrue(report.plainVerdict.contains("can resolve"))
    }

    /** P3: no null test means the numbers are shown and the ranking is withheld. */
    @Test
    fun `without a passing null test the verdict refuses to rank`() {
        val report = build(medianA = 15_000_000, medianB = 16_600_000, nullTest = null)
        assertTrue(report.plainVerdict.startsWith("Results are shown but not ranked"))
        assertFalse(report.comparisons.single().trustworthy)
        assertTrue(
            report.comparisons.single().warnings
                .contains(com.amaral.driverlab.telemetry.ComparabilityWarning.NULL_TEST_NOT_PASSED),
        )
    }

    @Test
    fun `a passing null test marks comparisons trustworthy and sets the floor`() {
        val report = build(medianA = 15_000_000, medianB = 16_600_000)
        val comparison = report.comparisons.single()
        assertTrue(comparison.trustworthy)
        assertTrue(comparison.warnings.isEmpty())
        assertEquals(report.nullTest!!.appliedNoiseFloor, comparison.noiseFloor, 1e-12)
    }

    @Test
    fun `the headline percentage and the interval agree`() {
        val report = build(medianA = 15_000_000, medianB = 16_600_000)
        val comparison = report.comparisons.single()
        // P5: a reader must never see "A is faster" beside an interval allowing B to be.
        assertTrue(comparison.speedupInterval.low > 1.0)
        assertEquals((comparison.speedupOfA - 1.0) * 100.0, comparison.percentDifference, 1e-9)
    }

    @Test
    fun `both drivers appear with the checksum that ran`() {
        val report = build()
        assertEquals(2, report.drivers.size)
        assertEquals("a".repeat(64), report.drivers.first { it.arm == "A" }.libraryChecksum)
        assertEquals("b".repeat(64), report.drivers.first { it.arm == "B" }.libraryChecksum)
        assertTrue(report.drivers.none { it.systemDriver })
    }

    @Test
    fun `failures are carried into the report rather than dropped`() {
        val outcome = ReportFixtures.outcome(
            failures = listOf(
                com.amaral.driverlab.bench.ExecutionFailure(
                    slot = com.amaral.driverlab.bench.ArmSlot(0, com.amaral.driverlab.bench.Arm.A, 0),
                    label = "Turnip v3",
                    workload = com.amaral.driverlab.vk.WorkloadSpec(com.amaral.driverlab.vk.WorkloadIds.BASELINE),
                    stage = "DEVICE_LOST",
                    message = "vkQueueSubmit: VK_ERROR_DEVICE_LOST",
                ),
            ),
        )
        val report = builder.build(
            outcome, ReportFixtures.device, ReportFixtures.cleanPreflight(), ReportFixtures.session(),
            ReportFixtures.passingNullTest(), config = config,
        )
        assertEquals("DEVICE_LOST", report.failures.single().stage)
    }

    @Test
    fun `an overridden preflight is recorded as low confidence`() {
        val report = builder.build(
            ReportFixtures.outcome(),
            ReportFixtures.device,
            com.amaral.driverlab.telemetry.PreflightReport(
                issues = listOf(
                    com.amaral.driverlab.telemetry.PreflightIssue(
                        com.amaral.driverlab.telemetry.PreflightIssue.Code.PLUGGED_IN,
                        "charger connected",
                        blocking = true,
                    ),
                ),
                overridden = true,
            ),
            ReportFixtures.session(),
            ReportFixtures.passingNullTest(),
            config = config,
        )
        assertTrue(report.preflight.lowConfidence)
        assertTrue(report.preflight.overridden)
    }

    @Test
    fun `the schema version is stamped on every report`() {
        assertEquals(ReportSchema.VERSION, build().schemaVersion)
    }
}
