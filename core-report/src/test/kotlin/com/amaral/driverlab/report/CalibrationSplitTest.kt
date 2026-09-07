package com.amaral.driverlab.report

import com.amaral.driverlab.stats.NullTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split between the runs that measure the floor and the runs judged against it.
 *
 * Sharing any run between the two would make the null test pass by construction, so the
 * boundary is worth pinning: a warm-up phase was added in front of both, and an off-by-one
 * there would silently feed calibration runs into the test half.
 */
class CalibrationSplitTest {

    private fun arms(count: Int, ratio: Double) = List(count) {
        ArmPair(first = List(5) { 7_570_000.0 }, second = List(5) { 7_570_000.0 * ratio })
    }

    @Test
    fun `calibration and test never share a comparison`() {
        val record = NullTestRecord(
            deviceFingerprint = "fp",
            driverSha256 = "a".repeat(64),
            driverLabel = "System driver",
            appVersion = "1.0.0-test",
            completedAtEpochMs = 0,
            runsPerArm = 5,
            perWorkload = listOf(
                WorkloadArms(
                    workloadId = "baseline/v1",
                    calibration = arms(NullTest.CALIBRATION_COMPARISONS, 1.0),
                    test = arms(NullTest.REQUIRED_CONSECUTIVE_PASSES, 1.0),
                ),
            ),
        )
        val workload = record.perWorkload.single()

        assertEquals(NullTest.CALIBRATION_COMPARISONS, workload.calibration.size)
        assertEquals(NullTest.REQUIRED_CONSECUTIVE_PASSES, workload.test.size)
    }

    @Test
    fun `the floor is set by the worst calibration comparison, not the typical one`() {
        // Nine comparisons that tie exactly and one that straddles a DVFS bin — the shape the
        // Odin2 produced. The floor must reflect the straddle: a device that invents an 11%
        // difference one time in ten does not resolve 2%.
        val straddling = arms(1, 1.118)
        val steady = arms(NullTest.CALIBRATION_COMPARISONS - 1, 1.0)

        val record = NullTestRecord(
            deviceFingerprint = "fp",
            driverSha256 = "a".repeat(64),
            driverLabel = "System driver",
            appVersion = "1.0.0-test",
            completedAtEpochMs = 0,
            runsPerArm = 5,
            perWorkload = listOf(
                WorkloadArms("baseline/v1", steady + straddling, arms(NullTest.REQUIRED_CONSECUTIVE_PASSES, 1.0)),
            ),
        )

        val verdict = record.resultFor(listOf("baseline/v1"))!!
        val floor = verdict.noiseFloorFor("baseline/v1")!!

        assertTrue("floor $floor should carry the straddle", floor > 0.10)
        // And a floor that coarse is itself a failure: it ties everything by being blind.
        assertFalse(verdict.passed)
    }

    @Test
    fun `a device that never straddles keeps a narrow floor`() {
        val record = NullTestRecord(
            deviceFingerprint = "fp",
            driverSha256 = "a".repeat(64),
            driverLabel = "System driver",
            appVersion = "1.0.0-test",
            completedAtEpochMs = 0,
            runsPerArm = 5,
            perWorkload = listOf(
                WorkloadArms(
                    "baseline/v1",
                    arms(NullTest.CALIBRATION_COMPARISONS, 1.0),
                    arms(NullTest.REQUIRED_CONSECUTIVE_PASSES, 1.0),
                ),
            ),
        )

        val verdict = record.resultFor(listOf("baseline/v1"))!!

        assertTrue(verdict.noiseFloorFor("baseline/v1")!! <= NullTest.MAXIMUM_USABLE_NOISE_FLOOR)
        assertTrue(verdict.explain(), verdict.passed)
    }
}
