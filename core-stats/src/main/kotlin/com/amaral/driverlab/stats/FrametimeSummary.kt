package com.amaral.driverlab.stats

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Everything derived from the frametime series of a single workload execution.
 *
 * Frametimes stay in nanoseconds all the way through. FPS is a presentation format, produced
 * at the very last moment by [nsToFps], and never an input to a statistic.
 *
 * ## Two questions, two statistics
 *
 * [medianNs] and the percentiles answer *what does a typical frame feel like* — the smoothness
 * story, where a median is exactly right because one hitch must not redefine "typical".
 *
 * [trimmedMeanNs] answers *how long did the fixed work take* — the throughput story, and it is
 * the only one the comparison, the floor and the score are allowed to read. The median cannot
 * do that job on this hardware, and the difference is not academic:
 *
 * An Adreno 740 hops between DVFS steps *during* a run, so a run's frametimes are multimodal
 * and its median reports whichever step happened to hold the middle sample. Two runs at 50.5%
 * and 49.5% slow frames — a fraction of a percent apart in real cost — report medians a whole
 * step apart. Measured on the reference device: the medians of 122 runs landed on the ladder
 * `6.12 / 6.77 / 7.57 / 8.76 ms` and nowhere between, and one A/A comparison of the system
 * driver against itself, at flat temperature under correct counterbalancing, reported its two
 * arms 11.8% apart. Their mean frametimes were 4.4% apart, and the ratio of median to mean
 * within a run swung from 0.84 to 1.09 — the median was not tracking cost at all.
 *
 * Trimming rather than a plain mean keeps the property the median was chosen for: a single
 * 500 ms hitch moves a 1000-frame mean by about 6% and moves this by nothing.
 */
public data class FrametimeSummary(
    val frameCount: Int,
    val medianNs: Double,
    val meanNs: Double,
    /**
     * Mean frametime with the fastest and slowest [TRIM_FRACTION] of frames dropped.
     *
     * **This is what the A/B comparison, the noise floor and the anchor score are computed
     * on**, and [medianNs] is not — see the class comment for the measurement that forced
     * the split.
     */
    val trimmedMeanNs: Double,
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

        /**
         * Dropped from each tail before averaging for [trimmedMeanNs].
         *
         * Enough to absorb the hitches a median was protecting against, small enough that the
         * fast/slow mix a multimodal run is made of still moves the number continuously. Both
         * tails, so the statistic stays symmetric and trimming cannot flatter a driver.
         */
        public const val TRIM_FRACTION: Double = 0.05

        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MINUTE = 60.0 * NANOS_PER_SECOND

        public fun nsToFps(ns: Double): Double = if (ns <= 0.0) 0.0 else NANOS_PER_SECOND / ns

        /**
         * @param sorted frametimes in ascending order.
         * @return the mean of what is left after [TRIM_FRACTION] is dropped from each end, or
         *   the untrimmed mean when the series is too short to spare anything. Short series
         *   keep every frame rather than silently becoming a different statistic.
         */
        internal fun trimmedMeanOfSorted(sorted: DoubleArray): Double {
            val cut = (sorted.size * TRIM_FRACTION).toInt()
            if (sorted.size - 2 * cut < 1) return sorted.average()
            var total = 0.0
            for (i in cut until sorted.size - cut) total += sorted[i]
            return total / (sorted.size - 2 * cut)
        }

        /**
         * @param frametimesNs one entry per presented frame, in submission order. Order matters:
         *   jitter and the thermal split both read it as a time series, not as a bag of numbers.
         */
        public fun of(frametimesNs: LongArray): FrametimeSummary {
            require(frametimesNs.isNotEmpty()) { "a workload must report at least one frame" }
            // Guards every ratio downstream: a median of zero turns a speedup into NaN, and a
            // NaN cannot be published at all. A frame that took no time did not happen.
            require(frametimesNs.any { it > 0L }) {
                "a workload reported ${frametimesNs.size} frames that all took zero time; " +
                    "that is a failed measurement, not a fast one"
            }
            val values = DoubleArray(frametimesNs.size) { frametimesNs[it].toDouble() }
            val sorted = values.sortedArray()

            val median = Quantiles.medianOfSorted(sorted)
            val mean = values.average()
            val trimmedMean = trimmedMeanOfSorted(sorted)
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
                trimmedMeanNs = trimmedMean,
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
