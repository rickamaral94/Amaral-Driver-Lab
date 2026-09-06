package com.amaral.driverlab.stats

/** A percentile bootstrap interval. [low] and [high] bracket [point] at [confidence]. */
public data class ConfidenceInterval(
    val point: Double,
    val low: Double,
    val high: Double,
    val confidence: Double,
) {
    /** Width relative to the point estimate; the honest measure of "how noisy was this run". */
    public val relativeWidth: Double
        get() = if (point == 0.0) Double.POSITIVE_INFINITY else (high - low) / point

    public operator fun contains(value: Double): Boolean = value in low..high
}

/**
 * Percentile bootstrap. Seeded on purpose — see [SplitMix64]. Resampling with a clock-derived
 * seed would make a published interval impossible for anyone else to reproduce from the
 * raw series, which is the one thing the ingestion pipeline needs to be able to do.
 */
public object Bootstrap {

    public const val DEFAULT_ITERATIONS: Int = 10_000
    public const val DEFAULT_CONFIDENCE: Double = 0.95
    public const val DEFAULT_SEED: Long = 0x5EED_1CE_A5A5L

    /** Confidence interval for the median of a single sample. */
    public fun medianCi(
        values: DoubleArray,
        iterations: Int = DEFAULT_ITERATIONS,
        confidence: Double = DEFAULT_CONFIDENCE,
        seed: Long = DEFAULT_SEED,
    ): ConfidenceInterval = statisticCi(values, iterations, confidence, seed) { Quantiles.median(it) }

    /**
     * Confidence interval for median(b) / median(a), resampling both arms independently.
     *
     * For frametimes this ratio *is* the speedup of a over b: if a is faster its frametimes are
     * lower, so the ratio rises above 1. The headline percentage the UI shows and this interval
     * are the same quantity, which is what keeps the two from contradicting each other.
     */
    public fun medianRatioCi(
        a: DoubleArray,
        b: DoubleArray,
        iterations: Int = DEFAULT_ITERATIONS,
        confidence: Double = DEFAULT_CONFIDENCE,
        seed: Long = DEFAULT_SEED,
    ): ConfidenceInterval {
        require(a.isNotEmpty() && b.isNotEmpty()) { "both samples must be non-empty" }
        require(iterations > 0) { "iterations must be positive" }
        val rng = SplitMix64(seed)
        val draws = DoubleArray(iterations)
        val bufferA = DoubleArray(a.size)
        val bufferB = DoubleArray(b.size)
        for (i in 0 until iterations) {
            for (j in a.indices) bufferA[j] = a[rng.nextInt(a.size)]
            for (j in b.indices) bufferB[j] = b[rng.nextInt(b.size)]
            val medianA = Quantiles.median(bufferA)
            draws[i] = if (medianA == 0.0) Double.NaN else Quantiles.median(bufferB) / medianA
        }
        val point = Quantiles.median(a).let { if (it == 0.0) Double.NaN else Quantiles.median(b) / it }
        return intervalOf(draws, point, confidence)
    }

    public fun statisticCi(
        values: DoubleArray,
        iterations: Int = DEFAULT_ITERATIONS,
        confidence: Double = DEFAULT_CONFIDENCE,
        seed: Long = DEFAULT_SEED,
        statistic: (DoubleArray) -> Double,
    ): ConfidenceInterval {
        require(values.isNotEmpty()) { "cannot bootstrap an empty sample" }
        require(iterations > 0) { "iterations must be positive" }
        require(confidence > 0.0 && confidence < 1.0) { "confidence must be in (0, 1)" }
        val rng = SplitMix64(seed)
        val draws = DoubleArray(iterations)
        val buffer = DoubleArray(values.size)
        for (i in 0 until iterations) {
            for (j in values.indices) buffer[j] = values[rng.nextInt(values.size)]
            draws[i] = statistic(buffer)
        }
        return intervalOf(draws, statistic(values), confidence)
    }

    private fun intervalOf(draws: DoubleArray, point: Double, confidence: Double): ConfidenceInterval {
        val sorted = draws.sortedArray()
        val tail = (1.0 - confidence) / 2.0
        return ConfidenceInterval(
            point = point,
            low = Quantiles.ofSorted(sorted, tail),
            high = Quantiles.ofSorted(sorted, 1.0 - tail),
            confidence = confidence,
        )
    }
}
