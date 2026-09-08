package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapTest {

    private val runs = doubleArrayOf(16_600_000.0, 16_710_000.0, 16_540_000.0, 16_680_000.0, 16_630_000.0)

    @Test
    fun `same seed and same samples reproduce the interval exactly`() {
        val first = Bootstrap.medianCi(runs, iterations = 2_000, seed = 99L)
        val second = Bootstrap.medianCi(runs, iterations = 2_000, seed = 99L)
        assertEquals(first, second)
    }

    @Test
    fun `interval brackets the point estimate`() {
        val ci = Bootstrap.medianCi(runs, iterations = 4_000)
        assertEquals(Quantiles.median(runs), ci.point, 1e-9)
        assertTrue("point ${ci.point} outside [${ci.low}, ${ci.high}]", ci.point in ci)
    }

    @Test
    fun `ratio of medians reads as the speedup of the first arm`() {
        // b takes 10% longer per frame, so a is 10% faster and the ratio lands near 1.10.
        val a = doubleArrayOf(10.0, 10.1, 9.9, 10.05, 9.95)
        val b = DoubleArray(a.size) { a[it] * 1.10 }
        val ci = Bootstrap.medianRatioCi(a, b, iterations = 4_000)
        assertEquals(1.10, ci.point, 1e-9)
        assertTrue("interval ${ci.low}..${ci.high} should exclude parity", 1.0 !in ci)
    }

    @Test
    fun `a noisy sample produces a visibly wider interval than a tight one`() {
        val tight = doubleArrayOf(100.0, 100.5, 99.5, 100.2, 99.8, 100.1)
        val noisy = doubleArrayOf(60.0, 140.0, 80.0, 130.0, 70.0, 150.0)
        val tightWidth = Bootstrap.medianCi(tight, iterations = 4_000).relativeWidth
        val noisyWidth = Bootstrap.medianCi(noisy, iterations = 4_000).relativeWidth
        assertTrue("tight=$tightWidth noisy=$noisyWidth", noisyWidth > tightWidth * 5)
    }

    @Test
    fun `interval covers the true median at roughly the nominal rate`() {
        // A coverage check, not a proof: a 95% interval that covered 60% of the time would mean
        // every published confidence interval in the app is decorative.
        val rng = SplitMix64(4242L)
        val trueMedian = 100.0
        var covered = 0
        val trials = 300
        repeat(trials) { trial ->
            val sample = DoubleArray(15) { trueMedian + (rng.nextInt(4001) - 2000) / 100.0 }
            if (trueMedian in Bootstrap.medianCi(sample, iterations = 600, seed = trial.toLong())) covered++
        }
        val rate = covered.toDouble() / trials
        assertTrue("coverage was $rate, expected near 0.95", rate > 0.85)
    }
}
