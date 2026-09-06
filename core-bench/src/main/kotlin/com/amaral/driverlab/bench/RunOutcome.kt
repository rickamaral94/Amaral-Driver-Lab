package com.amaral.driverlab.bench

import com.amaral.driverlab.driver.DriverIdentity
import com.amaral.driverlab.stats.FrametimeSummary
import com.amaral.driverlab.telemetry.TelemetrySample
import com.amaral.driverlab.vk.WorkloadSpec

/** One driver execution of one workload: the raw series and everything around it. */
public data class ExecutionRecord(
    val slot: ArmSlot,
    val label: String,
    val workload: WorkloadSpec,
    val identity: DriverIdentity,
    val unverifiedDriver: Boolean,
    /** The complete series, never only the aggregates. */
    val frametimesNs: LongArray,
    val cpuFrametimesNs: LongArray,
    val usedGpuTimestamps: Boolean,
    val imageSha256: String,
    val summary: FrametimeSummary,
    val telemetryBefore: TelemetrySample,
    val telemetryAfter: TelemetrySample,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExecutionRecord) return false
        return slot == other.slot && workload == other.workload &&
            frametimesNs.contentEquals(other.frametimesNs) && imageSha256 == other.imageSha256
    }

    override fun hashCode(): Int {
        var result = slot.hashCode()
        result = 31 * result + workload.hashCode()
        result = 31 * result + frametimesNs.contentHashCode()
        result = 31 * result + imageSha256.hashCode()
        return result
    }
}

/** A failure, recorded as data. A driver that crashes has told us something. */
public data class ExecutionFailure(
    val slot: ArmSlot,
    val label: String,
    val workload: WorkloadSpec,
    val stage: String,
    val message: String,
)

public data class BenchmarkOutcome(
    val plan: BenchmarkPlan,
    val finalState: RunnerState,
    val records: List<ExecutionRecord>,
    val failures: List<ExecutionFailure>,
    val transitions: List<TransitionResult.Moved>,
    val abortReason: String? = null,
) {
    public val completed: Boolean get() = finalState == RunnerState.DONE

    /** Per-run medians for one arm and workload — the unit the A/B test compares. */
    public fun runMediansFor(arm: Arm, workloadId: String): DoubleArray = records
        .filter { it.slot.arm == arm && it.workload.workloadId == workloadId }
        .sortedBy { it.slot.runIndexWithinArm }
        .map { it.summary.medianNs }
        .toDoubleArray()

    /**
     * Every distinct frame hash seen for one workload and arm. More than one means
     * the driver did not render the same scene twice, which is a correctness
     * finding in itself and is why the hash is per execution rather than per run.
     */
    public fun imageHashesFor(arm: Arm, workloadId: String): Set<String> = records
        .filter { it.slot.arm == arm && it.workload.workloadId == workloadId }
        .map { it.imageSha256 }
        .toSet()
}
