package com.amaral.driverlab.app

import com.amaral.driverlab.bench.BenchmarkProfiles
import com.amaral.driverlab.driver.RequestedDriver
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request crosses a process boundary, so it has to survive a round trip
 * through JSON with nothing lost. A workload spec that arrived in the runner with
 * a different frame count would silently change what was measured.
 */
class BenchRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun request() = BenchRequest(
        armA = DriverRef(
            systemDriver = false,
            label = "Turnip 25.1.0",
            libraryChecksum = "a".repeat(64),
            libraryDirectory = "/data/user/0/com.amaral.driverlab/files/drivers/x",
            libraryName = "libvulkan_freedreno.so",
        ),
        armB = DriverRef(systemDriver = true, label = "System driver"),
        workloads = BenchmarkProfiles.complete().map(WorkloadRef::of),
        runsPerArm = 5,
    )

    @Test
    fun `a request survives the process boundary unchanged`() {
        val original = request()
        val encoded = json.encodeToString(BenchRequest.serializer(), original)
        assertEquals(original, json.decodeFromString(BenchRequest.serializer(), encoded))
    }

    @Test
    fun `workload specs round trip with every count intact`() {
        for (spec in BenchmarkProfiles.complete()) {
            assertEquals(spec, WorkloadRef.of(spec).toSpec())
        }
    }

    @Test
    fun `a package reference rebuilds as the driver the guard will check`() {
        val driver = request().armA.toRequestedDriver()
        assertTrue(driver is RequestedDriver.Package)
        assertEquals("a".repeat(64), (driver as RequestedDriver.Package).libraryChecksum)
        assertTrue("an imported package must be expected to be Mesa", driver.expectMesa)
    }

    @Test
    fun `the system driver reference rebuilds as the system driver`() {
        assertTrue(request().armB.toRequestedDriver() is RequestedDriver.System)
    }

    @Test
    fun `progress always knows where it is`() {
        val progress = BenchProgress("RUNNING", 3, 12, "Turnip", "baseline/v1", 41.0)
        assertEquals(0.25, progress.fraction, 1e-12)
        // Section 14: an indeterminate bar on a twenty-minute test is an antipattern.
        assertEquals(0.0, BenchProgress("LOADING", 0, 0, "", "").fraction, 0.0)
    }

    @Test
    fun `a crash is reported as a result about the driver`() {
        val completion = BenchCompletion(
            ok = false,
            crashed = true,
            error = "The benchmark process stopped without finishing.",
        )
        val round = json.decodeFromString(
            BenchCompletion.serializer(),
            json.encodeToString(BenchCompletion.serializer(), completion),
        )
        assertTrue(round.crashed)
        assertTrue(!round.ok)
    }
}
