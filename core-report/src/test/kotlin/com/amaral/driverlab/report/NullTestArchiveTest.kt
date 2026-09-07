package com.amaral.driverlab.report

import com.amaral.driverlab.stats.NullTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NullTestArchiveTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val fingerprint = "AYN/odin2portal:13/TQ3A"

    /** A device that genuinely cannot tell the driver apart from itself. */
    private fun steadyArms(count: Int, offset: Int = 0) = List(count) { comparison ->
        val index = comparison + offset
        ArmPair(
            first = List(5) { 16_600_000.0 + ((it + index) % 3 - 1) * 4_000.0 },
            second = List(5) { 16_600_000.0 + ((it + index + 1) % 3 - 1) * 4_000.0 },
        )
    }

    /** The Odin2's two DVFS bins: one arm lands in the fast one, the other in the slow one. */
    private fun binnedArms(count: Int) = List(count) {
        ArmPair(
            first = List(5) { 5_780_000.0 },
            second = List(5) { 6_390_000.0 },
        )
    }

    private fun record(
        perWorkload: List<WorkloadArms> = listOf(
            WorkloadArms(
                workloadId = "baseline/v1",
                calibration = steadyArms(NullTest.CALIBRATION_COMPARISONS),
                test = steadyArms(NullTest.REQUIRED_CONSECUTIVE_PASSES, offset = 7),
            ),
        ),
    ) = NullTestRecord(
        deviceFingerprint = fingerprint,
        driverSha256 = "a".repeat(64),
        driverLabel = "Turnip",
        appVersion = "1.0.0-test",
        completedAtEpochMs = 1_757_100_000_000,
        runsPerArm = 5,
        perWorkload = perWorkload,
    )

    @Test
    fun `a steady device passes on the workload it covered`() {
        val verdict = record().resultFor(listOf("baseline/v1"))

        assertNotNull(verdict)
        assertTrue(verdict!!.explain(), verdict.passed)
    }

    @Test
    fun `a profile containing an uncovered workload is not gated by the record`() {
        // Not "failed" — unknown. The device never ran this workload against itself, so the
        // record has nothing to say about it and must not be read as permission.
        assertNull(record().resultFor(listOf("baseline/v1", "tiling_gmem/v1")))
    }

    @Test
    fun `one failing workload fails the profile even when another passes`() {
        val verdict = record(
            listOf(
                WorkloadArms(
                    "baseline/v1",
                    steadyArms(NullTest.CALIBRATION_COMPARISONS),
                    steadyArms(NullTest.REQUIRED_CONSECUTIVE_PASSES, offset = 7),
                ),
                WorkloadArms(
                    "tiling_gmem/v1",
                    steadyArms(NullTest.CALIBRATION_COMPARISONS),
                    binnedArms(NullTest.REQUIRED_CONSECUTIVE_PASSES),
                ),
            ),
        ).resultFor(listOf("baseline/v1", "tiling_gmem/v1"))

        assertNotNull(verdict)
        assertFalse(verdict!!.passed)
        assertTrue(verdict.perWorkload.getValue("baseline/v1").passed)
        assertFalse(verdict.perWorkload.getValue("tiling_gmem/v1").passed)
    }

    @Test
    fun `a device that always favours the same arm is caught as ordering bias`() {
        // Every comparison puts arm 1 in the fast bin. Each one ties on its own, and the
        // sign test is what notices that ten out of ten went the same way.
        val verdict = record(
            listOf(
                WorkloadArms(
                    "baseline/v1",
                    binnedArms(NullTest.CALIBRATION_COMPARISONS),
                    binnedArms(NullTest.REQUIRED_CONSECUTIVE_PASSES),
                ),
            ),
        ).resultFor(listOf("baseline/v1"))!!

        assertFalse(verdict.explain(), verdict.passed)
    }

    @Test
    fun `the verdict is re-derived, so editing a stored pass does not grant one`() {
        val store = NullTestStore(folder.newFolder("state"))
        val failing = record(
            listOf(
                WorkloadArms(
                    "baseline/v1",
                    binnedArms(NullTest.CALIBRATION_COMPARISONS),
                    binnedArms(NullTest.REQUIRED_CONSECUTIVE_PASSES),
                ),
            ),
        )
        store.write(failing)

        // Nothing in the file says "passed", so there is nothing to flip. The series are the
        // only claim, and they still describe a device favouring one arm every time.
        val text = java.io.File(folder.root, "state/${NullTestStore.FILE_NAME}").readText()
        assertFalse(text, text.contains("\"passed\""))

        val reread = store.read(fingerprint)!!
        assertFalse(reread.resultFor(listOf("baseline/v1"))!!.passed)
    }

    @Test
    fun `a record round trips through the store`() {
        val store = NullTestStore(folder.newFolder("state"))
        val original = record()
        store.write(original)

        assertEquals(original, store.read(fingerprint))
    }

    @Test
    fun `a record from other hardware is ignored rather than trusted`() {
        val store = NullTestStore(folder.newFolder("state"))
        store.write(record())

        assertNull(store.read("Xiaomi/other:14/UP1A"))
    }

    @Test
    fun `no record reads as no record, not as a failure`() {
        assertNull(NullTestStore(folder.newFolder("empty")).read(fingerprint))
    }

    @Test
    fun `a truncated file is ignored rather than crashing the reader`() {
        val directory = folder.newFolder("state")
        NullTestStore(directory).write(record())
        val file = java.io.File(directory, NullTestStore.FILE_NAME)
        file.writeText(file.readText().take(80))

        assertNull(NullTestStore(directory).read(fingerprint))
    }

    @Test
    fun `the noise floor is reported per workload`() {
        val verdict = record().resultFor(listOf("baseline/v1"))!!

        assertNotNull(verdict.noiseFloorFor("baseline/v1"))
        assertNull(verdict.noiseFloorFor("tiling_gmem/v1"))
    }
}
