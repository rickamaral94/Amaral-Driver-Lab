package com.amaral.driverlab.stats

/**
 * What the device demonstrated about its own resolution, measured on A/A data that is *not*
 * the data the null test is judged on.
 *
 * Measuring the floor and judging against it on the same samples would make the null test pass
 * by construction, which is worse than not running it. So calibration consumes its own runs and
 * hands forward only a number.
 */
public data class NoiseFloorCalibration(
    val comparisons: List<AbResult>,
    /** Relative difference below which this device cannot tell a driver apart from itself. */
    val floor: Double,
    val safetyFactor: Double,
) {
    public val runsUsed: Int = comparisons.sumOf { it.runsA + it.runsB }

    public fun describe(): String =
        "Measured on ${comparisons.size} A/A comparisons: this device resolves differences of about " +
            "${"%.1f".format(floor * 100)}% or larger. Anything smaller is reported as a technical tie."

    public companion object {
        /** For a first run, before any calibration exists: claim only the default resolution. */
        public fun assumed(config: AbConfig = AbConfig()): NoiseFloorCalibration =
            NoiseFloorCalibration(emptyList(), config.minimumPracticalDifference, safetyFactor = 1.0)
    }
}

/**
 * The A/A test, and the gate it controls.
 *
 * The same `.so` is run against itself. Any verdict other than a technical tie means the harness
 * is manufacturing differences — thermal drift between arms, a warmup that is too short, an
 * ordering effect — and every A/B number it produces is suspect. Until this passes, ranking is
 * blocked, not merely annotated.
 */
public data class NullTestResult(
    val comparisons: List<AbResult>,
    val requiredConsecutivePasses: Int,
    val driverSha256: String,
    val calibration: NoiseFloorCalibration,
) {
    /** Indices of the comparisons that did not come back as a technical tie. */
    public val failedIndices: List<Int> =
        comparisons.indices.filter { comparisons[it].verdict != Verdict.TECHNICAL_TIE }

    public val completedPasses: Int = comparisons.size

    /**
     * How many comparisons put the first arm ahead, and how surprising that split is under a fair
     * coin. A driver compared with itself has no reason to prefer either arm; a one-sided split
     * means the protocol has an ordering effect that calibration would silently absorb.
     */
    public val firstArmWins: Int = comparisons.count { it.speedupOfA > 1.0 }

    public val directedComparisons: Int = comparisons.count { it.speedupOfA != 1.0 }

    public val orderingBiasP: Double = SignTest.twoSidedP(firstArmWins, directedComparisons)

    public val hasOrderingBias: Boolean =
        completedPasses >= requiredConsecutivePasses && orderingBiasP < NullTest.ORDERING_BIAS_ALPHA

    /**
     * A floor this coarse means the device is too unstable to compare drivers on, even though
     * every comparison technically tied — it tied because the harness cannot see anything.
     */
    public val floorTooCoarse: Boolean = calibration.floor > NullTest.MAXIMUM_USABLE_NOISE_FLOOR

    public val passed: Boolean =
        completedPasses >= requiredConsecutivePasses &&
            failedIndices.isEmpty() &&
            !hasOrderingBias &&
            !floorTooCoarse

    /** The floor the comparisons were judged against. */
    public val appliedNoiseFloor: Double = calibration.floor

    /**
     * The widest apparent difference the harness produced between a driver and itself during the
     * null test proper. If this exceeds [appliedNoiseFloor], the device got less stable after
     * calibration and the run should be repeated.
     */
    public val observedNoiseFloor: Double =
        comparisons.maxOfOrNull { it.intervalDistanceFromParity } ?: 0.0

    /**
     * Why the test came out the way it did.
     *
     * The two decisive failures are checked before incompleteness, because a run that stopped
     * as soon as its outcome was fixed is *short on purpose*. Reporting it as "incomplete"
     * would name the symptom instead of the reason and invite the user to run it again to
     * find out something already known.
     */
    public fun explain(): String = when {
        floorTooCoarse ->
            "Null test failed: this device only resolves differences of about " +
                "${"%.1f".format(calibration.floor * 100)}%, past the " +
                "${"%.0f".format(NullTest.MAXIMUM_USABLE_NOISE_FLOOR * 100)}% limit. Every A/A comparison " +
                "tied because the harness cannot see anything, not because the device is steady. " +
                "Cool the device down, close other apps, or raise the number of runs per arm."
        failedIndices.isNotEmpty() -> {
            val worst = failedIndices.joinToString(", ") { index ->
                val c = comparisons[index]
                "#${index + 1} ${c.verdict} (${c.reason})"
            }
            "Null test failed on: $worst. The harness is separating a driver from itself, " +
                "so no ranking is trustworthy."
        }
        comparisons.size < requiredConsecutivePasses ->
            "Null test incomplete: ${comparisons.size} of $requiredConsecutivePasses A/A comparisons done."
        hasOrderingBias ->
            "Null test failed: the first arm won $firstArmWins of $directedComparisons comparisons " +
                "(sign test p=${"%.4f".format(orderingBiasP)}). A driver compared with itself should " +
                "split evenly, so the protocol has an ordering effect — usually drift between arms " +
                "that interleaving has not cancelled."
        else ->
            "Null test passed: $requiredConsecutivePasses consecutive A/A comparisons all returned a " +
                "technical tie against a ${"%.1f".format(appliedNoiseFloor * 100)}% noise floor. " +
                "Widest self-difference seen: ${"%.1f".format(observedNoiseFloor * 100)}%."
    }
}

