package com.amaral.driverlab.report

import com.amaral.driverlab.bench.Arm
import com.amaral.driverlab.bench.ArmDefinition
import com.amaral.driverlab.bench.ArmSlot
import com.amaral.driverlab.bench.BenchmarkOutcome
import com.amaral.driverlab.bench.BenchmarkPlan
import com.amaral.driverlab.bench.ExecutionFailure
import com.amaral.driverlab.bench.ExecutionRecord
import com.amaral.driverlab.bench.RunnerState
import com.amaral.driverlab.driver.ConformanceVersion
import com.amaral.driverlab.driver.DriverIdentity
import com.amaral.driverlab.driver.DriverSource
import com.amaral.driverlab.driver.RequestedDriver
import com.amaral.driverlab.driver.VkDriverId
import com.amaral.driverlab.stats.AbConfig
import com.amaral.driverlab.stats.FrametimeSummary
import com.amaral.driverlab.stats.NullTest
import com.amaral.driverlab.stats.NullTestResult
import com.amaral.driverlab.telemetry.BatteryState
import com.amaral.driverlab.telemetry.DeviceSnapshot
import com.amaral.driverlab.telemetry.PreflightReport
import com.amaral.driverlab.telemetry.TelemetrySample
import com.amaral.driverlab.telemetry.ThermalStatus
import com.amaral.driverlab.telemetry.ThermalZoneReading
import com.amaral.driverlab.vk.WorkloadIds
import com.amaral.driverlab.vk.WorkloadSpec

internal object ReportFixtures {

    val device: DeviceSnapshot = DeviceSnapshot(
        manufacturer = "AYN",
        model = "Odin2 Portal",
        device = "odin2portal",
        soc = "Qualcomm QCS8550",
        androidRelease = "13",
        sdkInt = 33,
        buildFingerprint = "AYN/odin2portal/odin2portal:13/TQ3A/user/release-keys",
        displayRefreshRateHz = 120.0,
        screenBrightness = 128,
    )

    fun identity(source: DriverSource = DriverSource.IMPORTED_PACKAGE, checksum: String = "a".repeat(64)) =
        DriverIdentity(
            libraryChecksum = checksum,
            driverId = if (source == DriverSource.SYSTEM) VkDriverId.QUALCOMM_PROPRIETARY else VkDriverId.MESA_TURNIP,
            driverName = if (source == DriverSource.SYSTEM) "Qualcomm driver" else "turnip",
            driverInfo = "Mesa 25.1.0",
            conformanceVersion = ConformanceVersion(1, 3, 6, 0),
            deviceName = "Adreno (TM) 740",
            vendorId = 0x5143,
            deviceId = 0x43050A01,
            apiVersion = (1 shl 22) or (3 shl 12) or 280,
            driverVersion = (25 shl 22) or (1 shl 12),
            instanceExtensions = listOf("VK_KHR_surface"),
            deviceExtensions = listOf("VK_KHR_swapchain"),
            source = source,
        )

    fun sample(celsius: Double) = TelemetrySample(
        elapsedRealtimeMs = 1_000,
        thermalStatus = ThermalStatus.NONE,
        battery = BatteryState(88, -430_000, false, false, 31.0),
        zones = listOf(ThermalZoneReading("thermal_zone7", "gpuss-0-usr", celsius)),
    )

    /**
     * @param medianA per-frame median for arm A, in nanoseconds
     * @param medianB the same for arm B; make them differ to produce a winner
     */
    fun outcome(
        medianA: Long = 16_600_000,
        medianB: Long = 16_600_000,
        runsPerArm: Int = 5,
        frameCount: Int = 60,
        failures: List<ExecutionFailure> = emptyList(),
    ): BenchmarkOutcome {
        val workload = WorkloadSpec(WorkloadIds.TILING_GMEM, frameCount = frameCount, warmupFrames = 10)
        val plan = BenchmarkPlan(
            a = ArmDefinition(Arm.A, RequestedDriver.Package("a".repeat(64), "Turnip v3"), "Turnip v3"),
            b = ArmDefinition(Arm.B, RequestedDriver.Package("b".repeat(64), "Turnip v4"), "Turnip v4"),
            workloads = listOf(workload),
            runsPerArm = runsPerArm,
        )

        val records = plan.schedule.map { slot ->
            val base = if (slot.arm == Arm.A) medianA else medianB
            // A little per-run and per-frame spread, deterministic so tests are stable.
            val offset = (slot.runIndexWithinArm - runsPerArm / 2) * (base / 500)
            val series = LongArray(frameCount) { frame ->
                base + offset + ((frame % 7) - 3) * (base / 800)
            }
            record(slot, if (slot.arm == Arm.A) "Turnip v3" else "Turnip v4", workload, series)
        }

        return BenchmarkOutcome(
            plan = plan,
            finalState = RunnerState.DONE,
            records = records,
            failures = failures,
            transitions = emptyList(),
        )
    }

    fun record(slot: ArmSlot, label: String, workload: WorkloadSpec, series: LongArray) = ExecutionRecord(
        slot = slot,
        label = label,
        workload = workload,
        identity = identity(checksum = if (label.endsWith("v3")) "a".repeat(64) else "b".repeat(64)),
        unverifiedDriver = false,
        frametimesNs = series,
        cpuFrametimesNs = LongArray(series.size) { 820_000 },
        usedGpuTimestamps = true,
        imageSha256 = "c".repeat(64),
        summary = FrametimeSummary.of(series),
        telemetryBefore = sample(38.0),
        telemetryAfter = sample(44.0),
    )

    fun session() = SessionInfo(
        id = "session-0001",
        thermalSessionId = "thermal-0001",
        startedAtEpochMs = 1_757_000_000_000,
        runnerFinalState = RunnerState.DONE.name,
    )

    fun cleanPreflight() = PreflightReport(issues = emptyList(), overridden = false)

    /** A null test that passes, built from run summaries that genuinely tie. */
    fun passingNullTest(): NullTestResult {
        val arms = List(NullTest.REQUIRED_CONSECUTIVE_PASSES) { comparison ->
            val a = DoubleArray(5) { 16_600_000.0 + ((it + comparison) % 3 - 1) * 4_000.0 }
            val b = DoubleArray(5) { 16_600_000.0 + ((it + comparison + 1) % 3 - 1) * 4_000.0 }
            a to b
        }
        val config = AbConfig(bootstrapIterations = 800)
        val calibration = NullTest.calibrate(arms, config)
        return NullTest.evaluate("a".repeat(64), arms, calibration, config = config)
    }
}
