package com.amaral.driverlab.stats

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Synthetic runs that look like a real device: a per-run median that wanders slightly, plus an
 * optional thermal drift so tests can reproduce the failure the null test exists to catch.
 */
internal class Simulation(seed: Long) {
    private val rng = SplitMix64(seed)

    /** Box-Muller, one value per call. Uniforms are pulled from the same seeded stream. */
    fun gaussian(mean: Double, sd: Double): Double {
        var u1 = rng.nextInt(1_000_000_000) / 1_000_000_000.0
        if (u1 <= 0.0) u1 = 1e-12
        val u2 = rng.nextInt(1_000_000_000) / 1_000_000_000.0
        return mean + sd * sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    /**
     * @param baseNs the driver's true median frametime
     * @param runNoise per-run relative spread, e.g. 0.01 for a device whose runs land within ~1%
     * @param driftPerRun relative slowdown added to each successive run, modelling a device that
     *   heats up across the protocol
     */
    fun runMedians(
        count: Int,
        baseNs: Double,
        runNoise: Double,
        driftPerRun: Double = 0.0,
        startIndex: Int = 0,
    ): DoubleArray = DoubleArray(count) { i ->
        val drift = 1.0 + driftPerRun * (startIndex + i)
        gaussian(baseNs * drift, baseNs * runNoise)
    }

    /**
     * The protocol the runner actually uses: pairs alternate AB, BA, AB, BA so each arm spends
     * the same time in the early, cool slots and the late, hot ones.
     *
     * With an odd number of runs per arm the pairs cannot balance exactly — one arm still leads
     * once more than the other — so [comparisonIndex] flips which arm leads on each successive
     * comparison, and the leftover cancels across the ten comparisons instead of accumulating.
     */
    fun counterbalancedAaPair(
        runsPerArm: Int,
        baseNs: Double,
        runNoise: Double,
        driftPerRun: Double = 0.0,
        comparisonIndex: Int = 0,
    ): Pair<DoubleArray, DoubleArray> = pairFromSlots(
        runsPerArm, baseNs, runNoise, driftPerRun,
    ) { pairIndex -> (pairIndex + comparisonIndex) % 2 == 0 }

    /**
     * Plain A,B,A,B alternation. Better than running the arms back to back, and still not enough:
     * the first arm takes the earlier slot of *every* pair, so under drift it wins every time.
     * Kept so the tests can show why counterbalancing is the protocol.
     */
    fun interleavedAaPair(
        runsPerArm: Int,
        baseNs: Double,
        runNoise: Double,
        driftPerRun: Double = 0.0,
    ): Pair<DoubleArray, DoubleArray> = pairFromSlots(
        runsPerArm, baseNs, runNoise, driftPerRun,
    ) { true }

    /** The same protocol run wrongly: all of A first, then all of B, so drift becomes "B is slower". */
    fun sequentialAaPair(
        runsPerArm: Int,
        baseNs: Double,
        runNoise: Double,
        driftPerRun: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val armA = runMedians(runsPerArm, baseNs, runNoise, driftPerRun, startIndex = 0)
        val armB = runMedians(runsPerArm, baseNs, runNoise, driftPerRun, startIndex = runsPerArm)
        return armA to armB
    }

    /** @param aFirst whether arm A takes the earlier of the two slots in the given pair. */
    private fun pairFromSlots(
        runsPerArm: Int,
        baseNs: Double,
        runNoise: Double,
        driftPerRun: Double,
        aFirst: (Int) -> Boolean,
    ): Pair<DoubleArray, DoubleArray> {
        val armA = DoubleArray(runsPerArm)
        val armB = DoubleArray(runsPerArm)
        for (i in 0 until runsPerArm) {
            val early = runMedians(1, baseNs, runNoise, driftPerRun, startIndex = 2 * i)[0]
            val late = runMedians(1, baseNs, runNoise, driftPerRun, startIndex = 2 * i + 1)[0]
            if (aFirst(i)) {
                armA[i] = early
                armB[i] = late
            } else {
                armA[i] = late
                armB[i] = early
            }
        }
        return armA to armB
    }
}
