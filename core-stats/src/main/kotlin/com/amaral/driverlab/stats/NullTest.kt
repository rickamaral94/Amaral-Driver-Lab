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

    /** True when no A/A comparison backs this number, so it is a default rather than a finding. */
    public val isAssumed: Boolean = comparisons.isEmpty()

    public fun describe(): String = if (isAssumed) {
        // This used to read "Measured on 0 A/A comparisons: this device resolves differences of
        // about 2.0%". A run that stopped before it could calibrate anything was reporting the
        // default claim as a measurement, and reporting it as the *best* result the app can
        // show — on screen, above the words "the calibration did not pass". The one number a
        // user takes away from that screen was both invented and flattering.
        "No A/A comparison has been completed on this device, so its resolution is unknown. " +
            "The ${"%.1f".format(floor * 100)}% below is the default the harness assumes, not " +
            "something this device demonstrated."
    } else {
        "Measured on ${comparisons.size} A/A comparisons: this device resolves differences of about " +
            "${"%.1f".format(floor * 100)}% or larger. Anything smaller is reported as a technical tie."
    }

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

    /**
     * How surprising the lean is, by Wilcoxon signed-rank on the log ratios.
     *
     * Signed-rank rather than a sign test on the same comparisons: the cross-validated pool
     * is half the length of the old split, and a sign test on ten values reads only direction
     * and misses most of a real ordering effect. [SignedRankTest] carries the measured
     * difference in power.
     */
    public val orderingBiasP: Double =
        SignedRankTest.twoSidedP(comparisons.map { kotlin.math.ln(it.speedupOfA) })

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
                "Cool the device down, close other apps, or raise the number of runs per arm." +
                // A coarse floor is a symptom and an ordering effect is a cause, so reporting
                // only the first tells the user to cool the device down when the actual problem
                // is that the schedule is letting drift land on one arm. When both are present
                // the cause is the useful half.
                if (hasOrderingBias) " $ORDERING_EFFECT_ALSO" else ""
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
        hasOrderingBias -> ORDERING_EFFECT
        else ->
            "Null test passed: $requiredConsecutivePasses consecutive A/A comparisons all returned a " +
                "technical tie against a ${"%.1f".format(appliedNoiseFloor * 100)}% noise floor. " +
                "Widest self-difference seen: ${"%.1f".format(observedNoiseFloor * 100)}%."
    }

    private val ORDERING_EFFECT: String
        get() = "Null test failed: the first arm came out ahead in $firstArmWins of " +
                "$directedComparisons comparisons, by enough that the lean is unlikely to be chance " +
                "(signed-rank p=${"%.4f".format(orderingBiasP)}). A driver compared with itself " +
                "should not favour a side, so the protocol has an ordering effect — usually drift " +
                "between arms that interleaving has not cancelled."

    private val ORDERING_EFFECT_ALSO: String
        get() = "There is also an ordering effect: the first arm led in $firstArmWins of " +
            "$directedComparisons comparisons (signed-rank p=${"%.4f".format(orderingBiasP)}), " +
            "which a driver compared with itself has no reason to do. Drift between the arms is " +
            "the likelier cause than the device being noisy."
}

public object NullTest {

    /**
     * Section 13, phase 1: this many consecutive A/A comparisons must all be technical ties.
     *
     * Six, not ten, and the direction is not a weakening. The floor is the **worst**
     * comparison's dispersion, so every comparison added is another chance to draw a bad one
     * and can only widen it, while every run added to a comparison narrows all of them. Ten
     * comparisons of five runs was the worst arrangement of its own budget: simulated at the
     * reference device's measured spread it calibrates a 22% floor, where eight of fifteen
     * calibrates about 10% — more device time, but spent on the axis that helps.
     *
     * Eight rather than fewer, because the ordering check is what stops the count falling
     * further and it was measured rather than guessed. Against plain alternation under drift —
     * the protocol finding 3 rejected — the check fires 24 times in 40 at eight comparisons,
     * 10 at six, and 27 at ten; on the correct counterbalanced protocol it fires 0 to 1 in 40
     * at every count. Six saves half an hour of device time and hands back most of finding 3's
     * safety net, which is not a trade worth making. Below five the check cannot fire at all:
     * Wilcoxon signed-rank on n comparisons has no two-sided p below 2/2^n.
     */
    public const val REQUIRED_CONSECUTIVE_PASSES: Int = 8


