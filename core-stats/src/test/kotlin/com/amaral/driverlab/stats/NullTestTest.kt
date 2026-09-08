package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1's acceptance criterion, exercised at the level the harness can actually control:
 * given run summaries drawn from one driver, do ten consecutive A/A comparisons all come back
 * as technical ties?
 *
 * The simulated device has a 1% run-to-run spread, which is roughly what a phone with a settled
 * thermal state produces. Calibration and the null test proper always draw separate runs.
 */
class NullTestTest {

    private val config = AbConfig(bootstrapIterations = 1_500)
    private val baseNs = 16_600_000.0

    private enum class Order { COUNTERBALANCED, ALTERNATING, SEQUENTIAL }

    private class Device(
        val calibration: NoiseFloorCalibration,
        val result: NullTestResult,
    )

    private fun runDevice(
        seed: Long,
        runNoise: Double,
        driftPerRun: Double = 0.0,
        order: Order = Order.COUNTERBALANCED,
        // Must match BenchmarkPlan.DEFAULT_RUNS_PER_ARM, which :core-stats cannot see from
        // here. It was 5 while the app shipped 15, so every power figure in this file was
        // measured on a protocol nobody runs.
        runsPerArm: Int = 15,
        comparisons: Int = NullTest.REQUIRED_CONSECUTIVE_PASSES,
    ): Device {
        val sim = Simulation(seed)
        fun pair(index: Int) = when (order) {
            Order.COUNTERBALANCED -> sim.counterbalancedAaPair(runsPerArm, baseNs, runNoise, driftPerRun, index)
            Order.ALTERNATING -> sim.interleavedAaPair(runsPerArm, baseNs, runNoise, driftPerRun)
            Order.SEQUENTIAL -> sim.sequentialAaPair(runsPerArm, baseNs, runNoise, driftPerRun)
        }
        val arms = (0 until comparisons).map { pair(it) }
        val result = NullTest.crossValidated(
            driverSha256 = "sha256:test",
            arms = arms,
            config = config,
        )
        return Device(NullTest.calibrate(arms, config), result)
    }

    /**
     * A clean device should mostly be allowed to rank. Measured over 200 trials the rate is
     * 93.5%, down from about 95% under the old twenty-comparison split — the price of halving
     * what the device has to run, paid as an occasional false block rather than a false pass.
     * A blocked device can re-run; a falsely passed one pollutes a public leaderboard, so the
     * asymmetry is the right way round. The threshold is set well below the measured rate
     * because forty trials of a 93.5% process land between 34 and 40.
     */
    @Test
    fun `a clean harness usually passes ten consecutive A A comparisons`() {
        var passes = 0
        val trials = 40
        repeat(trials) { trial ->
            if (runDevice(seed = 1000L + trial, runNoise = 0.01).result.passed) passes++
        }
        assertTrue(
            "only $passes of $trials null tests passed; the harness would block ranking too often",
            passes >= 34,
        )
    }

    @Test
    fun `calibration reports a floor the device can actually hold`() {
        val device = runDevice(seed = 7L, runNoise = 0.01)
        assertTrue(device.result.passed, )
        // This used to assert the measured floor exceeded the 2% default, on the reasoning
        // that a 1% device sampled five runs per arm cannot honestly claim to resolve 2%.
        // Finding 13 made that false: with the floor taken from the spread of the comparisons
        // rather than from a bootstrap inside one, a 1% device measures about 1.4% and the
        // default becomes the binding claim. The floor may never be *finer* than the default,
        // which is what is actually worth pinning.
        assertTrue(
            "floor ${device.calibration.floor} may not undercut the default claim",
            device.calibration.floor >= AbConfig.DEFAULT_MINIMUM_PRACTICAL_DIFFERENCE,
        )
        assertTrue(
            "the null test's own dispersion ${device.result.observedNoiseFloor} should fit inside " +
                "the calibrated floor ${device.result.appliedNoiseFloor}",
            device.result.observedNoiseFloor <= device.result.appliedNoiseFloor,
        )
        assertTrue(device.calibration.describe().contains("%"))
    }

