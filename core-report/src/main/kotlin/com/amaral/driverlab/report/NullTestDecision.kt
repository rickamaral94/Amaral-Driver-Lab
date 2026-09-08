package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.NullTest
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.sqrt

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
        required: Int = NullTest.REQUIRED_CONSECUTIVE_PASSES,
    ): String? {
        for (workload in perWorkload) {
            if (workload.comparisons.isEmpty()) continue

            // The floor itself is no longer safe to test here. It used to be the largest
            // dispersion seen, which never falls; finding 13 made it the spread of the
            // comparisons, which does fall when a steady comparison is added — a partial pool
            // can read 23.6% and finish at 19.5%. Stopping on that would abandon runs over a
            // verdict the finished test never reaches.
            //
            // What survives is a *lower bound* on where the finished floor can land. The
            // spread is 1.96 * sqrt(mean(x^2)) over the log ratios, and a sum of squares is at
            // least its largest term, so with `required` comparisons in the finished pool the
            // final spread cannot fall below the worst |x| seen so far divided by sqrt(required).
            // That bound only grows as comparisons arrive, which is the property this needs.
            val worstLogRatio = workload.comparisons.maxOf { pair ->
                val a = pair.first.median()
                val b = pair.second.median()
                if (a <= 0.0 || b <= 0.0) 0.0 else abs(ln(b / a))
            }
            val leastPossibleFloor =
                expm1(1.96 * worstLogRatio / sqrt(required.toDouble())) *
                    NullTest.CALIBRATION_SAFETY_FACTOR

            if (leastPossibleFloor > NullTest.MAXIMUM_USABLE_NOISE_FLOOR) {
                return "${workload.workloadId}: ${workload.comparisons.size} comparison(s) in, " +
                    "one of them is far enough off parity that the finished floor cannot come " +
                    "in under ${"%.1f".format(leastPossibleFloor * 100)}%, past the " +
                    "${"%.0f".format(NullTest.MAXIMUM_USABLE_NOISE_FLOOR * 100)}% limit. " +
                    "No further comparison can pull it back. " +
                    widestComparison(workload)
            }
        }
        return null
    }

    /**
     * The comparison that set the floor, spelled out.
     *
     * A run can be abandoned on a single comparison, so the line that abandons it should say
     * which one and what it saw. Without this, working out why a run stopped meant rebuilding
     * the counterbalanced schedule by hand against the log to find out which executions had
     * been which arm — and the answer turned out to matter: on the Odin2 the deciding
     * comparison had one arm at 8.76 ms and the other at 7.57 ms because the device was
     * heating through a frequency step while it ran, not because of anything a driver did.
     */
    private fun widestComparison(workload: WorkloadArms): String {
        val widest = workload.comparisons.withIndex().maxByOrNull { (_, pair) ->
            val a = pair.first.median()
            val b = pair.second.median()
            if (a <= 0.0 || b <= 0.0) 0.0 else kotlin.math.abs(b / a - 1.0)
        } ?: return ""
        val (index, pair) = widest
        val a = pair.first.median() / 1_000_000
        val b = pair.second.median() / 1_000_000
        return "Widest was #${index + 1}: arm 1 at ${"%.2f".format(a)} ms against arm 2 " +
            "${"%.2f".format(b)} ms over ${pair.first.size} and ${pair.second.size} runs."
    }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    // A second exit used to live here: stop as soon as one comparison came back as something
    // other than a tie. It does not survive cross-validation. Each comparison is now judged
    // against a floor calibrated on the others, and that floor only grows as the pool does —
    // so a comparison that looks like a non-tie against the pool so far may well tie against
    // the final one. Acting on the partial view would abandon runs over a verdict the
    // finished test never reached, which is the false-exit direction, so it is gone.
}
