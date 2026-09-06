package com.amaral.driverlab.stats

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Everything derived from the frametime series of a single workload execution.
 *
 * Frametimes stay in nanoseconds all the way through. FPS is a presentation format, produced
 * at the very last moment by [nsToFps], and never an input to a statistic.
 */
public data class FrametimeSummary(
    val frameCount: Int,
    val medianNs: Double,
    val meanNs: Double,
    val stdDevNs: Double,
    /** 95th percentile of frametime — the slow tail, not the fast one. */
    val p95Ns: Double,
    /** 99th percentile of frametime. Displayed as "1% low". */
    val p99Ns: Double,
    /** 99.9th percentile of frametime. Displayed as "0.1% low". */
    val p999Ns: Double,
    /** Mean of |Δframetime| between consecutive frames. */
    val jitterNs: Double,
    /** Frames slower than [STUTTER_FACTOR] × median. */
    val stutterCount: Int,
    val stuttersPerMinute: Double,
    /** Median of the last third — what the device actually sustains once it has heated up. */
    val sustainedMedianNs: Double,
    /** Median of the first third, kept so degradation can be shown rather than asserted. */
    val openingMedianNs: Double,
    val totalDurationNs: Long,
) {
    /**
     * Positive means the last third is slower than the first third, i.e. the device degraded.
     * Reported as a fraction, so 0.12 is a 12% slowdown.
     */
    public val thermalDegradation: Double
        get() = if (openingMedianNs <= 0.0) 0.0 else (sustainedMedianNs - openingMedianNs) / openingMedianNs

    public companion object {
        /** A frame is a stutter when it takes more than twice the median. */
        public const val STUTTER_FACTOR: Double = 2.0

        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MINUTE = 60.0 * NANOS_PER_SECOND

        public fun nsToFps(ns: Double): Double = if (ns <= 0.0) 0.0 else NANOS_PER_SECOND / ns

        /**
         * @param frametimesNs one entry per presented frame, in submission order. Order matters:
         *   jitter and the thermal split both read it as a time series, not as a bag of numbers.
         */
        public fun of(frametimesNs: LongArray): FrametimeSummary {
            require(frametimesNs.isNotEmpty()) { "a workload must report at least one frame" }
            val values = DoubleArray(frametimesNs.size) { frametimesNs[it].toDouble() }
            val sorted = values.sortedArray()

            val median = Quantiles.medianOfSorted(sorted)
            val mean = values.average()
            val variance = if (values.size < 2) {
                0.0
            } else {
                values.sumOf { val d = it - mean; d * d } / (values.size - 1)
            }

            var jitterAccumulator = 0.0
            for (i in 1 until values.size) jitterAccumulator += abs(values[i] - values[i - 1])
            val jitter = if (values.size < 2) 0.0 else jitterAccumulator / (values.size - 1)

            val stutterThreshold = median * STUTTER_FACTOR
            val stutters = values.count { it > stutterThreshold }
            val total = frametimesNs.sum()
            val minutes = total / NANOS_PER_MINUTE

            val third = values.size / 3
            val opening = if (third == 0) median else Quantiles.median(values.copyOfRange(0, third))
            val sustained = if (third == 0) median else Quantiles.median(values.copyOfRange(values.size - third, values.size))

            return FrametimeSummary(
                frameCount = values.size,
                medianNs = median,
                meanNs = mean,
                stdDevNs = sqrt(variance),
                p95Ns = Quantiles.ofSorted(sorted, 0.95),
                p99Ns = Quantiles.ofSorted(sorted, 0.99),
                p999Ns = Quantiles.ofSorted(sorted, 0.999),
                jitterNs = jitter,
                stutterCount = stutters,
                stuttersPerMinute = if (minutes <= 0.0) 0.0 else stutters / minutes,
                sustainedMedianNs = sustained,
                openingMedianNs = opening,
                totalDurationNs = total,
            )
        }
    }
}
