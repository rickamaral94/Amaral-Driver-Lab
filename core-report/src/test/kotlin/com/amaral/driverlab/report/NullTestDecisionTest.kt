package com.amaral.driverlab.report

import com.amaral.driverlab.stats.NullTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NullTestDecisionTest {

    /** Both arms in the same DVFS bin: the device telling a driver apart from itself, badly. */
    private fun tied(count: Int) = List(count) {
        ArmPair(first = List(5) { 7_570_000.0 }, second = List(5) { 7_570_000.0 })
    }

    /** Arms in different bins — the shape that produced the Odin2's 27.7% floor. */
    private fun straddling(count: Int) = List(count) {
        ArmPair(first = List(5) { 6_770_000.0 }, second = List(5) { 7_570_000.0 })
    }

    private fun workload(calibration: List<ArmPair>, test: List<ArmPair> = emptyList()) =
        listOf(WorkloadArms("baseline/v1", calibration, test))

    @Test
    fun `a floor past the limit settles the run before any test comparison`() {
        val reason = NullTestDecision.settledFailure(
            workload(calibration = straddling(NullTest.CALIBRATION_COMPARISONS)),
        )

        assertNotNull("a 12% floor cannot pass a 10% limit", reason)
        assertTrue(reason!!, reason.contains("past the"))
    }

    @Test
    fun `a steady calibration leaves the answer open`() {
        assertNull(
            NullTestDecision.settledFailure(
                workload(calibration = tied(NullTest.CALIBRATION_COMPARISONS)),
            ),
        )
    }

    @Test
    fun `one comparison that is not a tie settles the run`() {
        // A device that calibrates a usable floor and then separates itself anyway. The claim
        // is that all ten tie, so the first non-tie has already emptied it.
        val reason = NullTestDecision.settledFailure(
            workload(
                calibration = tied(NullTest.CALIBRATION_COMPARISONS),
                test = tied(2) + straddling(1),
            ),
        )

        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("#3"))
    }

    @Test
    fun `ties so far never settle the run, however many there are`() {
        // The dangerous direction. Nine ties out of ten is encouraging and decides nothing:
        // stopping on good news is how a stopping rule invents a pass rate.
        assertNull(
            NullTestDecision.settledFailure(
                workload(
                    calibration = tied(NullTest.CALIBRATION_COMPARISONS),
                    test = tied(NullTest.REQUIRED_CONSECUTIVE_PASSES - 1),
                ),
            ),
        )
    }

    @Test
    fun `a complete passing run is still not settled, because it did not fail`() {
        assertNull(
            NullTestDecision.settledFailure(
                workload(
                    calibration = tied(NullTest.CALIBRATION_COMPARISONS),
                    test = tied(NullTest.REQUIRED_CONSECUTIVE_PASSES),
                ),
            ),
        )
    }

    @Test
    fun `a run with no calibration yet decides nothing`() {
        assertNull(NullTestDecision.settledFailure(workload(calibration = emptyList())))
    }

    @Test
    fun `any workload failing settles the whole run`() {
        // The profile verdict needs every workload to pass, so one lost workload is enough.
        val reason = NullTestDecision.settledFailure(
            listOf(
                WorkloadArms("baseline/v1", tied(NullTest.CALIBRATION_COMPARISONS), tied(3)),
                WorkloadArms("tiling_gmem/v1", straddling(NullTest.CALIBRATION_COMPARISONS), emptyList()),
            ),
        )

        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("tiling_gmem/v1"))
    }

    @Test
    fun `stopping early never turns a failure into a pass`() {
        // The invariant that makes early exit safe: whatever the run stopped on, the stored
        // series still evaluate to a failure, so nothing is rescued by ending sooner.
        val record = NullTestRecord(
            deviceFingerprint = "fp",
            driverSha256 = "a".repeat(64),
            driverLabel = "System driver",
            appVersion = "1.0.0-test",
            completedAtEpochMs = 0,
            runsPerArm = 5,
            perWorkload = workload(
                calibration = straddling(NullTest.CALIBRATION_COMPARISONS),
                test = emptyList(),
            ),
        )

        val verdict = record.resultFor(listOf("baseline/v1"))!!

        assertTrue(!verdict.passed)
        // And it says why it lost rather than reporting itself unfinished.
        assertTrue(verdict.explain(), verdict.explain().contains("only resolves"))
    }
}
