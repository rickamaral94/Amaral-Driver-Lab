package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportFilesTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun report() = ReportBuilder("1.0.0-test", clock = { 1_757_100_000_000 }).build(
        outcome = ReportFixtures.outcome(medianA = 15_000_000, medianB = 16_600_000),
        device = ReportFixtures.device,
        preflight = ReportFixtures.cleanPreflight(),
        session = ReportFixtures.session(),
        nullTestResult = ReportFixtures.passingNullTest(),
        config = AbConfig(bootstrapIterations = 500),
    )

    @Test
    fun `the written file decodes back into the same report`() {
        val original = report()
        val written = ReportFiles.writeForSharing(original, folder.newFolder("share"))

        assertEquals(original, ReportJson.decode(written.readText()))
    }

    @Test
    fun `the directory is created if it does not exist yet`() {
        val directory = java.io.File(folder.root, "cache/share")
        val written = ReportFiles.writeForSharing(report(), directory)

        assertTrue(written.exists())
    }

    @Test
    fun `the name carries the session so two exports do not collide`() {
        val name = ReportFiles.fileNameFor(report())

        assertTrue(name, name.endsWith("-session-0001.json"))
        // The device model has a space in it; a share sheet should never see it raw.
        assertTrue(name, name.none { it.isWhitespace() })
        assertTrue(name, name.contains("odin2-portal"))
    }

    @Test
    fun `a device model with nothing usable in it still yields a name`() {
        val nameless = report().let { it.copy(device = it.device.copy(model = "   ")) }

        assertEquals("amaral-driver-lab-device-session-0001.json", ReportFiles.fileNameFor(nameless))
    }
}
