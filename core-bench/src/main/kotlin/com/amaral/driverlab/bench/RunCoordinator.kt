package com.amaral.driverlab.bench

import com.amaral.driverlab.driver.IdentityGuard
import com.amaral.driverlab.driver.IdentityVerdict
import com.amaral.driverlab.driver.DriverSource
import com.amaral.driverlab.driver.LoadRequest
import com.amaral.driverlab.driver.RequestedDriver
import com.amaral.driverlab.stats.FrametimeSummary
import com.amaral.driverlab.telemetry.DiagnosticLog
import com.amaral.driverlab.telemetry.TelemetrySample
import com.amaral.driverlab.vk.WorkloadResult
import com.amaral.driverlab.vk.WorkloadSpec

/** What the coordinator needs from the world, so the orchestration can be tested. */
public interface BenchHost {

    /** Opens the driver in an isolated runner process and returns a live session. */
    public fun openDriver(request: LoadRequest): DriverSessionResult

    public fun sampleTelemetry(): TelemetrySample

    /** Waits between arms. Suspending so the UI stays live and the user can abort. */
    public suspend fun cooldown(millis: Long)
}

public sealed interface DriverSessionResult {
    public data class Opened(val session: BenchDriverSession) : DriverSessionResult
    public data class Failed(val stage: String, val message: String) : DriverSessionResult
}

/** One loaded driver, able to run workloads until it is closed. */
public interface BenchDriverSession : AutoCloseable {
    public val identity: com.amaral.driverlab.driver.DriverIdentity
    public fun run(spec: WorkloadSpec): WorkloadResult
}

/** Progress for the UI. Every field is known in advance, so the bar is never indeterminate. */
public data class RunProgress(
    val state: RunnerState,
    val executionsDone: Int,
    val executionsTotal: Int,
    val currentLabel: String,
    val currentWorkloadId: String,
    val latestTelemetry: TelemetrySample?,
) {
    public val fraction: Double
        get() = if (executionsTotal == 0) 0.0 else executionsDone.toDouble() / executionsTotal
}

/**
 * Drives a plan to completion.
 *
 * The order is fixed by the plan before anything runs, so nothing here can react
 * to a result by changing what runs next. Failures are collected rather than
 * thrown: a driver that crashes on workload 2 has told us something, and the
 * remaining executions still carry information.
 */
