package com.amaral.driverlab.stats

import kotlin.math.exp
import kotlin.math.min

/** Normal tail probabilities, used only to turn a z score into a p value. */
internal object Gaussian {

    /**
     * Complementary error function, Numerical Recipes' Chebyshev fit.
     * Fractional error below 1.2e-7 everywhere, which is far tighter than any decision
     * this project makes with a p value.
     */
    fun erfc(x: Double): Double {
        val z = kotlin.math.abs(x)
        val t = 2.0 / (2.0 + z)
        val ty = 4.0 * t - 2.0
        val coefficients = doubleArrayOf(
            -1.3026537197817094, 6.4196979235649026e-1, 1.9476473204185836e-2,
            -9.561514786808631e-3, -9.46595344482036e-4, 3.66839497852761e-4,
            4.2523324806907e-5, -2.0278578112534e-5, -1.624290004647e-6,
            1.303655835580e-6, 1.5626441722e-8, -8.5238095915e-8,
            6.529054439e-9, 5.059343495e-9, -9.91364156e-10,
            -2.27365122e-10, 9.6467911e-11, 2.394038e-12,
            -6.886027e-12, 8.94487e-13, 3.13092e-13,
            -1.12708e-13, 3.81e-16, 7.106e-15,
        )
        var d = 0.0
        var dd = 0.0
        for (j in coefficients.size - 1 downTo 1) {
            val tmp = d
            d = ty * d - dd + coefficients[j]
            dd = tmp
        }
        val ans = t * exp(-z * z + 0.5 * (coefficients[0] + ty * d) - dd)
        return if (x >= 0.0) ans else 2.0 - ans
    }

    /** Two-sided tail area beyond |z| for a standard normal. */
    fun twoSidedP(z: Double): Double = min(1.0, erfc(kotlin.math.abs(z) / kotlin.math.sqrt(2.0)))
}
