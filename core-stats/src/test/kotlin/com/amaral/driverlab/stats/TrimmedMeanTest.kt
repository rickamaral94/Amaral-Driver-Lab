package com.amaral.driverlab.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The statistic the comparison, the noise floor and the anchor score are computed on.
 *
 * It replaced the median for a reason that came off the device rather than out of a textbook,
 * and the two tests that matter here are the two halves of that reason: it must move
 * continuously with a run's fast/slow mix where the median jumps a whole DVFS step, and it
 * must still ignore a hitch, which is the only thing the median was ever protecting.
 */
class TrimmedMeanTest {

    /** scipy.stats.trim_mean(x, 0.05) on the same input, to ten decimal places. */
    @Test
    fun `matches scipy trim_mean`() {
        // numpy.random.default_rng(20).lognormal(2.0, 0.4, 20), sorted.
        val reference = doubleArrayOf(
            4.5686526194, 4.7820681322, 4.9561413919, 5.1261337397, 5.1544259244,
            5.2770768959, 5.7202460735, 6.0749883929, 6.1551636308, 6.3989417027,
            6.7146294045, 7.2245376099, 7.4437595181, 7.7780964653, 8.3887738958,
            8.7203003123, 9.3901633186, 10.7153996846, 11.9588314703, 12.9196220890,
        )
        // scipy.stats.trim_mean(reference, 0.05) == 7.109982086854924 for this series.
        assertEquals(7.1099820869, FrametimeSummary.trimmedMeanOfSorted(reference), 1e-9)
    }

    /**
     * The defect this statistic exists to fix.
     *
     * A run's frametimes are multimodal because the GPU hops between DVFS steps during the
     * run. Two runs a single percentage point apart in their fast/slow mix are, in real cost,
     * a fraction of a percent apart — and the median reports them a whole step apart, because
     * a median of a two-valued series is a threshold function of the mix.
     */
    @Test
    fun `a one percent change in the mix does not move it, and moves the median a whole step`() {
        val fast = 6_770_000L
        val slow = 7_570_000L
        fun run(slowFrames: Int) = LongArray(1000) { if (it < slowFrames) slow else fast }

        val below = FrametimeSummary.of(run(495))
        val above = FrametimeSummary.of(run(505))

        val medianJump = abs(above.medianNs / below.medianNs - 1.0)
        val trimmedJump = abs(above.trimmedMeanNs / below.trimmedMeanNs - 1.0)

        // The median crosses from one step of the ladder to the next: 6.77 ms to 7.57 ms.
        assertEquals(fast.toDouble(), below.medianNs, 1.0)
        assertEquals(slow.toDouble(), above.medianNs, 1.0)
        assertTrue("median jumped $medianJump, expected the full 11.8% step", medianJump > 0.11)

        // The trimmed mean moves by about the amount the workload actually changed.
        assertTrue("trimmed mean moved $trimmedJump, which is not continuous", trimmedJump < 0.005)
        assertTrue(
            "the trimmed mean should be at least fifty times steadier here, was " +
                "${medianJump / trimmedJump}",
            medianJump / trimmedJump > 50,
        )
    }

    /**
     * The property the median was chosen for in the first place, which trimming has to keep.
     * A plain mean does not: one 500 ms frame moves a 1000-frame mean by 6.2%, which is half
     * the difference between the two drivers this app exists to compare.
     */
    @Test
    fun `one catastrophic frame moves it not at all, and moves a plain mean by six percent`() {
        val clean = LongArray(1000) { 8_000_000L }
        val hitched = LongArray(1000) { if (it == 999) 500_000_000L else 8_000_000L }

        val before = FrametimeSummary.of(clean)
        val after = FrametimeSummary.of(hitched)

        assertEquals(before.trimmedMeanNs, after.trimmedMeanNs, 1.0)
        assertTrue(
            "a plain mean should have been visibly dragged, was ${after.meanNs / before.meanNs}",
            after.meanNs / before.meanNs > 1.06,
        )
    }

    /** A series too short to spare anything keeps every frame rather than becoming a mean of nothing. */
    @Test
    fun `a short series is not silently a different statistic`() {
        val three = doubleArrayOf(1.0, 2.0, 30.0)
        assertEquals(11.0, FrametimeSummary.trimmedMeanOfSorted(three), 1e-12)
        assertEquals(5.0, FrametimeSummary.trimmedMeanOfSorted(doubleArrayOf(5.0)), 1e-12)
    }

    /** Both tails, so the statistic cannot be made to flatter a driver from one end. */
    @Test
    fun `trimming is symmetric`() {
        val values = DoubleArray(100) { (it + 1).toDouble() }
        // Dropping five from each end of 1..100 leaves 6..95, whose mean is still 50.5.
        assertEquals(50.5, FrametimeSummary.trimmedMeanOfSorted(values), 1e-12)
    }
}
