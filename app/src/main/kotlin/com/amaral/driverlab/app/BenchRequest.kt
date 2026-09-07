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
    val isNullTest: Boolean = false,
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
) {
    /** Always known: the work is fixed before the run starts, so the bar never guesses. */
    val fraction: Double
        get() = if (executionsTotal == 0) 0.0 else executionsDone.toDouble() / executionsTotal
}

/**
 * The end of a run, whichever way it ended.
 *
 * [crashed] is set by the *client* when the runner process dies without sending
 * anything, which is the case this whole arrangement exists for: a driver that
 * segfaults takes `:bench` down, and the app records a crash instead of losing the
 * session.
 */
@Serializable
data class BenchCompletion(
    val ok: Boolean,
    val reportJson: String = "",
    val error: String = "",
    val crashed: Boolean = false,
)