    /**
     * A/A comparisons run and thrown away before calibration begins. **Zero, on purpose.**
     *
     * This was one, to discard a cold ramp-up: seven of the eight slowest runs in a 150-run
     * session were in its first four minutes. Finding 8 showed that reading was backwards —
     * that session was not cold, it began the moment a 57-minute run ended, and a genuinely
     * cold device is the *fast* one. The justification was gone and nothing replaced it.
     *
     * Finding 9 then showed a mechanism by which it could actively hurt, and the run in
     * finding 11 is consistent with it: the first run after the discarded comparison was
     * 9.08 ms, the slowest of the twenty, and dropping it takes the session's run-to-run
     * spread from 5.7% to 3.9% — the difference between a shape that clears the 10% limit and
     * one that does not. That is one run and not proof.
     *
     * It goes on cost and absence of justification rather than on proof of harm: at fifteen
     * runs per arm it is thirty executions, about fifteen minutes, spent on a step whose only
     * stated reason has been refuted.
     */
    public const val WARMUP_COMPARISONS: Int = 0

    /**
     * Margin over the calibrated dispersion. A device whose A/A comparisons landed within 3.5%
     * is not credited with resolving 3.5% exactly — the next ten comparisons have to fit too.
     */
    public const val CALIBRATION_SAFETY_FACTOR: Double = 1.5

    // The floor is the *largest* dispersion calibration actually saw, not a high quantile of
    // it. Two properties matter, and only the second turned out to be the real reason.
    //
    // MONOTONIC. A maximum never falls as comparisons are added, so a partial calibration
    // whose floor already exceeds MAXIMUM_USABLE_NOISE_FLOOR can never come back under it.
    // That is what lets a run abandon itself mid-calibration instead of after the full
    // budget — on an Odin2 the first comparison already settled a seventy-minute test. A
    // quantile can fall as evidence accumulates, so swapping one in here would silently make
    // early exit able to abandon a run over a threshold its final answer never crossed.
    // `NullTestTest` pins this. A quantile is unstable in both directions here and the instability is not
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
     * The null test over a single pool of A/A comparisons, each judged against a floor
     * calibrated on the *others*.
     *
     * Measuring the floor and judging against it on the same runs would make the test pass by
     * construction — findings 1 and 2 — but that does not require two separate pools. Holding
     * one comparison out at a time is enough for none to judge itself, and it halves what the
     * device has to run: ten comparisons instead of ten to calibrate plus ten to test.
     *
     * It is also stricter than two pools, which is the part worth noticing. Under a split, the
     * widest calibration comparison inflates the floor that every test comparison is then
     * judged against, so it shelters them. Leaving each comparison out of its own floor means
     * the widest one is judged against a limit that excludes its own contribution: it cannot
     * hide behind itself.
     *
     * @param arms the whole pool, in the order it was acquired — the sign test reads direction
     *   from that order, so it must not be shuffled.
     */
    public fun crossValidated(
        driverSha256: String,
        arms: List<Pair<DoubleArray, DoubleArray>>,
        required: Int = REQUIRED_CONSECUTIVE_PASSES,
        config: AbConfig = AbConfig(),
    ): NullTestResult {
        // Leaving one out of a pool of one leaves nothing to calibrate on. A pool that short
        // is incomplete anyway, so it is reported against the default claim rather than
        // against a floor it cannot support.
        if (arms.size < 2) {
            return NullTestResult(
                comparisons = emptyList(),
                requiredConsecutivePasses = required,
                driverSha256 = driverSha256,
                calibration = NoiseFloorCalibration.assumed(config),
            )
        }

        val comparisons = arms.mapIndexed { index, (first, second) ->
            val others = arms.filterIndexed { other, _ -> other != index }
            val fold = calibrate(others, config)
            val judged = config.copy(
                minimumPracticalDifference =
                    maxOf(config.minimumPracticalDifference, fold.floor),
            )
            AbComparison.compare(
                labelA = "A/A #${index + 1} arm 1",
                labelB = "A/A #${index + 1} arm 2",
                a = first,
                b = second,
                lowerIsBetter = true,
                config = judged,
            )
        }

        // Reported against the whole pool, because that is the resolution the device
        // demonstrated overall — the per-fold floors are how each comparison was judged, not
        // what the device is entitled to claim afterwards.
        val overall = calibrate(arms, config)
        return NullTestResult(
            comparisons = comparisons,
            requiredConsecutivePasses = required,
            driverSha256 = driverSha256,
            calibration = overall.copy(
                floor = maxOf(config.minimumPracticalDifference, overall.floor),
            ),
        )
    }

    /**
     * Judges a pool against a floor supplied from outside.
     *
     * The primitive [crossValidated] is built on, and the way to test the gate's rules
     * against a floor chosen rather than measured. **Production goes through
     * [crossValidated]**: handing this function a floor calibrated on the same runs it then
     * judges is exactly the circularity findings 1 and 2 are about.
     *
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