    @Test
    fun `more runs per arm buy a finer floor`() {
        // A 1% device is steady enough that the default claim binds at both sizes, so the
        // effect has to be measured on a device noisy enough for the measurement to matter.
        val coarse = runDevice(seed = 3L, runNoise = 0.06, runsPerArm = 5).calibration.floor
        val fine = runDevice(seed = 3L, runNoise = 0.06, runsPerArm = 15).calibration.floor
        assertTrue("5 runs gave $coarse, 15 runs gave $fine", fine < coarse)
    }

    /**
     * P7 in miniature: running all of A and then all of B lets thermal drift masquerade as a
     * driver difference. Interleaving the arms is what makes the same device pass.
     */
    /**
     * P7 in miniature, and the reason calibration alone is not enough. Running all of A and then
     * all of B makes drift look like a driver difference *reproducibly*, so the calibrated floor
     * absorbs it and every comparison still ties. The sign test is what catches it: the first arm
     * wins every time, which a driver compared with itself has no reason to do.
     */
    @Test
    fun `sequential arms let thermal drift look like a driver difference`() {
        val sequential = runDevice(
            seed = 31L, runNoise = 0.004, driftPerRun = 0.004, order = Order.SEQUENTIAL,
        )
        assertFalse(
            "a 0.4%-per-run drift run sequentially should not pass: ${sequential.result.explain()}",
            sequential.result.passed,
        )
        assertTrue(sequential.result.hasOrderingBias)
        assertTrue(sequential.result.explain().contains("ordering effect"))
    }

    /**
     * Plain A,B,A,B alternation is not enough, which is worth stating because it is the
     * obvious thing to build. The first arm takes the earlier slot of every pair, so under
     * drift it leans one way every time — by a hair, small enough for the calibrated floor to
     * swallow and systematic enough for the ordering check to see.
     *
     * Asserted as a *rate* rather than on one seed. The check has finite power — measured at
     * 70% against this much drift, against 2.5% on the counterbalanced protocol — so any
     * single seed is a coin toss and pinning the test to one made it fail on changes that did
     * not touch what it checks. The claim finding 3 actually makes is the gap between those
     * two rates, so that is what is tested.
     */
    @Test
    fun `plain alternation leaves an ordering effect that counterbalancing does not`() {
        var alternatingCaught = 0
        var counterbalancedCaught = 0
        val trials = 40
        repeat(trials) { trial ->
            if (runDevice(
                    seed = 31L + trial, runNoise = 0.004, driftPerRun = 0.004,
                    order = Order.ALTERNATING,
                ).result.hasOrderingBias
            ) {
                alternatingCaught++
            }
            if (runDevice(
                    seed = 31L + trial, runNoise = 0.004, driftPerRun = 0.004,
                    order = Order.COUNTERBALANCED,
                ).result.hasOrderingBias
            ) {
                counterbalancedCaught++
            }
        }

        assertTrue(
            "alternation flagged only $alternatingCaught of $trials; the ordering check is blind",
            alternatingCaught >= trials / 2,
        )
        assertTrue(
            "counterbalanced order flagged $counterbalancedCaught of $trials, which is too many " +
                "false alarms for a protocol that is not biased",
            counterbalancedCaught <= trials / 5,
        )
    }

    /** Counterbalancing the pairs AB, BA, AB, BA is what makes the same drifting device pass. */
    @Test
    fun `counterbalanced order survives the same drift`() {
        val counterbalanced = runDevice(
            seed = 31L, runNoise = 0.004, driftPerRun = 0.004, order = Order.COUNTERBALANCED,
        )
        assertTrue(
            "counterbalanced order should pass: ${counterbalanced.result.explain()}",
            counterbalanced.result.passed,
        )
    }

