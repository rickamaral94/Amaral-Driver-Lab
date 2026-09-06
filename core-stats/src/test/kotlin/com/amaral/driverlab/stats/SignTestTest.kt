package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignTestTest {

    @Test
    fun `an even split is unremarkable`() {
        assertEquals(1.0, SignTest.twoSidedP(5, 10), 1e-12)
    }

    @Test
    fun `a clean sweep is the least likely outcome`() {
        // 2 * (1/2)^10 = 0.001953125
        assertEquals(0.001953125, SignTest.twoSidedP(10, 10), 1e-12)
        assertEquals(0.001953125, SignTest.twoSidedP(0, 10), 1e-12)
    }

    @Test
    fun `nine of ten is still past the usual threshold`() {
        // 2 * (1 + 10) / 1024
        assertEquals(0.021484375, SignTest.twoSidedP(9, 10), 1e-12)
        assertTrue(SignTest.twoSidedP(9, 10) < NullTest.ORDERING_BIAS_ALPHA)
    }

    @Test
    fun `eight of ten does not clear it`() {
        assertTrue(SignTest.twoSidedP(8, 10) > NullTest.ORDERING_BIAS_ALPHA)
    }

    @Test
    fun `probabilities over the whole range sum to one`() {
        // Sanity on the binomial coefficients: the two one-sided tails at k must overlap by
        // exactly P(X = k), so p(0) and p(n) are symmetric and p(k) is never above 1.
        for (n in 1..20) {
            for (k in 0..n) {
                val p = SignTest.twoSidedP(k, n)
                assertTrue("n=$n k=$k gave $p", p in 0.0..1.0)
                assertEquals("symmetry at n=$n k=$k", p, SignTest.twoSidedP(n - k, n), 1e-12)
            }
        }
    }

    @Test
    fun `no trials means nothing to report`() {
        assertEquals(1.0, SignTest.twoSidedP(0, 0), 0.0)
    }
}
