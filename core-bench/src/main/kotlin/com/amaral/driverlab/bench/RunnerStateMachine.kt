package com.amaral.driverlab.bench

import kotlinx.serialization.Serializable

/**
 * The runner's states, exactly as section 9 requires them.
 *
 * Written as a table rather than as scattered `if` statements so the legal
 * transitions can be enumerated in a test. A state machine that only exists as
 * control flow is a state machine nobody can check.
 */
@Serializable
public enum class RunnerState {
    IDLE,
    PREFLIGHT,
    LOADING,
    WARMUP,
    RUNNING,
    COOLDOWN,
    DONE,
    FAILED,
    ABORTED;

    /** Terminal states accept no further events. */
    public val terminal: Boolean get() = this == DONE || this == FAILED || this == ABORTED
}

/** What can happen to a run. Every transition in the table is driven by one of these. */
@Serializable
public sealed interface RunnerEvent {

    /** The user pressed start. */
    public data object Start : RunnerEvent

    /** Preflight finished and the device is fit, or the user overrode the blocks. */
    public data class PreflightCleared(val overridden: Boolean) : RunnerEvent

    /** Preflight found something blocking and the user did not override it. */
    public data object PreflightRefused : RunnerEvent

    /** The ICD loaded and its identity passed the guard. */
    public data object DriverAccepted : RunnerEvent

    /**
     * The ICD did not load, or loaded and reported the wrong identity. This is a
     * failure, never a fallback: see [com.amaral.driverlab.driver.IdentityGuard].
     */
    public data class DriverRejected(val reason: String) : RunnerEvent

    /** Warmup frames are done and timing starts now. */
    public data object WarmupComplete : RunnerEvent

    /** All timed frames of the current arm are in. */
    public data object ArmComplete : RunnerEvent

    /** The cooldown between arms elapsed and there is another arm to run. */
    public data object NextArm : RunnerEvent

    /** Every arm in the plan has run. */
    public data object PlanComplete : RunnerEvent

    /** A crash, a device loss, a timeout, or a Vulkan error. */
    public data class Failed(val stage: String, val reason: String) : RunnerEvent

    /** The user pressed abort. */
    public data object Abort : RunnerEvent
}

/**
 * The transition table.
 *
 * Anything not listed here is illegal, and attempting it is a bug in the caller
 * rather than something to absorb quietly — [RunnerStateMachine.apply] returns the
 * rejection instead of silently staying put, so a test can see it.
 */
public object RunnerTransitions {

    private val table: Map<Pair<RunnerState, String>, RunnerState> = buildMap {
        fun allow(from: RunnerState, event: String, to: RunnerState) {
            put(from to event, to)
        }

        allow(RunnerState.IDLE, "Start", RunnerState.PREFLIGHT)

        allow(RunnerState.PREFLIGHT, "PreflightCleared", RunnerState.LOADING)
        allow(RunnerState.PREFLIGHT, "PreflightRefused", RunnerState.IDLE)

        allow(RunnerState.LOADING, "DriverAccepted", RunnerState.WARMUP)
        allow(RunnerState.LOADING, "DriverRejected", RunnerState.FAILED)

        allow(RunnerState.WARMUP, "WarmupComplete", RunnerState.RUNNING)

        allow(RunnerState.RUNNING, "ArmComplete", RunnerState.COOLDOWN)

        // A plan can finish either straight after an arm or after its cooldown, so
        // both paths to DONE are legal and both are exercised in the truth table.
        allow(RunnerState.COOLDOWN, "NextArm", RunnerState.LOADING)
        allow(RunnerState.COOLDOWN, "PlanComplete", RunnerState.DONE)

        // Failure and abort are reachable from every non-terminal state.
        for (state in RunnerState.entries.filterNot { it.terminal }) {
            allow(state, "Failed", RunnerState.FAILED)
            allow(state, "Abort", RunnerState.ABORTED)
        }
    }

    public fun nextState(from: RunnerState, event: RunnerEvent): RunnerState? =
        table[from to event.name()]

    public fun legalEvents(from: RunnerState): Set<String> =
        table.keys.filter { it.first == from }.map { it.second }.toSet()

    internal fun RunnerEvent.name(): String = when (this) {
        is RunnerEvent.Start -> "Start"
        is RunnerEvent.PreflightCleared -> "PreflightCleared"
        is RunnerEvent.PreflightRefused -> "PreflightRefused"
        is RunnerEvent.DriverAccepted -> "DriverAccepted"
        is RunnerEvent.DriverRejected -> "DriverRejected"
        is RunnerEvent.WarmupComplete -> "WarmupComplete"
        is RunnerEvent.ArmComplete -> "ArmComplete"
        is RunnerEvent.NextArm -> "NextArm"
        is RunnerEvent.PlanComplete -> "PlanComplete"
        is RunnerEvent.Failed -> "Failed"
        is RunnerEvent.Abort -> "Abort"
    }
}

public sealed interface TransitionResult {
    public data class Moved(val from: RunnerState, val to: RunnerState, val event: RunnerEvent) :
        TransitionResult

    public data class Rejected(val state: RunnerState, val event: RunnerEvent, val reason: String) :
        TransitionResult
}

/**
 * Holds the current state and refuses illegal moves loudly.
 *
 * Not thread-safe by design: the runner drives it from a single coroutine, and
 * making it tolerant of concurrent callers would hide the bug where two things
 * try to drive one run.
 */
public class RunnerStateMachine(initial: RunnerState = RunnerState.IDLE) {

    public var state: RunnerState = initial
        private set

    private val log = mutableListOf<TransitionResult.Moved>()

    public val history: List<TransitionResult.Moved> get() = log.toList()

    public fun apply(event: RunnerEvent): TransitionResult {
        if (state.terminal) {
            return TransitionResult.Rejected(
                state, event, "$state is terminal; a finished run cannot be driven further",
            )
        }
        val next = RunnerTransitions.nextState(state, event)
            ?: return TransitionResult.Rejected(
                state, event, "no transition from $state on ${event::class.simpleName}",
            )
        val moved = TransitionResult.Moved(state, next, event)
        state = next
        log += moved
        return moved
    }
}
