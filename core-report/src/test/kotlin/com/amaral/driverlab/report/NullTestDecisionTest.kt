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

    /** A lean big enough to look like a real difference, small enough to keep the floor usable. */
    private fun leaning(count: Int) = List(count) {
        ArmPair(first = List(5) { 7_570_000.0 }, second = List(5) { 7_797_100.0 })
    }

    private fun workload(comparisons: List<ArmPair>) =
        listOf(WorkloadArms("baseline/v1", comparisons))

    @Test
    fun `a floor past the limit settles the run`() {
        val reason = NullTestDecision.settledFailure(
            workload(straddling(NullTest.REQUIRED_CONSECUTIVE_PASSES)),
        )

        assertNotNull("a 12% floor cannot pass a 10% limit", reason)
        assertTrue(reason!!, reason.contains("past the"))
    }

    @Test
    fun `a steady pool leaves the answer open`() {
        assertNull(
            NullTestDecision.settledFailure(
                                workload(tied(NullTest.REQUIRED_CONSECUTIVE_PASSES)),
            ),
        )
    }

    @Test
    fun `a comparison that looks like a non-tie so far does not settle the run`() {
        // This used to be a second exit: stop as soon as one comparison came back as anything
        // other than a tie. Cross-validation removed its ground. Each comparison is now judged
        // against a floor calibrated on the others, and that floor only widens as the pool
        // grows — so a comparison that separates against three comparisons may well tie
        // against ten. Acting on the partial view would abandon runs over a verdict the
        // finished test never reached.
        assertNull(NullTestDecision.settledFailure(workload(tied(2) + leaning(1))))
    }

    @Test
    fun `ties so far never settle the run, however many there are`() {
        // The dangerous direction. Nine ties out of ten is encouraging and decides nothing:
        // stopping on good news is how a stopping rule invents a pass rate.
        assertNull(
            NullTestDecision.settledFailure(
                workload(tied(NullTest.REQUIRED_CONSECUTIVE_PASSES - 1)),
            ),
        )
    }

    @Test
    fun `a complete passing run is still not settled, because it did not fail`() {
        assertNull(
            NullTestDecision.settledFailure(
                workload(tied(NullTest.REQUIRED_CONSECUTIVE_PASSES)),
            ),
        )
    }

    @Test
    fun `a run with nothing measured yet decides nothing`() {
        assertNull(NullTestDecision.settledFailure(workload(emptyList())))
    }

    @Test
    fun `any workload failing settles the whole run`() {
        // The profile verdict needs every workload to pass, so one lost workload is enough.
        val reason = NullTestDecision.settledFailure(
            listOf(
                WorkloadArms("baseline/v1", tied(NullTest.REQUIRED_CONSECUTIVE_PASSES)),
                WorkloadArms("tiling_gmem/v1", straddling(NullTest.REQUIRED_CONSECUTIVE_PASSES)),
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
            perWorkload = workload(straddling(NullTest.REQUIRED_CONSECUTIVE_PASSES)),
        )

        val verdict = record.resultFor(listOf("baseline/v1"))!!

        assertTrue(!verdict.passed)
        // And it says why it lost rather than reporting itself unfinished.
        assertTrue(verdict.explain(), verdict.explain().contains("only resolves"))
    }
}