    @Test
    fun `drift steep enough to swamp interleaving is reported, not absorbed`() {
        // 1.2% per run is a device heating up mid-protocol. Interleaving shares the drift between
        // the arms but cannot remove it from either, so the run is refused rather than trusted.
        val steep = runDevice(seed = 41L, runNoise = 0.004, driftPerRun = 0.012)
        assertFalse(steep.result.passed)
    }

    /**
     * The circularity guard. A device this noisy calibrates itself an enormous floor, against
     * which every A/A comparison trivially ties. Passing on that basis would mean the harness
     * reports "steady" precisely when it has gone blind.
     */
    @Test
    fun `a harness that cannot hold still fails the null test`() {
        val device = runDevice(seed = 5L, runNoise = 0.25)
        assertFalse(device.result.passed)
        assertTrue(device.result.floorTooCoarse)
        assertTrue(device.result.explain().contains("only resolves differences"))
    }

    @Test
    fun `an unusable floor is refused even when every comparison tied`() {
        // Built directly rather than simulated, so the rule is tested and not the noise: ten clean
        // ties, judged against a floor of 50%, is still a refusal.
        val runs = doubleArrayOf(16.60, 16.71, 16.54, 16.68, 16.63)
        val coarse = NoiseFloorCalibration(
            comparisons = emptyList(),
            floor = 0.50,
            safetyFactor = NullTest.CALIBRATION_SAFETY_FACTOR,
        )
        val result = NullTest.evaluate(
            driverSha256 = "sha256:test",
            arms = List(NullTest.REQUIRED_CONSECUTIVE_PASSES) { runs.copyOf() to runs.copyOf() },
            calibration = coarse,
            config = config,
        )
        assertTrue("every comparison should tie", result.failedIndices.isEmpty())
        assertFalse(result.hasOrderingBias)
        assertTrue(result.floorTooCoarse)
        assertFalse(result.passed)
    }

    @Test
    fun `an incomplete null test does not pass`() {
        val device = runDevice(seed = 11L, runNoise = 0.01, comparisons = 4)
        assertFalse(device.result.passed)
        assertEquals(4, device.result.completedPasses)
        assertTrue(device.result.explain().contains("incomplete"))
    }

    @Test
    fun `the ranking gate blocks until the null test passes`() {
        val blocked = RankingGate(runDevice(seed = 5L, runNoise = 0.25).result)
        assertFalse(blocked.allowed)
        assertNotNull(blocked.blockReason())

        val passing = runDevice(seed = 1000L, runNoise = 0.01).result
        val open = RankingGate(passing)
        assertTrue(passing.explain(), open.allowed)
        assertNull(open.blockReason())
    }

    @Test
    fun `the gate with no null test at all blocks`() {
        val gate = RankingGate(null)
        assertFalse(gate.allowed)
        assertTrue(gate.blockReason()!!.contains("No null test"))
    }

    @Test
    fun `a blocked gate stamps every comparison as untrustworthy`() {
        val gate = RankingGate(runDevice(seed = 5L, runNoise = 0.25).result)
        val comparison = AbComparison.compare(
            "v3", "v4",
            doubleArrayOf(15.0, 15.1, 14.9, 15.05, 14.95),
            doubleArrayOf(16.5, 16.6, 16.4, 16.55, 16.45),
            config = config,
        )
        assertTrue(comparison.trustworthy)
        val stamped = gate.stamp(comparison)
        assertTrue(stamped.blockedByNullTest)
        assertEquals(Verdict.A_FASTER, stamped.verdict)
    }

