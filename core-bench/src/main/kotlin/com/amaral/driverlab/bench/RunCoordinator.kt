package com.amaral.driverlab.bench

import com.amaral.driverlab.driver.IdentityGuard
import com.amaral.driverlab.driver.IdentityVerdict
import com.amaral.driverlab.driver.LoadRequest
import com.amaral.driverlab.stats.FrametimeSummary
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

    public suspend fun execute(
        plan: BenchmarkPlan,
        temporaryDirectory: java.io.File,
        onProgress: (RunProgress) -> Unit = {},
    ): BenchmarkOutcome {
        val machine = RunnerStateMachine()
        val records = mutableListOf<ExecutionRecord>()
        val failures = mutableListOf<ExecutionFailure>()
        var abortReason: String? = null

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

            val request = LoadRequest(
                source = planned.first().driver.let { driver ->
                    when (driver) {
                        is com.amaral.driverlab.driver.RequestedDriver.System ->
                            com.amaral.driverlab.driver.DriverSource.SYSTEM
                        is com.amaral.driverlab.driver.RequestedDriver.Package ->
                            com.amaral.driverlab.driver.DriverSource.IMPORTED_PACKAGE
                    }
                },
                libraryDirectory = null,
                libraryName = null,
                temporaryDirectory = temporaryDirectory,
            )

            when (val opened = host.openDriver(request)) {
                is DriverSessionResult.Failed -> {
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
                        machine.apply(RunnerEvent.DriverRejected(verdict.reason))
                        failures += ExecutionFailure(
                            slot, label, planned.first().workload, verdict.code.name, verdict.reason,
                        )
                        return BenchmarkOutcome(
                            plan, machine.state, records, failures, machine.history, abortReason,
                        )
                    }
                    val accepted = verdict as IdentityVerdict.Accepted
                    machine.apply(RunnerEvent.DriverAccepted)
                    machine.apply(RunnerEvent.WarmupComplete)

                    for (execution in planned) {
                        val before = host.sampleTelemetry()
                        when (val result = session.run(execution.workload)) {
                            is WorkloadResult.Failed -> failures += ExecutionFailure(
                                slot, label, execution.workload, result.stage, result.message,
                            )

                            is WorkloadResult.Completed -> {
                                val series = result.run.primaryFrametimesNs
                                if (series.isEmpty()) {
                                    failures += ExecutionFailure(
                                        slot, label, execution.workload, "NO_FRAMES",
                                        "the workload completed but reported no frametimes",
                                    )
                                } else {
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
                                        summary = FrametimeSummary.of(series),
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

        return BenchmarkOutcome(plan, machine.state, records, failures, machine.history, abortReason)
    }
}
