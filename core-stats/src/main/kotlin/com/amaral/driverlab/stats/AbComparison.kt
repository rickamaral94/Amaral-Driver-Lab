package com.amaral.driverlab.stats

import kotlin.math.abs
import kotlin.math.max

/** Which side won, if either. "Faster" is shorthand for "better on this metric". */
public enum class Verdict { A_FASTER, B_FASTER, TECHNICAL_TIE, INCONCLUSIVE }

/** Why [AbComparison] landed on the verdict it did. Every verdict carries one. */
public enum class VerdictReason {
    /** Significant, and the effect is large enough to be worth naming a winner. */
    SIGNIFICANT_EFFECT,

    /**
     * The whole interval sits inside the harness's own noise floor. Whatever difference exists
     * is smaller than the smallest difference this harness claims it can resolve, so calling a
     * winner would be reporting our own measurement error as a property of the driver.
     */
    WITHIN_NOISE_FLOOR,

    /** The test found no evidence of a difference and the interval is tight enough to say so. */
    NOT_SIGNIFICANT,

    /**
     * Significant, but Cliff's delta is negligible. With enough runs any two samples separate;
     * a difference this small is not a difference anyone can feel.
     */
    NEGLIGIBLE_EFFECT,

    /** Not significant, but the interval is too wide to call it a tie either. Run it again. */
    HIGH_VARIANCE,

    /**
     * The rank test and the bootstrap interval point different ways. Rather than show two
     * numbers that contradict each other, the comparison refuses to conclude.
     */
    ESTIMATORS_DISAGREE,

    /** Fewer independent runs than the protocol requires. */
    INSUFFICIENT_RUNS,
}

public data class AbConfig(
    val alpha: Double = 0.05,
    /** Section 7 of the spec: at least five independent runs per arm, interleaved. */
    val minimumRunsPerArm: Int = 5,
    /**
     * The smallest relative difference this harness claims to resolve. Any interval that fits
     * entirely inside ±this is a technical tie whatever the p value says.
     *
     * The default is a claim, not a measurement. [NullTest.calibrate] measures the real figure
     * for a given device and [RankingGate.configFor] widens the claim to match it — a harness
     * never gets to claim better resolution than its own A/A runs demonstrated.
     */
    val minimumPracticalDifference: Double = DEFAULT_MINIMUM_PRACTICAL_DIFFERENCE,
    /**
     * How wide the ratio interval may be and still count as a tie rather than as noise.
     * 0.10 means the 95% interval spans at most ten percentage points of relative speed.
     */
    val maximumTieIntervalWidth: Double = 0.10,
    val bootstrapIterations: Int = Bootstrap.DEFAULT_ITERATIONS,
    val bootstrapSeed: Long = Bootstrap.DEFAULT_SEED,
) {
    init {
        require(alpha > 0.0 && alpha < 1.0) { "alpha must be in (0, 1)" }
        require(minimumRunsPerArm >= 1) { "minimumRunsPerArm must be at least 1" }
        require(minimumPracticalDifference >= 0.0) { "minimumPracticalDifference cannot be negative" }
    }

    public companion object {
        /** 2%. Below this, run-to-run drift on a phone is indistinguishable from a driver change. */
        public const val DEFAULT_MINIMUM_PRACTICAL_DIFFERENCE: Double = 0.02
    }
}

public data class AbResult(
    val labelA: String,
    val labelB: String,
    val verdict: Verdict,
    val reason: VerdictReason,
    val runsA: Int,
    val runsB: Int,
    val medianA: Double,
    val medianB: Double,
    /** Above 1.0 means A is better on this metric. Same quantity the interval brackets. */
    val speedupOfA: Double,
    val speedupInterval: ConfidenceInterval,
    val mannWhitney: MannWhitneyResult,
    val cliffsDelta: CliffsDeltaResult,
    /** The noise floor this verdict was judged against, carried so a report can be re-read later. */
    val noiseFloor: Double,
    /** False when the device's null test has not passed. Set by [RankingGate], not derived here. */
    val trustworthy: Boolean = true,
) {
    /** Signed percentage: +7.2 means A is 7.2% faster than B. */
    public val percentDifference: Double get() = (speedupOfA - 1.0) * 100.0

    public val blockedByNullTest: Boolean get() = !trustworthy

    /** How far the interval strays from parity — the A/A dispersion when both arms are one driver. */
    public val intervalDistanceFromParity: Double
        get() = max(abs(speedupInterval.low - 1.0), abs(speedupInterval.high - 1.0))
}

