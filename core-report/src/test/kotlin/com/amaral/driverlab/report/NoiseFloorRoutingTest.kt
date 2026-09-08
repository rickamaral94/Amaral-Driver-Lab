package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.Verdict
import com.amaral.driverlab.vk.WorkloadIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor a device measured for one workload has to reach the comparison for that
 * workload, or the null test has been run for nothing.
 */
class NoiseFloorRoutingTest {

    private fun buildWith(floors: Map<String, Double>) =
        ReportBuilder("1.0.0-test", clock = { 1_757_100_000_000 }).build(
            // 4% apart: real enough to beat the default 2% claim, well inside a 10% floor.
            outcome = ReportFixtures.outcome(medianA = 16_000_000, medianB = 16_640_000),
            device = ReportFixtures.device,
            preflight = ReportFixtures.cleanPreflight(),
            session = ReportFixtures.session(),
            nullTestResult = ReportFixtures.passingNullTest(),
            noiseFloors = floors,
            config = AbConfig(bootstrapIterations = 800),
        )

    @Test
    fun `a coarse floor for the workload turns a real difference into a tie`() {
        val ranked = buildWith(emptyMap()).comparisons.single()
        val floored = buildWith(mapOf(WorkloadIds.TILING_GMEM to 0.10)).comparisons.single()

        assertEquals(Verdict.TECHNICAL_TIE.name, floored.verdict)
        assertTrue(
            "the same data without the measured floor should not tie: ${ranked.verdict}",
            ranked.verdict != Verdict.TECHNICAL_TIE.name,
        )
        assertEquals(0.10, floored.noiseFloor, 1e-9)
    }

    @Test
    fun `a floor for another workload does not touch this comparison`() {
        val other = buildWith(mapOf("baseline/v1" to 0.10)).comparisons.single()

        assertEquals(buildWith(emptyMap()).comparisons.single().verdict, other.verdict)
    }

    @Test
    fun `a measured floor may widen the claim but never sharpen it`() {
        // 0.1% is finer than the harness's default claim. Accepting it would let a device
        // that got lucky in calibration claim resolution the protocol does not have.
        val optimistic = buildWith(mapOf(WorkloadIds.TILING_GMEM to 0.001)).comparisons.single()

        assertEquals(
            AbConfig.DEFAULT_MINIMUM_PRACTICAL_DIFFERENCE,
            optimistic.noiseFloor,
            1e-9,
        )
    }
}
