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
    /**
     * When set, this is not a calibration but an experiment on how long a run should be.
     *
     * Finding 11 left the reference device at a 12.4% floor against a 10% limit, with its two
     * arms 0.50% apart — the protocol is fine and the run-to-run spread is not: 7.0%, of which
     * a linear trend across the session explains only 9%. So it is scatter rather than drift,
     * and scatter can be averaged down. Whether that is bought with more runs or longer ones
     * decides the shape of every calibration from here, and the two cost very different
     * amounts: the 20 s cooldown is charged per *run*, so for a fixed number of frames, fewer
     * and longer runs is the cheaper way to buy the same precision — if the scatter is inside
     * a run rather than between runs. Nobody knows which, because every run ever recorded is
     * 1000 frames.
     *
     * Comparisons alternate between the profile's frame count and this one in ABBA order, for
     * the same reason the cooldown experiment does, and nothing it measures reaches the null
     * test store: a run that varies the protocol on purpose is not a floor anything should be
     * judged against.
     */
    val frameCountExperiment: Int? = null,
) {
    val totalComparisons: Int get() = warmupComparisons + comparisons

    val isExperiment: Boolean get() = cooldownExperimentMs != null || frameCountExperiment != null
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

/** One comparison of a frame-count experiment: the size used, and the dispersion it produced. */
@Serializable
data class FrameCountSample(
    val comparisonIndex: Int,
    val frameCount: Int,
    val workloadId: String,
    val armFirstNs: List<Double>,
    val armSecondNs: List<Double>,
)

@Serializable
data class FrameCountExperiment(
    val deviceFingerprint: String,
    val driverLabel: String,
    val appVersion: String,
    val completedAtEpochMs: Long,
    val standardFrameCount: Int,
    val longFrameCount: Int,
    val runsPerArm: Int,
    val armCooldownMs: Long,
    val samples: List<FrameCountSample>,
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