    /**
     * The other half of the guarantee. A harness that ties everything would also pass a null
     * test, so the same simulated device must still detect a real regression.
     */
    @Test
    fun `the same harness settings still detect a real ten percent regression`() {
        val device = runDevice(seed = 77L, runNoise = 0.01)
        assertTrue(device.result.passed)
        val gateConfig = RankingGate(device.result).configFor(config)

        val sim = Simulation(78L)
        var detected = 0
        val trials = 30
        repeat(trials) {
            val a = sim.runMedians(5, baseNs, 0.01)
            val b = sim.runMedians(5, baseNs * 1.10, 0.01)
            if (AbComparison.compare("v3", "v4", a, b, config = gateConfig).verdict == Verdict.A_FASTER) detected++
        }
        assertTrue("only $detected of $trials real regressions were detected", detected >= trials - 2)
    }

    /**
     * The honest consequence of a calibrated floor: this device can back a 10% claim and cannot
     * back a 2% one, and it says so instead of picking a winner it cannot see.
     */
    @Test
    fun `a difference under the calibrated floor is reported as a tie`() {
        val device = runDevice(seed = 90L, runNoise = 0.01)
        assertTrue(device.result.passed)
        val gateConfig = RankingGate(device.result).configFor(config)

        val sim = Simulation(91L)
        val a = sim.runMedians(5, baseNs, 0.01)
        val b = sim.runMedians(5, baseNs * 1.02, 0.01)
        val result = AbComparison.compare("v3", "v4", a, b, config = gateConfig)
        assertEquals(
            "a 2% difference on a ${"%.1f".format(gateConfig.minimumPracticalDifference * 100)}% floor " +
                "must not name a winner",
            Verdict.TECHNICAL_TIE, result.verdict,
        )
    }
    /**
     * The property early exit used to rest on, and no longer does.
     *
     * The floor was the largest dispersion seen, which cannot fall, and that is what licensed
     * abandoning a run mid-calibration. Finding 13 made it the spread of the comparisons,
     * which *does* fall when a steady comparison is added — measured falling from 23.6% to
     * 19.5% on exactly the shape below. This pins the new behaviour so nothing quietly
     * reintroduces a stopping rule that assumes the old one.
     */
    @Test
    fun `the calibrated floor can fall as comparisons are added`() {
        // One wide comparison, then a run of tight ones. Under a maximum the wide one sets the
        // floor forever; under a spread it is diluted, which is the point of using a spread.
        val wide = DoubleArray(15) { 6_770_000.0 } to DoubleArray(15) { 7_570_000.0 }
        val tight = DoubleArray(15) { 7_570_000.0 } to DoubleArray(15) { 7_570_000.0 }
        val config = AbConfig(bootstrapIterations = 800)

        val floors = (3..10).map { count ->
            NullTest.calibrate(listOf(wide) + List(count - 1) { tight }, config).floor
        }

        assertTrue(
            "the floor should dilute as steady comparisons arrive, got $floors",
            floors.last() < floors.first(),
        )
    }

    /**
     * What early exit rests on instead: a lower bound on where the finished floor can land.
     *
     * The spread is 1.96 * sqrt(mean of the squared log ratios), and a sum of squares is at
     * least its largest term, so once the worst comparison is far enough off parity no
     * remaining comparison can pull the finished floor back under the limit. That bound only
     * grows, which is the property a stopping rule needs.
     */
    @Test
    fun `one comparison far enough off parity fixes the outcome whatever follows`() {
        val config = AbConfig(bootstrapIterations = 800)
        val required = NullTest.REQUIRED_CONSECUTIVE_PASSES
        // 40% off parity: far past what the bound tolerates over eight comparisons.
        val awful = DoubleArray(15) { 6_000_000.0 } to DoubleArray(15) { 8_400_000.0 }
        val tight = DoubleArray(15) { 7_570_000.0 } to DoubleArray(15) { 7_570_000.0 }

        val finished = NullTest.calibrate(
            listOf(awful) + List(required - 1) { tight },
            config,
        ).floor

        assertTrue(
            "a comparison this far off parity must keep the finished floor past the limit, " +
                "got $finished",
            finished > NullTest.MAXIMUM_USABLE_NOISE_FLOOR,
        )
    }

}
