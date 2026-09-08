package com.amaral.driverlab.stats

import kotlin.math.floor

/**
 * Linear-interpolation quantiles (the "type 7" definition used by NumPy and R's default).
 *
 * Every percentile in this project is a percentile *of frametime*, never of FPS. Taking a
 * percentile of FPS and converting back does not give the same number, and the difference
 * lands exactly on the tail frames that matter.
 */
public object Quantiles {

    /** Sorts a copy; callers that already hold sorted data should use [ofSorted]. */
    public fun of(values: DoubleArray, q: Double): Double = ofSorted(values.sortedArray(), q)

    public fun ofSorted(sorted: DoubleArray, q: Double): Double {
        require(sorted.isNotEmpty()) { "cannot take a quantile of an empty series" }
        require(q in 0.0..1.0) { "q must be in [0, 1], was $q" }
        if (sorted.size == 1) return sorted[0]
        val position = q * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = if (lower + 1 < sorted.size) lower + 1 else lower
        val fraction = position - lower
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower])
    }

    public fun median(values: DoubleArray): Double = of(values, 0.5)

    public fun medianOfSorted(sorted: DoubleArray): Double = ofSorted(sorted, 0.5)
}