/**
 * Compares two arms of an interleaved A/B protocol.
 *
 * Each element of [a] and [b] is one independent run reduced to a single number — typically the
 * median frametime of that run. Do not pass raw frames: see [MannWhitneyU].
 */
public object AbComparison {

    public fun compare(
        labelA: String,
        labelB: String,
        a: DoubleArray,
        b: DoubleArray,
        lowerIsBetter: Boolean = true,
        config: AbConfig = AbConfig(),
    ): AbResult {
        require(a.isNotEmpty() && b.isNotEmpty()) { "both arms must have at least one run" }

        val medianA = Quantiles.median(a)
        val medianB = Quantiles.median(b)

        // One quantity, one interval. Orienting the ratio here rather than in the UI is what
        // stops the headline number and the interval from disagreeing later.
        val interval = if (lowerIsBetter) {
            Bootstrap.medianRatioCi(a, b, config.bootstrapIterations, seed = config.bootstrapSeed)
        } else {
            Bootstrap.medianRatioCi(b, a, config.bootstrapIterations, seed = config.bootstrapSeed)
        }

        val mannWhitney = MannWhitneyU.test(a, b)
        val cliffs = CliffsDelta.of(a, b)

        // Cliff's delta is computed on raw values, so its sign follows the metric, not the winner.
        val aIsBetter = if (lowerIsBetter) medianA < medianB else medianA > medianB

        val floor = config.minimumPracticalDifference
        val result = { verdict: Verdict, reason: VerdictReason ->
            AbResult(
                labelA = labelA,
                labelB = labelB,
                verdict = verdict,
                reason = reason,
                runsA = a.size,
                runsB = b.size,
                medianA = medianA,
                medianB = medianB,
                speedupOfA = interval.point,
                speedupInterval = interval,
                mannWhitney = mannWhitney,
                cliffsDelta = cliffs,
                noiseFloor = floor,
            )
        }

        if (a.size < config.minimumRunsPerArm || b.size < config.minimumRunsPerArm) {
            return result(Verdict.INCONCLUSIVE, VerdictReason.INSUFFICIENT_RUNS)
        }

        // Equivalence comes first, and deliberately outranks the p value. A rank test on five runs
        // reaches significance whenever the two arms happen not to interleave, which says nothing
        // about how large the difference is. If the entire interval fits within the noise floor,
        // the honest answer is "the same", however the ranks fell.
        val withinNoiseFloor = interval.low >= 1.0 - floor && interval.high <= 1.0 + floor
        if (withinNoiseFloor) {
            return result(Verdict.TECHNICAL_TIE, VerdictReason.WITHIN_NOISE_FLOOR)
        }

        val significant = mannWhitney.pValue < config.alpha
        val intervalExcludesParity = !interval.contains(1.0)

        if (significant && cliffs.negligible) {
            return result(Verdict.TECHNICAL_TIE, VerdictReason.NEGLIGIBLE_EFFECT)
        }
        if (significant != intervalExcludesParity) {
            // One estimator sees a difference and the other does not. Showing both would put two
            // contradictory claims side by side, which is exactly what P5 forbids.
            return result(Verdict.INCONCLUSIVE, VerdictReason.ESTIMATORS_DISAGREE)
        }
        if (significant) {
            return result(
                if (aIsBetter) Verdict.A_FASTER else Verdict.B_FASTER,
                VerdictReason.SIGNIFICANT_EFFECT,
            )
        }
        return if (interval.relativeWidth <= config.maximumTieIntervalWidth) {
            result(Verdict.TECHNICAL_TIE, VerdictReason.NOT_SIGNIFICANT)
        } else {
            result(Verdict.INCONCLUSIVE, VerdictReason.HIGH_VARIANCE)
        }
    }
}
