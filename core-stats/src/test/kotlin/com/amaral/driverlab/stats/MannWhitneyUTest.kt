package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference U statistics and p values come from scipy.stats.mannwhitneyu
 * (two-sided; method='exact' where there are no ties, 'asymptotic' with use_continuity=True
 * otherwise), which is the same pair of definitions implemented here.
 */
class MannWhitneyUTest {

    @Test
    fun `exact p for fully separated samples`() {
        val r = MannWhitneyU.test(
            doubleArrayOf(10.0, 11.0, 12.0, 13.0, 14.0),
            doubleArrayOf(20.0, 21.0, 22.0, 23.0, 24.0),
        )
        assertTrue(r.exact)
        assertEquals(0.0, r.u, 0.0)
        assertEquals(0.007936507936507936, r.pValue, 1e-12)
    }

    @Test
    fun `exact p for interleaved samples finds nothing`() {
        val r = MannWhitneyU.test(
            doubleArrayOf(10.0, 12.0, 14.0, 16.0, 18.0),
            doubleArrayOf(11.0, 13.0, 15.0, 17.0, 19.0),
        )
        assertTrue(r.exact)
        assertEquals(10.0, r.u, 0.0)
        assertEquals(0.6904761904761905, r.pValue, 1e-12)
    }

    @Test
    fun `exact p with unequal sample sizes`() {
        val r = MannWhitneyU.test(
            doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            doubleArrayOf(5.0, 6.0, 7.0, 8.0, 9.0),
        )
        assertTrue(r.exact)
        assertEquals(0.0, r.u, 0.0)
        assertEquals(0.015873015873015872, r.pValue, 1e-12)
    }

    @Test
    fun `exact p for a partially overlapping pair`() {
        val r = MannWhitneyU.test(
            doubleArrayOf(12.1, 12.5, 12.3, 12.9, 12.2, 12.7),
            doubleArrayOf(12.4, 12.6, 12.8, 13.0, 12.35, 12.55),
        )
        assertTrue(r.exact)
        assertEquals(11.0, r.u, 0.0)
        assertEquals(0.30952380952380953, r.pValue, 1e-12)
    }

    @Test
    fun `ties switch to the tie-corrected normal approximation`() {
        val a = doubleArrayOf(10.0, 10.0, 11.0, 12.0, 12.0, 13.0, 14.0, 15.0, 16.0, 10.0, 11.0, 12.0)
        val b = doubleArrayOf(11.0, 12.0, 12.0, 13.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 12.0, 13.0)
        val r = MannWhitneyU.test(a, b)
        assertFalse(r.exact)
        assertEquals(39.5, r.u, 1e-9)
        assertEquals(0.06142390026788869, r.pValue, 1e-6)
    }

    @Test
    fun `samples above the exact limit use the normal approximation`() {
        val a = doubleArrayOf(
            100.0062, 101.4937, 98.6293, 95.547, 97.7266, 95.0418, 100.3007, 106.7011, 97.539,
            96.8976, 102.4492, 101.7844, 100.5271, 95.3477, 99.8537, 103.4765, 93.2789, 97.7119,
            90.4939, 93.5523, 90.7913, 98.8245, 93.6628, 101.3563, 100.7838,
        )
        val b = doubleArrayOf(
            102.0653, 90.4162, 100.3065, 102.7575, 103.5665, 95.3493, 100.6112, 98.1074, 98.9558,
            108.3045, 98.9623, 102.8374, 107.4219, 100.082, 102.4415, 103.5523, 103.3189, 96.8747,
            103.3807, 109.7941, 95.2643, 107.2969, 103.5968, 99.7926, 113.0021,
        )
        val r = MannWhitneyU.test(a, b)
        assertFalse(r.exact)
        assertEquals(166.0, r.u, 1e-9)
        assertEquals(0.004613992191848587, r.pValue, 1e-6)
    }

    @Test
    fun `identical samples give no evidence of a difference`() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        val r = MannWhitneyU.test(values, values.copyOf())
        assertEquals(1.0, r.pValue, 1e-9)
    }

    @Test
    fun `a constant series cannot separate from itself`() {
        val flat = doubleArrayOf(7.0, 7.0, 7.0, 7.0, 7.0)
        assertEquals(1.0, MannWhitneyU.test(flat, flat.copyOf()).pValue, 0.0)
    }
}
