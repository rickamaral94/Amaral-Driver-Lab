package com.amaral.driverlab.stats

import kotlin.math.min

/**
 * Two-sided sign test against a fair coin.
 *
 * Used on the direction of A/A comparisons. A clean harness has no reason to favour the first
 * arm over the second, so the signs should look like coin flips. Ten out of ten landing the same
 * way is an ordering artifact — the runs are not exchangeable — and a calibrated noise floor will
 * happily absorb it, because a reproducible bias is exactly what calibration measures.
 */
public object SignTest {

    /** @return the two-sided p value for [positives] out of [trials] under p = 0.5. */
    public fun twoSidedP(positives: Int, trials: Int): Double {
        require(positives in 0..trials) { "positives must be within 0..trials" }
        if (trials == 0) return 1.0
        var atOrBelow = 0.0
        var atOrAbove = 0.0
        for (k in 0..trials) {
            val probability = binomialCoefficient(trials, k) * Math.pow(0.5, trials.toDouble())
            if (k <= positives) atOrBelow += probability
            if (k >= positives) atOrAbove += probability
        }
        return min(1.0, 2.0 * min(atOrBelow, atOrAbove))
    }

    private fun binomialCoefficient(n: Int, k: Int): Double {
        var result = 1.0
        for (i in 1..min(k, n - k)) {
            result = result * (n - min(k, n - k) + i) / i
        }
        return result
    }
}
