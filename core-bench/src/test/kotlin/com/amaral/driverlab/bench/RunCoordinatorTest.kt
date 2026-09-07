package com.amaral.driverlab.bench

import com.amaral.driverlab.driver.ConformanceVersion
import com.amaral.driverlab.driver.DriverIdentity
import com.amaral.driverlab.driver.DriverSource
import com.amaral.driverlab.driver.LoadRequest
import com.amaral.driverlab.driver.RequestedDriver
import com.amaral.driverlab.driver.VkDriverId
import com.amaral.driverlab.telemetry.BatteryState
import com.amaral.driverlab.telemetry.TelemetrySample
import com.amaral.driverlab.telemetry.ThermalStatus
import com.amaral.driverlab.vk.WorkloadIds
import com.amaral.driverlab.vk.WorkloadResult
import com.amaral.driverlab.vk.WorkloadRun
import com.amaral.driverlab.vk.WorkloadSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunCoordinatorTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private fun identity(driverId: Int = VkDriverId.MESA_TURNIP) = DriverIdentity(
        libraryChecksum = "abc",
        driverId = driverId,
        driverName = "turnip",
        driverInfo = "Mesa 25.1.0",
        conformanceVersion = ConformanceVersion(1, 3, 6, 0),
        deviceName = "Adreno (TM) 740",
        vendorId = 0x5143,
        deviceId = 1,
        apiVersion = (1 shl 22) or (3 shl 12),
        driverVersion = (25 shl 22),
        instanceExtensions = emptyList(),
        deviceExtensions = emptyList(),
        source = DriverSource.IMPORTED_PACKAGE,
    )

    private val sample = TelemetrySample(
        elapsedRealtimeMs = 0,
        thermalStatus = ThermalStatus.NONE,
        battery = BatteryState(90, -400_000, false, false, 30.0),
        zones = emptyList(),
    )

    private class FakeSession(
        override val identity: DriverIdentity,
        private val behaviour: (WorkloadSpec) -> WorkloadResult,
    ) : BenchDriverSession {
        var closed = false
        override fun run(spec: WorkloadSpec): WorkloadResult = behaviour(spec)
        override fun close() { closed = true }
    }

    private inner class FakeHost(
        val open: (LoadRequest) -> DriverSessionResult,
    ) : BenchHost {
        var cooldowns = 0
        val sessions = mutableListOf<FakeSession>()
        val requests = mutableListOf<LoadRequest>()
        override fun openDriver(request: LoadRequest): DriverSessionResult {
            requests += request
            return open(request).also { if (it is DriverSessionResult.Opened) sessions += it.session as FakeSession }
        }
        override fun sampleTelemetry(): TelemetrySample = sample
        override suspend fun cooldown(millis: Long) { cooldowns++ }
    }

    private fun completed(spec: WorkloadSpec, medianNs: Long) = WorkloadResult.Completed(
        WorkloadRun(
            spec = spec,
            gpuFrametimesNs = LongArray(spec.frameCount) { medianNs + (it % 5) * 1000 },
            cpuFrametimesNs = LongArray(spec.frameCount) { 800_000 },
            imageSha256 = "f".repeat(64),
            imageWidth = spec.width,
            imageHeight = spec.height,
            sampleCount = 4,
            depthFormat = 130,
            attachmentBytes = 1024,
            timestampsUsable = true,
        ),
    )

    private fun packageDriver(checksum: String, name: String) = RequestedDriver.Package(
        libraryChecksum = checksum,
        displayName = name,
        installDirectory = "/data/user/0/com.amaral.driverlab/files/drivers/$checksum",
        libraryName = "libvulkan_freedreno.so",
    )

    private fun plan(runsPerArm: Int = 2) = BenchmarkPlan(
        a = ArmDefinition(Arm.A, packageDriver("abc", "Turnip v3"), "Turnip v3"),
        b = ArmDefinition(Arm.B, packageDriver("def", "Turnip v4"), "Turnip v4"),
        workloads = listOf(WorkloadSpec(WorkloadIds.BASELINE, frameCount = 20, warmupFrames = 2)),
        runsPerArm = runsPerArm,
    )

    @Test
    fun `a clean plan runs every execution and finishes`() = runTest {
        val benchPlan = plan(runsPerArm = 3)
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }

        val outcome = RunCoordinator(host).execute(benchPlan, temporaryFolder.newFolder())

        assertEquals(RunnerState.DONE, outcome.finalState)
        assertTrue(outcome.completed)
        assertEquals(benchPlan.totalExecutions, outcome.records.size)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(3, outcome.runMediansFor(Arm.A, WorkloadIds.BASELINE).size)
        assertEquals(3, outcome.runMediansFor(Arm.B, WorkloadIds.BASELINE).size)
    }

    @Test
    fun `every driver session is closed, including on the happy path`() = runTest {
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }
        RunCoordinator(host).execute(plan(), temporaryFolder.newFolder())
        assertTrue(host.sessions.isNotEmpty())
        assertTrue("a leaked session keeps a driver loaded into the next arm", host.sessions.all { it.closed })
    }

    @Test
    fun `arms are counterbalanced, not simply alternated`() = runTest {
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }
        val outcome = RunCoordinator(host).execute(plan(runsPerArm = 4), temporaryFolder.newFolder())
        assertEquals(
            listOf(Arm.A, Arm.B, Arm.B, Arm.A, Arm.A, Arm.B, Arm.B, Arm.A),
            outcome.records.map { it.slot.arm },
        )
    }

    @Test
    fun `a cooldown separates every arm but the last`() = runTest {
        val benchPlan = plan(runsPerArm = 3)
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }
        RunCoordinator(host).execute(benchPlan, temporaryFolder.newFolder())
        assertEquals(benchPlan.schedule.size - 1, host.cooldowns)
    }

    /** P1: a package answered by the proprietary blob stops the run, it does not become data. */
    @Test
    fun `a driver whose identity fails the guard ends the run`() = runTest {
        val host = FakeHost {
            DriverSessionResult.Opened(
                FakeSession(identity(VkDriverId.QUALCOMM_PROPRIETARY)) { completed(it, 16_000_000) },
            )
        }
        val outcome = RunCoordinator(host).execute(plan(), temporaryFolder.newFolder())

        assertEquals(RunnerState.FAILED, outcome.finalState)
        assertFalse(outcome.completed)
        assertTrue(outcome.records.isEmpty())
        assertTrue(outcome.failures.single().message.contains("Qualcomm proprietary"))
    }

    @Test
    fun `a driver that will not load ends the run with the loader's reason`() = runTest {
        val host = FakeHost { DriverSessionResult.Failed("HOOK_UNAVAILABLE", "no libadrenotools") }
        val outcome = RunCoordinator(host).execute(plan(), temporaryFolder.newFolder())

        assertEquals(RunnerState.FAILED, outcome.finalState)
        assertEquals("HOOK_UNAVAILABLE", outcome.failures.single().stage)
    }

    /**
     * A workload that crashes is a finding, not the end of the session. The rest of
     * the plan still runs and the failure is carried into the report.
     */
    @Test
    fun `a failing workload is recorded and the plan continues`() = runTest {
        var call = 0
        val host = FakeHost {
            DriverSessionResult.Opened(
                FakeSession(identity()) { spec ->
                    if (++call == 2) {
                        WorkloadResult.Failed("DEVICE_LOST", "vkQueueSubmit: VK_ERROR_DEVICE_LOST")
                    } else {
                        completed(spec, 16_000_000)
                    }
                },
            )
        }
        val benchPlan = plan(runsPerArm = 3)
        val outcome = RunCoordinator(host).execute(benchPlan, temporaryFolder.newFolder())

        assertEquals(RunnerState.DONE, outcome.finalState)
        assertEquals(benchPlan.totalExecutions - 1, outcome.records.size)
        assertEquals("DEVICE_LOST", outcome.failures.single().stage)
    }

    @Test
    fun `a workload that reports no frames is a failure, not a zero`() = runTest {
        val host = FakeHost {
            DriverSessionResult.Opened(
                FakeSession(identity()) { spec ->
                    WorkloadResult.Completed(
                        WorkloadRun(spec, LongArray(0), LongArray(0), "0".repeat(64),
                            spec.width, spec.height, 1, 0, 0, false),
                    )
                },
            )
        }
        val outcome = RunCoordinator(host).execute(plan(), temporaryFolder.newFolder())
        assertTrue(outcome.records.isEmpty())
        assertTrue(outcome.failures.all { it.stage == "NO_FRAMES" })
    }

    @Test
    fun `the raw series survives into the record, not just the summary`() = runTest {
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }
        val outcome = RunCoordinator(host).execute(plan(), temporaryFolder.newFolder())
        val record = outcome.records.first()
        assertEquals(20, record.frametimesNs.size)
        assertEquals(20, record.summary.frameCount)
        assertTrue(record.usedGpuTimestamps)
    }

    @Test
    fun `differing frame hashes within one arm are visible`() = runTest {
        var call = 0
        val host = FakeHost {
            DriverSessionResult.Opened(
                FakeSession(identity()) { spec ->
                    val base = completed(spec, 16_000_000) as WorkloadResult.Completed
                    WorkloadResult.Completed(base.run.copy(imageSha256 = "%064x".format(call++)))
                },
            )
        }
        val outcome = RunCoordinator(host).execute(plan(runsPerArm = 3), temporaryFolder.newFolder())
        assertTrue(
            "a driver that renders differently each run must not look consistent",
            outcome.imageHashesFor(Arm.A, WorkloadIds.BASELINE).size > 1,
        )
    }

    @Test
    fun `the transition history records the whole run`() = runTest {
        val outcome = RunCoordinator(
            FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) },
        ).execute(plan(runsPerArm = 2), temporaryFolder.newFolder())

        assertEquals(RunnerState.IDLE, outcome.transitions.first().from)
        assertEquals(RunnerState.DONE, outcome.transitions.last().to)
    }

    /**
     * Regression. An earlier version built the request with a null directory and name for every
     * driver, so an imported package could never load however well the rest of the chain worked
     * — and because the loader answers "could not open it" either way, the failure looked like a
     * driver problem rather than a missing argument.
     */
    @Test
    fun `an imported package carries its location into the load request`() = runTest {
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }
        RunCoordinator(host).execute(plan(), temporaryFolder.newFolder())

        val request = host.requests.first()
        assertEquals(DriverSource.IMPORTED_PACKAGE, request.source)
        assertEquals("libvulkan_freedreno.so", request.libraryName)
        assertTrue(request.libraryDirectory!!.path.contains("drivers/abc"))
        assertEquals("abc", request.libraryChecksum)
    }

    @Test
    fun `the system driver asks for no directory`() = runTest {
        val systemPlan = plan().copy(
            a = ArmDefinition(Arm.A, RequestedDriver.System, "System driver"),
        )
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }
        RunCoordinator(host).execute(systemPlan, temporaryFolder.newFolder())

        val request = host.requests.first { it.source == DriverSource.SYSTEM }
        assertNull(request.libraryDirectory)
        assertNull(request.libraryName)
    }

    @Test
    fun `a package with no location fails before the loader is asked`() = runTest {
        val incomplete = plan().copy(
            a = ArmDefinition(
                Arm.A,
                RequestedDriver.Package(libraryChecksum = "abc", displayName = "Turnip v3"),
                "Turnip v3",
            ),
        )
        val host = FakeHost { DriverSessionResult.Opened(FakeSession(identity()) { completed(it, 16_000_000) }) }
        val outcome = RunCoordinator(host).execute(incomplete, temporaryFolder.newFolder())

        assertEquals(RunnerState.FAILED, outcome.finalState)
        assertEquals("PACKAGE_LOCATION_MISSING", outcome.failures.single().stage)
        assertTrue("the loader must not be asked to open nothing", host.requests.isEmpty())
    }
}
