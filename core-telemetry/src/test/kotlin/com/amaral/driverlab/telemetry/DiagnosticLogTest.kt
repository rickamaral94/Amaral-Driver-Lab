package com.amaral.driverlab.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DiagnosticLogTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var filesDirectory: File

    @Before
    fun setUp() {
        filesDirectory = temporaryFolder.newFolder("files")
        // The object is a singleton, so each test resets it back to an unopened state.
        resetLog()
    }

    private fun resetLog() {
        val field = DiagnosticLog::class.java.getDeclaredField("sessionFile")
        field.isAccessible = true
        field.set(DiagnosticLog, null)
    }

    private fun sessionLog(): File =
        File(filesDirectory, "logs").listFiles()!!.first { it.name.endsWith(".log") }

    @Test
    fun `install creates the tree the user was promised`() {
        DiagnosticLog.install(filesDirectory, "com.amaral.driverlab.debug")
        val logs = File(filesDirectory, "logs")
        assertTrue(logs.isDirectory)
        assertTrue(File(logs, "crashes").isDirectory)
        assertTrue(DiagnosticLog.installed)
    }

    @Test
    fun `each process gets its own file`() {
        DiagnosticLog.install(filesDirectory, "com.amaral.driverlab.debug:bench")
        DiagnosticLog.i("test", "hello")
        // Two processes appending to one file interleave in an order neither can explain.
        assertTrue(sessionLog().name.endsWith("_bench.log"))
    }

    @Test
    fun `entries reach the file immediately, not a buffer`() {
        DiagnosticLog.install(filesDirectory, "app")
        DiagnosticLog.i("runner", "driver did not open")
        // The runner process can die at any moment; a buffered line is a line that is lost
        // exactly when it would have explained something.
        assertTrue(sessionLog().readText().contains("driver did not open"))
    }

    @Test
    fun `levels and tags are on every line`() {
        DiagnosticLog.install(filesDirectory, "app")
        DiagnosticLog.w("bench-service", "wakelock not acquired")
        val line = sessionLog().readLines().last()
        assertTrue(line, line.contains("W [app/bench-service]"))
        assertTrue(line, line.contains("wakelock not acquired"))
    }

    @Test
    fun `an error records its stack trace`() {
        DiagnosticLog.install(filesDirectory, "app")
        DiagnosticLog.e("runner", "run threw", IllegalStateException("no Vulkan device"))
        val text = sessionLog().readText()
        assertTrue(text.contains("no Vulkan device"))
        assertTrue(text.contains("IllegalStateException"))
    }

    /**
     * The token is the one secret the app holds, and this file exists to be shared. Redacting on
     * the way in means a token is never sitting in a file waiting for someone to forget.
     */
    @Test
    fun `a github token never reaches the file`() {
        DiagnosticLog.install(filesDirectory, "app")
        DiagnosticLog.i("publish", "using token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        DiagnosticLog.i("publish", "and github_pat_11ABCDEFG0abcdefghijklmnopqrstuvwxyz012345")

        val text = sessionLog().readText()
        assertFalse(text.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
        assertFalse(text.contains("abcdefghijklmnopqrstuvwxyz012345"))
        assertTrue(text.contains("redacted"))
    }

    @Test
    fun `ordinary text is not mangled by the redaction`() {
        DiagnosticLog.install(filesDirectory, "app")
        DiagnosticLog.i("runner", "sha256=abc123 dir=/data/user/0/com.amaral.driverlab/files")
        assertTrue(sessionLog().readText().contains("sha256=abc123 dir=/data/user/0"))
    }

    @Test
    fun `recent lines are kept for the ui without reading the file back`() {
        DiagnosticLog.install(filesDirectory, "app")
        DiagnosticLog.i("test", "first")
        DiagnosticLog.i("test", "second")
        val recent = DiagnosticLog.recentLines()
        assertTrue(recent.any { it.contains("first") })
        assertTrue(recent.any { it.contains("second") })
    }

    @Test
    fun `old session files are pruned so the folder cannot grow without end`() {
        val logs = File(filesDirectory, "logs").apply { mkdirs() }
        repeat(30) { index -> File(logs, "2026-01-%02d_app.log".format(index + 1)).writeText("old") }

        DiagnosticLog.install(filesDirectory, "app")

        val remaining = logs.listFiles { file -> file.name.endsWith(".log") }!!
        assertTrue("kept ${remaining.size} files", remaining.size <= 21)
    }

    @Test
    fun `logging before install does not throw`() {
        // The crash handler is installed early on purpose, and something may fail before the
        // directory exists. Losing the line is acceptable; crashing inside the logger is not.
        DiagnosticLog.i("early", "before install")
        assertFalse(DiagnosticLog.installed)
    }

    @Test
    fun `the bundle packs the whole tree for sharing`() {
        DiagnosticLog.install(filesDirectory, "app")
        DiagnosticLog.i("test", "something worth keeping")
        File(DiagnosticLog.crashesDirectory(), "2026-01-01_bench.txt").writeText("a crash")

        val zip = DiagnosticBundle.create(temporaryFolder.newFolder("share"))
        assertNotNull(zip)

        val names = ZipFile(zip!!).use { archive -> archive.entries().toList().map { it.name } }
        assertTrue(names.any { it.startsWith("logs/") && it.endsWith(".log") })
        assertTrue(names.any { it.contains("crashes/") })
    }

    @Test
    fun `the bundle is null when there is nothing to send`() {
        assertEquals(null, DiagnosticBundle.create(temporaryFolder.newFolder("share2")))
    }
}