public object NullTest {

    /** Section 13, phase 1: ten consecutive A/A comparisons must all be technical ties. */
    public const val REQUIRED_CONSECUTIVE_PASSES: Int = 10

    /**
     * How many A/A comparisons are spent measuring the floor before the null test proper.
     *
     * Ten rather than five, because [CALIBRATION_QUANTILE] over five samples is simply the
     * maximum. On a device whose run medians snap to discrete DVFS bins, that made the floor
     * depend on whether any one of five comparisons happened to straddle two bins: one that
     * did produced a 27.7% floor, and five that did not would have produced the 2% default.
     * A number that swings that far on luck is not an estimate. Measured on an Odin2 —
     * docs/STATISTICS.md, finding 5.
     */
    public const val CALIBRATION_COMPARISONS: Int = 10

    /**
     * A/A comparisons run and thrown away before calibration begins.
     *
     * The GPU spends the first minutes of a session in a different frequency step: on an
     * Adreno 740, seven of the eight slowest runs in a 150-run session were in the first four
     * minutes. Measuring that ramp-up and calling it the device's resolution is the same
     * mistake as timing a workload's first frame, which is why every workload already has
     * warmup frames. This is the same idea one level up.
     */
    public const val WARMUP_COMPARISONS: Int = 1

    /**
     * Margin over the calibrated dispersion. A device whose A/A comparisons landed within 3.5%
     * is not credited with resolving 3.5% exactly — the next ten comparisons have to fit too.
     */
    public const val CALIBRATION_SAFETY_FACTOR: Double = 1.5

    // The floor is the *largest* dispersion calibration actually saw, not a high quantile of
    // it. A quantile is unstable in both directions here and the instability is not
    // symmetric: over five comparisons the 0.95 quantile is simply the maximum, and over ten
    // it interpolates a single bin-straddle away to nearly nothing — an Odin2 that invented
    // an 11.8% difference out of nothing one time in ten came out claiming it resolved 9.7%,
    // which is a claim its own calibration data contradicts. A difference the device was seen
    // to manufacture is a difference it can manufacture; averaging that observation down is
    // how a harness ends up ranking its own noise. See docs/STATISTICS.md, finding 5.

