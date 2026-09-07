package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.NullTest

/**
 * Whether a null test in progress has already lost, so the remaining comparisons cannot
 * change the answer.
 *
 * The Odin2's first run spent fifty of its seventy minutes confirming a verdict that was
 * already fixed: its very first comparison put the floor past the usable limit, and nothing
 * that followed could bring it back. Time on the device is the scarce thing in this project,
 * and spending it on a foregone conclusion is the one saving available that costs nothing.
 *
 * **Only failure ever settles.** Stopping the moment things look good is a different thing
 * entirely: a test allowed to end early on favourable evidence passes far more often than
 * its stated criterion, which is the classic way a stopping rule manufactures results.
 */
public object NullTestDecision {

    /**
     * @param perWorkload the comparisons gathered so far, in acquisition order.
     * @return why the test is already lost, or null while the answer is still open.
     */
    public fun settledFailure(
        perWorkload: List<WorkloadArms>,
        config: AbConfig = AbConfig(),
    ): String? {
        for (workload in perWorkload) {
            if (workload.comparisons.isEmpty()) continue

            // The floor is the largest dispersion seen, so it never falls as comparisons are
            // added: a partial pool already past the limit stays past it. That monotonicity
            // is the whole licence for stopping here, and swapping the floor rule for
            // anything that can decrease would quietly invalidate this.
            val floor = NullTest.calibrate(
                arms = workload.comparisons.map { it.toArrays() },
                config = config,
            ).floor

            if (floor > NullTest.MAXIMUM_USABLE_NOISE_FLOOR) {
                return "${workload.workloadId}: ${workload.comparisons.size} comparison(s) in, " +
                    "the floor is already ${"%.1f".format(floor * 100)}%, past the " +
                    "${"%.0f".format(NullTest.MAXIMUM_USABLE_NOISE_FLOOR * 100)}% limit. " +
                    "It cannot come back down, so no further comparison can pass this."
            }
        }
        return null
    }

    // A second exit used to live here: stop as soon as one comparison came back as something
    // other than a tie. It does not survive cross-validation. Each comparison is now judged
    // against a floor calibrated on the others, and that floor only grows as the pool does —
    // so a comparison that looks like a non-tie against the pool so far may well tie against
    // the final one. Acting on the partial view would abandon runs over a verdict the
    // finished test never reached, which is the false-exit direction, so it is gone.
}
