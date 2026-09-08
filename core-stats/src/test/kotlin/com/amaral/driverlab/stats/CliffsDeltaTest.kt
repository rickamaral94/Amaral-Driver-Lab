package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliffsDeltaTest {

    @Test
    fun `full separation gives the extreme values`() {
        val low = doubleArrayOf(1.0, 2.0, 3.0)
        val high = doubleArrayOf(10.0, 11.0, 12.0)
        assertEquals(-1.0, CliffsDelta.of(low, high).delta, 0.0)
        assertEquals(1.0, CliffsDelta.of(high, low).delta, 0.0)
    }

    @Test
    fun `a sample against itself is exactly zero`() {
        val values = doubleArrayOf(3.0, 1.0, 4.0, 1.0, 5.0)
        val result = CliffsDelta.of(values, values.copyOf())
        assertEquals(0.0, result.delta, 0.0)
        assertTrue(result.negligible)
    }

    /**
     * P5 in practice: Cliff's delta and the Mann-Whitney U statistic are two views of the same
     * rank information, related by delta = 2U/(n1·n2) − 1. If they ever drift apart, the app
     * would be showing an effect size that disagrees with its own significance test.
     */
    @Test
    fun `delta stays consistent with the U statistic`() {
        val rng = SplitMix64(20260906L)
        repeat(200) {
            val n1 = 3 + rng.nextInt(10)
            val n2 = 3 + rng.nextInt(10)
            val a = DoubleArray(n1) { rng.nextInt(1_000_000) / 1000.0 }
            val b = DoubleArray(n2) { rng.nextInt(1_000_000) / 1000.0 }

            val delta = CliffsDelta.of(a, b).delta
            val u = MannWhitneyU.test(a, b).u
            val fromU = 2.0 * u / (n1.toDouble() * n2) - 1.0
            assertEquals("n1=$n1 n2=$n2", delta, fromU, 1e-9)
        }
    }

    @Test
    fun `magnitude thresholds follow Romano`() {
        assertEquals(EffectMagnitude.NEGLIGIBLE, CliffsDelta.magnitudeOf(0.10))
        assertEquals(EffectMagnitude.SMALL, CliffsDelta.magnitudeOf(0.20))
        assertEquals(EffectMagnitude.MEDIUM, CliffsDelta.magnitudeOf(0.40))
        assertEquals(EffectMagnitude.LARGE, CliffsDelta.magnitudeOf(-0.80))
    }
}
