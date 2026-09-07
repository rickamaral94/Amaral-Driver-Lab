package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbComparison
import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.NullTest
import com.amaral.driverlab.stats.Verdict

/**
 * Whether a null test in progress has already lost, so the remaining comparisons cannot
 * change the answer.
 *
 * The Odin2's first run spent fifty of its seventy minutes confirming a verdict that was
 * already fixed: calibration had measured a 27.7% floor, past the limit, and no sequence of
 * ten further comparisons could have rescued that. Time on the device is the scarce thing
 * in this project, and spending it on a foregone conclusion is the one saving available
 * that costs nothing.
 *
 * **Only failure ever settles.** Stopping the moment things look good would be a different
 * thing entirely — a test allowed to end early on favourable evidence passes far more often
 * than its stated criterion, which is the classic way a stopping rule manufactures results.
 * Both conditions here are deterministic rather than statistical: a floor past the limit is
 * an automatic fail whatever follows, and a single comparison that is not a tie already
 * empties the "all ten tied" claim. Neither peeks at a trend.
 */
public object NullTestDecision {

    /**
     * @param perWorkload the arms gathered so far — calibration complete, test partial.
     * @return why the test is already lost, or null while the answer is still open.
     */
    public fun settledFailure(
        perWorkload: List<WorkloadArms>,
        config: AbConfig = AbConfig(),
    ): String? {
        for (workload in perWorkload) {
            if (workload.calibration.isEmpty()) continue
            val calibration = NullTest.calibrate(
                arms = workload.calibration.map { it.toArrays() },
                config = config,
            )

            if (calibration.floor > NullTest.MAXIMUM_USABLE_NOISE_FLOOR) {
                return "${workload.workloadId}: calibration measured a " +
                    "${"%.1f".format(calibration.floor * 100)}% floor, past the " +
                    "${"%.0f".format(NullTest.MAXIMUM_USABLE_NOISE_FLOOR * 100)}% limit. " +
                    "No sequence of further comparisons can pass from here."
            }

            // Judged against the same widened config the final evaluation will use, so a
            // comparison counted as a failure here is one the final verdict also counts.
            val judged = config.copy(
                minimumPracticalDifference =
                    maxOf(config.minimumPracticalDifference, calibration.floor),
            )
            workload.test.forEachIndexed { index, pair ->
                val (first, second) = pair.toArrays()
                if (first.isEmpty() || second.isEmpty()) return@forEachIndexed
                val result = AbComparison.compare(
                    labelA = "A/A #${index + 1} arm 1",
                    labelB = "A/A #${index + 1} arm 2",
                    a = first,
                    b = second,
                    lowerIsBetter = true,
                    config = judged,
                )
                if (result.verdict != Verdict.TECHNICAL_TIE) {
                    return "${workload.workloadId}: A/A comparison #${index + 1} came back " +
                        "${result.verdict} rather than a tie, so the run has already separated " +
                        "a driver from itself. The test requires all " +
                        "${NullTest.REQUIRED_CONSECUTIVE_PASSES} to tie."
                }
            }
        }
        return null
    }
}
