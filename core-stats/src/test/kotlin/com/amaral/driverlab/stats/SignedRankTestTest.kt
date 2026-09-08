package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference values come from `scipy.stats.wilcoxon(v, alternative="two-sided", mode="exact")`,
 * not from this implementation. A statistic checked only against itself is a tautology.
 *
 * Every magnitude in the reference cases is distinct, and deliberately so. With ties the two
 * implementations legitimately disagree: scipy builds its null distribution over the integer
 * ranks 1..n and warns that it is no longer exact, while this one enumerates the sign
 * assignments over the average ranks actually observed, which is the exact conditional test.
 * Comparing them on tied data would be measuring that difference rather than this code, so
 * the tied case is covered separately by a value that can be reasoned out by hand.
 */
class SignedRankTestTest {

    @Test
    fun `a consistent lean matches scipy`() {
        val values = listOf(0.051, 0.042, 0.063, 0.034, 0.055, 0.046, 0.077, 0.058, 0.069, 0.040)

        assertEquals(0.001953125, SignedRankTest.twoSidedP(values), 1e-12)
    }

    @Test
    fun `an even split matches scipy`() {
        val values =
            listOf(0.051, -0.042, 0.063, -0.034, 0.055, -0.046, 0.077, -0.058, 0.069, -0.040)

        assertEquals(0.322265625, SignedRankTest.twoSidedP(values), 1e-12)
    }

    @Test
    fun `one large value against the trend matches scipy`() {
        // Nine small leans one way and a single big one the other. This is where the test
        // differs from a sign test, which would see 9 of 10 and call it decisive.
        val values = listOf(0.021, 0.032, 0.013, 0.024, 0.035, 0.026, 0.017, 0.038, 0.029, -0.300)

        assertEquals(0.083984375, SignedRankTest.twoSidedP(values), 1e-12)
        assertTrue(
            "a sign test reads the same data as decisive",
            SignTest.twoSidedP(9, 10) < 0.05,
        )
    }

    @Test
    fun `a shorter run matches scipy`() {
        val values = listOf(0.10, 0.20, 0.30, -0.055, 0.15, 0.25, 0.052)

        assertEquals(0.046875, SignedRankTest.twoSidedP(values), 1e-12)
    }

    @Test
    fun `too few values say nothing rather than something`() {
        assertEquals(1.0, SignedRankTest.twoSidedP(listOf(0.1, 0.2, 0.3, 0.4)), 1e-12)
    }

    @Test
    fun `zeroes are dropped, as the test is defined`() {
        val withZeroes =
            listOf(0.051, 0.0, 0.042, 0.0, 0.063, 0.034, 0.055, 0.046, 0.077, 0.058, 0.069, 0.040)
        val without =
            listOf(0.051, 0.042, 0.063, 0.034, 0.055, 0.046, 0.077, 0.058, 0.069, 0.040)

        assertEquals(SignedRankTest.twoSidedP(without), SignedRankTest.twoSidedP(withZeroes), 1e-12)
    }

    @Test
    fun `a p value is always a probability`() {
        val rng = SplitMix64(4242)
        repeat(200) {
            val values: List<Double> =
                List(3 + rng.nextInt(25)) { rng.nextInt(2001) / 1000.0 - 1.0 }
            val p = SignedRankTest.twoSidedP(values)
            assertTrue("p=$p out of range for $values", p in 0.0..1.0)
        }
    }

    @Test
    fun `tied magnitudes are enumerated rather than approximated`() {
        // Every magnitude equal, signs perfectly balanced. Conditioning on the magnitudes
        // seen and enumerating the sign assignments is the null distribution here, so this
        // has an exact answer and it is "completely unsurprising".
        val tied = listOf(0.05, -0.05, 0.05, -0.05, 0.05, -0.05, 0.05, -0.05, 0.05, -0.05)

        assertEquals(1.0, SignedRankTest.twoSidedP(tied), 1e-12)
    }
}