    /**
     * Past this, the calibrated floor is too coarse to compare drivers with and the null test
     * fails. Without this limit a wildly unstable device would "pass" by calibrating itself a
     * floor so wide that nothing could ever fall outside it.
     */
    public const val MAXIMUM_USABLE_NOISE_FLOOR: Double = 0.10

    /** Significance level for the ordering-bias sign test. */
    public const val ORDERING_BIAS_ALPHA: Double = 0.05

    /**
     * Measures the device's resolution. Produces no verdict — a calibration run cannot fail,
     * it can only report a floor that is too coarse to be useful.
     */
    public fun calibrate(
        arms: List<Pair<DoubleArray, DoubleArray>>,
        config: AbConfig = AbConfig(),
        safetyFactor: Double = CALIBRATION_SAFETY_FACTOR,
    ): NoiseFloorCalibration {
        require(arms.isNotEmpty()) { "calibration needs at least one A/A comparison" }
        val comparisons = arms.mapIndexed { index, (first, second) ->
            AbComparison.compare(
                labelA = "calibration #${index + 1} arm 1",
                labelB = "calibration #${index + 1} arm 2",
                a = first,
                b = second,
                lowerIsBetter = true,
                config = config,
            )
        }
        val measured = comparisons.maxOf { it.intervalDistanceFromParity } * safetyFactor
        return NoiseFloorCalibration(
            comparisons = comparisons,
            floor = maxOf(config.minimumPracticalDifference, measured),
            safetyFactor = safetyFactor,
        )
    }

    /**
     * @param arms pairs of run-summary arrays, each pair being one A/A comparison of a driver
     *   against itself. Both halves must come from the same `.so`, and must be runs that
     *   calibration did not already see.
     * @param calibration the floor to judge against; [NoiseFloorCalibration.assumed] when the
     *   device has never been calibrated, which claims only the default resolution.
     */
    public fun evaluate(
        driverSha256: String,
        arms: List<Pair<DoubleArray, DoubleArray>>,
        calibration: NoiseFloorCalibration = NoiseFloorCalibration.assumed(),
        required: Int = REQUIRED_CONSECUTIVE_PASSES,
        config: AbConfig = AbConfig(),
    ): NullTestResult {
        val judged = config.copy(
            minimumPracticalDifference = maxOf(config.minimumPracticalDifference, calibration.floor),
        )
        val comparisons = arms.mapIndexed { index, (first, second) ->
            AbComparison.compare(
                labelA = "A/A #${index + 1} arm 1",
                labelB = "A/A #${index + 1} arm 2",
                a = first,
                b = second,
                lowerIsBetter = true,
                config = judged,
            )
        }
        return NullTestResult(comparisons, required, driverSha256, calibration.copy(floor = judged.minimumPracticalDifference))
    }
}

/**
 * Ranking is a derived product of a harness that has proved it does not invent differences.
 * Every path that would show a score or an ordering asks this first.
 */
public class RankingGate(private val nullTestResult: NullTestResult?) {

    public val allowed: Boolean get() = nullTestResult?.passed == true

    /** The resolution any comparison shown through this gate is entitled to claim. */
    public val noiseFloor: Double =
        nullTestResult?.appliedNoiseFloor ?: AbConfig.DEFAULT_MINIMUM_PRACTICAL_DIFFERENCE

    public fun blockReason(): String? = when {
        nullTestResult == null -> "No null test has been run on this device yet."
        !nullTestResult.passed -> nullTestResult.explain()
        else -> null
    }

    /** Stamps a comparison with the gate's state so the UI never has to remember to check. */
    public fun stamp(result: AbResult): AbResult = result.copy(trustworthy = allowed)

    /** The config a trustworthy A/B comparison must use on this device. */
    public fun configFor(base: AbConfig = AbConfig()): AbConfig =
        base.copy(minimumPracticalDifference = maxOf(base.minimumPracticalDifference, noiseFloor))
}