public class RunCoordinator(
    private val host: BenchHost,
    private val guard: IdentityGuard = IdentityGuard(),
) {

    /**
     * @param nativeLibraryDirectory the app's own `applicationInfo.nativeLibraryDir`. The
     *   rootless hook is dlopened from there; see [LoadRequest.nativeLibraryDirectory].
     */
    public suspend fun execute(
        plan: BenchmarkPlan,
        temporaryDirectory: java.io.File,
        nativeLibraryDirectory: java.io.File,
        onProgress: (RunProgress) -> Unit = {},
    ): BenchmarkOutcome {
        val machine = RunnerStateMachine()
        val records = mutableListOf<ExecutionRecord>()
        val failures = mutableListOf<ExecutionFailure>()
        var abortReason: String? = null

        DiagnosticLog.i(
            TAG,
            "plan: ${plan.a.label} vs ${plan.b.label}, ${plan.runsPerArm} runs per arm, " +
                "${plan.workloads.size} workload(s), ${plan.totalExecutions} executions",
        )
        machine.apply(RunnerEvent.Start)
        // Preflight is evaluated by the caller, which owns the override decision;
        // reaching here means it cleared.
        machine.apply(RunnerEvent.PreflightCleared(overridden = false))

        val executionsBySlot = plan.executions.groupBy { it.slot }
        val slots = plan.schedule
        var done = 0

        for ((index, slot) in slots.withIndex()) {
            val planned = executionsBySlot[slot].orEmpty()
            if (planned.isEmpty()) continue
            val label = planned.first().label

            onProgress(
                RunProgress(machine.state, done, plan.totalExecutions, label, "", null),
            )

            val driver = planned.first().driver
            val request = when (driver) {
                is RequestedDriver.System -> LoadRequest(
                    source = DriverSource.SYSTEM,
                    libraryDirectory = null,
                    libraryName = null,
                    nativeLibraryDirectory = nativeLibraryDirectory,
                    temporaryDirectory = temporaryDirectory,
                )

                is RequestedDriver.Package -> LoadRequest(
                    source = DriverSource.IMPORTED_PACKAGE,
                    // Where the package lives has to reach the loader, or it opens nothing.
                    libraryDirectory = java.io.File(driver.installDirectory),
                    libraryName = driver.libraryName,
                    libraryChecksum = driver.libraryChecksum,
                    nativeLibraryDirectory = nativeLibraryDirectory,
                    temporaryDirectory = temporaryDirectory,
                )
            }

            if (driver is RequestedDriver.Package && !driver.loadable) {
                // Caught here rather than in the loader, because "the ICD could not be found"
                // and "the app forgot to say where it is" are different bugs and a report that
                // confuses them sends the reader looking in the wrong place.
                machine.apply(RunnerEvent.DriverRejected("the package location was not carried into the run"))
                failures += ExecutionFailure(
                    slot, label, planned.first().workload, "PACKAGE_LOCATION_MISSING",
                    "\"${driver.displayName}\" was selected but the run carried no directory or " +
                        "library name for it, so there was nothing for the loader to open.",
                )
                return BenchmarkOutcome(plan, machine.state, records, failures, machine.history, abortReason)
            }

            DiagnosticLog.i(
                TAG,
                "slot ${slot.slot} arm ${slot.arm}: opening ${request.source} " +
                    "dir=${request.libraryDirectory?.path ?: "-"} lib=${request.libraryName ?: "-"}",
            )

            when (val opened = host.openDriver(request)) {
                is DriverSessionResult.Failed -> {
                    DiagnosticLog.e(TAG, "driver did not open [${opened.stage}]: ${opened.message}")
                    machine.apply(RunnerEvent.DriverRejected(opened.message))
                    failures += ExecutionFailure(
                        slot, label, planned.first().workload, opened.stage, opened.message,
                    )
                    return BenchmarkOutcome(
                        plan, machine.state, records, failures, machine.history, abortReason,
                    )
                }

                is DriverSessionResult.Opened -> opened.session.use { session ->
                    // P1: the identity is checked before a single frame is timed.
                    val verdict = guard.check(planned.first().driver, session.identity)
                    if (verdict is IdentityVerdict.Rejected) {
                        DiagnosticLog.e(TAG, "identity refused [${verdict.code}]: ${verdict.reason}")
                        machine.apply(RunnerEvent.DriverRejected(verdict.reason))
                        failures += ExecutionFailure(
                            slot, label, planned.first().workload, verdict.code.name, verdict.reason,
                        )
                        return BenchmarkOutcome(
                            plan, machine.state, records, failures, machine.history, abortReason,
                        )
                    }
                    val accepted = verdict as IdentityVerdict.Accepted
                    DiagnosticLog.i(TAG, "identity accepted: ${session.identity.summary()}")
                    machine.apply(RunnerEvent.DriverAccepted)
                    machine.apply(RunnerEvent.WarmupComplete)

                    for (execution in planned) {
                        val before = host.sampleTelemetry()
                        when (val result = session.run(execution.workload)) {
                            is WorkloadResult.Failed -> {
                                DiagnosticLog.e(
                                    TAG,
                                    "workload ${execution.workload.workloadId} failed " +
                                        "[${result.stage}]: ${result.message}",
                                )
                                failures += ExecutionFailure(
                                    slot, label, execution.workload, result.stage, result.message,
                                )
                            }

                            is WorkloadResult.Completed -> {
                                val series = result.run.primaryFrametimesNs
                                if (series.isEmpty()) {
                                    failures += ExecutionFailure(
                                        slot, label, execution.workload, "NO_FRAMES",
                                        "the workload completed but reported no frametimes",
                                    )
                                } else {
                                    val summary = FrametimeSummary.of(series)
                                    DiagnosticLog.i(
                                        TAG,
                                        "${execution.workload.workloadId}: ${series.size} frames, " +
                                            "${summary.totalDurationNs / 1_000_000} ms of GPU time, " +
                                            "median ${"%.2f".format(summary.medianNs / 1_000_000)} ms" +
                                            if (summary.totalDurationNs < WorkloadSpec.MINIMUM_USEFUL_GPU_NANOS) {
                                                " — TOO BRIEF to separate drivers"
                                            } else {
                                                ""
                                            },
                                    )
                                    records += ExecutionRecord(
                                        slot = slot,
                                        label = accepted.label,
                                        workload = execution.workload,
                                        identity = session.identity,
                                        unverifiedDriver = accepted.unverified,
                                        frametimesNs = series,
                                        cpuFrametimesNs = result.run.cpuFrametimesNs,
                                        usedGpuTimestamps = result.run.timestampsUsable,
                                        imageSha256 = result.run.imageSha256,
                                        summary = summary,
                                        telemetryBefore = before,
                                        telemetryAfter = host.sampleTelemetry(),
                                    )
                                }
                            }
                        }
                        done++
                        onProgress(
                            RunProgress(
                                machine.state, done, plan.totalExecutions, accepted.label,
                                execution.workload.workloadId, host.sampleTelemetry(),
                            ),
                        )
                    }
                    machine.apply(RunnerEvent.ArmComplete)
                }
            }

            val lastSlot = index == slots.lastIndex
            if (lastSlot) {
                machine.apply(RunnerEvent.PlanComplete)
            } else {
                host.cooldown(plan.cooldownBetweenArmsMs)
                machine.apply(RunnerEvent.NextArm)
            }
        }

        DiagnosticLog.i(
            TAG,
            "finished in state ${machine.state}: ${records.size} record(s), ${failures.size} failure(s)",
        )
        return BenchmarkOutcome(plan, machine.state, records, failures, machine.history, abortReason)
    }

    private companion object {
        const val TAG = "runner"
    }
}
