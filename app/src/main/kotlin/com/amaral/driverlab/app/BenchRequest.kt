package com.amaral.driverlab.app

import com.amaral.driverlab.driver.RequestedDriver
import com.amaral.driverlab.vk.WorkloadSpec
import kotlinx.serialization.Serializable

/**
 * What crosses the process boundary to start a run.
 *
 * The plan is rebuilt in the `:bench` process from this rather than being handed
 * over as objects, because the two processes share no heap. Keeping the request
 * small and explicit also means the runner cannot be asked to do anything the UI
 * did not describe.
 */
@Serializable
data class BenchRequest(
    val armA: DriverRef,
    val armB: DriverRef,
    val workloads: List<WorkloadRef>,
    val runsPerArm: Int,
    val comparisonIndex: Int = 0,
    /**
     * Present when this is an A/A null test rather than a comparison. Both arms then carry
     * the same driver and the runner performs a whole sequence of comparisons rather than
     * one, because a single A/A comparison proves nothing: the test is that *ten* of them
     * in a row come back as ties.
     */
    val nullTest: NullTestSpec? = null,
)

/**
 * How many A/A comparisons the runner should perform.
 *
 * One pool, not a calibration half and a test half. Each comparison is judged against a
 * floor calibrated on the others, so none judges itself and the device runs half as much.
 */
@Serializable
data class NullTestSpec(
    val comparisons: Int,
    /**
     * Comparisons run and discarded before the pool starts, so the GPU's cold ramp-up does
     * not get measured as the device's resolution.
     */
    val warmupComparisons: Int = 0,
    /**
     * When set, this is not a calibration but an experiment on the arm cooldown itself.
     *
     * The 20 s wait between arms is about seventy percent of a calibration's wall time and
     * has never been measured — it is a number that was chosen, not one that was justified.
     * Comparisons alternate between the standard cooldown and this one in ABBA order, so
     * neither setting is confounded with how far into the session it ran, and the run keeps
     * going to the end rather than stopping early: it is gathering dispersion, not deciding
     * a verdict. Nothing it measures is written to the null test store.
     */
    val cooldownExperimentMs: Long? = null,
) {
    val totalComparisons: Int get() = warmupComparisons + comparisons

    val isExperiment: Boolean get() = cooldownExperimentMs != null
}

/** One comparison of a cooldown experiment: the setting used, and what dispersion it produced. */
@Serializable
data class CooldownSample(
    val comparisonIndex: Int,
    val cooldownMs: Long,
    val workloadId: String,
    val armFirstMedianNs: List<Double>,
    val armSecondMedianNs: List<Double>,
)

@Serializable
data class CooldownExperiment(
    val deviceFingerprint: String,
    val driverLabel: String,
    val appVersion: String,
    val completedAtEpochMs: Long,
    val standardCooldownMs: Long,
    val shortCooldownMs: Long,
    val runsPerArm: Int,
    val samples: List<CooldownSample>,
)

@Serializable
data class DriverRef(
    val systemDriver: Boolean,
    val label: String,
    val libraryChecksum: String = "",
    val libraryDirectory: String = "",
    val libraryName: String = "",
) {
    fun toRequestedDriver(): RequestedDriver =
        if (systemDriver) {
            RequestedDriver.System
        } else {
            RequestedDriver.Package(
                libraryChecksum = libraryChecksum,
                displayName = label,
                installDirectory = libraryDirectory,
                libraryName = libraryName,
            )
        }
}

@Serializable
data class WorkloadRef(
    val workloadId: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val warmupFrames: Int,
    val drawsPerFrame: Int,
    val trianglesPerDraw: Int,
) {
    fun toSpec(): WorkloadSpec = WorkloadSpec(
        workloadId = workloadId,
        width = width,
        height = height,
        frameCount = frameCount,
        warmupFrames = warmupFrames,
        drawsPerFrame = drawsPerFrame,
        trianglesPerDraw = trianglesPerDraw,
    )

    companion object {
        fun of(spec: WorkloadSpec): WorkloadRef = WorkloadRef(
            workloadId = spec.workloadId,
            width = spec.width,
            height = spec.height,
            frameCount = spec.frameCount,
            warmupFrames = spec.warmupFrames,
            drawsPerFrame = spec.drawsPerFrame,
            trianglesPerDraw = spec.trianglesPerDraw,
        )
    }
}

/** Progress, sent back from the runner process as it goes. */
@Serializable
data class BenchProgress(
    val state: String,
    val executionsDone: Int,
    val executionsTotal: Int,
    val label: String,
    val workloadId: String,
    val peakCelsius: Double? = null,
    /**
     * True when [executionsTotal] is a ceiling rather than a plan. A null test stops the
     * moment its outcome is fixed, which on a device that fails is usually after the first
     * couple of comparisons — presenting the full budget as the number to expect told the
     * user to settle in for a hundred minutes of a run that was going to end in ten.
     */
    val totalIsUpperBound: Boolean = false,
) {
    /** Always known: the work is fixed before the run starts, so the bar never guesses. */
    val fraction: Double
        get() = if (executionsTotal == 0) 0.0 else executionsDone.toDouble() / executionsTotal
}

/**
 * The end of a run, whichever way it ended.
 *
 * The report travels as a **path**, never as its contents. A Binder transaction is bounded
 * at about a megabyte for the whole process, and a real run — twenty executions, thousands
 * of frametimes, sixty-six thermal zones sampled twice each — comfortably reaches a quarter
 * of that on its own. Sending the JSON inline made the completion message fail silently and
 * left the UI sitting on a finished benchmark forever.
 *
 * [crashed] is set by the *client* when the runner process dies without sending anything,
 * which is the case this whole arrangement exists for: a driver that segfaults takes
 * `:bench` down, and the app records a crash instead of losing the session.
 */
@Serializable
data class BenchCompletion(
    val ok: Boolean,
    /** File the runner wrote the report to. Both processes can read it; neither ships it. */
    val reportPath: String = "",
    val error: String = "",
    val crashed: Boolean = false,
    /**
     * True when this was a null test. It produces no report: it writes its record to the
     * shared store, and the UI re-derives the verdict from there rather than being told
     * one over the wire.
     */
    val nullTest: Boolean = false,
)
