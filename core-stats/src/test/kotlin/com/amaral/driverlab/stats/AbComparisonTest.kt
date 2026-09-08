package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbComparisonTest {

    private fun compare(
        a: DoubleArray,
        b: DoubleArray,
        config: AbConfig = AbConfig(bootstrapIterations = 3_000),
    ) = AbComparison.compare("A", "B", a, b, lowerIsBetter = true, config = config)

    @Test
    fun `a driver compared with itself is a technical tie`() {
        val runs = doubleArrayOf(16.60, 16.71, 16.54, 16.68, 16.63)
        val result = compare(runs, runs.copyOf())
        assertEquals(Verdict.TECHNICAL_TIE, result.verdict)
        assertEquals(1.0, result.speedupOfA, 1e-12)
        assertEquals(0.0, result.percentDifference, 1e-9)
    }

    @Test
    fun `a clear ten percent win names a winner`() {
        val a = doubleArrayOf(15.00, 15.10, 14.90, 15.05, 14.95)
        val b = doubleArrayOf(16.50, 16.61, 16.39, 16.56, 16.45)
        val result = compare(a, b)
        assertEquals(Verdict.A_FASTER, result.verdict)
        assertEquals(VerdictReason.SIGNIFICANT_EFFECT, result.reason)
        assertEquals(10.0, result.percentDifference, 0.5)
        assertTrue(result.mannWhitney.pValue < 0.05)
        assertEquals(EffectMagnitude.LARGE, result.cliffsDelta.magnitude)
    }

    @Test
    fun `the slower driver is named when B wins`() {
        val a = doubleArrayOf(16.50, 16.61, 16.39, 16.56, 16.45)
        val b = doubleArrayOf(15.00, 15.10, 14.90, 15.05, 14.95)
        val result = compare(a, b)
        assertEquals(Verdict.B_FASTER, result.verdict)
        assertTrue("A should look slower than B", result.percentDifference < 0)
    }

    /**
     * The case the noise floor exists for: five runs that never interleave, so the rank test
     * reaches its smallest possible p value, over a difference of a third of one percent.
     */
    @Test
    fun `a difference below the noise floor is a tie despite a significant p value`() {
        val a = doubleArrayOf(16.600, 16.601, 16.602, 16.603, 16.604)
        val b = doubleArrayOf(16.650, 16.651, 16.652, 16.653, 16.654)
        val result = compare(a, b)
        assertTrue("expected significance, got p=${result.mannWhitney.pValue}", result.mannWhitney.pValue < 0.05)
        assertEquals(Verdict.TECHNICAL_TIE, result.verdict)
        assertEquals(VerdictReason.WITHIN_NOISE_FLOOR, result.reason)
    }

    @Test
    fun `fewer runs than the protocol requires is never a verdict`() {
        val a = doubleArrayOf(15.0, 15.1, 14.9, 15.05)
        val b = doubleArrayOf(16.5, 16.6, 16.4, 16.55)
        val result = compare(a, b)
        assertEquals(Verdict.INCONCLUSIVE, result.verdict)
        assertEquals(VerdictReason.INSUFFICIENT_RUNS, result.reason)
    }

    @Test
    fun `a noisy pair is inconclusive rather than a tie`() {
        val a = doubleArrayOf(10.0, 30.0, 12.0, 28.0, 14.0, 26.0)
        val b = doubleArrayOf(11.0, 29.0, 13.0, 27.0, 15.0, 25.0)
        val result = compare(a, b)
        assertEquals(Verdict.INCONCLUSIVE, result.verdict)
        assertEquals(VerdictReason.HIGH_VARIANCE, result.reason)
    }

    @Test
    fun `the headline percentage and the interval describe the same quantity`() {
        val a = doubleArrayOf(15.00, 15.10, 14.90, 15.05, 14.95)
        val b = doubleArrayOf(16.50, 16.61, 16.39, 16.56, 16.45)
        val result = compare(a, b)
        // P5: a reader must never see "A is faster" next to an interval that allows B to be faster.
        assertTrue(result.speedupOfA in result.speedupInterval)
        assertTrue("interval ${result.speedupInterval} must exclude parity", 1.0 !in result.speedupInterval)
        assertTrue(result.percentDifference > 0)
    }

    @Test
    fun `a higher-is-better metric flips the orientation`() {
        val a = doubleArrayOf(1200.0, 1210.0, 1190.0, 1205.0, 1195.0)
        val b = doubleArrayOf(1000.0, 1010.0, 990.0, 1005.0, 995.0)
        val result = AbComparison.compare(
            "A", "B", a, b, lowerIsBetter = false, config = AbConfig(bootstrapIterations = 3_000),
        )
        assertEquals(Verdict.A_FASTER, result.verdict)
        assertEquals(20.0, result.percentDifference, 1.0)
    }
}
