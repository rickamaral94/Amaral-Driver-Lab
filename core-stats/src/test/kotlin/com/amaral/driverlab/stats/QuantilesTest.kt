package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reference values produced by numpy.percentile, which uses the same type-7 definition. */
class QuantilesTest {

    private val sample = doubleArrayOf(5.0, 1.0, 4.0, 2.0, 3.0, 9.0, 7.0, 6.0, 8.0, 10.0)

    @Test
    fun `matches numpy type 7 quantiles`() {
        assertEquals(1.0, Quantiles.of(sample, 0.0), 1e-12)
        assertEquals(3.25, Quantiles.of(sample, 0.25), 1e-12)
        assertEquals(5.5, Quantiles.of(sample, 0.5), 1e-12)
        assertEquals(9.549999999999999, Quantiles.of(sample, 0.95), 1e-12)
        assertEquals(9.91, Quantiles.of(sample, 0.99), 1e-12)
        assertEquals(9.991000000000001, Quantiles.of(sample, 0.999), 1e-12)
        assertEquals(10.0, Quantiles.of(sample, 1.0), 1e-12)
    }

    @Test
    fun `single element series has no spread`() {
        val one = doubleArrayOf(42.0)
        assertEquals(42.0, Quantiles.of(one, 0.0), 0.0)
        assertEquals(42.0, Quantiles.of(one, 0.999), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty series is rejected rather than silently zero`() {
        Quantiles.median(doubleArrayOf())
    }
}
